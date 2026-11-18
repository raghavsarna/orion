import { sleep } from '../api.js'
import { pct } from '../figure/caption.js'

/**
 * Guided walkthroughs, one per idea from the paper. Each step's run() changes the live system, then returns what
 * actually happened (measured, not scripted). They use the demo fabric (config/topology.yaml):
 * top t1 (10.0.1.0/24), t2; middle m1..m4; bottom b1 (10.0.3.0/24), b2.
 */
export const REQUIRED = ['t1', 't2', 'm1', 'm2', 'm3', 'm4', 'b1', 'b2']
const SRC = 't1'
const DST = '10.0.3.0/24'
const SPINES = ['m1', 'm2', 'm3', 'm4']

/** {linkId: n} at the sending switch -> "m1 20 · m2 20 · …" (the switch at the other end of each link). */
function spreadText(spread, topo) {
  const peer = (id) => {
    const l = topo.netTopo.links.find((x) => x.id === id)
    if (!l) return id
    return l.a.startsWith(SRC + ':') ? l.b.split(':')[0] : l.a.split(':')[0]
  }
  const rows = Object.entries(spread).map(([l, n]) => [peer(l), n]).sort(([a], [b]) => a.localeCompare(b))
  return rows.length ? rows.map(([s, n]) => `${s} ${n}`).join(' · ') : 'nothing left t1'
}

const delivered = (r) => `${r.delivered} of ${r.sent} delivered`

async function flowsReport(lab, prefix = '') {
  const r = await lab.sendAndSettle({ from: SRC, to: DST, flows: 40, count: 80, pps: 100 })
  return `${prefix}80 packets in 40 flows from t1 to b1. t1 sent them via ${spreadText(r.spread, lab.topo())}; ${delivered(r)}.`
}

