package com.orion.controller.api;

import com.orion.common.southbound.LinkNotification;
import com.orion.common.southbound.PacketIn;
import com.orion.common.southbound.ProbeReply;
import com.orion.common.southbound.Southbound;
import com.orion.common.southbound.SwitchProbe;
import com.orion.common.southbound.SwitchRegistration;
import com.orion.controller.core.ControllerException;
import com.orion.controller.core.OrionCore;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Southbound API on the controller: called by switches. Every call needs the auth header. */
@RestController
@RequestMapping("/controller")
public class SouthboundApi {

    private final OrionCore core;

    public SouthboundApi(OrionCore core) {
        this.core = core;
    }

    private void auth(String header) {
        if (header == null || !header.equals(Southbound.bearer(core.settings().authToken()))) {
            throw new ControllerException(401, "missing or wrong auth token");
        }
    }

    @PostMapping("/register")
    public Map<String, String> register(@RequestHeader(value = Southbound.AUTH_HEADER, required = false) String auth,
                                        @RequestBody SwitchRegistration reg) {
        auth(auth);
        core.topologyManager().register(reg);
        return Map.of("status", "registered", "switchId", reg.switchId());
    }

    @PostMapping("/probe")
    public ProbeReply probe(@RequestHeader(value = Southbound.AUTH_HEADER, required = false) String auth,
                            @RequestBody SwitchProbe probe) {
        auth(auth);
        return core.topologyManager().onSwitchProbe(probe);
    }

    @PostMapping("/notification")
    public ResponseEntity<Map<String, String>> notification(
            @RequestHeader(value = Southbound.AUTH_HEADER, required = false) String auth,
            @RequestBody LinkNotification n) {
        auth(auth);
        if (!core.topologyManager().onNotification(n)) {
            return ResponseEntity.status(404).body(Map.of("error", "unknown switch, register first"));
        }
        return ResponseEntity.ok(Map.of("status", "applied"));
    }

    @PostMapping("/packet-in")
    public ResponseEntity<Map<String, String>> packetIn(
            @RequestHeader(value = Southbound.AUTH_HEADER, required = false) String auth,
            @RequestBody PacketIn in) {
        auth(auth);
        core.packetInHandler().handle(in);
        return ResponseEntity.accepted().body(Map.of("status", "accepted"));
    }
}
