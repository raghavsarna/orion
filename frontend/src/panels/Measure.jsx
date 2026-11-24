import { useState } from 'react'
import { Play } from 'lucide-react'
import { ctrl, usePoll } from '../api.js'
import { REQUIRED } from '../walkthrough/scenarios.js'

const PARTS = [
  ['queueMs', 'queued (200 ms batching window)'],
  ['computeMs', 'compute rules'],
  ['programMs', 'program switches'],
]

/** Figure 3 (control-plane convergence per recomputation) and Table 1 (link-failure trials). */
export default function Measure({ ctx, lab, trials, addTrials }) {
  const [conv] = usePoll(() => ctrl('/api/metrics/convergence'), 2000)
  const [busy, setBusy] = useState('')
  const samples = (conv?.samples || []).slice(-12).reverse()
  const max = Math.max(1, ...samples.map((s) => s.totalMs))
  const ring = REQUIRED.every((id) => ctx.netTopo?.switches.some((s) => s.id === id))
  const from = ring ? 'A' : ctx.ctrlTopo?.hosts?.[0]?.switchId
  const to = ring ? '10.0.2.0/24' : ctx.ctrlTopo?.hosts?.find((h) => h.switchId !== from)?.subnet

  const runTrials = async () => {
    const out = []
    try {
      for (let i = 1; i <= 5; i++) {
        setBusy(`trial ${i} of 5`)
        out.push(await lab.failureTrial({ from, to }))
      }
      addTrials(out)
    } catch (e) {
      ctx.toast(e.message, 'err')
    } finally {
      setBusy('')
    }
  }

  const med = (k) => {
    const v = trials.map((t) => t[k]).filter((x) => x != null).sort((a, b) => a - b)
    return v.length ? v[Math.floor((v.length - 1) / 2)] : '–'
  }

  return (
    <div className="measure">
      <p className="lede">The paper's evaluation (§6) reports how fast the control plane converges. Every recomputation is timed from the event that caused it to the last switch acknowledging its new rules.</p>
      <figure className="bars">
        <div className="bar-legend">
          {PARTS.map(([k, label]) => <span key={k}><i className={'sw ' + k} /> {label}</span>)}
        </div>
        <ol>
          {samples.map((s) => (
            <li key={s.seq}>
              <span className="what" title={`${s.triggers} event(s) coalesced`}>{s.trigger.replace(/_/g, ' ').toLowerCase()} {s.subject}</span>
              <span className="track">
                {PARTS.map(([k]) => <span key={k} className={'seg ' + k} style={{ width: `${(100 * s[k]) / max}%` }} />)}
              </span>
              <span className="num">{s.totalMs} ms</span>
            </li>
          ))}
          {!samples.length && <li className="empty-row">No measurements yet. Fail a link or add an intent.</li>}
        </ol>
        <figcaption><b>Figure 3:</b> <i>Control-plane convergence,</i> newest first. Each bar runs from the triggering event to every switch programmed{conv?.summary?.count ? `; median ${conv.summary.medianMs} ms over ${conv.summary.count} recomputations, computing rules took ${conv.summary.meanComputeMs.toFixed(1)} ms on average` : ''}.</figcaption>
      </figure>

      <div className="row">
        <button className="btn primary" onClick={runTrials} disabled={!!busy || !from || !to}><Play size={16} /> Run 5 link-failure trials</button>
        {busy && <span className="tick">{busy}…</span>}
      </div>
      <table className="data">
        <caption><b>Table 1:</b> Link failure under a steady 200 packets/s flow{from ? ` from ${from} to ${to}` : ''}. Detection: link cut → NIB event. Control plane: NIB event → all switches programmed. Outage: packets lost × 5 ms.</caption>
        <thead><tr><th>link</th><th className="num">detect</th><th className="num">control</th><th className="num">compute</th><th className="num">rounds</th><th className="num">lost</th><th className="num">outage</th></tr></thead>
        <tbody>
          {trials.map((t, i) => (
            <tr key={i}>
              <td>{t.link}</td><td className="num">{t.detectMs} ms</td><td className="num">{t.controlMs} ms</td>
              <td className="num">{t.computeMs} ms</td><td className="num">{t.rounds}</td><td className="num">{t.lost}/{t.sent}</td><td className="num">{t.outageMs} ms</td>
            </tr>
          ))}
          {trials.length > 0 && (
            <tr className="median"><td>median</td><td className="num">{med('detectMs')} ms</td><td className="num">{med('controlMs')} ms</td><td className="num">{med('computeMs')} ms</td><td className="num">{med('rounds')}</td><td className="num">{med('lost')}</td><td className="num">{med('outageMs')} ms</td></tr>
          )}
          {!trials.length && <tr><td colSpan="7" className="empty-row">No trials yet.</td></tr>}
        </tbody>
      </table>
      <p className="hint">Small scale, honestly reported: one machine, simulated switches. The same experiment runs from the command line with <code>docker compose --profile test run --rm benchmark</code>.</p>
    </div>
  )
}
