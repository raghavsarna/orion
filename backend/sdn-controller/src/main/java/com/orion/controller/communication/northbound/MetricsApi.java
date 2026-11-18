package com.orion.controller.communication.northbound;

import com.orion.controller.OrionCore;
import com.orion.controller.flowrules.FailMode;
import com.orion.controller.metrics.ConvergenceTracker;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Northbound: Fail Static / Fail Closed state and convergence measurements. */
@RestController
@RequestMapping("/api")
public class MetricsApi {

    public record ThresholdRequest(double threshold) {
    }

    public record FailModeView(FailMode failMode, String explanation) {
    }

    public record ConvergenceView(List<ConvergenceTracker.Sample> samples, ConvergenceTracker.Summary summary) {
    }

    private final OrionCore core;

    public MetricsApi(OrionCore core) {
        this.core = core;
    }

    /** Current reaction to switches in unknown state, and the capacity degradation threshold. */
    @GetMapping("/fail-mode")
    public FailModeView failMode() {
        FailMode m = core.intentManager().failMode();
        return new FailModeView(m, m.explain());
    }

    /** Change the threshold (0..1); the controller re-decides at once. */
    @PutMapping("/fail-mode")
    public FailModeView setThreshold(@RequestBody ThresholdRequest r) {
        core.intentManager().setFailStaticThreshold(r.threshold());
        return failMode();
    }

    /** Control-plane convergence samples (trigger -> every switch programmed). */
    @GetMapping("/metrics/convergence")
    public ConvergenceView convergence(@RequestParam(defaultValue = "0") long after) {
        ConvergenceTracker t = core.intentManager().convergence();
        return new ConvergenceView(t.samples(after), t.summary());
    }

    @DeleteMapping("/metrics/convergence")
    public Map<String, String> clearConvergence() {
        core.intentManager().convergence().clear();
        return Map.of("cleared", "convergence");
    }
}
