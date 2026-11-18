package com.orion.systemtest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * End-to-end check of the whole running system, through the same HTTP APIs the UI uses.
 * Prints PASS/FAIL per step and fails if any step failed.
 *
 * <p>Run against the triangle topology (needs only Docker):
 * {@code ORION_TOPOLOGY=topology-triangle.yaml docker compose up -d --build}, then
 * {@code docker compose --profile test run --rm system-test}
 *
 * <p>It changes the running network (intents, a link, a switch) and cleans up after itself.
 */
@EnabledIfSystemProperty(named = "orion.systemTest", matches = "true")
class SystemCheckTest {

    private static final String CTRL = env("CTRL", "http://localhost:8181");
    private static final String NET = env("NET", "http://localhost:9191");
    /** How the controller reaches the switch network (for the manual-add step). */
    private static final String NET_FROM_CONTROLLER = env("NET_FROM_CONTROLLER", "http://switch-network:9191");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final List<Boolean> results = new ArrayList<>();

    @Test
    void wholeSystem() throws Exception {
        System.out.println("== Orion-Lite system check ==");

        // 1. topology from the file, registered with the controller
        JsonNode topo = waitFor(() -> get(CTRL + "/api/topology"), t -> t.path("links").size() >= 3, 60);
        check("controller knows 3 switches and 3 links", topo != null && topo.path("switches").size() == 3, "");
        deleteAllIntents();
        sleep(1);
        traffic(30, 50, Map.of());   // warm up the JVMs so latency measurements are not skewed by JIT compilation

        // 2. baseline routing: fewest hops (s1 -> s3 directly over l3)
        List<JsonNode> logs = traffic(10, 20, Map.of());
        check("baseline: 10 packets delivered at s3", count(logs, "DELIVERED", "s3", null) == 10, "");
        check("baseline: s1 forwards on port 2 (direct link l3)", count(logs, "FORWARDED", "s1", 2) == 10, "");

        // 3. latency intent: path through s2 (10 ms) instead of l3 (20 ms)
        JsonNode lat = intent(Map.of("type", "MAX_LATENCY", "src", "10.0.1.0/24", "dst", "10.0.3.0/24", "maxLatencyMs", 15));
        check("MAX_LATENCY 15 ms is ACTIVE", status(lat).equals("ACTIVE"), reason(lat));
        sleep(1);
        logs = traffic(10, 20, Map.of());
        check("latency: s1 now forwards on port 1 (via s2)", count(logs, "FORWARDED", "s1", 1) == 10, "");
        // simulated path latency (sum of link latencies) must beat the 20 ms direct link; wall clock adds host jitter
        long max = logs.stream().filter(e -> e.path("event").asText().equals("DELIVERED"))
                .mapToLong(e -> e.path("pathLatencyMs").asLong()).max().orElse(999);
        long wall = logs.stream().filter(e -> e.path("event").asText().equals("DELIVERED"))
                .mapToLong(e -> e.path("latencyMs").asLong()).max().orElse(999);
        check("latency: faster than the direct 20 ms link", max < 20,
                "path " + max + " ms, wall clock max " + wall + " ms");
        JsonNode bad = intent(Map.of("type", "MAX_LATENCY", "src", "10.0.1.0/24", "dst", "10.0.3.0/24", "maxLatencyMs", 5));
        check("MAX_LATENCY 5 ms is NOT_POSSIBLE", status(bad).equals("NOT_POSSIBLE"), reason(bad));
        delete(CTRL + "/api/intents/" + bad.path("id").asText());

        // 4. group + block protocol
        call("POST", CTRL + "/api/groups", Map.of("name", "guests", "members", List.of("10.0.1.0/24")));
        JsonNode ssh = intent(Map.of("type", "BLOCK_PROTOCOL", "src", "group:guests", "protocol", "SSH"));
        check("BLOCK_PROTOCOL SSH from group:guests is ACTIVE", status(ssh).equals("ACTIVE"), "");
        sleep(1);
        logs = traffic(5, 20, Map.of("dstPort", 22, "appProtocol", "SSH"));
        check("SSH packets dropped at s1", count(logs, "DROPPED", "s1", null) == 5, "");
        logs = traffic(5, 20, Map.of());
        check("HTTP packets still delivered", count(logs, "DELIVERED", "s3", null) == 5, "");

        // 5. block a website inside a time window that covers the whole day
        JsonNode web = intent(Map.of("type", "BLOCK_TRAFFIC", "website", "youtube.com",
                "timeWindow", Map.of("from", "00:00", "to", "23:59")));
        sleep(1);
        logs = traffic(5, 20, Map.of("host", "youtube.com", "dstPort", 443, "appProtocol", "HTTPS"));
        check("website blocked inside its time window", count(logs, "DROPPED", "s1", null) == 5, "");

        // 6. rate limit
        JsonNode rl = intent(Map.of("type", "RATE_LIMIT", "src", "10.0.1.5", "packetsPerSecond", 10));
        sleep(1);
        logs = traffic(100, 50, Map.of());
        long delivered = count(logs, "DELIVERED", "s3", null);
        long limited = count(logs, "RATE_LIMITED", "s1", null);
        check("rate limit: ~10 pps delivered, rest limited", delivered >= 15 && delivered <= 35 && limited > 50,
                delivered + " delivered, " + limited + " limited of 100 in 2 s");
        for (JsonNode i : List.of(rl, web, ssh)) {
            delete(CTRL + "/api/intents/" + i.path("id").asText());
        }

        // 7. avoid link
        JsonNode avoid = intent(Map.of("type", "AVOID_LINK", "linkId", "l1"));
        check("AVOID_LINK l1 is ACTIVE", status(avoid).equals("ACTIVE"), "");
        JsonNode latNow = findIntent(lat.path("id").asText());
        check("latency intent NOT_POSSIBLE while l1 is avoided", status(latNow).equals("NOT_POSSIBLE"), reason(latNow));
        delete(CTRL + "/api/intents/" + avoid.path("id").asText());
        sleep(1);

        // 8. link failure -> NIB update -> re-route
        call("PATCH", NET + "/network/links/l2", Map.of("up", false));
        check("link l2 down disappears from controller topology", waitUntil(() -> !hasLink("l2"), 10), "");
        sleep(1);
        check("latency intent NOT_POSSIBLE without l2", status(findIntent(lat.path("id").asText())).equals("NOT_POSSIBLE"), "");
        logs = traffic(5, 20, Map.of());
        check("traffic still delivered over l3", count(logs, "DELIVERED", "s3", null) == 5, "");
        call("PATCH", NET + "/network/links/l2", Map.of("up", true));
        check("link l2 back up", waitUntil(() -> hasLink("l2"), 10), "");
        check("latency intent ACTIVE again",
                waitUntil(() -> status(findIntent(lat.path("id").asText())).equals("ACTIVE"), 10), "");

        // 9. manual add: a switch that does not register by itself
        call("POST", NET + "/network/switches", Map.of("id", "s4", "name", "lab", "ports", 4,
                "hosts", List.of(Map.of("port", 4, "subnet", "10.0.4.0/24")), "autoRegister", false));
        call("POST", NET + "/network/links", Map.of("id", "l4", "a", "s2:3", "b", "s4:1", "latencyMs", 3));
        sleep(4);
        check("s4 is not known before manual add", !switchIds().contains("s4"), "");
        JsonNode added = call("POST", CTRL + "/api/switches",
                Map.of("switchId", "s4", "address", NET_FROM_CONTROLLER + "/switch/s4"));
        check("manual add contacted s4", added.path("contacted").asBoolean(), "");
        check("link l4 appears", waitUntil(() -> hasLink("l4"), 10), "");
        sleep(1.5);
        logs = traffic(5, 20, Map.of("dstIp", "10.0.4.9"));
        check("traffic to the new subnet reaches s4", count(logs, "DELIVERED", "s4", null) == 5, "");

        // 10. drop a switch -> probes fail -> DEAD -> neighbours say link down -> removed
        delete(NET + "/network/switches/s4");
        check("dropped switch s4 removed from topology", waitUntil(() -> !switchIds().contains("s4"), 25), "");
        delete(CTRL + "/api/switches/s4");

        // 11. control channel lost but forwarding works -> DEAD and kept (neighbours see its links up)
        call("PATCH", NET + "/network/switches/s2", Map.of("connected", false));
        check("s2 marked DEAD but kept (neighbours see its links up)", waitUntil(() -> switchStatus("s2").equals("DEAD"), 25), "");
        // s2 carries 2 of the 3 links: routing around it costs 67% of capacity > 40% threshold -> Fail Static
        check("unknown s2 -> FAIL_STATIC (67% capacity > 40%)",
                waitUntil(() -> failMode().equals("FAIL_STATIC"), 10), failModeText());
        call("PUT", CTRL + "/api/fail-mode", Map.of("threshold", 0.9));
        check("threshold 90% -> FAIL_CLOSED (route around s2)",
                waitUntil(() -> failMode().equals("FAIL_CLOSED"), 10), failModeText());
        call("PUT", CTRL + "/api/fail-mode", Map.of("threshold", 0.4));
        call("PATCH", NET + "/network/switches/s2", Map.of("connected", true));
        check("s2 ALIVE again", waitUntil(() -> switchStatus("s2").equals("ALIVE"), 15), "");

        deleteAllIntents();
        long passed = results.stream().filter(b -> b).count();
        System.out.println();
        System.out.println(passed + "/" + results.size() + " checks passed");
        assertTrue(passed == results.size(), (results.size() - passed) + " check(s) failed, see output above");
    }

