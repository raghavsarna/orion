package com.orion.network.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.orion.common.packet.Packet;
import com.orion.common.southbound.LinkNotification;
import com.orion.common.southbound.PacketIn;
import com.orion.common.southbound.PortInfo;
import com.orion.common.southbound.ProbeReply;
import com.orion.common.southbound.Southbound;
import com.orion.common.southbound.SwitchProbe;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A switch's link to the controller: registers on start (retrying until it works), probes the
 * controller regularly, reports link changes and sends packet-ins.
 * If the controller is unreachable the switch keeps forwarding with the rules it already has.
 */
public final class ControllerConnection {

    private static final Logger log = LoggerFactory.getLogger(ControllerConnection.class);

    private final Switch sw;
    private final SwitchNetwork network;
    private final boolean autoRegister;
    private volatile boolean registered;
    private volatile boolean reachable;
    private volatile boolean contactedByController;
    private ScheduledFuture<?> task;

    ControllerConnection(Switch sw, SwitchNetwork network, boolean autoRegister) {
        this.sw = sw;
        this.network = network;
        this.autoRegister = autoRegister;
    }

    void start() {
        long firstDelay = 300 + ThreadLocalRandom.current().nextInt(700);
        task = network.controlScheduler().scheduleWithFixedDelay(this::tick, firstDelay,
                network.settings().heartbeatSec() * 1000L, TimeUnit.MILLISECONDS);
    }

    void stop() {
        if (task != null) {
            task.cancel(true);
        }
    }

    private void tick() {
        try {
            if (!sw.isControlConnected()) {
                setReachable(false);
                return;
            }
            if (!registered) {
                if (autoRegister || contactedByController) {
                    register();
                }
                return;
            }
            probe();
        } catch (RuntimeException e) {
            log.warn("Switch {}: controller check failed: {}", sw.id(), e.getMessage());
        }
    }

    private void register() {
        try {
            HttpResponse<String> r = post("/controller/register", sw.registration());
            if (r.statusCode() / 100 == 2) {
                registered = true;
                setReachable(true);
                log.info("Switch {} registered with controller {}", sw.id(), network.settings().controllerUrl());
            } else {
                setReachable(false);
                log.warn("Switch {}: registration rejected ({}): {}", sw.id(), r.statusCode(), r.body());
            }
        } catch (IOException e) {
            setReachable(false);
        }
    }

    private void probe() {
        try {
            HttpResponse<String> r = post("/controller/probe", new SwitchProbe(sw.id(), sw.bootId()));
            if (r.statusCode() / 100 != 2) {
                setReachable(false);
                return;
            }
            setReachable(true);
            ProbeReply reply = network.json().readValue(r.body(), ProbeReply.class);
            if (!reply.known()) {
                log.info("Switch {}: controller does not know this switch, registering again", sw.id());
                registered = false;
                register();
            }
        } catch (IOException e) {
            setReachable(false);
        }
    }

    /** The controller fetched our details itself (manual add), so from now on act as registered. */
    void markContactedByController() {
        contactedByController = true;
        registered = true;
        setReachable(true);
    }

    void notifyLink(PortInfo added, PortInfo deleted) {
        if (!sw.isControlConnected() || !registered) {
            return;
        }
        postAsync("/controller/notification", new LinkNotification(sw.id(), added, deleted), true);
    }

    /** Returns false when the packet cannot be sent (controller unreachable), so the switch drops it. */
    boolean sendPacketIn(Packet packet, int inPort) {
        if (!sw.isControlConnected() || !registered || !reachable) {
            return false;
        }
        postAsync("/controller/packet-in", new PacketIn(sw.id(), inPort, packet), false);
        return true;
    }

    private void setReachable(boolean now) {
        if (reachable != now) {
            reachable = now;
            if (now) {
                log.info("Switch {}: controller reachable", sw.id());
            } else {
                log.warn("Switch {}: controller unreachable, keeping existing flow rules", sw.id());
            }
        }
    }

    private HttpRequest request(String path, Object body) throws JsonProcessingException {
        return HttpRequest.newBuilder(URI.create(network.settings().controllerUrl() + path))
                .timeout(Duration.ofSeconds(2))
                .header("Content-Type", "application/json")
                .header(Southbound.AUTH_HEADER, Southbound.bearer(network.settings().authToken()))
                .POST(HttpRequest.BodyPublishers.ofString(network.json().writeValueAsString(body)))
                .build();
    }

    private HttpResponse<String> post(String path, Object body) throws IOException {
        try {
            return network.http().send(request(path, body), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    /** Fire-and-forget. If {@code resyncOnFailure}, a failure makes the switch register again (full port table). */
    private void postAsync(String path, Object body, boolean resyncOnFailure) {
        try {
            network.http().sendAsync(request(path, body), HttpResponse.BodyHandlers.ofString())
                    .whenComplete((r, err) -> {
                        boolean failed = err != null || r.statusCode() / 100 != 2;
                        if (failed && resyncOnFailure) {
                            registered = false;
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("Switch {}: cannot encode request", sw.id(), e);
        }
    }

    public boolean isRegistered() {
        return registered;
    }

    public boolean isReachable() {
        return reachable;
    }

    public boolean isAutoRegister() {
        return autoRegister;
    }
}
