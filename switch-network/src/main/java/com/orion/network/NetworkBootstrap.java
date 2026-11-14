package com.orion.network;

import com.orion.network.core.NetworkSettings;
import com.orion.network.core.SwitchNetwork;
import com.orion.network.core.TrafficGenerator;
import com.orion.network.topology.TopologyFile;
import com.orion.network.topology.TopologyLoader;
import java.nio.file.Path;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place where Spring meets the plain-Java core: it builds the core objects once
 * and hands them to the REST API classes. All logic lives in plain-Java classes.
 */
@Configuration
public class NetworkBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NetworkBootstrap.class);

    @Bean
    public TopologyFile topologyFile(@Value("${orion.topology-file}") String path,
                                     @Value("${orion.controller-url:}") String controllerUrlOverride) {
        TopologyFile file = new TopologyLoader().load(Path.of(path));
        if (!controllerUrlOverride.isBlank()) {
            file = file.withControllerUrl(controllerUrlOverride);
        }
        log.info("Reading topology from {}\n{}", Path.of(path).toAbsolutePath(), TopologyLoader.describe(file));
        return file;
    }

    @Bean(destroyMethod = "shutdown")
    public SwitchNetwork switchNetwork(TopologyFile file,
                                       @Value("${orion.public-url}") String publicUrl,
                                       @Value("${orion.heartbeat-sec}") int heartbeatSec,
                                       @Value("${orion.timezone}") String timezone) {
        NetworkSettings settings = new NetworkSettings(file.controller().url(), file.controller().authToken(),
                publicUrl, heartbeatSec, ZoneId.of(timezone));
        return new SwitchNetwork(file, settings);
    }

    /** Start the switch threads only once the web server is listening, so the controller can call back at once. */
    @EventListener(ApplicationReadyEvent.class)
    public void startNetwork(ApplicationReadyEvent event) {
        event.getApplicationContext().getBean(SwitchNetwork.class).start();
    }

    @Bean
    public TrafficGenerator trafficGenerator(SwitchNetwork network) {
        return new TrafficGenerator(network);
    }
}
