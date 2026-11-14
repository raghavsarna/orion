package com.orion.network.topology;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads topology.yaml and checks that it describes a valid network. Plain Java. */
public final class TopologyLoader {

    private static final Pattern IPV4_CIDR =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$");

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public TopologyFile load(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new TopologyException("topology file not found: " + path.toAbsolutePath());
        }
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        } catch (IOException e) {
            throw new TopologyException("cannot read topology file " + path + ": " + e.getMessage(), e);
        }
    }

    public TopologyFile load(InputStream in) {
        TopologyFile file;
        try {
            file = yaml.readValue(in, TopologyFile.class);
        } catch (IOException e) {
            throw new TopologyException("topology file is not valid YAML: " + e.getMessage(), e);
        }
        if (file == null) {
            throw new TopologyException("topology file is empty");
        }
        validate(file);
        return file;
    }

    /** Collects every problem in the file and reports them all at once. */
    public void validate(TopologyFile file) {
        List<String> errors = new ArrayList<>();

        if (file.controller() == null || isBlank(file.controller().url())) {
            errors.add("controller.url is missing");
        }
        if (file.switches().isEmpty()) {
            errors.add("no switches defined");
        }

        Map<String, SwitchDef> switchesById = new HashMap<>();
        Set<String> usedPorts = new HashSet<>();   // "s1:2" = port already taken by a host or a link

        for (SwitchDef sw : file.switches()) {
            if (isBlank(sw.id())) {
                errors.add("a switch has no id");
                continue;
            }
            if (switchesById.putIfAbsent(sw.id(), sw) != null) {
                errors.add("duplicate switch id " + sw.id());
            }
            if (sw.ports() < 1) {
                errors.add("switch " + sw.id() + " must have at least 1 port");
            }
            for (HostDef host : sw.hosts()) {
                String where = "host subnet " + host.subnet() + " on " + sw.id() + ":" + host.port();
                if (!isValidCidr(host.subnet())) {
                    errors.add(where + ": subnet must look like 10.0.1.0/24");
                }
                checkPort(sw, host.port(), where, usedPorts, errors);
            }
        }

        Set<String> linkIds = new HashSet<>();
        for (LinkDef link : file.links()) {
            String name = "link " + (isBlank(link.id()) ? "(no id)" : link.id());
            if (isBlank(link.id())) {
                errors.add("a link has no id");
            } else if (!linkIds.add(link.id())) {
                errors.add("duplicate link id " + link.id());
            }
            if (link.latencyMs() <= 0) {
                errors.add(name + ": latencyMs must be greater than 0");
            }
            PortRef a;
            PortRef b;
            try {
                a = link.endA();
                b = link.endB();
            } catch (TopologyException e) {
                errors.add(name + ": " + e.getMessage());
                continue;
            }
            if (a.switchId().equals(b.switchId())) {
                errors.add(name + ": both ends are on switch " + a.switchId());
            }
            for (PortRef end : List.of(a, b)) {
                SwitchDef sw = switchesById.get(end.switchId());
                if (sw == null) {
                    errors.add(name + ": unknown switch " + end.switchId());
                } else {
                    checkPort(sw, end.port(), name, usedPorts, errors);
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new TopologyException("invalid topology file:\n  - " + String.join("\n  - ", errors));
        }
    }

    private static void checkPort(SwitchDef sw, int port, String user, Set<String> usedPorts, List<String> errors) {
        if (port < 1 || port > sw.ports()) {
            errors.add(user + ": port " + port + " does not exist on " + sw.id() + " (ports 1.." + sw.ports() + ")");
        } else if (!usedPorts.add(sw.id() + ":" + port)) {
            errors.add(user + ": port " + sw.id() + ":" + port + " is already used");
        }
    }

    static boolean isValidCidr(String text) {
        if (text == null) {
            return false;
        }
        Matcher m = IPV4_CIDR.matcher(text.trim());
        if (!m.matches()) {
            return false;
        }
        for (int i = 1; i <= 4; i++) {
            if (Integer.parseInt(m.group(i)) > 255) {
                return false;
            }
        }
        return Integer.parseInt(m.group(5)) <= 32;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Human-readable summary printed at startup. */
    public static String describe(TopologyFile file) {
        StringBuilder sb = new StringBuilder();
        sb.append("Topology loaded: ").append(file.switches().size()).append(" switches, ")
          .append(file.links().size()).append(" links, controller ").append(file.controller().url()).append('\n');
        for (SwitchDef sw : file.switches()) {
            sb.append("  switch ").append(sw.id()).append(" (").append(sw.name()).append("), ")
              .append(sw.ports()).append(" ports");
            for (HostDef h : sw.hosts()) {
                sb.append(", port ").append(h.port()).append(" -> hosts ").append(h.subnet());
            }
            sb.append('\n');
        }
        for (LinkDef l : file.links()) {
            sb.append("  link ").append(l.id()).append(": ").append(l.a()).append(" <-> ").append(l.b())
              .append(", ").append(l.latencyMs()).append(" ms\n");
        }
        return sb.toString().stripTrailing();
    }
}
