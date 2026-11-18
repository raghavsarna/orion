package com.orion.controller.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Measures control-plane convergence, the quantity the Orion paper's evaluation reports (§6):
 * the time from a change entering the controller (a NIB event, or an intent change) until the
 * resulting flow rules are programmed on every switch.
 *
 * <p>Each recomputation drains the triggers that arrived before it started, so a burst of events that the
 * Intent Manager coalesces into one recomputation produces one sample (as the paper's NIB coalesces updates).
 */
public final class ConvergenceTracker {

    private static final int CAPACITY = 200;

    /** One thing that made the controller recompute. */
    public record Trigger(String type, String subject, long time) {
    }

    /**
     * One convergence measurement.
     *
     * @param trigger        first trigger type (e.g. LINK_REMOVED)
     * @param subject        what it was about (link / switch / intent)
     * @param triggers       how many triggers were coalesced into this recomputation
     * @param queueMs        trigger -> recomputation starts (includes the 200 ms debounce)
     * @param computeMs      intents + topology -> rules (pure computation)
     * @param programMs      rules sent to switches, in sequenced rounds, until the last one acknowledged
     * @param totalMs        trigger -> every switch programmed
     * @param rounds         programming rounds (destination side first, deletes last)
     */
    public record Sample(long seq, long time, String trigger, String subject, int triggers, long queueMs,
                         long computeMs, long programMs, long totalMs, int rounds, int added, int modified,
                         int deleted, int switchesChanged, String failMode) {
    }

    public record Summary(int count, long medianMs, long p90Ms, long maxMs, double meanComputeMs,
                          double meanProgramMs) {
    }

    private final List<Trigger> pending = new ArrayList<>();
    private final Deque<Sample> samples = new ArrayDeque<>();
    private long seq;

    public synchronized void triggered(String type, String subject, long time) {
        pending.add(new Trigger(type, subject, time));
    }

    /** Called when a recomputation starts: the triggers it will account for. */
    public synchronized List<Trigger> drain() {
        List<Trigger> out = List.copyOf(pending);
        pending.clear();
        return out;
    }

    public synchronized void record(List<Trigger> triggers, long computeStart, long computeEnd, long programEnd,
                                    int rounds, int added, int modified, int deleted, int switchesChanged,
                                    String failMode) {
        if (triggers.isEmpty()) {
            return;   // periodic safety check with nothing new: not a convergence event
        }
        Trigger first = triggers.get(0);
        for (Trigger t : triggers) {
            if (t.time() < first.time()) {
                first = t;
            }
        }
        samples.addLast(new Sample(++seq, programEnd, first.type(), first.subject(), triggers.size(),
                Math.max(0, computeStart - first.time()), computeEnd - computeStart, programEnd - computeEnd,
                Math.max(0, programEnd - first.time()), rounds, added, modified, deleted, switchesChanged, failMode));
        if (samples.size() > CAPACITY) {
            samples.removeFirst();
        }
    }

    public synchronized List<Sample> samples(long afterSeq) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : samples) {
            if (s.seq() > afterSeq) {
                out.add(s);
            }
        }
        return out;
    }

    public synchronized Summary summary() {
        List<Long> totals = new ArrayList<>();
        double compute = 0;
        double program = 0;
        for (Sample s : samples) {
            totals.add(s.totalMs());
            compute += s.computeMs();
            program += s.programMs();
        }
        if (totals.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, 0);
        }
        totals.sort(Long::compare);
        int n = totals.size();
        return new Summary(n, totals.get((n - 1) / 2), totals.get(Math.min(n - 1, (int) Math.ceil(n * 0.9) - 1)),
                totals.get(n - 1), compute / n, program / n);
    }

    public synchronized void clear() {
        samples.clear();
    }
}
