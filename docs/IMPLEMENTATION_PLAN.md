# Orion-Lite — Implementation Plan

> **Status: all pieces implemented (2026-11-12).** Run `docker compose up --build`, open http://localhost:3000, and check with `python scripts/demo.py`.

Based on [ORION_DESIGN.md](ORION_DESIGN.md) v2.0.

## How we work

For every piece:
1. I tell you the **objective** in one or two lines.
2. I implement it.
3. You **verify** it, using the check listed for that piece.
4. We move on only after you approve.

## The final goal: one command, on any computer

```bash
docker compose up --build
```

- The only thing anyone needs installed is **Docker Desktop** (with Compose). Java, Maven and Postgres all run inside containers.
- The Java code is compiled **inside Docker** with a multi-stage build, so the result does not depend on the JDK installed on a given machine.
- Containers: `postgres`, `controller` (port 8181) and `switch-network` (port 9191).
- Java 21 (LTS) inside the containers, Spring Boot 3, plain JDBC for Postgres.
- Piece 2 sets up this command, so from then on every piece is checked the same way.

---

## Phase 0 — Project setup

| # | Piece | Objective | How you verify |
|---|---|---|---|
| 1 | Project skeleton | Maven project with 3 modules (`common`, `switch-network`, `controller`), each app with a `/health` endpoint | Build succeeds; both apps start and `/health` answers |
| 2 | One-command run | Dockerfiles + `docker-compose.yml` for postgres, controller and switch-network | `docker compose up --build` starts all three; both `/health` endpoints answer |

## Phase A — Switch Network

| # | Piece | Objective | How you verify |
|---|---|---|---|
| 3 | Topology file | Read `topology.yaml` (switches, ports, hosts, links) at startup | Startup log prints the topology; `GET /network/topology` returns it |
| 4 | Switch threads + links | One thread per switch; links connect the ports of two switches | Log shows one thread started per switch with its port table |
| 5 | Flow rule + matching | Flow rule model (match fields, action, priority, time window) and the FlowTable that finds the matching rule, with unit tests | Tests pass (match on IP, port, protocol, size, in-port, time window; highest priority wins) |
| 6 | Flow API on switch | `POST /switch/{id}/flow` (add/modify/delete, one or many) and `GET /switch/{id}/flow` | Add rules with curl, read them back |
| 7 | Packet handling + logging | Switch thread matches each packet: forward to neighbour, deliver to host or drop; logs every packet | A test packet visibly moves hop by hop in the logs |
| 8 | Send-packet API | `POST /switch/{id}/send` with packet details, packets per second and duration | Send 10 pps for 3 s → about 30 packets logged along the path |
| 9 | Rate limit | Token bucket on rules that have a rate limit | Send 50 pps against a 10 pps rule → about 10 pps forwarded, rest dropped |
| 10 | Runtime changes | Add/drop switch and add/drop link APIs | Drop a link → packets using it are dropped; add it back → they flow again |

## Phase B — Topology Manager (NIB)

| # | Piece | Objective | How you verify |
|---|---|---|---|
| 11 | Database | Controller connects to Postgres and creates the 4 tables | Tables visible in Postgres; controller restarts without errors |
| 12 | Registration | Switches register on start (retrying until it works); controller builds the adjacency matrix + switch map | `GET /api/topology` shows all switches, links and host subnets |
| 13 | Manual add switch | `POST /api/switches` → controller calls `GET /switch/{id}/details` | A switch started with a wrong controller address appears after a manual add |
| 14 | Link notifications | Switches report link add/delete; NIB updates the matrix | Drop a link in the network → it disappears from `/api/topology` |
| 15 | Probing + dead switches | Controller probes switches, marks DEAD after K misses, asks neighbours, removes the switch if they say the link is down | Drop a switch → it goes DEAD, then is removed from the topology |
| 16 | Switch probes controller | Switch checks the controller; registers again if unknown; keeps its rules while the controller is down | Restart only the controller → switches re-register and their rules survive |
| 17 | Pub/sub | Subscriber list in DB; NIB applies a change, then notifies subscribers | Log shows the Intent Manager receiving "link removed" after a link drop |

## Phase C — Intent Manager

| # | Piece | Objective | How you verify |
|---|---|---|---|
| 18 | Dijkstra + baseline rules | Shortest path on link latency; one forward rule per destination subnet on every switch (unit tests) | Tests pass on the sample topology |
| 19 | Pushing rules | Compare generated rules with each switch's rules, send only differences, destination-side first | After startup, traffic from 10.0.1.x reaches 10.0.3.x with no manual rules |
| 20 | React to changes + safety check | Regenerate on NIB notifications, and also periodically | Drop link l2 → traffic reroutes through l3 within seconds |
| 21 | Packet-in / packet-out | Handle packets with no match: send them on toward the destination, or drop if unknown | Delete a rule by hand → packet goes to the controller and still arrives; rule is restored |
| 22 | Intent + group APIs | Store intents and IP groups in the DB; `POST/GET/DELETE /api/intents`, `/api/groups` | Add/list/delete intents and groups; they survive a controller restart |
| 23 | Block traffic / protocol | `BLOCK_TRAFFIC` and `BLOCK_PROTOCOL` → DROP rules at the entry switch | Block SSH → SSH packets dropped, HTTP packets still arrive |
| 24 | Isolate groups | `ISOLATE_GROUPS` → DROP rules between the groups | Traffic guests → servers dropped; other traffic arrives |
| 25 | Rate limit intent | `RATE_LIMIT` → rule with a token bucket | Sender limited to the configured pps |
| 26 | Avoid link | `AVOID_LINK` → routes recomputed without that link | Packets stop using the avoided link |
| 27 | Max latency + not possible | `MAX_LATENCY` path rules; intents that can't be applied get status `NOT_POSSIBLE` with a reason | Impossible bound → `NOT_POSSIBLE`; possible bound → traffic on the fast path |
| 28 | Time-bound intents | Rules carry a time window; expired intents are removed | Intent active only in its window; marked `EXPIRED` afterwards |
| 29 | Recovery | Restart each container separately; the system heals | Restart controller, then switch-network, then postgres: traffic and intents are back each time |

## Phase D — Natural language (Hindi)

| # | Piece | Objective | How you verify |
|---|---|---|---|
| 30 | LLM client | `llm.yaml` config + clients for Anthropic and OpenAI-compatible APIs | With your key set, a test call returns a reply |
| 31 | Translate endpoint | `POST /api/intents/translate`: Hindi/English text → validated intent JSON | "guest network se SSH block karo" → correct `BLOCK_PROTOCOL` JSON |

## Phase E — Finish

| # | Piece | Objective | How you verify |
|---|---|---|---|
| 32 | Demo + README | README (how to run on any computer) and a demo script that walks through the main features | A fresh clone on another computer runs with `docker compose up --build` and the demo passes |
