package com.orion.network.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orion.common.flow.Action;
import com.orion.common.flow.FlowBatch;
import com.orion.common.flow.FlowRule;
import com.orion.common.flow.Match;
import com.orion.common.flow.RateLimit;
import com.orion.common.packet.Packet;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FlowTableTest {

    private static final Packet P =
            new Packet("p", "10.0.1.5", 1000, "10.0.3.7", 22, "ETHERNET", "IPV4", "TCP", "SSH", null, 100, 64, 0);

    private static FlowRule rule(String id, int prio, Match m, Action a) {
        return new FlowRule(id, prio, m, a, null, null);
    }

    @Test
    void highestPriorityWinsAndNoMatchReturnsNull() {
        FlowTable t = new FlowTable();
        t.apply(new FlowBatch(List.of(
                rule("fwd", 10, Match.builder().dstIp("10.0.3.0/24").build(), Action.forward(2)),
                rule("drop", 50, Match.builder().appProtocol("SSH").build(), Action.drop())), null, null), 4);
        assertEquals("drop", t.lookup(P, 4, ZonedDateTime.now()).ruleId());
        t.apply(new FlowBatch(null, null, List.of("drop")), 4);
        assertEquals("fwd", t.lookup(P, 4, ZonedDateTime.now()).ruleId());
        t.apply(new FlowBatch(null, null, List.of("fwd")), 4);
        assertNull(t.lookup(P, 4, ZonedDateTime.now()));
    }

    @Test
    void duplicatePriorityIsRejectedAllOrNothing() {
        FlowTable t = new FlowTable();
        t.apply(new FlowBatch(List.of(rule("a", 10, Match.builder().build(), Action.drop())), null, null), 4);
        NetworkException e = assertThrows(NetworkException.class, () -> t.apply(new FlowBatch(List.of(
                rule("b", 20, Match.builder().build(), Action.drop()),
                rule("c", 10, Match.builder().build(), Action.drop())), null, null), 4));
        assertTrue(e.getMessage().contains("priority 10"));
        assertEquals(1, t.view().rules().size());   // nothing from the failed batch was applied
    }

    @Test
    void forwardPortMustExist() {
        FlowTable t = new FlowTable();
        assertThrows(NetworkException.class, () -> t.apply(new FlowBatch(List.of(
                rule("x", 1, Match.builder().build(), Action.forward(9))), null, null), 4));
    }

    @Test
    void tokenBucketLimitsPackets() {
        FlowTable t = new FlowTable();
        FlowRule limited = new FlowRule("rl", 5, Match.builder().build(), Action.forward(1), new RateLimit(5, "k"), null);
        t.apply(new FlowBatch(List.of(limited), null, null), 4);
        int allowed = 0;
        for (int i = 0; i < 50; i++) {
            if (t.allowedByRateLimit(limited)) {
                allowed++;
            }
        }
        assertTrue(allowed >= 5 && allowed <= 6, "allowed " + allowed);
    }
}
