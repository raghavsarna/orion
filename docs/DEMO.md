# Demo script

A presenter's script for the live part of the talk. It takes about 8 minutes at a normal pace. Every number the class sees is measured on the running system, so the exact values will differ from the ones quoted here.

## Before the talk

1. Start Docker Desktop, then run `docker compose up --build` in the repository root. Wait until http://localhost:3000 shows eight switches in Figure 1.
2. Open the Walkthrough tab and press **Restore the network**. This removes intents, repairs links, reconnects switches and sets the Fail Static threshold to 40%.
3. Do one warm-up run of any walkthrough step. The first packets after a cold start are slower while Java compiles its hot code.
4. Set the projector to mirror the laptop at 1920×1080 or 1280×720. The page is designed for a white screen in a lit room.

If something goes wrong mid-talk, press **Restore** at the top of the open walkthrough and run the step again.

## What is on screen

The left side is Figure 1, the network drawn the way the paper draws its Figure 1: two top switches (t1, t2), four middle switches (m1 to m4), two bottom switches (b1, b2). Each top and bottom switch holds one host subnet. The caption under the figure is rewritten from live data every second.

| Mark | Meaning |
|---|---|
| Solid box | Healthy switch: it answers the controller's probes |
| Red dashed box, "unknown" | The controller lost contact, but neighbours still see its links up |
| Thin black line | Link present in the controller's NIB |
| Dotted line | Link exists in the network but the NIB does not know it yet |
| Red dashed line with a cross | Failed link |
| Blue stroke with a number | Packets that crossed this link in the last 6 seconds |

The running head at the top shows which section of the paper is on screen.

## 1. Fail Static and Fail Closed (§3.2), about 3 minutes

This is the main result. Open **Fail Static and Fail Closed**.

1. **Normal.** Press Run. Forty flows from t1 to b1 spread over all four middle switches, about 20 each. Say: "Every top-to-bottom path is two hops, so t1 holds one rule with four next hops. This is ECMP, and it is the left panel of the paper's Figure 1."
2. **m1 loses its controller connection.** Press Run and wait about 10 seconds while the controller misses 3 probes. m1 turns red and dashed. Avoiding it costs 25% of link capacity, which is under the 40% threshold, so the controller routes around it. This is Fail Closed. Traffic now uses m2, m3 and m4.
3. **m2 too.** Avoiding both would cost 50% of capacity, over the threshold. The controller stops reacting and keeps sending traffic through both unknown switches on the rules they already have. This is Fail Static, the right panel of the paper's figure. All packets still arrive.
4. **Raise the threshold to 60%.** The same situation is now under the threshold, so the controller routes around both.
5. **Every switch loses the controller.** All eight go unknown. The controller fails static, and traffic still flows. Say: "The control plane and the data plane do not share fate. A controller outage does not take the network down."
6. **Reconnect everything.**

## 2. Intents (§3.1, §3.3), about 2 minutes

Open **Intents: say what, not how**.

1. **Under 5 ms.** The controller finds the path through m1 (4 ms) and installs path rules that outrank the multipath baseline. All traffic now takes m1.
2. **Drain t1m1.** With that link avoided, the fastest path is 6 ms, so the latency intent becomes NOT POSSIBLE, with the reason shown. It stays stored and comes back once the drain is removed.
3. **Block SSH.** SSH is dropped at t1, where it enters the network. HTTP still arrives.
4. **Remove the intents.** The rules go back to the baseline.

## 3. Convergence (§4.2, §6), about 2 minutes

Open **Loss-free sequencing and convergence**, or the Measure tab.

1. **Measure one link failure.** A 200 packets/s flow loses its link. The result splits the recovery into detection, the controller's queue, computing rules and programming switches. Programming happens in rounds, nearest the destination first, so no switch forwards onto a path that is not ready.
2. **Repeat five times.** Table 1 in the Measure tab fills with the trials. Typical numbers on a laptop: detection under 30 ms, control plane about 230 to 270 ms, of which 200 ms is the deliberate window that batches bursts of events into one recomputation. Computing rules takes 1 to 3 ms.

Be honest about scale: this is one machine with simulated switches, not Google's fleet.

## Optional extras

- **The NIB (§4.1).** The first walkthrough shows the adjacency matrix (Figure 2) and a link failure travelling through the NIB to its subscriber.
- **Inspect.** Click a switch to see its flow table. The ECMP rule shows `ports 1, 2, 3, 4`. Deleting a rule by hand sends the next packet to the controller (Packet-In), and the periodic safety check reinstalls the rule.

## Command-line evidence for the slides

```bash
docker compose --profile test run --rm benchmark
```

This runs 10 link-failure trials and writes `backend/system-test/target/convergence.csv`.

```bash
ORION_TOPOLOGY=topology-triangle.yaml docker compose up -d --build
docker compose --profile test run --rm system-test
```

These run the 28 end-to-end checks on the triangle topology. Run `docker compose up -d --build` afterwards to return to the fabric.
