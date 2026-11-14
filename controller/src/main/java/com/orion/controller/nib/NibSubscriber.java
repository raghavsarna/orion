package com.orion.controller.nib;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Someone the NIB notifies about changes. Each subscriber has its own single thread, so events arrive
 * in order and a slow subscriber never blocks the NIB.
 * Address "local:&lt;name&gt;" = a component inside this process; "http://..." = POST the event as JSON.
 */
public abstract class NibSubscriber {

    private static final Logger log = LoggerFactory.getLogger(NibSubscriber.class);

    private final String name;
    private final String address;
    private final ExecutorService thread;

    protected NibSubscriber(String name, String address) {
        this.name = name;
        this.address = address;
        this.thread = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("nib-sub-" + name).daemon(true).factory());
    }

    public String name() {
        return name;
    }

    public String address() {
        return address;
    }

    /** Queues the event; returns immediately. */
    void enqueue(NibEvent event) {
        thread.execute(() -> {
            try {
                handle(event);
            } catch (RuntimeException e) {
                log.warn("Subscriber {} failed on {}: {}", name, event.type(), e.getMessage());
            }
        });
    }

    void close() {
        thread.shutdownNow();
    }

    protected abstract void handle(NibEvent event);

    /** In-process subscriber (e.g. the Intent Manager). */
    public static NibSubscriber local(String name, Consumer<NibEvent> handler) {
        return new NibSubscriber(name, "local:" + name) {
            @Override
            protected void handle(NibEvent event) {
                handler.accept(event);
            }
        };
    }

    /** Remote subscriber: each event is POSTed as JSON to {@code url}. */
    public static NibSubscriber http(String name, String url) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        ObjectMapper json = new ObjectMapper();
        return new NibSubscriber(name, url) {
            @Override
            protected void handle(NibEvent event) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event))).build();
                    client.send(req, HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    log.debug("HTTP subscriber {} unreachable: {}", name, e.getMessage());
                }
            }
        };
    }
}
