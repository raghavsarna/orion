package com.orion.switchnetwork.communication;

import com.orion.switchnetwork.network.HostDef;
import com.orion.switchnetwork.network.LinkDef;
import com.orion.switchnetwork.network.SwitchDef;
import com.orion.switchnetwork.network.SwitchNetwork;
import com.orion.switchnetwork.processing.PacketLog;
import com.orion.switchnetwork.processing.TrafficGenerator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Runtime control of the switch network: add/drop switches and links, logs, traffic jobs. */
@RestController
@RequestMapping("/network")
public class NetworkApi {

    /** POST /network/switches body. autoRegister=false: the switch waits for the controller to contact it. */
    public record AddSwitchRequest(String id, String name, Integer ports, List<HostDef> hosts, Boolean autoRegister) {
    }

    public record LinkStateRequest(boolean up) {
    }

    public record ControlStateRequest(boolean connected) {
    }

    private final SwitchNetwork network;
    private final TrafficGenerator traffic;

    public NetworkApi(SwitchNetwork network, TrafficGenerator traffic) {
        this.network = network;
        this.traffic = traffic;
    }

    /** Live topology: switches (with ports and controller status) and links (with up/down state). */
    @GetMapping("/topology")
    public SwitchNetwork.NetworkView topology() {
        return network.view();
    }

    @PostMapping("/switches")
    @ResponseStatus(HttpStatus.CREATED)
    public SwitchNetwork.SwitchView addSwitch(@RequestBody AddSwitchRequest r) {
        SwitchDef def = new SwitchDef(r.id(), r.name(), r.ports() == null ? 4 : r.ports(), r.hosts());
        return network.addSwitch(def, r.autoRegister() == null || r.autoRegister());
    }

    @DeleteMapping("/switches/{id}")
    public Map<String, String> removeSwitch(@PathVariable String id) {
        network.removeSwitch(id);
        return Map.of("removed", id);
    }

    /** Disconnect/reconnect a switch from the controller (it keeps forwarding with its rules). */
    @PatchMapping("/switches/{id}")
    public SwitchNetwork.SwitchView setControl(@PathVariable String id, @RequestBody ControlStateRequest r) {
        return network.setControlConnected(id, r.connected());
    }

    @PostMapping("/links")
    @ResponseStatus(HttpStatus.CREATED)
    public SwitchNetwork.LinkView addLink(@RequestBody LinkDef def) {
        return network.addLink(def);
    }

    @DeleteMapping("/links/{id}")
    public Map<String, String> removeLink(@PathVariable String id) {
        network.removeLink(id);
        return Map.of("removed", id);
    }

    /** Set a link down (simulated failure) or back up. */
    @PatchMapping("/links/{id}")
    public SwitchNetwork.LinkView setLink(@PathVariable String id, @RequestBody LinkStateRequest r) {
        return network.setLinkUp(id, r.up());
    }

    /** Packet log: entries after {@code after} (sequence number), optionally for one switch. */
    @GetMapping("/logs")
    public List<PacketLog.Entry> logs(@RequestParam(defaultValue = "0") long after,
                                      @RequestParam(required = false) String switchId,
                                      @RequestParam(defaultValue = "300") int limit) {
        return network.log().since(after, switchId, Math.min(limit, 3000));
    }

    @DeleteMapping("/logs")
    public Map<String, String> clearLogs() {
        network.log().clear();
        return Map.of("cleared", "logs");
    }

    @GetMapping("/traffic")
    public List<TrafficGenerator.JobView> trafficJobs() {
        return traffic.list();
    }

    @DeleteMapping("/traffic/{jobId}")
    public Map<String, String> stopTraffic(@PathVariable String jobId) {
        traffic.stop(jobId);
        return Map.of("stopped", jobId);
    }
}
