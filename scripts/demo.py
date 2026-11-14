"""
Orion-Lite end-to-end demo / check. Run after `docker compose up --build`:

    python scripts/demo.py

It walks through every feature against the running system and prints PASS/FAIL for each step.
Uses only the Python standard library. Note: it changes the running network (adds/removes intents,
links and a switch) and cleans up after itself where it can.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
CTRL = os.environ.get("CTRL", "http://localhost:8181")
NET = os.environ.get("NET", "http://localhost:9191")
results = []


def call(method, url, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            text = r.read().decode()
            return r.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except ValueError:
            return e.code, text


def get(url):
    return call("GET", url)[1]


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""))


def wait(pred, timeout=20, step=0.5):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = pred()
            if v:
                return v
        except Exception:
            pass
        time.sleep(step)
    return None


def last_seq():
    logs = get(f"{NET}/network/logs?limit=1")
    return logs[-1]["seq"] if logs else 0


def send(sw, count, pps=20, **pkt):
    body = {"inPort": 4, "srcIp": "10.0.1.5", "srcPort": 51000, "dstIp": "10.0.3.7", "dstPort": 80,
            "l4Protocol": "TCP", "appProtocol": "HTTP", "sizeBytes": 512,
            "packetsPerSecond": pps, "durationSec": max(1, count // pps + 1), "count": count}
    body.update(pkt)
    return call("POST", f"{NET}/switch/{sw}/send", body)


def traffic(count, pps=20, settle=1.5, **pkt):
    """Send packets from s1 and return the log entries they produced."""
    after = last_seq()
    code, job = send("s1", count, pps, **pkt)
    assert code == 200, job
    time.sleep(count / pps + settle)
    logs = get(f"{NET}/network/logs?after={after}&limit=3000")
    return [e for e in logs if e["packetId"] and e["packetId"].startswith(job["jobId"] + "-")]


def events(logs, event, switch=None, out=None):
    return [e for e in logs if e["event"] == event and (switch is None or e["switchId"] == switch)
            and (out is None or e["outPort"] == out)]


def intent(spec):
    code, body = call("POST", f"{CTRL}/api/intents", spec)
    assert code == 201, body
    return wait(lambda: next((i for i in get(f"{CTRL}/api/intents")
                              if i["id"] == body["id"] and i["status"] != "PENDING"), None), 10)


def delete_all_intents():
    for i in get(f"{CTRL}/api/intents"):
        call("DELETE", f"{CTRL}/api/intents/{i['id']}")


def has_link(link_id):
    return any(l["linkId"] == link_id for l in get(f"{CTRL}/api/topology")["links"])


print("== Orion-Lite demo ==")

# 1. topology from the file, registered with the controller
topo = wait(lambda: (lambda t: t if len(t["links"]) >= 3 else None)(get(f"{CTRL}/api/topology")), 60)
check("controller knows 3 switches and 3 links", topo is not None and len(topo["switches"]) == 3)
delete_all_intents()
time.sleep(1)

# 2. baseline routing: fewest hops (s1 -> s3 directly over l3)
logs = traffic(10)
check("baseline: 10 packets delivered at s3", len(events(logs, "DELIVERED", "s3")) == 10)
check("baseline: s1 forwards on port 2 (direct link l3)", len(events(logs, "FORWARDED", "s1", 2)) == 10)

# 3. latency intent: path through s2 (10 ms) instead of l3 (20 ms)
i_lat = intent({"type": "MAX_LATENCY", "src": "10.0.1.0/24", "dst": "10.0.3.0/24", "maxLatencyMs": 15})
check("MAX_LATENCY 15 ms is ACTIVE", i_lat and i_lat["status"] == "ACTIVE", i_lat and i_lat["reason"])
time.sleep(1)
logs = traffic(10)
check("latency: s1 now forwards on port 1 (via s2)", len(events(logs, "FORWARDED", "s1", 1)) == 10)
lat = [e["latencyMs"] for e in events(logs, "DELIVERED", "s3")]
# measured latency = configured link latency (10 ms) + a few ms of simulator overhead (threads, timers);
# what matters is that it beats the 20 ms direct link
check("latency: faster than the direct 20 ms link", lat and max(lat) < 20,
      f"measured max {max(lat) if lat else '-'} ms, configured path 10 ms")
i_bad = intent({"type": "MAX_LATENCY", "src": "10.0.1.0/24", "dst": "10.0.3.0/24", "maxLatencyMs": 5})
check("MAX_LATENCY 5 ms is NOT_POSSIBLE", i_bad and i_bad["status"] == "NOT_POSSIBLE", i_bad and i_bad["reason"])
call("DELETE", f"{CTRL}/api/intents/{i_bad['id']}")

# 4. group + block protocol
call("POST", f"{CTRL}/api/groups", {"name": "guests", "members": ["10.0.1.0/24"]})
i_ssh = intent({"type": "BLOCK_PROTOCOL", "src": "group:guests", "protocol": "SSH"})
check("BLOCK_PROTOCOL SSH from group:guests is ACTIVE", i_ssh and i_ssh["status"] == "ACTIVE")
time.sleep(1)
logs = traffic(5, dstPort=22, appProtocol="SSH")
check("SSH packets dropped at s1", len(events(logs, "DROPPED", "s1")) == 5)
logs = traffic(5)
check("HTTP packets still delivered", len(events(logs, "DELIVERED", "s3")) == 5)

# 5. block website with a time window that covers the whole day
i_web = intent({"type": "BLOCK_TRAFFIC", "website": "youtube.com",
                "timeWindow": {"from": "00:00", "to": "23:59", "until": None}})
time.sleep(1)
logs = traffic(5, host="youtube.com", dstPort=443, appProtocol="HTTPS")
check("website blocked inside its time window", len(events(logs, "DROPPED", "s1")) == 5)

# 6. rate limit
i_rl = intent({"type": "RATE_LIMIT", "src": "10.0.1.5", "packetsPerSecond": 10})
time.sleep(1)
logs = traffic(100, pps=50, settle=2)
delivered = len(events(logs, "DELIVERED", "s3"))
limited = len(events(logs, "RATE_LIMITED", "s1"))
check("rate limit: ~10 pps delivered, rest limited", 15 <= delivered <= 35 and limited > 50,
      f"{delivered} delivered, {limited} limited of 100 in 2 s")
for i in (i_rl, i_web, i_ssh):
    call("DELETE", f"{CTRL}/api/intents/{i['id']}")

# 7. avoid link
i_avoid = intent({"type": "AVOID_LINK", "linkId": "l1"})
check("AVOID_LINK l1 is ACTIVE", i_avoid and i_avoid["status"] == "ACTIVE")
lat_now = next(i for i in get(f"{CTRL}/api/intents") if i["id"] == i_lat["id"])
check("latency intent became NOT_POSSIBLE while l1 is avoided", lat_now["status"] == "NOT_POSSIBLE", lat_now["reason"])
call("DELETE", f"{CTRL}/api/intents/{i_avoid['id']}")
time.sleep(1)

# 8. link failure -> NIB update -> re-route
call("PATCH", f"{NET}/network/links/l2", {"up": False})
check("link l2 down disappears from controller topology", wait(lambda: not has_link("l2"), 10) is not None)
time.sleep(1)
lat_now = next(i for i in get(f"{CTRL}/api/intents") if i["id"] == i_lat["id"])
check("latency intent NOT_POSSIBLE without l2", lat_now["status"] == "NOT_POSSIBLE")
logs = traffic(5)
check("traffic still delivered over l3", len(events(logs, "DELIVERED", "s3")) == 5)
call("PATCH", f"{NET}/network/links/l2", {"up": True})
check("link l2 back up", wait(lambda: has_link("l2"), 10) is not None)
check("latency intent ACTIVE again", wait(lambda: next(i for i in get(f"{CTRL}/api/intents")
                                                        if i["id"] == i_lat["id"])["status"] == "ACTIVE", 10))

# 9. manual add: a switch that does not register by itself
call("POST", f"{NET}/network/switches", {"id": "s4", "name": "lab", "ports": 4,
                                         "hosts": [{"port": 4, "subnet": "10.0.4.0/24"}], "autoRegister": False})
call("POST", f"{NET}/network/links", {"id": "l4", "a": "s2:3", "b": "s4:1", "latencyMs": 3})
time.sleep(4)
check("s4 is not known before manual add", "s4" not in [s["id"] for s in get(f"{CTRL}/api/switches")])
code, r = call("POST", f"{CTRL}/api/switches", {"switchId": "s4", "address": "http://switch-network:9191/switch/s4"})
check("manual add contacted s4", code == 202 and r["contacted"])
check("link l4 appears", wait(lambda: has_link("l4"), 10) is not None)
time.sleep(1.5)
logs = traffic(5, dstIp="10.0.4.9")
check("traffic to the new subnet reaches s4", len(events(logs, "DELIVERED", "s4")) == 5)

# 10. drop a switch -> probes fail -> DEAD -> neighbours say link down -> removed
call("DELETE", f"{NET}/network/switches/s4")
check("dropped switch s4 removed from topology",
      wait(lambda: "s4" not in [s["id"] for s in get(f"{CTRL}/api/switches")], 25) is not None)
call("DELETE", f"{CTRL}/api/switches/s4")

# 11. control channel lost but forwarding works -> DEAD, kept (neighbours see link up)
call("PATCH", f"{NET}/network/switches/s2", {"connected": False})
dead = wait(lambda: next((s for s in get(f"{CTRL}/api/switches") if s["id"] == "s2" and s["status"] == "DEAD"), None), 25)
check("s2 marked DEAD but kept (neighbours see its links up)", dead is not None)
call("PATCH", f"{NET}/network/switches/s2", {"connected": True})
check("s2 ALIVE again", wait(lambda: next((s for s in get(f"{CTRL}/api/switches")
                                           if s["id"] == "s2" and s["status"] == "ALIVE"), None), 15) is not None)

delete_all_intents()
print()
print(f"{sum(results)}/{len(results)} checks passed")
sys.exit(0 if all(results) else 1)
