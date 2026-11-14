package com.orion.switchnetwork.processing;

import com.orion.shared.packet.Packet;
import com.orion.switchnetwork.network.NetworkException;
import com.orion.switchnetwork.network.SwitchNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Sends packets into a switch port at a given rate, as asked through POST /switch/{id}/send. */
public final class TrafficGenerator {

    private static final Logger log = LoggerFactory.getLogger(TrafficGenerator.class);
    private static final int MAX_PPS = 1000;

    /**
     * POST /switch/{id}/send body: the packet to send, how fast, for how long.
     * {@code count} (optional) caps the total; otherwise packetsPerSecond × durationSec packets are sent.
     */
    public record SendRequest(Integer inPort, String srcIp, Integer srcPort, String dstIp, Integer dstPort,
                              String l2Protocol, String l3Protocol, String l4Protocol, String appProtocol,
                              String host, Integer sizeBytes, Integer packetsPerSecond, Integer durationSec,
                              Integer count) {
    }

    /** A running or finished send job. */
    public record JobView(String jobId, String switchId, int inPort, String packet, int packetsPerSecond,
                          long total, long sent, boolean running) {
    }

    private final class Job {
        final String id;
        final String switchId;
        final int inPort;
        final Packet template;
        final int pps;
        final long total;
        final AtomicLong sent = new AtomicLong();
        volatile ScheduledFuture<?> future;
        volatile boolean running = true;

        Job(String id, String switchId, int inPort, Packet template, int pps, long total) {
            this.id = id;
            this.switchId = switchId;
            this.inPort = inPort;
            this.template = template;
            this.pps = pps;
            this.total = total;
        }

        void tick() {
            long n = sent.incrementAndGet();
            if (n > total) {
                finish();
                return;
            }
            try {
                Switch sw = network.get(switchId);
                sw.receive(template.withIdentity(id + "-" + n, System.currentTimeMillis()), inPort);
            } catch (NetworkException e) {
                finish();   // switch was removed
            }
            if (n >= total) {
                finish();
            }
        }

        void finish() {
            running = false;
            sent.set(Math.min(sent.get(), total));
            if (future != null) {
                future.cancel(false);
            }
        }

        JobView view() {
            return new JobView(id, switchId, inPort, template.summary(), pps, total, Math.min(sent.get(), total),
                    running);
        }
    }

    private final SwitchNetwork network;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();

    public TrafficGenerator(SwitchNetwork network) {
        this.network = network;
    }

    public JobView start(String switchId, SendRequest r) {
        Switch sw = network.get(switchId);
        if (r.inPort() == null || r.inPort() < 1 || r.inPort() > sw.totalPorts()) {
            throw NetworkException.badRequest("inPort must be 1.." + sw.totalPorts());
        }
        if (r.srcIp() == null || r.dstIp() == null) {
            throw NetworkException.badRequest("srcIp and dstIp are required");
        }
        int pps = r.packetsPerSecond() == null ? 1 : r.packetsPerSecond();
        int duration = r.durationSec() == null ? 1 : r.durationSec();
        if (pps < 1 || pps > MAX_PPS) {
            throw NetworkException.badRequest("packetsPerSecond must be 1.." + MAX_PPS);
        }
        if (duration < 1 || duration > 3600) {
            throw NetworkException.badRequest("durationSec must be 1..3600");
        }
        long total = r.count() != null && r.count() > 0 ? r.count() : (long) pps * duration;
        Packet template = new Packet(null, r.srcIp(), r.srcPort(), r.dstIp(), r.dstPort(),
                orDefault(r.l2Protocol(), "ETHERNET"), orDefault(r.l3Protocol(), "IPV4"),
                blankToNull(r.l4Protocol()), blankToNull(r.appProtocol()), blankToNull(r.host()),
                r.sizeBytes() == null ? 512 : r.sizeBytes(), 64, 0);

        String id = "t" + nextId.incrementAndGet();
        Job job = new Job(id, switchId, r.inPort(), template, pps, total);
        jobs.put(id, job);
        long periodMicros = 1_000_000L / pps;
        job.future = network.trafficScheduler().scheduleAtFixedRate(job::tick, 0, periodMicros, TimeUnit.MICROSECONDS);
        log.info("Traffic {}: {} packets at {} pps into {}:{} ({})", id, total, pps, switchId, r.inPort(),
                template.summary());
        cleanup();
        return job.view();
    }

    public void stop(String jobId) {
        Job job = jobs.get(jobId);
        if (job == null) {
            throw NetworkException.notFound("no traffic job " + jobId);
        }
        job.finish();
    }

    public List<JobView> list() {
        List<JobView> out = new ArrayList<>();
        jobs.values().forEach(j -> out.add(j.view()));
        out.sort((a, b) -> Long.compare(Long.parseLong(b.jobId().substring(1)), Long.parseLong(a.jobId().substring(1))));
        return out;
    }

    /** Keep only the 50 most recent jobs. */
    private void cleanup() {
        List<JobView> all = list();
        for (int i = 50; i < all.size(); i++) {
            if (!all.get(i).running()) {
                jobs.remove(all.get(i).jobId());
            }
        }
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
