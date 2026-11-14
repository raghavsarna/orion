package com.orion.network.api;

import com.orion.common.flow.FlowBatch;
import com.orion.common.flow.FlowTableView;
import com.orion.common.southbound.LinkStatus;
import com.orion.common.southbound.PacketOut;
import com.orion.common.southbound.SwitchProbe;
import com.orion.common.southbound.SwitchRegistration;
import com.orion.network.core.Switch;
import com.orion.network.core.SwitchNetwork;
import com.orion.network.core.TrafficGenerator;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The API each switch exposes: southbound calls from the controller, plus "send packets" for testing. */
@RestController
@RequestMapping("/switch/{id}")
public class SwitchApi {

    private final SwitchNetwork network;
    private final TrafficGenerator traffic;

    public SwitchApi(SwitchNetwork network, TrafficGenerator traffic) {
        this.network = network;
        this.traffic = traffic;
    }

    /** Same body as registration; used when the controller adds a switch manually. */
    @GetMapping("/details")
    public SwitchRegistration details(@PathVariable String id) {
        return network.get(id).details();
    }

    /** Controller -> switch liveness probe. */
    @GetMapping("/probe")
    public SwitchProbe probe(@PathVariable String id) {
        Switch sw = network.get(id);
        sw.details(); // fails with 503 when the control channel is disconnected
        return new SwitchProbe(sw.id(), sw.bootId());
    }

    /** Is a link on this switch up? Asked by the controller when a neighbour looks dead. */
    @GetMapping("/link/{linkId}")
    public LinkStatus link(@PathVariable String id, @PathVariable String linkId) {
        return network.get(id).linkStatus(linkId);
    }

    @PostMapping("/packet-out")
    public Map<String, String> packetOut(@PathVariable String id, @RequestBody PacketOut out) {
        network.get(id).packetOut(out);
        return Map.of("sent", "port " + out.outPort());
    }

    /** Add, modify and delete one rule or a group of rules (all-or-nothing). */
    @PostMapping("/flow")
    public FlowTableView flow(@PathVariable String id, @RequestBody FlowBatch batch) {
        Switch sw = network.get(id);
        sw.applyFlows(batch);
        return sw.flows();
    }

    @GetMapping("/flow")
    public FlowTableView flows(@PathVariable String id) {
        return network.get(id).flows();
    }

    /** Send packets into this switch at a port: packet details, packets per second, duration. */
    @PostMapping("/send")
    public TrafficGenerator.JobView send(@PathVariable String id, @RequestBody TrafficGenerator.SendRequest request) {
        return traffic.start(id, request);
    }
}
