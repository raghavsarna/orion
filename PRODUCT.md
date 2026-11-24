# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
A student team presenting a course project (IC course, Plaksha, Semester 7): an implementation of the NSDI '21 paper
"Orion: Google's Software-Defined Networking Control Plane". The primary user is the presenter, driving the console
live in front of an instructor and classmates. The audience watches; they need to see cause and effect in the network
and connect each behaviour to the paper.

## Product Purpose
This project, Orion, is a small, runnable SDN control plane modelled on Google's Orion: a simulated switch network, a controller with an
in-memory Network Information Base (NIB) and publish/subscribe, intent-based flow programming, loss-free sequenced
rule updates, Fail Static / Fail Closed handling of unknown switches, ECMP multipath, and convergence measurement.
The console exists to demonstrate that implementation convincingly: success means the audience understands which
paper idea they are watching and sees it actually happen in the running system.

## Positioning
It is a working implementation, not slides: every behaviour shown is produced by the real controller and switch
simulator, and each one maps to a named section of the paper.

## Operating Context
- Shown on a classroom projector to people several metres away; room lighting varies.
- Part of a 15-20 minute slot that also includes a slide deck (made separately, later). The demo segment is a few
  minutes of that slot.
- Driven through guided scenarios tied to paper sections (the presenter clicks Next; the app performs the step and
  explains what happened), with free exploration of the topology still available.
- Runs locally with `docker compose up`; UI at http://localhost:3000.

## Capabilities and Constraints
- Topologies: a 5-switch ring for the demo scenarios (default), a Clos fabric shaped like the paper's Figure 1, and a
  3-switch triangle (system test).
- Intents: AVOID_LINK, MAX_LATENCY, BLOCK_TRAFFIC, BLOCK_PROTOCOL, ISOLATE_GROUPS, RATE_LIMIT, optional time windows;
  NOT_POSSIBLE with a reason when the network cannot satisfy one.
- Simulated link latency is reported as path latency (sum of configured link latencies); wall-clock timing on Docker
  Desktop for Mac carries tens of ms of timer jitter.
- Paper vocabulary is authoritative: NIB, intent, Topology Manager, Flow Manager / reconciliation, Routing Engine,
  Fail Static, Fail Closed, healthy / unknown / unhealthy, Packet-In / Packet-Out, convergence.

## Evidence on Hand
- The paper: paper.pdf (not committed).
- Measured results: backend/system-test (28 end-to-end checks), ConvergenceBenchmark (detection ~20 ms, control-plane
  convergence ~230 ms of which ~200 ms is the deliberate coalescing window, data-plane outage ~250 ms on the triangle).
- No production deployment, users, or comparison to Google's numbers exists; never imply otherwise.

## Product Principles
- Every screen answers "which idea from the paper is this, and what just happened?"
- Show, then explain: the network visibly changes first, the explanation follows in one or two sentences.
- Truthful about scale: a faithful small model, never presented as Google-scale.
- Readable from the back of the room.

## Accessibility & Inclusion
Projector legibility: large type, high contrast, and states that do not rely on colour alone (shape, line style, labels).
