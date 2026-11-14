package com.orion.network.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orion.common.net.Ip;
import com.orion.common.packet.Packet;
import com.orion.common.southbound.PortInfo;
import com.orion.network.topology.HostDef;
import com.orion.network.topology.LinkDef;
import com.orion.network.topology.PortRef;
import com.orion.network.topology.SwitchDef;
import com.orion.network.topology.TopologyFile;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The whole simulated network: all switch threads and the links between them.
 * Built from the topology file, then changed at runtime through the API.
 */
public final class SwitchNetwork {

    private static final Logger log = LoggerFactory.getLogger(SwitchNetwork.class);

    /** What the API returns for one switch. */
    public record SwitchView(String id, String name, int totalPorts, List<HostDef> hosts, boolean controlConnected,
                             boolean registered, boolean controllerReachable, boolean autoRegister, String bootId,
                             String address, List<PortInfo> ports) {
    }

    /** What the API returns for one link. */
    public record LinkView(String id, String a, String b, int latencyMs, boolean up) {
    }

    public record NetworkView(String controllerUrl, List<SwitchView> switches, List<LinkView> links) {
    }

    private final NetworkSettings settings;
    private final Map<String, Switch> switches = new ConcurrentSkipListMap<>();
    private final Map<String, Link> links = new ConcurrentSkipListMap<>();
    private final PacketLog packetLog = new PacketLog();
    private final ScheduledExecutorService controlScheduler = Executors.newScheduledThreadPool(8,
            Thread.ofPlatform().name("control-", 0).daemon(true).factory());
    private final ScheduledExecutorService wireScheduler = Executors.newScheduledThreadPool(4,
            Thread.ofPlatform().name("wire-", 0).daemon(true).factory());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public SwitchNetwork(TopologyFile file, NetworkSettings settings) {
        this.settings = settings;
        for (SwitchDef def : file.switches()) {
            switches.put(def.id(), new Switch(def, true, this));
        }
        for (LinkDef def : file.links()) {
            Link link = new Link(def.id(), def.endA(), def.endB(), def.latencyMs());
            links.put(link.id(), link);
            switches.get(link.a().switchId()).attachLink(link.a().port(), link);
            switches.get(link.b().switchId()).attachLink(link.b().port(), link);
        }
    }

    /** Starts every switch thread (they then register with the controller). */
    public void start() {
        switches.values().forEach(Switch::start);
        log.info("Switch network running: {} switches, {} links", switches.size(), links.size());
    }

    public void shutdown() {
        switches.values().forEach(Switch::stop);
        controlScheduler.shutdownNow();
        wireScheduler.shutdownNow();
    }

    // ---------- runtime changes ----------

    public synchronized SwitchView addSwitch(SwitchDef def, boolean autoRegister) {
        if (def.id() == null || def.id().isBlank()) {
            throw NetworkException.badRequest("switch id is required");
        }
        if (switches.containsKey(def.id())) {
            throw NetworkException.badRequest("switch " + def.id() + " already exists");
        }
        if (def.ports() < 1 || def.ports() > 64) {
            throw NetworkException.badRequest("ports must be between 1 and 64");
        }
        Set<Integer> used = new HashSet<>();
        for (HostDef h : def.hosts()) {
            if (h.port() < 1 || h.port() > def.ports() || !used.add(h.port())) {
                throw NetworkException.badRequest("host port " + h.port() + " is invalid or used twice");
            }
            if (!Ip.isCidr(h.subnet())) {
                throw NetworkException.badRequest("subnet must look like 10.0.5.0/24");
            }
        }
        Switch sw = new Switch(def, autoRegister, this);
        switches.put(def.id(), sw);
        sw.start();
        return view(sw);
    }

    public synchronized void removeSwitch(String id) {
        Switch sw = get(id);
        for (Link link : sw.links()) {
            removeLink(link.id());
        }
        sw.stop();
        switches.remove(id);
    }

