# Demo script

The live part of the talk: six short scenarios, about 7 minutes in total. Each scenario is a few steps. Press the blue button, watch the network change, then read the one-line result.

## Before the talk

1. Start Docker Desktop, then run `docker compose up --build` in the project folder.
2. Open http://localhost:3000 and wait until the drawing shows the controller and switches A to E.
3. Press **Restore the network** under the scenario list.
4. Run scenario 1 once as a warm-up, then press **Restore the network** again.

If something looks wrong during the talk, go back to **All scenarios**, press **Restore the network**, and run the scenario again.

## The picture

- **The black box at the top is the controller.** The thin dotted lines are its connections to each switch. Click it to see what it knows.
- **A and B have hosts:** 10.0.1.x and 10.0.2.x. Packets in the demo go from A to B.
- **There are two routes from A to B:** the short one through E, and the long one through C and D.
- **The blue line with moving arrows** is the route the last packet took.
- **A red dashed connection with a cross** means that switch lost contact with the controller.
- **A red dashed link with a cross** means the wire is cut.

## 1. The long way round (about 1 minute)

1. **Give the instruction.** The controller is told not to use the A–E link. Say: "We tell the controller what we want, not how to do it."
2. **Send packets.** They take A → C → D → B.

## 2. Change the intent (about 1 minute)

1. **Change the instruction.** The A–E link is allowed again.
2. **Send packets.** They take the short route A → E → B. Say: "We only changed the instruction. The controller worked out the new routes and updated the switches."

## 3. A link breaks (about 1 minute)

1. **Cut the wire** between E and B. The link turns red. E and B tell the controller straight away, and it updates the routes in about a quarter of a second.
2. **Send packets.** They go around the break: A → C → D → B.
3. **Repair and send.** The short route comes back.

## 4. Add and remove a switch (about 1 minute)

1. **Plug in F** next to D. F introduces itself and the controller now knows 6 switches.
2. **Send packets** to F's hosts. The route A → C → D → F was already set up.
3. **Unplug F.** F turns grey: the controller still lists it until F misses 3 check-ins (the controller checks every 3 seconds). Then it is removed.

## 5. A switch loses contact (about 2 minutes)

This is the paper's Fail Static idea.

1. **Cut E off.** Only E's connection to the controller breaks; E itself keeps working. After about 10 seconds the controller assumes E has failed and sends traffic around it: A → C → D → B. Say: "When it is cheap to avoid a switch it cannot see, the controller plays safe. That is Fail Closed."
2. **Cut C off too.** Going around both C and E would cut A off from B. The controller stops reacting and keeps the routes it already had, so traffic still flows A → E → B. Say: "When avoiding them would do more harm than good, it keeps what it had. That is Fail Static. It works because a switch that lost contact usually still forwards packets."
3. **Reconnect.**

## 6. A packet with no rule (about 1 minute)

1. **Delete the rule.** E forgets where to send packets for B.
2. **Send one packet.** E has no rule, so it sends the packet to the controller; the line from E to the controller turns blue. The controller tells E where to send it and gives E the rule back.
3. **Send another packet.** It goes straight through. E did not need to ask.

## Optional extras

- **The Intents tab** lists the instructions given to the controller and lets you write your own, for example "from 10.0.1.0/24 block SSH".
- **The Measure tab** times how long the controller takes to react to a cut link, as in the paper's evaluation.
- **The paper's own Figure 1** can be shown with `ORION_TOPOLOGY=topology-fabric.yaml docker compose up --build`. The scenarios need the default ring, though.

## Command-line evidence for the slides

```bash
docker compose --profile test run --rm benchmark
```

This runs 10 link-failure trials and writes `backend/system-test/target/convergence.csv`.

```bash
ORION_TOPOLOGY=topology-triangle.yaml docker compose up -d --build
docker compose --profile test run --rm system-test
```

These run the 28 end-to-end checks on the triangle topology. Run `docker compose up -d --build` afterwards to return to the demo ring.