export const SCENARIOS = [
  {
    id: 'nib',
    section: '§4.1',
    title: 'The NIB and publish/subscribe',
    paper: 'All state lives in one in-memory Network Information Base. Every change is applied first, then published '
      + 'to subscribers in one order, the paper\'s "single arrow of time".',
    steps: [
      {
        title: 'Read the NIB',
        does: 'Shows the controller\'s adjacency matrix next to the figure.',
        focus: {},
        run: async (lab, ui) => {
          ui.showMatrix(true)
          const t = lab.topo().ctrlTopo
          return `The NIB is at version ${t.version}: ${t.switches.length} switches, ${t.links.length} links, `
            + `${t.hosts.length} host subnets. Figure 2 is its adjacency matrix; each cell is the link between two switches.`
        },
      },
      {
        title: 'Fail link t1m1',
        does: 'Cuts the wire between t1 and m1 in the simulated network.',
        focus: { switches: ['t1', 'm1'], links: ['t1m1'] },
        run: async (lab) => {
          const before = lab.topo().ctrlTopo.version
          const after = await lab.lastSampleSeq()
          await lab.setLink('t1m1', false)
          const t = await lab.waitLink('t1m1', false)
          const s = await lab.sampleFor('LINK_REMOVED', 't1m1', after)
          return `t1 and m1 reported the port down. The Topology Manager removed t1m1 from the NIB `
            + `(version ${before} → ${t?.version ?? '?'}) and published LINK_REMOVED to its subscriber, the Intent Manager`
            + (s ? `, which reprogrammed ${s.switchesChanged} switches in ${s.rounds} rounds, ${s.totalMs} ms after the event.` : '.')
        },
      },
      {
        title: 'Repair t1m1',
        does: 'Brings the wire back.',
        focus: { links: ['t1m1'] },
        run: async (lab) => {
          await lab.setLink('t1m1', true)
          const t = await lab.waitLink('t1m1', true)
          return `LINK_ADDED: t1m1 is back in the NIB at version ${t?.version ?? '?'}, and the matrix cell refilled.`
        },
      },
    ],
  },
  {
    id: 'ecmp',
    section: '§4.2',
    title: 'Routing Engine: multipath and reroute',
    paper: 'The Routing Engine computes shortest paths, spreads traffic over every viable path for capacity, '
      + 'and reprograms around failures.',
    steps: [
      {
        title: 'Send 40 flows from pod-a to pod-c',
        does: 'Sends 80 packets, 40 different flows, from t1 to 10.0.3.0/24.',
        focus: { switches: ['t1', 'b1'] },
        run: (lab) => flowsReport(lab, 'Every top-to-bottom path is 2 hops, so t1 holds one rule with all four spines as next hops. '),
      },
      {
        title: 'Fail m1b1 under load',
        does: 'Starts traffic, then cuts the link from spine m1 down to b1 one second in.',
        focus: { switches: ['m1', 'b1'], links: ['m1b1'] },
        run: async (lab) => {
          const after = await lab.lastSampleSeq()
          const run = await lab.send({ from: SRC, to: DST, flows: 40, count: 300, pps: 100 })
          await sleep(1000)
          await lab.setLink('m1b1', false)
          const r = await lab.settle(run)
          const s = await lab.sampleFor('LINK_REMOVED', 'm1b1', after)
          return `t1 stopped using m1 for b1: ${spreadText(r.spread, lab.topo())} over the whole run. `
            + `${delivered(r)}; ${r.sent - r.delivered} lost while the controller converged`
            + (s ? ` (${s.totalMs} ms from the NIB event to every switch programmed).` : '.')
        },
      },
      {
        title: 'Repair m1b1',
        does: 'Brings the link back and sends the 40 flows again.',
        focus: { links: ['m1b1'] },
        run: async (lab) => {
          await lab.setLink('m1b1', true)
          await lab.waitLink('m1b1', true)
          await sleep(600)
          return flowsReport(lab, 'All four spines are back in the multipath set. ')
        },
      },
    ],
  },
  {
    id: 'failstatic',
    section: '§3.2',
    title: 'Fail Static and Fail Closed',
    paper: 'A switch the controller cannot reach is in unknown state. If avoiding the unknown switches is cheap, '
      + 'route around them (Fail Closed); if it would cost too much capacity, keep using them (Fail Static). '
      + 'This walkthrough reproduces the paper\'s Figure 1.',
    steps: [
      {
        title: 'Normal: every spine healthy',
        does: 'Sets the capacity threshold to 40% and sends 40 flows.',
        focus: { switches: SPINES },
        run: async (lab) => {
          await lab.setThreshold(0.4)
          await sleep(500)
          return flowsReport(lab, 'Figure 1 (left) of the paper: flows use all middle switches. ')
        },
      },
      {
        title: 'm1 loses its controller connection',
        does: 'Cuts m1\'s control channel. It keeps forwarding; the controller just stops hearing from it.',
        focus: { switches: ['m1'] },
        run: async (lab, ui) => {
          await lab.setControl('m1', false)
          const m = await lab.waitMode('FAIL_CLOSED', (s) => ui.tick(`waiting for 3 missed probes… ${s} s`))
          if (!m) return 'm1 did not reach unknown state in 30 s.'
          return `After 3 missed probes m1 is unknown. Avoiding it costs ${pct(m.degradation)} of link capacity, `
            + `under the ${pct(m.threshold)} threshold: Fail Closed. ` + await flowsReport(lab)
        },
      },
      {
        title: 'm2 loses its connection too',
        does: 'Cuts m2\'s control channel as well.',
        focus: { switches: ['m1', 'm2'] },
        run: async (lab, ui) => {
          await lab.setControl('m2', false)
          const m = await lab.waitMode('FAIL_STATIC', (s) => ui.tick(`waiting for 3 missed probes… ${s} s`))
          if (!m) return 'm2 did not reach unknown state in 30 s.'
          return `Now avoiding m1 and m2 would cost ${pct(m.degradation)} of capacity, over ${pct(m.threshold)}: `
            + 'Fail Static. The controller stops reacting and keeps forwarding through them on their existing rules. '
            + await flowsReport(lab)
        },
      },
      {
        title: 'Raise the threshold to 60%',
        does: 'Tells the controller it may give up to 60% of capacity to avoid unknown switches.',
        focus: { switches: ['m1', 'm2'] },
        run: async (lab) => {
          await lab.setThreshold(0.6)
          const m = await lab.waitMode('FAIL_CLOSED')
          return `${pct(m?.degradation)} is now under the threshold, so the controller routes around both: Fail Closed. `
            + await flowsReport(lab)
        },
      },
      {
        title: 'Every switch loses the controller',
        does: 'Threshold back to 40%, then cuts all eight control channels: a controller outage as the switches see it.',
        focus: { switches: REQUIRED },
        run: async (lab, ui) => {
          await lab.setThreshold(0.4)
          for (const s of REQUIRED) await lab.setControl(s, false)
          const m = await lab.waitMode('FAIL_STATIC', (s) => ui.tick(`waiting for every switch to go unknown… ${s} s`), REQUIRED.length)
          return (m ? 'All eight switches are unknown and the controller fails static. ' : '')
            + 'Control and data plane do not share fate: ' + await flowsReport(lab)
        },
      },
      {
        title: 'Reconnect everything',
        does: 'Restores every control channel.',
        focus: {},
        run: async (lab, ui) => {
          await lab.reconnectAll()
          const m = await lab.waitMode('NORMAL', (s) => ui.tick(`switches answering probes again… ${s} s`))
          return m ? 'Every switch answers probes again; the NIB marks them healthy and the mode is back to normal.'
            : 'Switches reconnected; they will turn healthy at the next probe.'
        },
      },
    ],
  },
  {
    id: 'intents',
    section: '§3.1, §3.3',
    title: 'Intents: say what, not how',
    paper: 'Operators state the end state. Intent flows top-down; flow rules are derived state, rebuilt from intent '
      + 'whenever the network changes, and an intent the network cannot meet is reported, not silently dropped.',
    steps: [
      {
        title: 'pod-a to pod-c in under 5 ms',
        does: 'Adds a MAX_LATENCY intent from 10.0.1.0/24 to 10.0.3.0/24.',
        focus: { switches: ['t1', 'm1', 'b1'] },
        run: async (lab) => {
          await lab.clearIntents()
          const i = await lab.addIntent({ type: 'MAX_LATENCY', src: '10.0.1.0/24', dst: DST, maxLatencyMs: 5 })
          await sleep(500)
          const r = await lab.sendAndSettle({ from: SRC, to: DST, flows: 40, count: 80, pps: 100 })
          return `${i?.status}: ${i?.reason}. Path rules outrank the multipath baseline, so t1 now sends via `
            + `${spreadText(r.spread, lab.topo())}; delivered packets took ${r.maxPathMs} ms of link latency.`
        },
      },
      {
        title: 'Drain link t1m1',
        does: 'Adds AVOID_LINK t1m1, as an operator draining it for maintenance would.',
        focus: { links: ['t1m1'] },
        run: async (lab) => {
          await lab.addIntent({ type: 'AVOID_LINK', linkId: 't1m1' })
          await sleep(800)
          const lat = (await lab.intents()).find((x) => x.spec.type === 'MAX_LATENCY')
          return `The drain removes t1m1 from the routing graph. The latency intent is now ${lat?.status}: ${lat?.reason}. `
            + 'It stays stored and comes back by itself once the drain is lifted.'
        },
      },
      {
        title: 'Block SSH from pod-a',
        does: 'Adds BLOCK_PROTOCOL SSH from 10.0.1.0/24, then sends SSH and HTTP.',
        focus: { switches: ['t1'] },
        run: async (lab) => {
          await lab.addIntent({ type: 'BLOCK_PROTOCOL', src: '10.0.1.0/24', protocol: 'SSH' })
          await sleep(500)
          const ssh = await lab.sendAndSettle({ from: SRC, to: DST, flows: 5, count: 20, pps: 40, dstPort: 22, app: 'SSH' })
          const http = await lab.sendAndSettle({ from: SRC, to: DST, flows: 5, count: 20, pps: 40 })
          return `SSH: ${ssh.drops.t1 || 0} of 20 dropped at t1, the edge where the traffic enters. HTTP: ${delivered(http)}.`
        },
      },
      {
        title: 'Remove the intents',
        does: 'Deletes every intent.',
        focus: {},
        run: async (lab) => {
          await lab.clearIntents()
          await sleep(600)
          return 'Rules regenerate from what is left: the multipath baseline. ' + await flowsReport(lab)
        },
      },
    ],
  },
  {
    id: 'convergence',
    section: '§4.2, §6',
    title: 'Loss-free sequencing and convergence',
    paper: 'Rules are programmed in rounds, switches nearest the destination first and old rules deleted last, '
      + 'so no switch forwards onto a path that is not ready. The paper\'s evaluation measures how fast this converges.',
    steps: [
      {
        title: 'Measure one link failure',
        does: 'Sends a steady 200 packets/s flow, cuts the link it uses, and times the recovery.',
        focus: { switches: ['t1'] },
        run: async (lab, ui) => {
          const t = await lab.failureTrial({ from: SRC, to: DST })
          ui.addTrials([t])
          return `Cut ${t.link}. Detection ${t.detectMs} ms (switch reports the port down). Controller: ${t.queueMs} ms `
            + `queued (mostly the 200 ms window that batches events), ${t.computeMs} ms to compute, ${t.programMs} ms to program ${t.switchesChanged} switches `
            + `in ${t.rounds} rounds. ${t.lost} of ${t.sent} packets lost, about ${t.outageMs} ms of outage.`
        },
      },
      {
        title: 'Repeat five times',
        does: 'Runs five more trials; the results fill Table 1 in the Measure tab.',
        focus: {},
        run: async (lab, ui) => {
          const out = []
          for (let i = 1; i <= 5; i++) {
            ui.tick(`trial ${i} of 5…`)
            out.push(await lab.failureTrial({ from: SRC, to: DST }))
          }
          ui.addTrials(out)
          const med = (k) => out.map((x) => x[k]).sort((a, b) => a - b)[2]
          return `Median of 5: detection ${med('detectMs')} ms, control plane ${med('controlMs')} ms, `
            + `outage ${med('outageMs')} ms. Most of the control-plane time is the deliberate 200 ms window that `
            + 'coalesces bursts of events into one recomputation.'
        },
      },
    ],
  },
]
