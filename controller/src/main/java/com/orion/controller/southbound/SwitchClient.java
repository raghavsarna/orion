package com.orion.controller.southbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orion.common.flow.FlowBatch;
import com.orion.common.flow.FlowTableView;
import com.orion.common.southbound.LinkStatus;
import com.orion.common.southbound.PacketOut;
import com.orion.common.southbound.Southbound;
import com.orion.common.southbound.SwitchProbe;
import com.orion.common.southbound.SwitchRegistration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Controller -> switch REST calls (the southbound API on the switch side). */
public final class SwitchClient {

    /** A switch call failed (unreachable or rejected). */
    public static final class SwitchCallException extends Exception {
        public SwitchCallException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final String authToken;

    public SwitchClient(String authToken) {
        this.authToken = authToken;
    }

    public SwitchRegistration details(String address) throws SwitchCallException {
        return json(get(address + "/details"), SwitchRegistration.class);
    }

    public SwitchProbe probe(String address) throws SwitchCallException {
        return json(get(address + "/probe"), SwitchProbe.class);
    }

    /** true/false = the neighbour answered; empty = the neighbour could not be asked. */
    public Optional<Boolean> linkUp(String address, String linkId) {
        try {
            HttpResponse<String> r = send(request(address + "/link/" + linkId).GET().build());
            if (r.statusCode() == 404) {
                return Optional.of(false);
            }
            if (r.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            return Optional.of(json.readValue(r.body(), LinkStatus.class).up());
        } catch (IOException | SwitchCallException e) {
            return Optional.empty();
        }
    }

    public FlowTableView flows(String address) throws SwitchCallException {
        return json(get(address + "/flow"), FlowTableView.class);
    }

    public FlowTableView pushFlows(String address, FlowBatch batch) throws SwitchCallException {
        return json(post(address + "/flow", batch), FlowTableView.class);
    }

    public void packetOut(String address, PacketOut out) throws SwitchCallException {
        post(address + "/packet-out", out);
    }

    // ---------- plumbing ----------

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                .header(Southbound.AUTH_HEADER, Southbound.bearer(authToken));
    }

    private String get(String url) throws SwitchCallException {
        return checked(send(request(url).GET().build()), url);
    }

    private String post(String url, Object body) throws SwitchCallException {
        try {
            return checked(send(request(url).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build()), url);
        } catch (IOException e) {
            throw new SwitchCallException("cannot encode request: " + e.getMessage());
        }
    }

    private HttpResponse<String> send(HttpRequest req) throws SwitchCallException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SwitchCallException("unreachable: " + req.uri());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SwitchCallException("interrupted");
        }
    }

    private static String checked(HttpResponse<String> r, String url) throws SwitchCallException {
        if (r.statusCode() / 100 != 2) {
            throw new SwitchCallException(r.statusCode() + " from " + url + ": " + r.body());
        }
        return r.body();
    }

    private <T> T json(String body, Class<T> type) throws SwitchCallException {
        try {
            return json.readValue(body, type);
        } catch (IOException e) {
            throw new SwitchCallException("bad reply: " + e.getMessage());
        }
    }
}
