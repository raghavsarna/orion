package com.orion.switchnetwork.processing;

import com.orion.shared.packet.Packet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every packet a switch handles is logged here (one line per switch hop) and printed to the console.
 * Keeps the most recent entries in memory for the API/UI.
 */
public final class PacketLog {

    private static final Logger log = LoggerFactory.getLogger("packets");
    private static final int CAPACITY = 3000;

    /**
     * One handled packet at one switch. For DELIVERED packets: {@code pathLatencyMs} = simulated latency (sum of
     * the links' configured latencies), {@code latencyMs} = wall-clock time since sent (adds host scheduling jitter).
     */
    public record Entry(long seq, long time, String switchId, String packetId, int inPort, String event,
                        Integer outPort, String ruleId, String packet, Long latencyMs, Integer pathLatencyMs,
                        Integer srcPort) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long seq;

    public void record(String switchId, Packet p, int inPort, String event, Integer outPort, String ruleId) {
        boolean delivered = "DELIVERED".equals(event);
        Long latency = delivered ? Math.max(0, System.currentTimeMillis() - p.sentAt()) : null;
        Integer path = delivered ? p.pathMs() : null;
        Entry e;
        synchronized (this) {
            e = new Entry(++seq, System.currentTimeMillis(), switchId, p.id(), inPort, event, outPort, ruleId,
                    p.summary(), latency, path, p.srcPort());
            entries.addLast(e);
            if (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
        }
        log.info("[{}] pkt={} in={} {}{}{} | {}{}", switchId, p.id(), inPort, event,
                outPort == null ? "" : " out=" + outPort,
                ruleId == null ? "" : " rule=" + ruleId,
                p.summary(),
                latency == null ? "" : " | path " + path + " ms, wall clock " + latency + " ms");
    }

    /** Entries newer than {@code afterSeq}, optionally for one switch, oldest first, at most {@code limit}. */
    public synchronized List<Entry> since(long afterSeq, String switchId, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > afterSeq && (switchId == null || switchId.isBlank() || switchId.equals(e.switchId()))) {
                out.add(e);
            }
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    public synchronized void clear() {
        entries.clear();
    }
}
