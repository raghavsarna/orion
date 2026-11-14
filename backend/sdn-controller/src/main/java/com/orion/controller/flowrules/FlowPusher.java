package com.orion.controller.flowrules;

import com.orion.controller.ActivityLog;
import com.orion.controller.communication.southbound.SwitchClient;
import com.orion.controller.topology.NibSnapshot;
import com.orion.controller.topology.SwitchStatus;
import com.orion.controller.topology.SwitchView;
import com.orion.shared.flow.FlowBatch;
import com.orion.shared.flow.FlowRule;
import com.orion.shared.flow.FlowTableView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes rules to the switches. Compares the rules each switch should have with what it has and
 * sends only the differences, in a safe order:
 * <ol>
 *   <li>rounds by distance to the destination: the switch nearest the destination gets its new rules first,
 *       the switch nearest the source last (DROP rules go in the very first round);</li>
 *   <li>old rules are deleted only after every new rule is in place.</li>
 * </ol>
 * So no switch ever forwards onto a path that is not ready yet.
 */
public final class FlowPusher {

    private static final Logger log = LoggerFactory.getLogger(FlowPusher.class);

    /** What one push did. */
    public record PushReport(int switchesChanged, int added, int modified, int deleted, List<String> failed) {
    }

    private final SwitchClient client;
    private final ActivityLog activity;
    private final Map<String, Map<String, FlowRule>> installed = new ConcurrentHashMap<>();
    private final Set<String> needsVerify = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(8,
            Thread.ofPlatform().name("flow-push-", 0).daemon(true).factory());

    public FlowPusher(SwitchClient client, ActivityLog activity) {
        this.client = client;
        this.activity = activity;
    }

    /** Re-read this switch's rules before the next push (it rebooted, missed a rule, or a push failed). */
    public void markVerify(String switchId) {
        needsVerify.add(switchId);
    }

    /** What the controller believes the switch has (last read or pushed). */
    public List<FlowRule> installedOn(String switchId) {
        return new ArrayList<>(installed.getOrDefault(switchId, Map.of()).values());
    }

    public void stop() {
        pool.shutdownNow();
    }

    public synchronized PushReport push(Map<String, List<PlannedRule>> desired, NibSnapshot snap, boolean verifyAll) {
        Map<String, String> alive = new LinkedHashMap<>();
        for (SwitchView s : snap.switches().values()) {
            if (s.status() == SwitchStatus.ALIVE) {
                alive.put(s.id(), s.address());
            }
        }
        installed.keySet().retainAll(alive.keySet());

        // 1. refresh our view of switches we are unsure about
        Set<String> skip = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> reads = new ArrayList<>();
        for (Map.Entry<String, String> e : alive.entrySet()) {
            String sw = e.getKey();
            if (verifyAll || !installed.containsKey(sw) || needsVerify.remove(sw)) {
                reads.add(CompletableFuture.runAsync(() -> {
                    try {
                        installed.put(sw, byId(client.flows(e.getValue()).rules()));
                    } catch (SwitchClient.SwitchCallException ex) {
                        needsVerify.add(sw);
                        skip.add(sw);
                    }
                }, pool));
            }
        }
        reads.forEach(CompletableFuture::join);

        // 2. differences per switch
        Map<String, List<PlannedRule>> upserts = new HashMap<>();
        Map<String, Map<String, FlowRule>> deletes = new HashMap<>();
        TreeSet<Integer> rounds = new TreeSet<>();
        for (String sw : alive.keySet()) {
            if (skip.contains(sw)) {
                continue;
            }
            Map<String, FlowRule> have = installed.getOrDefault(sw, Map.of());
            Map<String, PlannedRule> want = new LinkedHashMap<>();
            for (PlannedRule p : desired.getOrDefault(sw, List.of())) {
                want.put(p.rule().ruleId(), p);
            }
            List<PlannedRule> up = new ArrayList<>();
            for (PlannedRule p : want.values()) {
                if (!p.rule().equals(have.get(p.rule().ruleId()))) {
                    up.add(p);
                    rounds.add(p.order());
                }
            }
            Map<String, FlowRule> del = new LinkedHashMap<>();
            have.forEach((id, r) -> {
                if (!want.containsKey(id)) {
                    del.put(id, r);
                }
            });
            if (!up.isEmpty()) {
                upserts.put(sw, up);
            }
            if (!del.isEmpty()) {
                deletes.put(sw, del);
            }
        }
        if (upserts.isEmpty() && deletes.isEmpty()) {
            return new PushReport(0, 0, 0, 0, new ArrayList<>(skip));
        }

        int[] counts = new int[3];   // added, modified, deleted
        Set<String> failed = ConcurrentHashMap.newKeySet();
        failed.addAll(skip);
        Set<String> changed = ConcurrentHashMap.newKeySet();

        // 3. adds/modifies, round by round (destination side first)
        for (int round : rounds) {
            List<CompletableFuture<Void>> pushes = new ArrayList<>();
            for (Map.Entry<String, List<PlannedRule>> e : upserts.entrySet()) {
                String sw = e.getKey();
                if (failed.contains(sw)) {
                    continue;
                }
                List<PlannedRule> thisRound = e.getValue().stream().filter(p -> p.order() == round).toList();
                if (thisRound.isEmpty()) {
                    continue;
                }
                FlowBatch batch = buildBatch(sw, thisRound, e.getValue(), deletes.getOrDefault(sw, new LinkedHashMap<>()));
                synchronized (counts) {
                    counts[0] += batch.add().size();
                    counts[1] += batch.modify().size();
                    counts[2] += batch.delete().size();
                }
                pushes.add(CompletableFuture.runAsync(() -> send(sw, alive.get(sw), batch, failed, changed), pool));
            }
            pushes.forEach(CompletableFuture::join);
        }

        // 4. delete old rules last
        List<CompletableFuture<Void>> removals = new ArrayList<>();
        for (Map.Entry<String, Map<String, FlowRule>> e : deletes.entrySet()) {
            String sw = e.getKey();
            if (failed.contains(sw) || e.getValue().isEmpty()) {
                continue;
            }
            FlowBatch batch = new FlowBatch(List.of(), List.of(), new ArrayList<>(e.getValue().keySet()));
            counts[2] += batch.delete().size();
            removals.add(CompletableFuture.runAsync(() -> send(sw, alive.get(sw), batch, failed, changed), pool));
        }
        removals.forEach(CompletableFuture::join);

        PushReport report = new PushReport(changed.size(), counts[0], counts[1], counts[2], new ArrayList<>(failed));
        activity.add("RULES_PUSHED", report.added() + " added, " + report.modified() + " modified, "
                + report.deleted() + " deleted on " + String.join(", ", new TreeSet<>(changed))
                + (failed.isEmpty() ? "" : "; failed on " + String.join(", ", failed)));
        return report;
    }

