package com.orion.controller;

import com.orion.controller.core.ControllerSettings;
import com.orion.controller.core.OrionCore;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only place where Spring meets the plain-Java core: builds OrionCore once and hands it to the APIs. */
@Configuration
public class ControllerBootstrap {

    @Bean(destroyMethod = "stop")
    public OrionCore orionCore(@Value("${orion.auth-token}") String authToken,
                               @Value("${orion.probe-interval-sec}") int probeIntervalSec,
                               @Value("${orion.max-missed-probes}") int maxMissedProbes,
                               @Value("${orion.safety-check-sec}") int safetyCheckSec,
                               @Value("${orion.db.url}") String dbUrl,
                               @Value("${orion.db.user}") String dbUser,
                               @Value("${orion.db.password}") String dbPassword,
                               @Value("${orion.llm-config}") String llmConfig,
                               @Value("${orion.timezone}") String timezone) {
        ControllerSettings settings = new ControllerSettings(authToken, probeIntervalSec, maxMissedProbes,
                safetyCheckSec, dbUrl, dbUser, dbPassword, llmConfig, ZoneId.of(timezone));
        OrionCore core = new OrionCore(settings);
        core.start();
        return core;
    }
}
