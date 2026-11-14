package com.orion.controller.core;

import java.time.ZoneId;

/**
 * Controller settings (from application.yml / environment variables).
 *
 * @param authToken          token switches must send on southbound calls
 * @param probeIntervalSec   how often the controller probes each switch
 * @param maxMissedProbes    missed probes in a row before a switch is marked DEAD
 * @param safetyCheckSec     how often the Intent Manager re-reads topology and switch rules to fix drift
 * @param dbUrl              JDBC URL of PostgreSQL
 * @param llmConfigFile      path to llm.yaml
 * @param zone               time zone for intent time windows
 */
public record ControllerSettings(String authToken, int probeIntervalSec, int maxMissedProbes, int safetyCheckSec,
                                 String dbUrl, String dbUser, String dbPassword, String llmConfigFile,
                                 ZoneId zone) {
}