    public synchronized LinkView addLink(LinkDef def) {
        if (def.id() == null || def.id().isBlank()) {
            throw NetworkException.badRequest("link id is required");
        }
        if (links.containsKey(def.id())) {
            throw NetworkException.badRequest("link " + def.id() + " already exists");
        }
        if (def.latencyMs() <= 0) {
            throw NetworkException.badRequest("latencyMs must be greater than 0");
        }
        PortRef a;
        PortRef b;
        try {
            a = def.endA();
            b = def.endB();
        } catch (RuntimeException e) {
            throw NetworkException.badRequest(e.getMessage());
        }
        if (a.switchId().equals(b.switchId())) {
            throw NetworkException.badRequest("a link must connect two different switches");
        }
        Switch sa = get(a.switchId());
        Switch sb = get(b.switchId());
        if (!sa.isPortFree(a.port())) {
            throw NetworkException.badRequest("port " + a + " is not free");
        }
        if (!sb.isPortFree(b.port())) {
            throw NetworkException.badRequest("port " + b + " is not free");
        }
        Link link = new Link(def.id(), a, b, def.latencyMs());
        links.put(link.id(), link);
        sa.attachLink(a.port(), link);
        sb.attachLink(b.port(), link);
        sa.controller().notifyLink(sa.portInfo(a.port()), null);
        sb.controller().notifyLink(sb.portInfo(b.port()), null);
        log.info("Link {} added: {} <-> {}", link.id(), a, b);
        return view(link);
    }

    public synchronized void removeLink(String id) {
        Link link = links.remove(id);
        if (link == null) {
            throw NetworkException.notFound("no link " + id);
        }
        for (PortRef end : List.of(link.a(), link.b())) {
            Switch sw = switches.get(end.switchId());
            if (sw != null) {
                PortInfo before = sw.portInfo(end.port());
                sw.detachLink(end.port());
                sw.controller().notifyLink(null, before);
            }
        }
        log.info("Link {} removed", id);
    }

    public synchronized LinkView setLinkUp(String id, boolean up) {
        Link link = links.get(id);
        if (link == null) {
            throw NetworkException.notFound("no link " + id);
        }
        if (link.isUp() != up) {
            link.setUp(up);
            for (PortRef end : List.of(link.a(), link.b())) {
                Switch sw = switches.get(end.switchId());
                if (sw != null) {
                    PortInfo info = sw.portInfo(end.port());
                    sw.controller().notifyLink(up ? info : null, up ? null : info);
                }
            }
            log.info("Link {} is now {}", id, up ? "UP" : "DOWN");
        }
        return view(link);
    }

    public SwitchView setControlConnected(String id, boolean connected) {
        Switch sw = get(id);
        sw.setControlConnected(connected);
        return view(sw);
    }

    // ---------- packet delivery ----------

    /** Put a packet on a link; it arrives at the far switch after the link's latency. */
    void deliver(Link link, String fromSwitch, Packet packet) {
        PortRef peer = link.peerOf(fromSwitch);
        wireScheduler.schedule(() -> {
            Switch target = switches.get(peer.switchId());
            if (target != null && link.isUp()) {
                target.receive(packet, peer.port());
            }
        }, link.latencyMs(), TimeUnit.MILLISECONDS);
    }

    // ---------- lookups and views ----------

    public Switch get(String id) {
        Switch sw = switches.get(id);
        if (sw == null) {
            throw NetworkException.notFound("no switch " + id);
        }
        return sw;
    }

    String addressOf(String switchId) {
        return settings.publicBaseUrl() + "/switch/" + switchId;
    }

    public NetworkView view() {
        List<SwitchView> sv = new ArrayList<>();
        switches.values().forEach(s -> sv.add(view(s)));
        List<LinkView> lv = new ArrayList<>();
        links.values().forEach(l -> lv.add(view(l)));
        return new NetworkView(settings.controllerUrl(), sv, lv);
    }

    private SwitchView view(Switch s) {
        List<HostDef> hosts = new ArrayList<>();
        s.hostPorts().forEach((port, subnet) -> hosts.add(new HostDef(port, subnet)));
        ControllerConnection c = s.controller();
        return new SwitchView(s.id(), s.name(), s.totalPorts(), hosts, s.isControlConnected(), c.isRegistered(),
                c.isReachable(), c.isAutoRegister(), s.bootId(), addressOf(s.id()), s.ports());
    }

    private static LinkView view(Link l) {
        return new LinkView(l.id(), l.a().toString(), l.b().toString(), l.latencyMs(), l.isUp());
    }

    public NetworkSettings settings() {
        return settings;
    }

    public PacketLog log() {
        return packetLog;
    }

    ScheduledExecutorService controlScheduler() {
        return controlScheduler;
    }

    public ScheduledExecutorService trafficScheduler() {
        return wireScheduler;
    }

    HttpClient http() {
        return http;
    }

    ObjectMapper json() {
        return json;
    }
}