    // ---------- steps ----------

    /** Sends packets into s1 port 4 and returns the log entries those packets produced. */
    private List<JsonNode> traffic(int count, int pps, Map<String, Object> overrides) throws Exception {
        long after = lastLogSeq();
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "inPort", 4, "srcIp", "10.0.1.5", "srcPort", 51000, "dstIp", "10.0.3.7", "dstPort", 80,
                "l4Protocol", "TCP", "appProtocol", "HTTP", "sizeBytes", 512, "packetsPerSecond", pps));
        body.put("durationSec", Math.max(1, count / pps + 1));
        body.put("count", count);
        body.putAll(overrides);
        String job = call("POST", NET + "/switch/s1/send", body).path("jobId").asText();
        sleep((double) count / pps + 2);
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode e : get(NET + "/network/logs?after=" + after + "&limit=3000")) {
            if (e.path("packetId").asText().startsWith(job + "-")) {
                out.add(e);
            }
        }
        return out;
    }

    private JsonNode intent(Map<String, Object> spec) throws Exception {
        String id = call("POST", CTRL + "/api/intents", spec).path("id").asText();
        return waitFor(() -> findIntent(id), i -> i != null && !status(i).equals("PENDING"), 10);
    }

    private JsonNode findIntent(String id) {
        for (JsonNode i : get(CTRL + "/api/intents")) {
            if (i.path("id").asText().equals(id)) {
                return i;
            }
        }
        return null;
    }

    private void deleteAllIntents() {
        for (JsonNode i : get(CTRL + "/api/intents")) {
            delete(CTRL + "/api/intents/" + i.path("id").asText());
        }
    }

    private boolean hasLink(String linkId) {
        for (JsonNode l : get(CTRL + "/api/topology").path("links")) {
            if (l.path("linkId").asText().equals(linkId)) {
                return true;
            }
        }
        return false;
    }

    private List<String> switchIds() {
        List<String> ids = new ArrayList<>();
        get(CTRL + "/api/switches").forEach(s -> ids.add(s.path("id").asText()));
        return ids;
    }

    private String switchStatus(String id) {
        for (JsonNode s : get(CTRL + "/api/switches")) {
            if (s.path("id").asText().equals(id)) {
                return s.path("status").asText();
            }
        }
        return "";
    }

    private String failMode() {
        return get(CTRL + "/api/fail-mode").path("failMode").path("mode").asText();
    }

    private String failModeText() {
        return get(CTRL + "/api/fail-mode").path("explanation").asText();
    }

    private long lastLogSeq() {
        JsonNode logs = get(NET + "/network/logs?limit=1");
        return logs.isEmpty() ? 0 : logs.get(logs.size() - 1).path("seq").asLong();
    }

    private static long count(List<JsonNode> logs, String event, String sw, Integer outPort) {
        return logs.stream().filter(e -> e.path("event").asText().equals(event))
                .filter(e -> sw == null || e.path("switchId").asText().equals(sw))
                .filter(e -> outPort == null || e.path("outPort").asInt(-1) == outPort).count();
    }

    private static String status(JsonNode intent) {
        return intent == null ? "" : intent.path("status").asText();
    }

    private static String reason(JsonNode intent) {
        return intent == null ? "" : intent.path("reason").asText();
    }

    private void check(String name, boolean ok, String detail) {
        results.add(ok);
        System.out.println((ok ? "PASS " : "FAIL ") + name + (detail.isBlank() ? "" : "  (" + detail + ")"));
    }

    // ---------- HTTP and waiting ----------

    private JsonNode get(String url) {
        return call("GET", url, null);
    }

    private void delete(String url) {
        call("DELETE", url, null);
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

    private static <T> T waitFor(Supplier<T> value, Predicate<T> done, double seconds) {
        long end = System.currentTimeMillis() + (long) (seconds * 1000);
        T last = null;
        while (System.currentTimeMillis() < end) {
            try {
                last = value.get();
                if (done.test(last)) {
                    return last;
                }
            } catch (RuntimeException ignored) {
                // system still starting
            }
            sleep(0.5);
        }
        return null;
    }

    private static boolean waitUntil(Supplier<Boolean> condition, double seconds) {
        return waitFor(condition, Boolean::booleanValue, seconds) != null;
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
