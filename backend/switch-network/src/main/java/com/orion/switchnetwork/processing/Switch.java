package com.orion.switchnetwork.processing;

import com.orion.shared.flow.FlowBatch;
import com.orion.shared.flow.FlowRule;
import com.orion.shared.flow.FlowTableView;
import com.orion.shared.messages.LinkStatus;
import com.orion.shared.messages.PacketOut;
import com.orion.shared.messages.PortInfo;
import com.orion.shared.messages.SwitchRegistration;
import com.orion.shared.packet.Packet;
import com.orion.switchnetwork.communication.ControllerConnection;
import com.orion.switchnetwork.network.HostDef;
import com.orion.switchnetwork.network.Link;
import com.orion.switchnetwork.network.NetworkException;
import com.orion.switchnetwork.network.PortRef;
import com.orion.switchnetwork.network.SwitchDef;
import com.orion.switchnetwork.network.SwitchNetwork;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One simulated switch = one thread. It takes packets from its queue, logs each one,
 * matches it against its flow table, then forwards, drops, or sends it to the controller.
 */
public final class Switch implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Switch.class);

    private record Arrival(Packet packet, int inPort) {
    }

    private final String id;
    private final String name;
    private final int totalPorts;
    private final String bootId = UUID.randomUUID().toString();
    private final Map<Integer, String> hostPorts = new ConcurrentHashMap<>();
    private final Map<Integer, Link> linkPorts = new ConcurrentHashMap<>();
    private final FlowTable flowTable = new FlowTable();
    private final BlockingQueue<Arrival> queue = new LinkedBlockingQueue<>(20_000);
    private final SwitchNetwork network;
    private final ControllerConnection controller;
    private volatile boolean running;
    private volatile boolean controlConnected = true;
    private Thread thread;

    public Switch(SwitchDef def, boolean autoRegister, SwitchNetwork network) {
        this.id = def.id();
        this.name = def.name() == null ? def.id() : def.name();
        this.totalPorts = def.ports();
        this.network = network;
        for (HostDef h : def.hosts()) {
            hostPorts.put(h.port(), h.subnet());
        }
        this.controller = new ControllerConnection(this, network, autoRegister);
    }

    // ---------- lifecycle ----------

    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("switch-" + id).daemon(true).start(this);
        controller.start();
        log.info("Switch {} started: {}", id, describePorts());
    }

    public void stop() {
        running = false;
        controller.stop();
        if (thread != null) {
            thread.interrupt();
        }
        queue.clear();
        log.info("Switch {} stopped", id);
    }

    @Override
    public void run() {
        while (running) {
            try {
                Arrival a = queue.take();
                handle(a.packet(), a.inPort());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Switch {} failed to handle a packet", id, e);
            }
        }
    }

    // ---------- packet path ----------

    /** A packet arrives at a port. Called by links, the traffic generator, and packet-out. */
    public void receive(Packet packet, int inPort) {
        if (!running) {
            return;
        }
        if (!queue.offer(new Arrival(packet, inPort))) {
            network.log().record(id, packet, inPort, "DROPPED_QUEUE_FULL", null, null);
        }
    }

    private void handle(Packet p, int inPort) {
        if (p.ttl() <= 0) {
            network.log().record(id, p, inPort, "DROPPED_TTL", null, null);
            return;
        }
        FlowRule rule = flowTable.lookup(p, inPort, ZonedDateTime.now(network.settings().zone()));
        if (rule == null) {
            if (controller.sendPacketIn(p, inPort)) {
                network.log().record(id, p, inPort, "TO_CONTROLLER", null, null);
            } else {
                network.log().record(id, p, inPort, "DROPPED_NO_RULE", null, null);
            }
            return;
        }
        if (!flowTable.allowedByRateLimit(rule)) {
            network.log().record(id, p, inPort, "RATE_LIMITED", null, rule.ruleId());
            return;
        }
        if (rule.action().isDrop()) {
            network.log().record(id, p, inPort, "DROPPED", null, rule.ruleId());
            return;
        }
        output(p, inPort, rule.action().port(), rule.ruleId());
    }

    /** Send a packet out of a port: deliver to the host subnet, or put it on the link. */
    private void output(Packet p, int inPort, int port, String ruleId) {
        if (hostPorts.containsKey(port)) {
            network.log().record(id, p, inPort, "DELIVERED", port, ruleId);
            return;
        }
        Link link = linkPorts.get(port);
        if (link == null) {
            network.log().record(id, p, inPort, "DROPPED_NO_PORT", port, ruleId);
            return;
        }
        if (!link.isUp()) {
            network.log().record(id, p, inPort, "DROPPED_LINK_DOWN", port, ruleId);
            return;
        }
        network.log().record(id, p, inPort, "FORWARDED", port, ruleId);
        network.deliver(link, id, p.withTtl(p.ttl() - 1));
    }

    // ---------- southbound operations ----------

    public void packetOut(PacketOut out) {
        requireControlConnected();
        output(out.packet(), 0, out.outPort(), "packet-out");
    }

    public void applyFlows(FlowBatch batch) {
        flowTable.apply(batch, totalPorts);
    }

    public FlowTableView flows() {
        return flowTable.view();
    }

    public LinkStatus linkStatus(String linkId) {
        requireControlConnected();
        for (Link l : linkPorts.values()) {
            if (l.id().equals(linkId)) {
                return new LinkStatus(linkId, l.isUp());
            }
        }
        throw NetworkException.notFound("switch " + id + " has no link " + linkId);
    }

    /** Called when the controller fetches this switch's details (manual add). */
    public SwitchRegistration details() {
        requireControlConnected();
        controller.markContactedByController();
        return registration();
    }

    public SwitchRegistration registration() {
        return new SwitchRegistration(id, name, network.addressOf(id), bootId, totalPorts, ports());
    }

    public void requireControlConnected() {
        if (!controlConnected) {
            throw NetworkException.unavailable("switch " + id + " is disconnected from the controller");
        }
    }

    // ---------- ports and links ----------

    public boolean isPortFree(int port) {
        return port >= 1 && port <= totalPorts && !hostPorts.containsKey(port) && !linkPorts.containsKey(port);
    }

    public void attachLink(int port, Link link) {
        linkPorts.put(port, link);
    }

    public void detachLink(int port) {
        linkPorts.remove(port);
    }

    public List<Link> links() {
        return new ArrayList<>(linkPorts.values());
    }

    public PortInfo portInfo(int port) {
        String subnet = hostPorts.get(port);
        if (subnet != null) {
            return new PortInfo(port, null, null, null, null, subnet, true);
        }
        Link link = linkPorts.get(port);
        if (link != null) {
            PortRef peer = link.peerOf(id);
            return new PortInfo(port, link.id(), peer.switchId(), peer.port(), link.latencyMs(), null, link.isUp());
        }
        return new PortInfo(port, null, null, null, null, null, false);
    }

    public List<PortInfo> ports() {
        List<PortInfo> out = new ArrayList<>();
        for (int p = 1; p <= totalPorts; p++) {
            out.add(portInfo(p));
        }
        return out;
    }

    public Map<Integer, String> hostPorts() {
        return new TreeMap<>(hostPorts);
    }

    private String describePorts() {
        StringBuilder sb = new StringBuilder();
        for (PortInfo p : ports()) {
            sb.append("\n    port ").append(p.port()).append(": ");
            if (p.isHost()) {
                sb.append("hosts ").append(p.hostSubnet());
            } else if (p.isLink()) {
                sb.append("link ").append(p.linkId()).append(" -> ").append(p.peerSwitch()).append(':')
                  .append(p.peerPort()).append(" (").append(p.latencyMs()).append(" ms)");
            } else {
                sb.append("free");
            }
        }
        return sb.toString();
    }

    // ---------- accessors ----------

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public int totalPorts() {
        return totalPorts;
    }

    public String bootId() {
        return bootId;
    }

    public boolean isControlConnected() {
        return controlConnected;
    }

    public void setControlConnected(boolean connected) {
        this.controlConnected = connected;
        log.info("Switch {} control channel {}", id, connected ? "reconnected" : "disconnected (keeps forwarding)");
    }

    public ControllerConnection controller() {
        return controller;
    }
}
