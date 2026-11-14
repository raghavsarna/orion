package com.orion.controller.api;

import com.orion.common.OrionInfo;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthApi {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("service", "controller", "status", "UP", "version", OrionInfo.VERSION);
    }
}
