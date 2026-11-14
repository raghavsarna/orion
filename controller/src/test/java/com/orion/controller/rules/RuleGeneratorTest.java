package com.orion.controller.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orion.common.flow.FlowRule;
import com.orion.common.flow.TimeWindow;
import com.orion.common.southbound.PortInfo;
import com.orion.common.southbound.SwitchRegistration;
import com.orion.controller.core.ActivityLog;
import com.orion.controller.intent.Intent;
import com.orion.controller.intent.IntentSpec;
import com.orion.controller.nib.Nib;
import com.orion.controller.nib.NibSnapshot;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Sample topology (same as config/topology.yaml):
 * s1 (10.0.1.0/24 on port 4) --l1 5ms-- s2 --l2 5ms-- s3 (10.0.3.0/24 on port 4), plus s1 --l3 20ms-- s3.
 */
class RuleGeneratorTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 12, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
    private final RuleGenerator gen = new RuleGenerator();

    private static NibSnapshot topology() {
        Nib nib = new Nib(new ActivityLog());
        nib.register(new SwitchRegistration("s1", "s1", "http://x/switch/s1", "b1", 4, List.of(
                new PortInfo(1, "l1", "s2", 1, 5, null, true),
                new PortInfo(2, "l3", "s3", 2, 20, null, true),
                new PortInfo(4, null, null, null, null, "10.0.1.0/24", true))));
        nib.register(new SwitchRegistration("s2", "s2", "http://x/switch/s2", "b2", 4, List.of(
                new PortInfo(1, "l1", "s1", 1, 5, null, true),
                new PortInfo(2, "l2", "s3", 1, 5, null, true))));
        nib.register(new SwitchRegistration("s3", "s3", "http://x/switch/s3", "b3", 4, List.of(
                new PortInfo(1, "l2", "s2", 2, 5, null, true),
                new PortInfo(2, "l3", "s1", 2, 20, null, true),
                new PortInfo(4, null, null, null, null, "10.0.3.0/24", true))));
        return nib.snapshot();
    }

    private static Intent intent(IntentSpec spec) {
        return new Intent(UUID.randomUUID(), spec, Intent.PENDING, null, Instant.now());
    }

    private static IntentSpec spec(String type, String src, String dst, String protocol, String link, Integer maxMs,
                                   Integer pps, TimeWindow tw) {
        return new IntentSpec(type, src, dst, protocol, null, null, link, maxMs, pps, tw, null);
    }

    private static FlowRule forwardTo(GenerationResult r, String sw, String dst, String kind) {
        return r.rules().get(sw).stream().filter(p -> p.kind().equals(kind))
                .filter(p -> dst.equals(p.rule().match().dstIp())).findFirst().orElseThrow().rule();
    }

    @Test
    void baselineUsesFewestHops() {
        GenerationResult r = gen.generate(topology(), List.of(), Map.of(), NOW);
        assertEquals(2, forwardTo(r, "s1", "10.0.3.0/24", PlannedRule.BASELINE).action().port()); // direct l3
        assertEquals(2, forwardTo(r, "s2", "10.0.3.0/24", PlannedRule.BASELINE).action().port());
        assertEquals(4, forwardTo(r, "s3", "10.0.3.0/24", PlannedRule.BASELINE).action().port());
        assertEquals(4, forwardTo(r, "s1", "10.0.1.0/24", PlannedRule.BASELINE).action().port());
    }

    @Test
    void latencyIntentTakesTheFastPathOrIsNotPossible() {
        Intent ok = intent(spec("MAX_LATENCY", "10.0.1.0/24", "10.0.3.0/24", null, null, 15, null, null));
        Intent tooFast = intent(spec("MAX_LATENCY", "10.0.1.0/24", "10.0.3.0/24", null, null, 5, null, null));
        GenerationResult r = gen.generate(topology(), List.of(ok, tooFast), Map.of(), NOW);
        assertEquals(Intent.ACTIVE, r.outcomes().get(ok.id()).status());
        assertEquals(1, forwardTo(r, "s1", "10.0.3.0/24", PlannedRule.PATH).action().port()); // via s2, 10 ms
        assertEquals(Intent.NOT_POSSIBLE, r.outcomes().get(tooFast.id()).status());
        assertTrue(r.outcomes().get(tooFast.id()).reason().contains("10 ms"));
    }

    @Test
    void avoidLinkReroutesAndRefusesToCutTheNetwork() {
        Intent avoidL3 = intent(spec("AVOID_LINK", null, null, null, "l3", null, null, null));
        Intent avoidL1 = intent(spec("AVOID_LINK", null, null, null, "l1", null, null, null));
        GenerationResult r = gen.generate(topology(), List.of(avoidL3, avoidL1), Map.of(), NOW);
        assertEquals(Intent.ACTIVE, r.outcomes().get(avoidL3.id()).status());
        assertEquals(1, forwardTo(r, "s1", "10.0.3.0/24", PlannedRule.BASELINE).action().port());
        assertEquals(Intent.NOT_POSSIBLE, r.outcomes().get(avoidL1.id()).status());
    }

    @Test
    void blockProtocolDropsAtTheEntrySwitchAboveForwarding() {
        Intent block = intent(spec("BLOCK_PROTOCOL", "group:guests", null, "SSH", null, null, null, null));
        GenerationResult r = gen.generate(topology(), List.of(block), Map.of("guests", List.of("10.0.1.0/24")), NOW);
        assertEquals(Intent.ACTIVE, r.outcomes().get(block.id()).status());
        FlowRule drop = r.rules().get("s1").stream().filter(p -> p.kind().equals(PlannedRule.DROP)).findFirst()
                .orElseThrow().rule();
        assertEquals("SSH", drop.match().appProtocol());
        assertTrue(drop.priority() > forwardTo(r, "s1", "10.0.3.0/24", PlannedRule.BASELINE).priority());
        assertTrue(r.rules().get("s3").stream().noneMatch(p -> p.kind().equals(PlannedRule.DROP)));
    }

    @Test
    void unknownGroupAndExpiredIntent() {
        Intent unknown = intent(spec("ISOLATE_GROUPS", "group:nope", "10.0.3.0/24", null, null, null, null, null));
        Intent expired = intent(spec("BLOCK_PROTOCOL", null, null, "UDP", null, null, null,
                new TimeWindow(null, null, "2026-01-01T00:00")));
        GenerationResult r = gen.generate(topology(), List.of(unknown, expired), Map.of(), NOW);
        assertEquals(Intent.NOT_POSSIBLE, r.outcomes().get(unknown.id()).status());
        assertEquals(Intent.EXPIRED, r.outcomes().get(expired.id()).status());
    }

    @Test
    void rateLimitSharesOneBucketPerIntent() {
        Intent rl = intent(spec("RATE_LIMIT", "10.0.1.5", null, null, null, null, 10, null));
        GenerationResult r = gen.generate(topology(), List.of(rl), Map.of(), NOW);
        List<PlannedRule> limited = r.rules().get("s1").stream().filter(p -> p.kind().equals(PlannedRule.RATE_LIMIT)).toList();
        assertEquals(2, limited.size());   // one per destination subnet
        assertEquals(1, limited.stream().map(p -> p.rule().rateLimit().key()).distinct().count());
    }

    @Test
    void prioritiesAreUniqueAndIdsAreStable() {
        Intent block = intent(spec("BLOCK_PROTOCOL", null, null, "UDP", null, null, null, null));
        GenerationResult a = gen.generate(topology(), List.of(block), Map.of(), NOW);
        GenerationResult b = gen.generate(topology(), List.of(block), Map.of(), NOW);
        for (List<PlannedRule> rules : a.rules().values()) {
            Set<Integer> seen = new HashSet<>();
            rules.forEach(p -> assertTrue(seen.add(p.rule().priority()), "duplicate priority"));
        }
        assertEquals(a.rules(), b.rules());
    }
}
