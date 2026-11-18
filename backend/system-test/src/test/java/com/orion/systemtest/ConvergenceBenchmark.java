package com.orion.systemtest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Convergence benchmark, in the spirit of the Orion paper's evaluation (§6): repeatedly fail the link a flow is
 * using and measure how long the network takes to recover.
 *
 * <p>Per trial:
 * <ul>
 *   <li><b>detection</b>: link cut -> the NIB has the LINK_REMOVED event (the switch reports it);</li>
 *   <li><b>control plane</b>: NIB event -> new rules programmed on every switch (from /api/metrics/convergence);</li>
 *   <li><b>data plane outage</b>: packets lost × send interval of a constant-rate flow (independent of host
 *       timer jitter, which on Docker Desktop for Mac can add tens of ms to any wall-clock gap);</li>
 *   <li><b>packets lost</b>: sent minus delivered.</li>
 * </ul>
 * Works on any topology: it picks two host subnets on different switches and fails the first link the flow uses.
 *
 * <p>Run with the system up: {@code docker compose --profile test run --rm benchmark}
 */
@EnabledIfSystemProperty(named = "orion.benchmark", matches = "true")
class ConvergenceBenchmark {

    private static final String CTRL = env("CTRL", "http://localhost:8181");
    private static final String NET = env("NET", "http://localhost:9191");
    private static final int TRIALS = Integer.parseInt(env("TRIALS", "10"));
    private static final int PPS = 200;
    private static final int SECONDS = 3;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    record Trial(int n, String link, long detectMs, long controlMs, long computeMs, long programMs, int rounds,
                 long outageMs, long sent, long delivered) {
    }

