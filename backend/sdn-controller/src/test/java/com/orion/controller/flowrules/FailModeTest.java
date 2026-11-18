package com.orion.controller.flowrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orion.controller.ActivityLog;
import com.orion.controller.topology.Nib;
import com.orion.shared.flow.FlowRule;
import com.orion.shared.messages.PortInfo;
import com.orion.shared.messages.SwitchRegistration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The demo fabric (config/topology.yaml), Figure 1 of the Orion paper: top t1, t2; middle m1..m4; bottom b1, b2.
 * Every top and bottom switch links to every middle switch (16 links). t1 port k goes to mk; hosts on port 5.
 */
class FailModeTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 18, 12, 0, 0, 0, ZoneId.of("Asia/Kolkata"));
    private static final String[] EDGES = {"t1", "t2", "b1", "b2"};
    private static final String[] SUBNETS = {"10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24", "10.0.4.0/24"};
    private final RuleGenerator gen = new RuleGenerator();

    private static Nib fabric() {
        Nib nib = new Nib(new ActivityLog());
        for (int e = 0; e < EDGES.length; e++) {
            List<PortInfo> ports = new ArrayList<>();
            for (int m = 1; m <= 4; m++) {
                ports.add(new PortInfo(m, link(EDGES[e], m), "m" + m, e + 1, m + 1, null, true));
            }
            ports.add(new PortInfo(5, null, null, null, null, SUBNETS[e], true));
            nib.register(new SwitchRegistration(EDGES[e], EDGES[e], "http://x/" + EDGES[e], "boot", 5, ports));
        }
        for (int m = 1; m <= 4; m++) {
            List<PortInfo> ports = new ArrayList<>();
            for (int e = 0; e < EDGES.length; e++) {
                ports.add(new PortInfo(e + 1, link(EDGES[e], m), EDGES[e], m, m + 1, null, true));
            }
            nib.register(new SwitchRegistration("m" + m, "m" + m, "http://x/m" + m, "boot", 4, ports));
        }
        return nib;
    }

    private static String link(String edge, int m) {
        return edge.startsWith("t") ? edge + "m" + m : "m" + m + edge;
    }

    private GenerationResult run(Nib nib, double threshold) {
        return gen.generate(nib.snapshot(), List.of(), Map.of(), NOW, threshold);
    }

    private static List<Integer> portsTo(GenerationResult r, String sw, String dst) {
        FlowRule rule = r.rules().get(sw).stream().map(PlannedRule::rule)
                .filter(x -> dst.equals(x.match().dstIp())).findFirst().orElseThrow();
        return rule.action().allPorts();
    }

    @Test
    void healthyFabricSpreadsOverEveryMiddleSwitch() {
        GenerationResult r = run(fabric(), 0.4);
        assertEquals(FailMode.NORMAL, r.failMode().mode());
        assertEquals(List.of(1, 2, 3, 4), portsTo(r, "t1", "10.0.3.0/24"));
        assertEquals(List.of(5), portsTo(r, "b1", "10.0.3.0/24"));
        assertEquals(List.of(3), portsTo(r, "m2", "10.0.3.0/24"));
    }

    @Test
    void oneUnknownMiddleSwitchIsRoutedAround() {
        Nib nib = fabric();
        nib.markDead("m1", "test");
        GenerationResult r = run(nib, 0.4);
        assertEquals(FailMode.FAIL_CLOSED, r.failMode().mode());
        assertEquals(4, r.failMode().linksLost());
        assertEquals(0.25, r.failMode().degradation(), 1e-9);
        assertEquals(List.of(2, 3, 4), portsTo(r, "t1", "10.0.3.0/24"));
    }

    @Test
    void manyUnknownSwitchesFailStatic() {
        Nib nib = fabric();
        nib.markDead("m1", "test");
        nib.markDead("m2", "test");
        GenerationResult r = run(nib, 0.4);
        assertEquals(FailMode.FAIL_STATIC, r.failMode().mode());
        assertEquals(0.5, r.failMode().degradation(), 1e-9);
        assertEquals(List.of(1, 2, 3, 4), portsTo(r, "t1", "10.0.3.0/24"));
        assertTrue(r.failMode().explain().contains("keeps using them"));
    }

    @Test
    void higherThresholdStaysFailClosed() {
        Nib nib = fabric();
        nib.markDead("m1", "test");
        nib.markDead("m2", "test");
        GenerationResult r = run(nib, 0.6);
        assertEquals(FailMode.FAIL_CLOSED, r.failMode().mode());
        assertEquals(List.of(3, 4), portsTo(r, "t1", "10.0.3.0/24"));
    }

    @Test
    void unknownEdgeSwitchFailsStaticToKeepItsSubnetReachable() {
        Nib nib = fabric();
        nib.markDead("b1", "test");
        GenerationResult r = run(nib, 0.9);
        assertEquals(FailMode.FAIL_STATIC, r.failMode().mode());
        assertEquals("10.0.3.0/24", r.failMode().disconnects());
        assertEquals(List.of(1, 2, 3, 4), portsTo(r, "t1", "10.0.3.0/24"));
    }
}