    /**
     * One round's batch for a switch. If a new rule's priority is still held by an old rule, that old rule is
     * deleted (or its pending modify is pulled forward) in the same batch, so priorities stay unique.
     */
    private FlowBatch buildBatch(String sw, List<PlannedRule> thisRound, List<PlannedRule> allUpserts,
                                 Map<String, FlowRule> pendingDeletes) {
        Map<String, FlowRule> have = installed.getOrDefault(sw, Map.of());
        Map<String, FlowRule> inBatch = new LinkedHashMap<>();
        thisRound.forEach(p -> inBatch.put(p.rule().ruleId(), p.rule()));
        List<String> delete = new ArrayList<>();

        boolean changedSomething = true;
        while (changedSomething) {
            changedSomething = false;
            Map<Integer, String> occupied = new HashMap<>();
            have.forEach((id, r) -> {
                if (!inBatch.containsKey(id) && !delete.contains(id)) {
                    occupied.put(r.priority(), id);
                }
            });
            for (FlowRule r : new ArrayList<>(inBatch.values())) {
                String holder = occupied.get(r.priority());
                if (holder == null) {
                    continue;
                }
                if (pendingDeletes.remove(holder) != null) {
                    delete.add(holder);
                    changedSomething = true;
                } else {
                    for (PlannedRule p : allUpserts) {
                        if (p.rule().ruleId().equals(holder)) {
                            inBatch.put(holder, p.rule());
                            changedSomething = true;
                        }
                    }
                }
            }
        }
        List<FlowRule> add = new ArrayList<>();
        List<FlowRule> modify = new ArrayList<>();
        for (FlowRule r : inBatch.values()) {
            (have.containsKey(r.ruleId()) ? modify : add).add(r);
        }
        // a rule pulled forward from a later round must not be sent twice
        Set<String> sent = new HashSet<>(inBatch.keySet());
        allUpserts.removeIf(p -> sent.contains(p.rule().ruleId()) && !thisRound.contains(p));
        return new FlowBatch(add, modify, delete);
    }

    private void send(String sw, String address, FlowBatch batch, Set<String> failed, Set<String> changed) {
        try {
            FlowTableView after = client.pushFlows(address, batch);
            installed.put(sw, byId(after.rules()));
            changed.add(sw);
        } catch (SwitchClient.SwitchCallException e) {
            failed.add(sw);
            needsVerify.add(sw);
            log.warn("Pushing rules to {} failed: {}", sw, e.getMessage());
        }
    }

    private static Map<String, FlowRule> byId(List<FlowRule> rules) {
        Map<String, FlowRule> out = new LinkedHashMap<>();
        rules.forEach(r -> out.put(r.ruleId(), r));
        return out;
    }
}
