import { ctrl, hostIp, net, portToLink, sleep, waitFor } from '../api.js'

/**
 * Everything the walkthroughs and the Measure tab do to the live system, through the same APIs the rest of the UI
 * uses. Results are read back from the packet log and the controller, never assumed.
 */
export function makeLab(getTopo) {
  const sw = (id) => getTopo().netTopo?.switches.find((s) => s.id === id)

  const lastSeq = async () => {
    const l = await net('/network/logs?limit=1')
    return l.length ? l[l.length - 1].seq : 0
  }

  const jobEntries = async (jobId, after) =>
    (await net(`/network/logs?after=${after}&limit=3000`)).filter((e) => e.packetId.startsWith(jobId + '-'))

  /** Start packets from a switch's host port towards a subnet. Returns { jobId, after }. */
  const send = async ({ from, to, flows = 40, count = 80, pps = 100, dstPort = 80, app = 'HTTP', l4 = 'TCP', host }) => {
    const s = sw(from)
    if (!s?.hosts?.length) throw new Error(`${from} has no host subnet to send from`)
    const after = await lastSeq()
    const body = {
      inPort: s.hosts[0].port, srcIp: hostIp(s.hosts[0].subnet, 5), srcPort: 40000 + Math.floor(Math.random() * 20000),
      dstIp: hostIp(to, 7), dstPort, l4Protocol: l4, appProtocol: app, host: host || '', sizeBytes: 512,
      packetsPerSecond: pps, durationSec: Math.max(1, Math.ceil(count / pps)), count, flows,
    }
    const job = await net(`/switch/${from}/send`, { method: 'POST', body })
    return { jobId: job.jobId, after, count, pps, from }
  }

  /** Wait for a send to finish, then count where its packets went. */
  const settle = async ({ jobId, after, count, pps, from }) => {
    await sleep((count / pps) * 1000 + 1200)
    const entries = await jobEntries(jobId, after)
    const toLink = portToLink(getTopo().netTopo)
    const spread = {}
    let delivered = 0
    const drops = {}
    for (const e of entries) {
      if (e.event === 'FORWARDED' && e.switchId === from) {
        const l = toLink[`${e.switchId}:${e.outPort}`]
        spread[l] = (spread[l] || 0) + 1
      } else if (e.event === 'DELIVERED') {
        delivered++
      } else if (e.event.startsWith('DROPPED') || e.event === 'RATE_LIMITED') {
        drops[e.switchId] = (drops[e.switchId] || 0) + 1
      }
    }
    const pathMs = entries.filter((e) => e.event === 'DELIVERED').map((e) => e.pathLatencyMs)
    // the route of each packet, switch by switch, in the order it travelled
    const routes = new Map()
    entries.slice().sort((x, y) => x.seq - y.seq).forEach((e) => {
      const r = routes.get(e.packetId) || []
      if (r[r.length - 1] !== e.switchId) r.push(e.switchId)
      routes.set(e.packetId, r)
    })
    const first = [...routes.keys()].sort((x, y) => Number(x.split('-')[1]) - Number(y.split('-')[1]))[0]
    return {
      sent: count, delivered, spread, drops, maxPathMs: pathMs.length ? Math.max(...pathMs) : null,
      route: first ? routes.get(first) : [],
      askedController: entries.some((e) => e.event === 'TO_CONTROLLER'),
    }
  }

  const failMode = async () => (await ctrl('/api/fail-mode')).failMode

  const lastSampleSeq = async () => {
    const r = await ctrl('/api/metrics/convergence')
    return r.samples.length ? r.samples[r.samples.length - 1].seq : 0
  }

  const sampleFor = (trigger, subject, after, timeout = 6000) => waitFor(async () => {
    const r = await ctrl(`/api/metrics/convergence?after=${after}`)
    return r.samples.find((s) => s.trigger === trigger && (!subject || s.subject === subject))
  }, timeout)

  const intentReady = (id) => waitFor(async () => {
    const i = (await ctrl('/api/intents')).find((x) => x.id === id)
    return i && i.status !== 'PENDING' ? i : null
  }, 10000)

  const waitSwitch = (id, present, onTick) => waitFor(async () => {
    const t = await ctrl('/api/topology')
    return t.switches.some((x) => x.id === id) === present ? t : null
  }, 40000, onTick)

  return {
    topo: getTopo,
    waitSwitch,

    /** Ask the LLM to turn plain words into an intent (nothing is applied). */
    translate: (text) => ctrl('/api/intents/translate', { method: 'POST', body: { text } }),

    /** Delete the rule a switch uses for a destination subnet. Returns the rule, or null if there was none. */
    deleteRuleFor: async (switchId, subnet) => {
      const table = await net(`/switch/${switchId}/flow`)
      const rule = table.rules.find((r) => r.match?.dstIp === subnet && r.action?.type !== 'DROP')
      if (rule) await net(`/switch/${switchId}/flow`, { method: 'POST', body: { delete: [rule.ruleId] } })
      return rule || null
    },

    /** Plug a new switch F (hosts 10.0.3.0/24) into D's free port 3. */
    plugInF: async () => {
      if (!getTopo().netTopo.switches.some((x) => x.id === 'F')) {
        await net('/network/switches', { method: 'POST', body: {
          id: 'F', name: 'new', ports: 2, hosts: [{ port: 2, subnet: '10.0.3.0/24' }], autoRegister: true,
        } })
        await net('/network/links', { method: 'POST', body: { id: 'DF', a: 'D:3', b: 'F:1', latencyMs: 5 } })
      }
      return waitFor(async () => {
        const t = await ctrl('/api/topology')
        return t.switches.some((x) => x.id === 'F') && t.links.some((l) => l.linkId === 'DF') ? t : null
      }, 20000)
    },
    unplugF: () => net('/network/switches/F', { method: 'DELETE' }),

    /** Put the network back the way the demo starts, doing only what is needed. */
    reset: async () => {
      const n = getTopo().netTopo
      for (const i of await ctrl('/api/intents')) await ctrl(`/api/intents/${i.id}`, { method: 'DELETE' })
      for (const l of n.links) if (!l.up) await net(`/network/links/${l.id}`, { method: 'PATCH', body: { up: true } })
      for (const x of n.switches) {
        if (!x.controlConnected) await net(`/network/switches/${x.id}`, { method: 'PATCH', body: { connected: true } })
      }
      if (n.switches.some((x) => x.id === 'F')) await net('/network/switches/F', { method: 'DELETE' })
      await ctrl('/api/fail-mode', { method: 'PUT', body: { threshold: 0.4 } })
      await sleep(400)
    },
    send,
    settle,
    sendAndSettle: async (opts) => settle(await send(opts)),
    failMode,
    lastSampleSeq,
    sampleFor,
    setControl: (id, connected) => net(`/network/switches/${id}`, { method: 'PATCH', body: { connected } }),
    setLink: (id, up) => net(`/network/links/${id}`, { method: 'PATCH', body: { up } }),
    setThreshold: (threshold) => ctrl('/api/fail-mode', { method: 'PUT', body: { threshold } }),
    waitMode: (mode, onTick, unknownCount) => waitFor(async () => {
      const m = await failMode()
      return m.mode === mode && (unknownCount == null || m.unknown.length === unknownCount) ? m : null
    }, 30000, onTick),
    waitLink: (id, present) => waitFor(async () => {
      const t = await ctrl('/api/topology')
      return t.links.some((l) => l.linkId === id) === present ? t : null
    }, 10000),
    addIntent: async (spec) => intentReady((await ctrl('/api/intents', { method: 'POST', body: spec })).id),
    intents: () => ctrl('/api/intents'),
    clearIntents: async () => {
      for (const i of await ctrl('/api/intents')) await ctrl(`/api/intents/${i.id}`, { method: 'DELETE' })
    },
    reconnectAll: async () => {
      for (const s of getTopo().netTopo.switches) {
        if (!s.controlConnected) await net(`/network/switches/${s.id}`, { method: 'PATCH', body: { connected: true } })
      }
    },
    repairAll: async () => {
      for (const l of getTopo().netTopo.links) {
        if (!l.up) await net(`/network/links/${l.id}`, { method: 'PATCH', body: { up: true } })
      }
    },

    /**
     * One link-failure trial (same method as backend/system-test ConvergenceBenchmark): a steady flow, cut the link
     * it uses, measure detection (cut -> NIB event), control-plane convergence (NIB event -> all switches programmed)
     * and packets lost.
     */
    failureTrial: async ({ from, to, pps = 200, seconds = 3 }) => {
      const sampleAfter = await lastSampleSeq()
      const run = await send({ from, to, flows: 1, count: pps * seconds, pps })
      await sleep(1000)
      const first = (await jobEntries(run.jobId, run.after)).find((e) => e.event === 'FORWARDED' && e.switchId === from)
      const link = first && portToLink(getTopo().netTopo)[`${from}:${first.outPort}`]
      if (!link) throw new Error('the flow did not leave ' + from)
      const cutAt = Date.now()
      await net(`/network/links/${link}`, { method: 'PATCH', body: { up: false } })
      const result = await settle({ ...run, count: run.count, pps, from: '__none__' })
      const sample = await sampleFor('LINK_REMOVED', link, sampleAfter)
      await net(`/network/links/${link}`, { method: 'PATCH', body: { up: true } })
      await waitFor(async () => (await ctrl('/api/topology')).links.some((l) => l.linkId === link), 10000)
      await sleep(800)
      const lost = result.sent - result.delivered
      return {
        link,
        detectMs: sample ? Math.max(0, sample.time - sample.totalMs - cutAt) : null,
        controlMs: sample?.totalMs ?? null,
        queueMs: sample?.queueMs ?? null,
        computeMs: sample?.computeMs ?? null,
        programMs: sample?.programMs ?? null,
        rounds: sample?.rounds ?? null,
        switchesChanged: sample?.switchesChanged ?? null,
        sent: result.sent,
        lost,
        outageMs: Math.round((lost * 1000) / pps),
      }
    },
  }
}
