package com.orion.network.core;

import com.orion.common.packet.Packet;
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

    /** One handled packet at one switch. */
    public record Entry(long seq, long time, String switchId, String packetId, int inPort, String event,
                        Integer outPort, String ruleId, String packet, Long latencyMs) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long seq;

    public void record(String switchId, Packet p, int inPort, String event, Integer outPort, String ruleId) {
        Long latency = "DELIVERED".equals(event) ? Math.max(0, System.currentTimeMillis() - p.sentAt()) : null;
        Entry e;
        synchronized (this) {
            e = new Entry(++seq, System.currentTimeMillis(), switchId, p.id(), inPort, event, outPort, ruleId,
                    p.summary(), latency);
            entries.addLast(e);
            if (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
        }
        log.info("[{}] pkt={} in={} {}{}{} | {}{}", switchId, p.id(), inPort, event,
                outPort == null ? "" : " out=" + outPort,
                ruleId == null ? "" : " rule=" + ruleId,
                p.summary(),
                latency == null ? "" : " | " + latency + " ms");
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
