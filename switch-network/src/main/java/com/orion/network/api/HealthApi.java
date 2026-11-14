package com.orion.network.api;

import com.orion.common.OrionInfo;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthApi {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "switch-network", "status", "UP", "version", OrionInfo.VERSION);
    }
}
