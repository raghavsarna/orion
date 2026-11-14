package com.orion.network.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TopologyLoaderTest {

    private final TopologyLoader loader = new TopologyLoader();

    private TopologyFile load(String yaml) {
        return loader.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private String errorOf(String yaml) {
        return assertThrows(TopologyException.class, () -> load(yaml)).getMessage();
    }

    private static final String VALID = """
            controller: { url: http://localhost:8181, authToken: t }
            switches:
              - { id: s1, name: a, ports: 4, hosts: [ { port: 4, subnet: 10.0.1.0/24 } ] }
              - { id: s2, name: b, ports: 4 }
            links:
              - { id: l1, a: "s1:1", b: "s2:1", latencyMs: 5 }
            """;

    @Test
    void loadsValidFile() {
        TopologyFile file = load(VALID);
        assertEquals(2, file.switches().size());
        assertEquals("10.0.1.0/24", file.switches().get(0).hosts().get(0).subnet());
        assertEquals(new PortRef("s2", 1), file.links().get(0).endB());
        assertTrue(file.switches().get(1).hosts().isEmpty());
    }

    @Test
    void loadsTheProjectTopologyFile() {
        TopologyFile file = loader.load(Path.of("../config/topology.yaml"));
        assertEquals(3, file.switches().size());
        assertEquals(3, file.links().size());
    }

    @Test
    void rejectsLinkToUnknownSwitch() {
        assertTrue(errorOf(VALID.replace("\"s2:1\"", "\"s9:1\"")).contains("unknown switch s9"));
    }

    @Test
    void rejectsPortUsedTwice() {
        String yaml = VALID.replace("port: 4, subnet", "port: 1, subnet");
        assertTrue(errorOf(yaml).contains("s1:1 is already used"));
    }

    @Test
    void rejectsPortOutOfRange() {
        assertTrue(errorOf(VALID.replace("\"s1:1\"", "\"s1:7\"")).contains("port 7 does not exist on s1"));
    }

    @Test
    void rejectsDuplicateSwitchId() {
        assertTrue(errorOf(VALID.replace("id: s2", "id: s1")).contains("duplicate switch id s1"));
    }

    @Test
    void rejectsBadSubnetAndLatency() {
        String msg = errorOf(VALID.replace("10.0.1.0/24", "10.0.1.300/24").replace("latencyMs: 5", "latencyMs: 0"));
        assertTrue(msg.contains("subnet must look like"));
        assertTrue(msg.contains("latencyMs must be greater than 0"));
    }

    @Test
    void rejectsUnknownField() {
        assertTrue(errorOf(VALID.replace("ports: 4 }", "ports: 4, colour: red }")).contains("not valid YAML"));
    }

    @Test
    void cidrCheck() {
        assertTrue(TopologyLoader.isValidCidr("192.168.0.0/16"));
        assertFalse(TopologyLoader.isValidCidr("10.0.0.0/33"));
        assertFalse(TopologyLoader.isValidCidr("10.0.0/24"));
    }
}
