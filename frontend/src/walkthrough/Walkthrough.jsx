import { useEffect, useRef, useState } from 'react'
import { ctrl, time, usePoll } from '../api.js'
import { ArrowLeft, Check, LoaderCircle, Play, RotateCcw } from 'lucide-react'
import { REQUIRED, SCENARIOS } from './scenarios.js'

/**
 * Guided walkthroughs. The presenter opens one, presses Run on each step: the system changes first (Figure 1
 * redraws), then the step's measured result is written under it.
 */
export default function Walkthrough({ lab, netTopo, onScene, onFocus, showMatrix, addTrials, toast }) {
  const [openId, setOpenId] = useState(() => {
    try {
      return localStorage.getItem('orion-walk') || 'failstatic'
    } catch {
      return 'failstatic'
    }
  })
  const [cursor, setCursor] = useState(0)
  const [runs, setRuns] = useState({})
  const [tick, setTick] = useState('')
  const [busy, setBusy] = useState(false)
  const scenario = SCENARIOS.find((s) => s.id === openId)
  const curRef = useRef(null)
  const ran = useRef(false)
  useEffect(() => {
    // only after a step ran: on first render this would scroll the whole page on a phone
    if (ran.current) curRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, [cursor, runs])
  const missing = netTopo ? REQUIRED.filter((id) => !netTopo.switches.some((s) => s.id === id)) : []

  useEffect(() => {
    onScene(scenario ? { section: scenario.section, title: scenario.title } : null)
    onFocus(scenario ? scenario.steps[cursor]?.focus || {} : {})
  }, [openId, cursor]) // eslint-disable-line

  const open = (id) => {
    try {
      localStorage.setItem('orion-walk', id || '')
    } catch {
      /* remembered for this page only */
    }
    setOpenId(id)
    setCursor(0)
    setRuns({})
    showMatrix(false)
  }

  const run = async (i) => {
    const step = scenario.steps[i]
    ran.current = true
    setCursor(i)
    setBusy(true)
    setTick('')
    setRuns((r) => ({ ...r, [i]: { status: 'running' } }))
    try {
      const result = await step.run(lab, { tick: setTick, showMatrix, addTrials })
      setRuns((r) => ({ ...r, [i]: { status: 'done', result } }))
      if (i + 1 < scenario.steps.length) setCursor(i + 1)
    } catch (e) {
      setRuns((r) => ({ ...r, [i]: { status: 'error', result: e.message } }))
    } finally {
      setBusy(false)
      setTick('')
    }
  }

  const reset = async () => {
    setBusy(true)
    try {
      await lab.clearIntents()
      await lab.repairAll()
      await lab.reconnectAll()
      await lab.setThreshold(0.4)
      showMatrix(false)
      setRuns({})
      setCursor(0)
      toast('Network restored: intents removed, links repaired, switches reconnected, threshold 40%')
    } catch (e) {
      toast(e.message, 'err')
    } finally {
      setBusy(false)
    }
  }

  if (!scenario) {
    return (
      <div className="walk">
        <p className="lede">Each walkthrough runs one idea from the paper on the live system. Press Run on a step; Figure 1 changes, then the measured result appears under the step.</p>
        {missing.length > 0 && (
          <p className="warn">These walkthroughs use the demo fabric (config/topology.yaml). This network is missing {missing.join(', ')}; start it with <code>docker compose up</code> without ORION_TOPOLOGY.</p>
        )}
        <ol className="scenarios">
          {SCENARIOS.map((s) => (
            <li key={s.id}>
              <button className="scenario" onClick={() => open(s.id)} disabled={missing.length > 0}>
                <span className="sec">{s.section}</span>
                <span className="t">{s.title}</span>
                <span className="n">{s.steps.length} steps</span>
              </button>
            </li>
          ))}
        </ol>
        <button className="btn quiet" onClick={reset} disabled={busy}><RotateCcw size={16} /> Restore the network</button>
      </div>
    )
  }

  return (
    <div className="walk">
      <div className="walk-head">
        <button className="btn quiet" onClick={() => { open(null); showMatrix(false) }} disabled={busy}>
          <ArrowLeft size={16} /> All walkthroughs
        </button>
        <button className="btn quiet" onClick={reset} disabled={busy} title="Remove intents, repair links, reconnect switches, threshold 40%">
          <RotateCcw size={16} /> Restore
        </button>
      </div>
      <h2><span className="sec">{scenario.section}</span> {scenario.title}</h2>
      <p className="lede">{scenario.paper}</p>
      {missing.length > 0 && <p className="warn">This walkthrough needs the demo fabric (config/topology.yaml); this network is missing {missing.join(', ')}.</p>}
      <ol className="steps">
        {scenario.steps.map((step, i) => {
          const r = runs[i]
          const current = i === cursor
          return (
            <li key={i} ref={current ? curRef : null} className={`step ${current ? 'cur' : ''} ${r?.status || ''}`}>
              <button className="step-title" onClick={() => setCursor(i)} disabled={busy}>
                <span className="mark">
                  {r?.status === 'done' ? <Check size={18} /> : r?.status === 'running' ? <LoaderCircle size={18} className="spin" /> : i + 1}
                </span>
                <span>{step.title}</span>
              </button>
              {(current || r) && (
                <div className="step-body">
                  {current && !r && <p className="does">{step.does}</p>}
                  {r?.status === 'running' && <p className="does">{step.does}<br /><span className="tick">{tick || 'running…'}</span></p>}
                  {r?.status === 'done' && <p className="result">{r.result}</p>}
                  {r?.status === 'error' && <p className="result bad">{r.result}</p>}
                  {current && r?.status !== 'running' && (
                    <button className="btn primary" onClick={() => run(i)} disabled={busy || missing.length > 0}>
                      <Play size={16} /> {r ? 'Run again' : 'Run'}
                    </button>
                  )}
                </div>
              )}
            </li>
          )
        })}
      </ol>
      <RecentEvents />
    </div>
  )
}

/** The last few NIB and controller events, so the class sees the version bump next to the step that caused it. */
function RecentEvents() {
  const [events] = usePoll(() => ctrl('/api/events?limit=5'), 1200)
  return (
    <table className="data">
      <caption><b>Latest controller events,</b> newest first (full list in Log).</caption>
      <tbody>
        {(events || []).slice().reverse().map((e) => (
          <tr key={e.seq}>
            <td className="num">{time(e.time)}</td>
            <td>{e.type.replace(/_/g, ' ').toLowerCase()}</td>
            <td>{e.message}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
