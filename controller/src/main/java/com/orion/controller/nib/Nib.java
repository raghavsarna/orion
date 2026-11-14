package com.orion.controller.nib;

import com.orion.common.southbound.PortInfo;
import com.orion.common.southbound.SwitchRegistration;
import com.orion.controller.core.ActivityLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Network Information Base: the single source of truth for the topology, kept in memory.
 * Holds a map of switch details and an adjacency-matrix graph, and publishes change events.
 * Every change is applied first, then subscribers are notified (in order), so they never see stale data.
 * Only the Topology Manager calls the write methods.
 */
public final class Nib {

    /** Mutable per-switch record, guarded by the NIB lock. */
    private static final class SwitchRecord {
        String id;
        String name;
        String address;
        String bootId;
        int totalPorts;
        SwitchStatus status = SwitchStatus.ALIVE;
        int missedProbes;
        long lastSeen;
        long registeredAt;
        final Map<Integer, PortInfo> ports = new TreeMap<>();

        SwitchView view() {
            return new SwitchView(id, name, address, bootId, totalPorts, status, missedProbes, lastSeen, registeredAt,
                    List.copyOf(ports.values()));
        }
    }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, SwitchRecord> switches = new TreeMap<>();
    private final List<NibSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final ActivityLog activity;
    private TopologyGraph graph = TopologyGraph.empty();
    private long version;

    public Nib(ActivityLog activity) {
        this.activity = activity;
    }

    // ---------- pub/sub ----------

    public void subscribe(NibSubscriber s) {
        subscribers.removeIf(old -> {
            if (old.name().equals(s.name())) {
                old.close();
                return true;
            }
            return false;
        });
        subscribers.add(s);
    }

    public void unsubscribe(String name) {
        subscribers.removeIf(old -> {
            if (old.name().equals(name)) {
                old.close();
                return true;
            }
            return false;
        });
    }

    public List<String> subscriberNames() {
        return subscribers.stream().map(NibSubscriber::name).toList();
    }

    // ---------- writes (Topology Manager only) ----------

    /** Adds or refreshes a switch from its registration. */
    public void register(SwitchRegistration reg) {
        mutate(() -> {
            List<NibEvent> events = new ArrayList<>();
            SwitchRecord s = switches.get(reg.switchId());
            long now = System.currentTimeMillis();
            if (s == null) {
                s = new SwitchRecord();
                s.registeredAt = now;
                switches.put(reg.switchId(), s);
                events.add(NibEvent.of("SWITCH_ADDED", reg.switchId(), null, reg.switchAddress()));
            } else if (!reg.bootId().equals(s.bootId)) {
                events.add(NibEvent.of("SWITCH_REBOOTED", reg.switchId(), null, "flow table is empty"));
            } else if (s.status == SwitchStatus.DEAD) {
                events.add(NibEvent.of("SWITCH_ALIVE", reg.switchId(), null, "registered again"));
            }
            s.id = reg.switchId();
            s.name = reg.switchName();
            s.address = reg.switchAddress();
            s.bootId = reg.bootId();
            s.totalPorts = reg.switchTotalPorts();
            s.status = SwitchStatus.ALIVE;
            s.missedProbes = 0;
            s.lastSeen = now;
            s.ports.clear();
            for (PortInfo p : reg.ports()) {
                s.ports.put(p.port(), p);
            }
            return events;
        });
    }

    /** Applies a link add/delete reported by a switch. Returns false if the switch is unknown. */
    public boolean updatePort(String switchId, PortInfo port, boolean added) {
        boolean[] known = {false};
        mutate(() -> {
            SwitchRecord s = switches.get(switchId);
            if (s == null) {
                return List.of();
            }
            known[0] = true;
            s.lastSeen = System.currentTimeMillis();
            if (added) {
                s.ports.put(port.port(), port);
            } else {
                PortInfo old = s.ports.get(port.port());
                if (old != null && old.isLink()) {
                    s.ports.put(port.port(), new PortInfo(port.port(), old.linkId(), old.peerSwitch(), old.peerPort(),
                            old.latencyMs(), null, false));
                }
            }
            return List.of();
        });
        return known[0];
    }

    /** A probe succeeded. */
    public void markAlive(String switchId) {
        mutate(() -> {
            SwitchRecord s = switches.get(switchId);
            if (s == null) {
                return List.of();
            }
            s.missedProbes = 0;
            s.lastSeen = System.currentTimeMillis();
            if (s.status == SwitchStatus.DEAD) {
                s.status = SwitchStatus.ALIVE;
                return List.of(NibEvent.of("SWITCH_ALIVE", switchId, null, "answers probes again"));
            }
            return List.of();
        });
    }