    @Test
    void linkFailureConvergence() throws Exception {
        JsonNode topo = get(CTRL + "/api/topology");
        JsonNode hosts = topo.path("hosts");
        assertTrue(hosts.size() >= 2, "need two host subnets");
        JsonNode src = hosts.get(0);
        JsonNode dst = null;
        for (JsonNode h : hosts) {
            if (!h.path("switchId").asText().equals(src.path("switchId").asText())) {
                dst = h;
            }
        }
        assertTrue(dst != null, "need host subnets on two different switches");
        String srcSwitch = src.path("switchId").asText();
        String srcIp = hostIp(src.path("subnet").asText(), 10);
        String dstIp = hostIp(dst.path("subnet").asText(), 20);
        System.out.printf("== Orion convergence benchmark: %d trials, %s (%s) -> %s (%s), %d pps ==%n",
                TRIALS, srcIp, srcSwitch, dstIp, dst.path("switchId").asText(), PPS);
        for (JsonNode i : get(CTRL + "/api/intents")) {
            call("DELETE", CTRL + "/api/intents/" + i.path("id").asText(), null);
        }
        Thread.sleep(1500);

        List<Trial> trials = new ArrayList<>();
        for (int n = 1; n <= TRIALS; n++) {
            Trial t = trial(n, srcSwitch, src.path("port").asInt(), srcIp, dstIp);
            if (t != null) {
                trials.add(t);
                System.out.printf(Locale.ROOT, "trial %2d  link %-6s detect %4d ms  control %4d ms (compute %3d, program %3d, %d rounds)"
                                + "  outage %4d ms  lost %3d/%d%n", t.n(), t.link(), t.detectMs(), t.controlMs(), t.computeMs(),
                        t.programMs(), t.rounds(), t.outageMs(), t.sent() - t.delivered(), t.sent());
            }
        }
        assertTrue(!trials.isEmpty(), "no trial completed");

        System.out.println();
        summary("detection (ms)", trials.stream().mapToLong(Trial::detectMs).toArray());
        summary("control plane (ms)", trials.stream().mapToLong(Trial::controlMs).toArray());
        summary("data plane outage (ms)", trials.stream().mapToLong(Trial::outageMs).toArray());
        summary("packets lost", trials.stream().mapToLong(t -> t.sent() - t.delivered()).toArray());

        StringBuilder csv = new StringBuilder("trial,link,detect_ms,control_ms,compute_ms,program_ms,rounds,outage_ms,sent,delivered\n");
        for (Trial t : trials) {
            csv.append(String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%d,%d,%d,%d,%d%n", t.n(), t.link(), t.detectMs(),
                    t.controlMs(), t.computeMs(), t.programMs(), t.rounds(), t.outageMs(), t.sent(), t.delivered()));
        }
        Path out = Path.of("target", "convergence.csv");
        Files.createDirectories(out.getParent());
        Files.writeString(out, csv);
        System.out.println("\nCSV written to backend/system-test/" + out);
    }

    private Trial trial(int n, String srcSwitch, int hostPort, String srcIp, String dstIp) throws Exception {
        long logAfter = lastLogSeq();
        long sampleAfter = lastSampleSeq();
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "inPort", hostPort, "srcIp", srcIp, "srcPort", 50000 + n, "dstIp", dstIp, "dstPort", 80,
                "l4Protocol", "TCP", "appProtocol", "HTTP", "packetsPerSecond", PPS, "durationSec", SECONDS));
        String job = call("POST", NET + "/switch/" + srcSwitch + "/send", body).path("jobId").asText();
        Thread.sleep(1000);

        // which link is the flow on?
        Integer outPort = null;
        for (JsonNode e : get(NET + "/network/logs?after=" + logAfter + "&switchId=" + srcSwitch + "&limit=50")) {
            if (e.path("packetId").asText().startsWith(job + "-") && e.path("event").asText().equals("FORWARDED")) {
                outPort = e.path("outPort").asInt();
                break;
            }
        }
        String link = outPort == null ? null : linkOn(srcSwitch, outPort);
        if (link == null) {
            System.out.println("trial " + n + ": flow not forwarded over a link, skipped");
            return null;
        }
        long cutAt = System.currentTimeMillis();
        call("PATCH", NET + "/network/links/" + link, Map.of("up", false));
        Thread.sleep(SECONDS * 1000L + 500);

        // data plane: packets delivered; each lost packet is one send interval without connectivity
        long delivered = 0;
        for (JsonNode e : get(NET + "/network/logs?after=" + logAfter + "&limit=3000")) {
            if (e.path("packetId").asText().startsWith(job + "-") && e.path("event").asText().equals("DELIVERED")) {
                delivered++;
            }
        }
        long sent = (long) PPS * SECONDS;
        long outage = (sent - delivered) * 1000 / PPS;

        // control plane: the convergence sample for this link
        JsonNode sample = null;
        for (JsonNode s : get(CTRL + "/api/metrics/convergence?after=" + sampleAfter).path("samples")) {
            if (s.path("trigger").asText().equals("LINK_REMOVED") && s.path("subject").asText().equals(link)) {
                sample = s;
                break;
            }
        }
        call("PATCH", NET + "/network/links/" + link, Map.of("up", true));
        Thread.sleep(2000);
        if (sample == null) {
            System.out.println("trial " + n + ": no convergence sample for " + link + ", skipped");
            return null;
        }
        long eventAt = sample.path("time").asLong() - sample.path("totalMs").asLong();
        return new Trial(n, link, Math.max(0, eventAt - cutAt), sample.path("totalMs").asLong(),
                sample.path("computeMs").asLong(), sample.path("programMs").asLong(), sample.path("rounds").asInt(),
                outage, sent, delivered);
    }

    private String linkOn(String switchId, int port) {
        for (JsonNode l : get(CTRL + "/api/topology").path("links")) {
            if (l.path("fromSwitch").asText().equals(switchId) && l.path("fromPort").asInt() == port
                    || l.path("toSwitch").asText().equals(switchId) && l.path("toPort").asInt() == port) {
                return l.path("linkId").asText();
            }
        }
        return null;
    }

    private static void summary(String name, long[] values) {
        long[] v = values.clone();
        java.util.Arrays.sort(v);
        double mean = java.util.Arrays.stream(v).average().orElse(0);
        System.out.printf(Locale.ROOT, "%-24s median %5d   p90 %5d   max %5d   mean %7.1f%n", name, v[(v.length - 1) / 2],
                v[Math.min(v.length - 1, (int) Math.ceil(v.length * 0.9) - 1)], v[v.length - 1], mean);
    }

    /** 10.0.1.0/24, 10 -> 10.0.1.10 */
    private static String hostIp(String subnet, int host) {
        String base = subnet.split("/")[0];
        return base.substring(0, base.lastIndexOf('.') + 1) + host;
    }

    private long lastLogSeq() {
        JsonNode logs = get(NET + "/network/logs?limit=1");
        return logs.isEmpty() ? 0 : logs.get(logs.size() - 1).path("seq").asLong();
    }

    private long lastSampleSeq() {
        long max = 0;
        for (JsonNode s : get(CTRL + "/api/metrics/convergence").path("samples")) {
            max = Math.max(max, s.path("seq").asLong());
        }
        return max;
    }

    private JsonNode get(String url) {
        return call("GET", url, null);
    }

    private JsonNode call(String method, String url, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            if (body == null) {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                b.header("Content-Type", "application/json")
                 .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
