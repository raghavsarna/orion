package com.orion.controller.intent;

import com.orion.common.flow.FlowRule;
import com.orion.controller.core.ActivityLog;
import com.orion.controller.core.ControllerException;
import com.orion.controller.core.ControllerSettings;
import com.orion.controller.db.GroupRepository;
import com.orion.controller.db.IntentRepository;
import com.orion.controller.nib.Nib;
import com.orion.controller.nib.NibEvent;
import com.orion.controller.nib.NibSnapshot;
import com.orion.controller.rules.FlowPusher;
import com.orion.controller.rules.GenerationResult;
import com.orion.controller.rules.PlannedRule;
import com.orion.controller.rules.RuleGenerator;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Component 2: keeps intents in the database and turns them into flow rules on the switches.
 * Rules are regenerated (all of them; the network is small) when:
 * the controller starts, the user changes intents or groups, the NIB reports a change,
 * and periodically as a safety check (re-reads topology and the switches' rules to fix drift).
 * All work runs on one thread, so two recomputations never race.
 */
public final class IntentManager {

    private static final Logger log = LoggerFactory.getLogger(IntentManager.class);
    private static final long DEBOUNCE_MS = 200;
    /**
     * After a controller restart the NIB is empty until switches re-register (they probe every few seconds).
     * Computing rules earlier would see a partial network and delete good rules, so the first computation
     * waits this long. Switches keep forwarding with their existing rules meanwhile.
     */
    private static final long STARTUP_GRACE_MS = 8000;
    private volatile long readyAt = Long.MAX_VALUE;

    private final IntentRepository intents;
    private final GroupRepository groups;
    private final Nib nib;
    private final RuleGenerator generator;
    private final FlowPusher pusher;
    private final IntentValidator validator = new IntentValidator();
    private final ControllerSettings settings;
    private final ActivityLog activity;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("intent-manager").daemon(true).factory());
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final AtomicBoolean verifyRequested = new AtomicBoolean();
    private volatile GenerationResult last = GenerationResult.empty();
    private volatile List<Intent> lastIntents = List.of();

    public IntentManager(IntentRepository intents, GroupRepository groups, Nib nib, RuleGenerator generator,
                         FlowPusher pusher, ControllerSettings settings, ActivityLog activity) {
        this.intents = intents;
        this.groups = groups;
        this.nib = nib;
        this.generator = generator;
        this.pusher = pusher;
        this.settings = settings;
        this.activity = activity;
    }

    /** Called once the database is ready: first computation, then the periodic safety check. */
    public void start() {
        readyAt = System.currentTimeMillis() + STARTUP_GRACE_MS;
        worker.schedule(() -> requestRecompute(true), STARTUP_GRACE_MS, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(() -> requestRecompute(true), settings.safetyCheckSec(),
                settings.safetyCheckSec(), TimeUnit.SECONDS);
    }

    public void stop() {
        worker.shutdownNow();
    }

    /** NIB notification (this manager is a NIB subscriber). */
    public void onNibEvent(NibEvent e) {
        switch (e.type()) {
            case "SWITCH_ADDED", "SWITCH_REBOOTED", "SWITCH_ALIVE" -> pusher.markVerify(e.switchId());
            default -> {
            }
        }
        requestRecompute(false);
    }

    /** A switch missed a rule (packet-in for a known destination): re-read it and fix it. */
    public void onDrift(String switchId) {
        pusher.markVerify(switchId);
        requestRecompute(false);
    }

    /** Coalesces bursts of requests into one recomputation shortly after. */
    public void requestRecompute(boolean verify) {
        if (verify) {
            verifyRequested.set(true);
        }
        if (scheduled.compareAndSet(false, true)) {
            worker.schedule(() -> {
                scheduled.set(false);
                recompute(verifyRequested.getAndSet(false));
            }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void recompute(boolean verify) {
        if (System.currentTimeMillis() < readyAt) {
            verifyRequested.set(true);   // the scheduled first run will pick this up
            return;
        }
        try {
            List<Intent> all = intents.findAll();
            Map<String, List<String>> groupMap = groups.findAll();
            NibSnapshot snap = nib.snapshot();
            GenerationResult result = generator.generate(snap, all, groupMap, ZonedDateTime.now(settings.zone()));
            FlowPusher.PushReport report = pusher.push(result.rules(), snap, verify);
            if (!report.failed().isEmpty()) {
                // a switch could not be read or written (e.g. still starting): try again shortly
                worker.schedule(() -> requestRecompute(false), 2, TimeUnit.SECONDS);
            }
            saveOutcomes(all, result);
            last = result;
            lastIntents = all;
        } catch (ControllerException e) {
            // database down: switches keep the rules they have; try again on the next trigger
            log.warn("Recompute skipped: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Recompute failed", e);
        }
    }

    private void saveOutcomes(List<Intent> all, GenerationResult result) {
        for (Intent i : all) {
            GenerationResult.Outcome o = result.outcomes().get(i.id());
            if (o == null) {
                continue;
            }
            if (!Objects.equals(o.status(), i.status()) || !Objects.equals(o.reason(), i.reason())) {
                intents.updateStatus(i.id(), o.status(), o.reason());
                if (!Objects.equals(o.status(), i.status())) {
                    activity.add("INTENT_" + o.status(), label(i) + (o.reason() == null || o.reason().isBlank()
                            ? "" : ": " + o.reason()));
                }
            }
        }
    }

    // ---------- user operations ----------

    public Intent add(IntentSpec spec) {
        IntentSpec clean = validator.validate(spec);
        Intent intent = new Intent(UUID.randomUUID(), clean, Intent.PENDING, "generating rules", Instant.now());
        intents.insert(intent);
        activity.add("INTENT_STORED", label(intent));
        requestRecompute(false);
        return intent;
    }

    public void delete(UUID id) {
        if (!intents.delete(id)) {
            throw ControllerException.notFound("no intent " + id);
        }
        activity.add("INTENT_DELETED", id.toString());
        requestRecompute(false);
    }

    public List<Intent> list() {
        return intents.findAll();
    }

    public IntentSpec validate(IntentSpec spec) {
        return validator.validate(spec);
    }

    // ---------- views ----------

    /** Rules the switch should have (from the last computation). */
    public List<FlowRule> desiredOn(String switchId) {
        List<FlowRule> out = new ArrayList<>();
        for (PlannedRule p : last.rules().getOrDefault(switchId, List.of())) {
            out.add(p.rule());
        }
        return out;
    }

    private static String label(Intent i) {
        IntentSpec s = i.spec();
        StringBuilder sb = new StringBuilder(s.type());
        if (s.src() != null) {
            sb.append(" src=").append(s.src());
        }
        if (s.dst() != null) {
            sb.append(" dst=").append(s.dst());
        }
        if (s.protocol() != null) {
            sb.append(" protocol=").append(s.protocol());
        }
        if (s.port() != null) {
            sb.append(" port=").append(s.port());
        }
        if (s.website() != null) {
            sb.append(" website=").append(s.website());
        }
        if (s.linkId() != null) {
            sb.append(" link=").append(s.linkId());
        }
        if (s.maxLatencyMs() != null) {
            sb.append(" max=").append(s.maxLatencyMs()).append("ms");
        }
        if (s.packetsPerSecond() != null) {
            sb.append(" limit=").append(s.packetsPerSecond()).append("pps");
        }
        return sb.toString();
    }
}
