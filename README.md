# Orion

A small, runnable implementation of the ideas in "Orion: Google's Software-Defined Networking Control Plane" (NSDI 2021). It has a simulated switch network, a controller with an in-memory Network Information Base (NIB) and publish/subscribe, intent-based flow programming, sequenced rule updates, Fail Static and Fail Closed handling of unreachable switches, ECMP multipath, convergence measurement, PostgreSQL for intents, and a React console that runs guided walkthroughs of the paper on the live system.

## Run it

Only Docker Desktop is needed. Java, Maven, Node and Postgres all run inside containers.

```bash
docker compose up --build
```

Then open http://localhost:3000. The presenter's script is in [docs/DEMO.md](docs/DEMO.md).

| Service | URL | What it is |
|---|---|---|
| UI | http://localhost:3000 | Figure 1 (the live network), walkthroughs, intents, inspect, measure, log |
| Controller | http://localhost:8181 | Topology Manager (NIB), Intent Manager, southbound and northbound APIs |
| Switch network | http://localhost:9191 | one container, one thread per switch |
| PostgreSQL | localhost:5433 | db, user and password are all `orion` |

Stop with `docker compose down`. Add `-v` to also delete the database. If a port is taken, change it, for example `UI_PORT=3100 CONTROLLER_PORT=8282 docker compose up --build`. To rebuild only the UI without restarting the network, use `docker compose up -d --build --no-deps frontend`.

### Topologies

The default, [config/topology.yaml](config/topology.yaml), is a Clos fabric shaped like Figure 1 of the paper: top switches t1 and t2, middle switches m1 to m4, bottom switches b1 and b2, 16 links. The original 3-switch triangle is in [config/topology-triangle.yaml](config/topology-triangle.yaml):

```bash
ORION_TOPOLOGY=topology-triangle.yaml docker compose up --build
```

Edit a topology file, then `docker compose restart switch-network` to load it.

### Natural-language intents (optional)

Set an API key before starting, then use the "In words" box in the Intents tab:

```bash
ORION_LLM_API_KEY=your-key docker compose up --build
```

Provider and model are in [config/llm.yaml](config/llm.yaml) (default: Anthropic, `claude-opus-5-5`). `provider: openai-compatible` with a `baseUrl` works with any `/chat/completions` API, including local model servers. The LLM only turns text into an intent. The controller still generates every flow rule itself.

## What from the paper is implemented

| Paper | This implementation | Code |
|---|---|---|
| §4.1 NIB: in-memory, versioned, publish/subscribe, one event order for all subscribers | Adjacency matrix plus switch map behind one lock; every change is applied, versioned, then queued to each subscriber's own thread | `controller/topology/Nib.java`, `NibSubscriber.java` |
| §4.1 Topology Manager | Registration, link notifications, probes, neighbour checks for silent switches | `topology/TopologyManager.java` |
| §3.1, §3.3 Intent flows top-down; derived state is rebuilt from intent | Intents in PostgreSQL; flow rules = generate(intents, NIB), recomputed on every change | `intent/IntentManager.java`, `flowrules/RuleGenerator.java` |
| §4.1 Flow Manager reconciliation | Diff desired vs installed rules per switch; periodic safety check (30 s) re-reads every switch | `flowrules/FlowPusher.java` |
| §4.2 Routing Engine: shortest paths, multipath, avoid drained links, reroute on failure | Dijkstra by hops or latency; ECMP over every equal-cost next hop, hashed per flow; AVOID_LINK as a drain | `flowrules/PathGraph.java`, `shared/flow/Action.java` |
| §4.2 Loss-free sequencing | Rules pushed in rounds, nearest the destination first; old rules deleted last | `FlowPusher.push()` |
| §3.2 Health states, Fail Static vs Fail Closed | Healthy / unknown / unhealthy; route around unknown switches unless that loses more than a capacity threshold or cuts off a subnet | `flowrules/FailMode.java` |
| §3.2 Control and data planes do not share fate | Switches keep forwarding on their rules when the controller is unreachable | `switch-network/processing/Switch.java` |
| §4.1 Packet-In / Packet-Out | A packet with no matching rule goes to the controller, which forwards it and repairs the switch | `flowrules/PacketInHandler.java` |
| §6 Evaluation: convergence | Per recomputation: queue, compute and program time, rounds; link-failure benchmark | `metrics/ConvergenceTracker.java`, `system-test/ConvergenceBenchmark.java` |

Simplified or left out, on purpose: the controller is one process with separate component classes, not separate microservices; the southbound protocol is REST, not OpenFlow; the Capability Readiness Protocol is an 8-second start-up wait; there is no replication, leader election, config manager with two-phase commit, or hierarchy of control domains.

## Check everything works

Unit tests (30), run inside Docker:

```bash
docker run --rm -v "$PWD:/repo" -w /repo/backend maven:3.9.11-eclipse-temurin-21 mvn -q test -pl shared,switch-network,sdn-controller -am
```

End-to-end checks (28) need the triangle topology:

```bash
ORION_TOPOLOGY=topology-triangle.yaml docker compose up -d --build
docker compose --profile test run --rm system-test
```

They cover baseline routing, path latency, blocking, website time windows, rate limiting, avoiding a link, link failure and repair, manual switch add, dead-switch removal, a silent switch kept as unknown, and the Fail Static and Fail Closed decision.

Convergence benchmark (any topology, 10 trials by default):

```bash
TRIALS=10 docker compose --profile test run --rm benchmark
```

Sample run on the triangle (laptop, Docker Desktop): detection median 22 ms, control-plane convergence median 228 ms (200 ms of it is the batching window, computing rules about 2 ms), data-plane outage median 250 ms.

Docker Desktop on macOS oversleeps timers by up to about 100 ms, so wall-clock packet latency is noisy. Packets therefore also carry their simulated path latency, the sum of the configured latencies of the links they crossed, and the checks use that.

## Project layout

```
docker-compose.yml        runs everything (plus the system-test and benchmark profiles)
config/                   topology.yaml (fabric), topology-triangle.yaml, llm.yaml, prompts/
docs/                     ORION_DESIGN.md, IMPLEMENTATION_PLAN.md, DEMO.md
backend/                  Java 21, Maven
  shared/                 packet, flow rule (match, action incl. ECMP), switch-controller messages
  switch-network/         simulated switches: topology file, switch threads, flow tables, traffic generator
  sdn-controller/         OrionCore wires: topology/ (NIB), intent/, flowrules/ (rules, fail mode, pushing),
                          metrics/ (convergence), storage/ (PostgreSQL), communication/ (REST)
  system-test/            end-to-end checks and the convergence benchmark
frontend/src/             React (Vite), served by nginx, which proxies /ctrl and /net
  figure/                 Figure 1 (topology), its caption, the NIB matrix
  walkthrough/            guided walkthroughs and the experiments they run
  panels/                 Intents, Inspect, Measure, Log
```

Spring is used only for the REST endpoints and the two bootstrap classes. All controller logic is plain Java. See [docs/ORION_DESIGN.md](docs/ORION_DESIGN.md) for the original design.
