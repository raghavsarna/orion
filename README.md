# Orion-Lite

A small SDN control plane modelled on Google's Orion: a simulated switch network, a controller with an
in-memory Network Information Base (NIB), intent-based flow programming (including Hindi / Hinglish intents
through an LLM), PostgreSQL, and a React UI.

## Run it (any computer)

Only **Docker Desktop** is needed. Java, Maven, Node and Postgres all run inside containers.

```bash
docker compose up --build
```

Then open **http://localhost:3000**.

| Service | URL | What it is |
|---|---|---|
| UI | http://localhost:3000 | topology view and every API call from the browser |
| Controller | http://localhost:8181 | Topology Manager (NIB), Intent Manager, southbound + northbound APIs |
| Switch network | http://localhost:9191 | one container, one thread per switch |
| PostgreSQL | localhost:5433 | db/user/password `orion` |

Stop with `docker compose down` (add `-v` to also delete the database).
If a port is taken, change it: `UI_PORT=3100 CONTROLLER_PORT=8282 docker compose up --build`.

### Hindi / natural-language intents (optional)

Set an API key before starting, then use the ✨ box in the intent form:

```bash
ORION_LLM_API_KEY=your-key docker compose up --build
```

Provider and model are in [config/llm.yaml](config/llm.yaml) (default: Anthropic, `claude-opus-5-5`).
`provider: openai-compatible` with a `baseUrl` works with any `/chat/completions` API, including local model servers.
The file is re-read on every translation, so edits need no restart.

## Using the UI

- **Canvas**: switches are coloured by the controller's view (green alive, red dead, grey not registered).
  Links are solid when the controller knows them, dotted when it doesn't yet, and dashed red when down.
  Links that carry packets glow. Drag switches to arrange them.
- **Click a switch**: send packets (presets HTTP/SSH/DNS/YouTube, rate and duration), see its flow table with
  hit counts (add/delete rules by hand), see its ports, cut its controller connection, add it to the controller
  manually, or drop it.
- **Click a link**: fail it, repair it, or remove it.
- **Header**: add switch, add link, adjacency matrix (the NIB), run the safety check now.
- **Side tabs**: intents (form + Hindi box), IP groups, packet log (every hop), controller activity.

## Change the network

Edit [config/topology.yaml](config/topology.yaml) (switches, ports, host subnets, links with latency), then:

```bash
docker compose restart switch-network
```

## Check everything works

With the system running:

```bash
docker compose --profile test run --rm system-test
```

This runs 26 end-to-end checks (a Java test in `backend/system-test`) against the running system: baseline routing,
latency, blocking, website time windows, rate limiting, avoiding a link, link failure and repair, manual switch add,
dead-switch removal, and a dead-but-kept switch. Only Docker is needed.

Unit tests (needs JDK 21+ and Maven locally): `cd backend && mvn test`.

## Project layout

```
ICE/
├── docker-compose.yml        runs everything
├── config/                   topology.yaml, llm.yaml, prompts/ (mounted into the containers)
├── docs/                     design + implementation plan
├── backend/                  Java 21, Maven (one build for all modules)
│   ├── shared/               com.orion.shared: packet, flow rule, switch↔controller messages, IP helpers
│   ├── switch-network/       com.orion.switchnetwork
│   │   ├── network/          topology file, SwitchNetwork, links, runtime add/drop
│   │   ├── processing/       Switch thread, FlowTable, token bucket, packet log, traffic generator
│   │   └── communication/    ControllerConnection + REST endpoints
│   ├── sdn-controller/       com.orion.controller (OrionCore wires everything)
│   │   ├── communication/    southbound/ (switches) and northbound/ (user, UI)
│   │   ├── topology/         NIB: adjacency matrix, registration, probing, dead switches, pub/sub
│   │   ├── intent/           intents, validation, Hindi → intent (LLM)
│   │   ├── flowrules/        intent → flow rules (Dijkstra, priorities), safe pushing, packet-in
│   │   └── storage/          PostgreSQL repositories
│   └── system-test/          end-to-end check
└── frontend/                 React (Vite), served by nginx (which proxies /ctrl and /net)
    └── src/
        ├── topology/         network drawing, adjacency matrix, add-switch / add-link dialogs
        └── sidebar/          switch, link, intents, groups, packet log, activity
```

Spring is used only for the REST endpoints (in the `communication` packages, plus the two bootstrap classes);
all logic is plain Java.
See [docs/ORION_DESIGN.md](docs/ORION_DESIGN.md) for the design.
