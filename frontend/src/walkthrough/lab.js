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
    return { sent: count, delivered, spread, drops, maxPathMs: pathMs.length ? Math.max(...pathMs) : null }
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

  return {
    topo: getTopo,
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
