package com.orion.controller.topology;

import com.orion.controller.ActivityLog;
import com.orion.controller.ControllerException;
import com.orion.controller.ControllerSettings;
import com.orion.controller.communication.southbound.SwitchClient;
import com.orion.controller.storage.ManualSwitchRepository;
import com.orion.shared.messages.LinkNotification;
import com.orion.shared.messages.PortInfo;
import com.orion.shared.messages.ProbeReply;
import com.orion.shared.messages.SwitchProbe;
import com.orion.shared.messages.SwitchRegistration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and maintains the topology (component 1). It is the only writer to the NIB:
 * registration, manual add, link notifications, probing, dead-switch detection with neighbour checks.
 */
public final class TopologyManager {

    private static final Logger log = LoggerFactory.getLogger(TopologyManager.class);

    private final Nib nib;
    private final SwitchClient client;
    private final ManualSwitchRepository manualSwitches;
    private final ControllerSettings settings;
    private final ActivityLog activity;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("topology-probe").daemon(true).factory());
    private final ExecutorService probePool = Executors.newFixedThreadPool(8,
            Thread.ofPlatform().name("probe-", 0).daemon(true).factory());

    public TopologyManager(Nib nib, SwitchClient client, ManualSwitchRepository manualSwitches,
                           ControllerSettings settings, ActivityLog activity) {
        this.nib = nib;
        this.client = client;
        this.manualSwitches = manualSwitches;
        this.settings = settings;
        this.activity = activity;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::probeCycle, settings.probeIntervalSec(), settings.probeIntervalSec(),
                TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
        probePool.shutdownNow();
    }

    // ---------- southbound: switch -> controller ----------

    public void register(SwitchRegistration reg) {
        if (reg.switchId() == null || reg.switchId().isBlank() || reg.switchAddress() == null || reg.bootId() == null) {
            throw ControllerException.badRequest("registration needs switchId, switchAddress and bootId");
        }
        nib.register(reg);
    }

    /** A switch checks the controller is alive. known=false tells it to register again. */
    public ProbeReply onSwitchProbe(SwitchProbe probe) {
        SwitchView s = nib.get(probe.switchId());
        boolean known = s != null && s.bootId().equals(probe.bootId());
        if (known) {
            nib.touch(probe.switchId());
        }
        return new ProbeReply(known);
    }

    /** Link added/deleted on a switch port. Returns false for an unknown switch (it should re-register). */
    public boolean onNotification(LinkNotification n) {
        boolean known = true;
        if (n.addedLink() != null) {
            known = nib.updatePort(n.switchId(), n.addedLink(), true);
        }
        if (n.deletedLink() != null) {
            known = nib.updatePort(n.switchId(), n.deletedLink(), false) && known;
        }
        return known;
    }

    // ---------- manual add ----------

    /**
     * Adds a switch that could not register by itself: remember it in the DB, then fetch its details
     * from its address. Returns true if the switch answered now (otherwise it is retried every probe cycle).
     */
    public boolean addManually(String switchId, String address) {
        if (switchId == null || switchId.isBlank() || address == null || !address.startsWith("http")) {
            throw ControllerException.badRequest("switchId and an http address are required");
        }
        manualSwitches.save(switchId, address);
        activity.add("MANUAL_ADD", switchId + " at " + address);
        return contact(switchId, address);
    }

    public void forget(String switchId) {
        manualSwitches.delete(switchId);
        nib.remove(switchId, "removed by user");
    }

    private boolean contact(String switchId, String address) {
        try {
            SwitchRegistration reg = client.details(address);
            if (!switchId.equals(reg.switchId())) {
                log.warn("Address {} answered as {} (expected {})", address, reg.switchId(), switchId);
            }
            register(reg);
            return true;
        } catch (SwitchClient.SwitchCallException e) {
            log.debug("Manual switch {} not reachable yet: {}", switchId, e.getMessage());
            return false;
        }
    }

    /** At startup: try to reach every switch that was added manually before. */
    public void contactManualSwitches() {
        try {
            for (Map.Entry<String, String> e : manualSwitches.findAll().entrySet()) {
                if (nib.get(e.getKey()) == null) {
                    contact(e.getKey(), e.getValue());
                }
            }
        } catch (ControllerException e) {
            log.debug("Manual switches not loaded: {}", e.getMessage());
        }
    }

    // ---------- probing ----------

    private void probeCycle() {
        try {
            NibSnapshot snap = nib.snapshot();
            List<CompletableFuture<Void>> probes = new ArrayList<>();
            for (SwitchView s : snap.switches().values()) {
                probes.add(CompletableFuture.runAsync(() -> probe(s), probePool));
            }
            CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new)).join();
            contactManualSwitches();
        } catch (RuntimeException e) {
            log.warn("Probe cycle failed: {}", e.getMessage());
        }
    }

    private void probe(SwitchView s) {
        try {
            SwitchProbe reply = client.probe(s.address());
            if (!reply.bootId().equals(s.bootId())) {
                // the switch restarted (empty flow table): fetch its details again
                register(client.details(s.address()));
            } else {
                nib.markAlive(s.id());
            }
        } catch (SwitchClient.SwitchCallException e) {
            int missed = nib.recordMissedProbe(s.id());
            if (missed >= settings.maxMissedProbes()) {
                if (s.status() == SwitchStatus.ALIVE) {
                    nib.markDead(s.id(), "missed " + missed + " probes");
                }
                checkNeighbours(s);
            }
        }
    }

    /**
     * The switch is dead. Ask each neighbour about the link to it:
     * any neighbour says "up" -> keep it as DEAD; otherwise -> remove it (and its links) from the topology.
     */
    private void checkNeighbours(SwitchView dead) {
        boolean anyUp = false;
        List<String> answers = new ArrayList<>();
        for (PortInfo p : dead.ports()) {
            if (!p.isLink() || p.peerSwitch() == null) {
                continue;
            }
            SwitchView peer = nib.get(p.peerSwitch());
            if (peer == null || peer.status() != SwitchStatus.ALIVE) {
                continue;
            }
            Optional<Boolean> up = client.linkUp(peer.address(), p.linkId());
            answers.add(peer.id() + " says " + p.linkId() + " is " + up.map(u -> u ? "up" : "down").orElse("unknown"));
            if (up.orElse(false)) {
                anyUp = true;
            }
        }
        if (anyUp) {
            log.info("Switch {} stays DEAD: {}", dead.id(), answers);
        } else {
            nib.remove(dead.id(), answers.isEmpty() ? "no neighbour sees it" : String.join(", ", answers));
        }
    }
}
