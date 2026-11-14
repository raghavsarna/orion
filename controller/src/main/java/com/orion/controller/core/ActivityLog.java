package com.orion.controller.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Recent controller activity (topology events, rule pushes, packet-ins) shown in the UI and printed to the log. */
public final class ActivityLog {

    private static final Logger log = LoggerFactory.getLogger("activity");
    private static final int CAPACITY = 500;

    public record Entry(long seq, long time, String type, String message) {
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private long seq;

    public void add(String type, String message) {
        synchronized (this) {
            entries.addLast(new Entry(++seq, System.currentTimeMillis(), type, message));
            if (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
        }
        log.info("{} {}", type, message);
    }

    public synchronized List<Entry> since(long afterSeq, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.seq() > afterSeq) {
                out.add(e);
            }
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }
}