    /** A probe failed; returns the number of misses in a row. */
    public int recordMissedProbe(String switchId) {
        lock.writeLock().lock();
        try {
            SwitchRecord s = switches.get(switchId);
            return s == null ? 0 : ++s.missedProbes;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void markDead(String switchId, String reason) {
        mutate(() -> {
            SwitchRecord s = switches.get(switchId);
            if (s == null || s.status == SwitchStatus.DEAD) {
                return List.of();
            }
            s.status = SwitchStatus.DEAD;
            return List.of(NibEvent.of("SWITCH_DEAD", switchId, null, reason));
        });
    }

    public void remove(String switchId, String reason) {
        mutate(() -> switches.remove(switchId) == null
                ? List.of()
                : List.of(NibEvent.of("SWITCH_REMOVED", switchId, null, reason)));
    }

    public void touch(String switchId) {
        lock.writeLock().lock();
        try {
            SwitchRecord s = switches.get(switchId);
            if (s != null) {
                s.lastSeen = System.currentTimeMillis();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Applies a change under the write lock, rebuilds the matrix, adds link events for the difference,
     * bumps the version, and queues the events to subscribers (still under the lock, so order is kept).
     */
    private void mutate(Supplier<List<NibEvent>> change) {
        List<NibEvent> published = new ArrayList<>();
        lock.writeLock().lock();
        try {
            Map<String, LinkInfo> before = linksById(graph);
            List<NibEvent> events = new ArrayList<>(change.get());
            graph = buildGraph();
            Map<String, LinkInfo> after = linksById(graph);
            for (String id : after.keySet()) {
                if (!before.containsKey(id)) {
                    LinkInfo l = after.get(id);
                    events.add(NibEvent.of("LINK_ADDED", l.fromSwitch(), id, l.fromSwitch() + ":" + l.fromPort()
                            + " <-> " + l.toSwitch() + ":" + l.toPort()));
                }
            }
            for (String id : before.keySet()) {
                if (!after.containsKey(id)) {
                    LinkInfo l = before.get(id);
                    events.add(NibEvent.of("LINK_REMOVED", l.fromSwitch(), id, l.fromSwitch() + " <-> " + l.toSwitch()));
                }
            }
            if (!events.isEmpty()) {
                version++;
                for (NibEvent e : events) {
                    NibEvent v = e.withVersion(version);
                    published.add(v);
                    for (NibSubscriber s : subscribers) {
                        s.enqueue(v);
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
        for (NibEvent e : published) {
            activity.add(e.type(), (e.switchId() == null ? "" : e.switchId())
                    + (e.linkId() == null ? "" : " link " + e.linkId())
                    + (e.detail() == null ? "" : " (" + e.detail() + ")"));
        }
    }

    /** A link is in the topology when both switches are known and both ends report it up. */
    private TopologyGraph buildGraph() {
        TopologyGraph g = new TopologyGraph(new ArrayList<>(switches.keySet()));
        for (SwitchRecord s : switches.values()) {
            for (PortInfo p : s.ports.values()) {
                if (!p.isLink() || !p.up() || p.peerSwitch() == null || p.peerPort() == null) {
                    continue;
                }
                SwitchRecord peer = switches.get(p.peerSwitch());
                if (peer == null) {
                    continue;
                }
                PortInfo q = peer.ports.get(p.peerPort());
                if (q != null && p.linkId().equals(q.linkId()) && q.up()) {
                    int latency = p.latencyMs() == null ? 1 : p.latencyMs();
                    g.set(new LinkInfo(p.linkId(), s.id, p.port(), peer.id, p.peerPort(), latency));
                }
            }
        }
        return g;
    }

    private static Map<String, LinkInfo> linksById(TopologyGraph g) {
        Map<String, LinkInfo> out = new HashMap<>();
        for (LinkInfo l : g.links()) {
            out.put(l.linkId(), l);
        }
        return out;
    }

    // ---------- reads ----------

    public NibSnapshot snapshot() {
        lock.readLock().lock();
        try {
            Map<String, SwitchView> views = new TreeMap<>();
            List<NibSnapshot.HostSubnet> hosts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (SwitchRecord s : switches.values()) {
                views.put(s.id, s.view());
                for (PortInfo p : s.ports.values()) {
                    if (p.isHost() && seen.add(p.hostSubnet())) {
                        hosts.add(new NibSnapshot.HostSubnet(p.hostSubnet(), s.id, p.port()));
                    }
                }
            }
            return new NibSnapshot(version, views, graph, List.copyOf(hosts));
        } finally {
            lock.readLock().unlock();
        }
    }

    public SwitchView get(String switchId) {
        lock.readLock().lock();
        try {
            SwitchRecord s = switches.get(switchId);
            return s == null ? null : s.view();
        } finally {
            lock.readLock().unlock();
        }
    }

    public long version() {
        lock.readLock().lock();
        try {
            return version;
        } finally {
            lock.readLock().unlock();
        }
    }
}
