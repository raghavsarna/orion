package com.orion.controller.api;

import com.orion.common.flow.FlowRule;
import com.orion.controller.core.ActivityLog;
import com.orion.controller.core.ControllerException;
import com.orion.controller.core.OrionCore;
import com.orion.controller.llm.LlmTranslator;
import com.orion.controller.nib.LinkInfo;
import com.orion.controller.nib.NibSnapshot;
import com.orion.controller.nib.SwitchView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Northbound: topology, switches, flows, activity, subscribers, status. */
@RestController
@RequestMapping("/api")
public class TopologyApi {

    public record Matrix(List<String> nodes, List<List<String>> cells) {
    }

    public record TopologyView(long version, List<SwitchView> switches, List<LinkInfo> links, Matrix matrix,
                               List<NibSnapshot.HostSubnet> hosts) {
    }

    public record ManualSwitchRequest(String switchId, String address) {
    }

    public record SubscriberRequest(String name, String address) {
    }

    public record FlowsView(String switchId, List<FlowRule> desired, List<FlowRule> installed) {
    }

    public record Status(boolean databaseReady, long topologyVersion, int switches, List<String> subscribers,
                         LlmTranslator.Status llm) {
    }

    private final OrionCore core;

    public TopologyApi(OrionCore core) {
        this.core = core;
    }

    /** The NIB: switches with status, links, the adjacency matrix, host subnets. */
    @GetMapping("/topology")
    public TopologyView topology() {
        NibSnapshot snap = core.nib().snapshot();
        return new TopologyView(snap.version(), new ArrayList<>(snap.switches().values()), snap.graph().links(),
                new Matrix(snap.graph().nodes(), snap.graph().matrixOfLinkIds()), snap.hosts());
    }

    @GetMapping("/switches")
    public List<SwitchView> switches() {
        return new ArrayList<>(core.nib().snapshot().switches().values());
    }

    /** Add a switch that could not register by itself; the controller contacts it at {@code address}. */
    @PostMapping("/switches")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> addSwitch(@RequestBody ManualSwitchRequest r) {
        boolean contacted = core.topologyManager().addManually(r.switchId(), r.address());
        return Map.of("switchId", r.switchId(), "contacted", contacted);
    }

    @GetMapping("/switches/manual")
    public Map<String, String> manualSwitches() {
        return core.manualSwitches().findAll();
    }

    @DeleteMapping("/switches/{id}")
    public Map<String, String> forget(@PathVariable String id) {
        core.topologyManager().forget(id);
        return Map.of("removed", id);
    }

    /** Rules the switch should have vs. what the controller last saw on it. */
    @GetMapping("/switches/{id}/flows")
    public FlowsView flows(@PathVariable String id) {
        if (core.nib().get(id) == null) {
            throw ControllerException.notFound("no switch " + id);
        }
        return new FlowsView(id, core.intentManager().desiredOn(id), core.flowPusher().installedOn(id));
    }

    /** Controller activity: NIB events, intent status changes, rule pushes, packet-ins. */
    @GetMapping("/events")
    public List<ActivityLog.Entry> events(@RequestParam(defaultValue = "0") long after,
                                          @RequestParam(defaultValue = "200") int limit) {
        return core.activity().since(after, Math.min(limit, 500));
    }

    /** Run the safety check now: re-read topology and switch rules and fix differences. */
    @PostMapping("/recompute")
    public Map<String, String> recompute() {
        core.intentManager().requestRecompute(true);
        return Map.of("status", "scheduled");
    }

    @GetMapping("/subscribers")
    public Map<String, String> subscribers() {
        return core.subscribers().findAll();
    }

    @PostMapping("/subscribers")
    public Map<String, String> addSubscriber(@RequestBody SubscriberRequest r) {
        if (r.name() == null || r.name().isBlank() || r.address() == null
                || !(r.address().startsWith("http") || r.address().startsWith("local:"))) {
            throw ControllerException.badRequest("name and an address (http://... or local:...) are required");
        }
        core.subscribers().save(r.name(), r.address());
        core.loadSubscribers();
        return core.subscribers().findAll();
    }

    @DeleteMapping("/subscribers/{name}")
    public Map<String, String> removeSubscriber(@PathVariable String name) {
        if (OrionCore.INTENT_MANAGER_SUBSCRIBER.equals(name)) {
            throw ControllerException.badRequest("the intent manager subscription cannot be removed");
        }
        core.subscribers().delete(name);
        core.loadSubscribers();
        return core.subscribers().findAll();
    }

    @GetMapping("/status")
    public Status status() {
        NibSnapshot snap = core.nib().snapshot();
        return new Status(core.database().isReady(), snap.version(), snap.switches().size(),
                core.nib().subscriberNames(), core.translator().status());
    }
}
