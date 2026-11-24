import { useEffect, useState } from 'react'
import { ArrowLeft, ArrowRight, Check, LoaderCircle, Play, RotateCcw } from 'lucide-react'
import { REQUIRED, SCENARIOS } from './scenarios.js'

/**
 * Scenarios, one step at a time. The list shows six short scenarios; inside one, only the current step is on
 * screen: what will happen, one button, then what happened. The figure changes first, the sentence follows.
 */
export default function Walkthrough({ lab, netTopo, onScene, onFocus, toast }) {
  const [openId, setOpenId] = useState(null)
  const [step, setStep] = useState(0)
  const [result, setResult] = useState(null)   // { text, error } for the current step, once run
  const [busy, setBusy] = useState(false)
  const [tick, setTick] = useState('')
  const idx = SCENARIOS.findIndex((s) => s.id === openId)
  const scenario = SCENARIOS[idx]
  const missing = netTopo ? REQUIRED.filter((id) => !netTopo.switches.some((s) => s.id === id)) : []

  useEffect(() => {
    onScene(scenario ? { section: scenario.paper, title: scenario.title } : null)
    onFocus(scenario ? scenario.steps[step]?.focus || {} : {})
  }, [openId, step]) // eslint-disable-line

  const open = (id) => {
    setOpenId(id)
    setStep(0)
    setResult(null)
  }

  const run = async () => {
    const s = scenario.steps[step]
    setBusy(true)
    setTick('')
    try {
      if (step === 0 && scenario.reset) {
        setTick('Getting the network ready…')
        await lab.reset()
        setTick('')
      }
      setResult({ text: await s.run(lab, { tick: setTick }) })
    } catch (e) {
      setResult({ text: e.message, error: true })
    } finally {
      setBusy(false)
      setTick('')
    }
  }

  const next = () => {
    setStep(step + 1)
    setResult(null)
  }

  const restore = async () => {
    setBusy(true)
    try {
      await lab.reset()
      setOpenId(null)
      toast('Network restored')
    } catch (e) {
      toast(e.message, 'err')
    } finally {
      setBusy(false)
    }
  }

  if (!scenario) {
    return (
      <div className="walk">
        {missing.length > 0 && (
          <p className="warn">These scenarios use the demo network (config/topology.yaml) with switches A to E. Start it with <code>docker compose up</code>.</p>
        )}
        <ol className="scenarios">
          {SCENARIOS.map((s, i) => (
            <li key={s.id}>
              <button className="scenario" onClick={() => open(s.id)} disabled={missing.length > 0}>
                <span className="num">{i + 1}</span>
                <span className="t">{s.title}<span className="b">{s.blurb}</span></span>
              </button>
            </li>
          ))}
        </ol>
        <button className="btn quiet" onClick={restore} disabled={busy}><RotateCcw size={16} /> Restore the network</button>
      </div>
    )
  }

  const s = scenario.steps[step]
  const last = step === scenario.steps.length - 1
  const nextScenario = SCENARIOS[idx + 1]

  return (
    <div className="walk">
      <div className="walk-head">
        <button className="btn quiet" onClick={() => setOpenId(null)} disabled={busy}><ArrowLeft size={16} /> All scenarios</button>
        <span className="paper-tag">Paper {scenario.paper}</span>
      </div>
      <h2><span className="num">{idx + 1}</span> {scenario.title}</h2>
      <ol className="dots" aria-label={`Step ${step + 1} of ${scenario.steps.length}`}>
        {scenario.steps.map((_, i) => (
          <li key={i} className={i < step || (i === step && result) ? 'done' : i === step ? 'cur' : ''} />
        ))}
        <li className="dots-label">Step {step + 1} of {scenario.steps.length}</li>
      </ol>

      <div className="stage" key={`${openId}-${step}`}>
        <p className="say">{s.say}</p>
        {!result && (
          <button className="btn primary big" onClick={run} disabled={busy || missing.length > 0}>
            {busy ? <LoaderCircle size={18} className="spin" /> : <Play size={18} />} {busy ? 'Working…' : s.button}
          </button>
        )}
        {busy && tick && <p className="tick">{tick}</p>}
        {result && (
          <>
            <p className={'result' + (result.error ? ' bad' : '')}>
              {!result.error && <Check size={20} className="ok" />} {result.text}
            </p>
            <div className="row">
              {!last && <button className="btn primary big" onClick={next}>Next step <ArrowRight size={18} /></button>}
              {last && nextScenario && (
                <button className="btn primary big" onClick={() => open(nextScenario.id)}>
                  Next: {nextScenario.title} <ArrowRight size={18} />
                </button>
              )}
              {last && <button className="btn" onClick={() => setOpenId(null)}>All scenarios</button>}
              <button className="btn quiet" onClick={() => setResult(null)}>Run again</button>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
