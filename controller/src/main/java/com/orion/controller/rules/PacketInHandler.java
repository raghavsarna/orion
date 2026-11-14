package com.orion.controller.rules;

import com.orion.common.flow.FlowRule;
import com.orion.common.southbound.PacketIn;
import com.orion.common.southbound.PacketOut;
import com.orion.controller.core.ActivityLog;
import com.orion.controller.intent.IntentManager;
import com.orion.controller.nib.Nib;
import com.orion.controller.nib.SwitchView;
import com.orion.controller.southbound.SwitchClient;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A switch sent a packet that matched no rule. Rules are installed in advance, so this means the switch
 * is missing a rule (or the destination is unknown). Decide with the rules the switch *should* have:
 * forward -> packet-out on that port and fix the switch; drop or unknown -> drop.
 */
public final class PacketInHandler {

    private final Nib nib;
    private final IntentManager intents;
    private final SwitchClient client;
    private final ActivityLog activity;
    private final ZoneId zone;
    private final ExecutorService pool = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().name("packet-in-", 0).daemon(true).factory());

    public PacketInHandler(Nib nib, IntentManager intents, SwitchClient client, ActivityLog activity, ZoneId zone) {
        this.nib = nib;
        this.intents = intents;
        this.client = client;
        this.activity = activity;
        this.zone = zone;
    }

    public void handle(PacketIn in) {
        pool.execute(() -> process(in));
    }

    private void process(PacketIn in) {
        SwitchView sw = nib.get(in.switchId());
        if (sw == null || in.packet() == null) {
            return;
        }
        List<FlowRule> rules = intents.desiredOn(in.switchId());
        ZonedDateTime now = ZonedDateTime.now(zone);
        for (FlowRule r : rules) {
            if (r.match() == null || r.match().matches(in.packet(), in.inPort(), now)) {
                if (r.action().isForward()) {
                    try {
                        client.packetOut(sw.address(), new PacketOut(in.packet(), r.action().port()));
                        activity.add("PACKET_IN", in.switchId() + " " + in.packet().summary() + " -> packet-out port "
                                + r.action().port() + " (switch was missing rule " + r.ruleId() + ")");
                    } catch (SwitchClient.SwitchCallException e) {
                        activity.add("PACKET_IN", in.switchId() + " packet-out failed: " + e.getMessage());
                    }
                    intents.onDrift(in.switchId());
                } else {
                    activity.add("PACKET_IN", in.switchId() + " " + in.packet().summary() + " -> dropped by " + r.ruleId());
                }
                return;
            }
        }
        activity.add("PACKET_IN", in.switchId() + " " + in.packet().summary() + " -> dropped (unknown destination)");
    }

    public void stop() {
        pool.shutdownNow();
    }
}
