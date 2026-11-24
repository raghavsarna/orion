import { useEffect, useMemo, useRef, useState } from 'react'
import { ctrl, net, usePacketLog, usePoll } from './api.js'
import Figure from './figure/Figure.jsx'
import Matrix from './figure/Matrix.jsx'
import { caption } from './figure/caption.js'
import { summarise } from './figure/traffic.js'
import Walkthrough from './walkthrough/Walkthrough.jsx'
import { makeLab } from './walkthrough/lab.js'
import Intents from './panels/Intents.jsx'
import Inspect from './panels/Inspect.jsx'
import Measure from './panels/Measure.jsx'
import Log from './panels/Log.jsx'

const TABS = [
  { id: 'walk', title: 'Scenarios', section: 'A live demo of the Orion paper' },
  { id: 'intents', title: 'Intents', section: '§3.1 Intent-based management' },
  { id: 'inspect', title: 'Inspect', section: '§4.1 Orion Core' },
  { id: 'measure', title: 'Measure', section: '§6 Evaluation' },
  { id: 'log', title: 'Log', section: '§4.1 The NIB' },
]

export default function App() {
  const [netTopo, netErr, refreshNet] = usePoll(() => net('/network/topology'), 1200)
  const [ctrlTopo, ctrlErr, refreshCtrl] = usePoll(() => ctrl('/api/topology'), 1200)
  const [status] = usePoll(() => ctrl('/api/status'), 4000)
  const [fail] = usePoll(() => ctrl('/api/fail-mode'), 1500)
  const [paused, setPaused] = useState(false)
  const log = usePacketLog(paused)
  const [selected, setSelected] = useState(null)
  const [tab, setTab] = useState('walk')
  const [scene, setScene] = useState(null)
  const [focus, setFocus] = useState({})
  const [matrix, setMatrix] = useState(false)
  const [trials, setTrials] = useState([])
  const [toasts, setToasts] = useState([])
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(t)
  }, [])

  const topoRef = useRef({})
  topoRef.current = { netTopo, ctrlTopo }
  const lab = useMemo(() => makeLab(() => topoRef.current), [])

  const toast = (text, kind = 'ok') => {
    const id = Math.random()
    setToasts((t) => [...t.slice(-2), { id, text, kind }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === 'err' ? 7000 : 3500)
  }
  const act = async (fn, okText) => {
    try {
      const r = await fn()
      if (okText) toast(okText)
      refreshNet()
      refreshCtrl()
      return r
    } catch (e) {
      toast(e.message, 'err')
      return undefined
    }
  }
  const select = (s) => {
    setSelected(s)
    if (s) setTab('inspect')
  }

  const traffic = useMemo(() => summarise(log.entries, netTopo, now), [log.entries, netTopo, now])
  const failMode = fail?.failMode
  const lines = caption({ netTopo, ctrlTopo, failMode, traffic, now })
  const ctx = { netTopo, ctrlTopo, status, act, toast, log }
  const live = failMode?.mode === 'FAIL_STATIC' ? '§3.2 Fail Static' : failMode?.mode === 'FAIL_CLOSED' ? '§3.2 Fail Closed' : null
  const guide = scene && tab === 'walk' ? `${scene.title} (paper ${scene.section})` : live || TABS.find((t) => t.id === tab).section

  return (
    <div className="app">
      <header className="head">
        <h1>Orion</h1>
        <p className="guide" aria-label="Paper section">{guide}</p>
        <dl className="state">
          <Health ok={!ctrlErr && ctrlTopo} label="controller" />
          <Health ok={!netErr && netTopo} label="switches" />
          <Health ok={status?.databaseReady} label="database" />
          <div><dt>map</dt><dd className="num">v{ctrlTopo?.version ?? '–'}</dd></div>
          <div className={failMode && failMode.mode !== 'NORMAL' ? 'alert' : ''}>
            <dt>lost contact</dt>
            <dd>{!failMode ? '–' : failMode.mode === 'NORMAL' ? 'none' : `${failMode.unknown.join(', ')} (${failMode.mode === 'FAIL_STATIC' ? 'Fail Static' : 'Fail Closed'})`}</dd>
          </div>
        </dl>
      </header>

      <main>
        <section className="paper" aria-label="Figures">
          <figure className="fig1">
            {netTopo ? (
              <Figure netTopo={netTopo} ctrlTopo={ctrlTopo} traffic={traffic} selected={selected} onSelect={select} focus={focus} now={now} />
            ) : (
              <div className="placeholder">{netErr ? 'The switch network is not answering. Is docker compose up running?' : 'Loading the network…'}</div>
            )}
            <figcaption>
              <b>Figure 1:</b> <i>The network, live.</i>{' '}
              <span className="scroll-hint">Scroll the figure sideways. </span>
              {lines.map((p) => <span key={p.key} className="cap-part">{p.text} </span>)}
            </figcaption>
            <Key />
          </figure>
          {matrix && ctrlTopo && (
            <figure className="fig2">
              <Matrix ctrlTopo={ctrlTopo} />
              <figcaption><b>Figure 2:</b> <i>The controller's map as a table.</i> One row and one column per switch; a cell names the link between them. Version {ctrlTopo.version}.
                {' '}<button className="link-btn" onClick={() => setMatrix(false)}>Hide</button></figcaption>
            </figure>
          )}
          {!matrix && ctrlTopo && <button className="link-btn show-matrix" onClick={() => setMatrix(true)}>Show the controller's map as a table (Figure 2)</button>}
        </section>

        <aside className="side">
          <nav className="tabs" role="tablist" aria-label="Views">
            {TABS.map((t) => (
              <button key={t.id} role="tab" aria-selected={tab === t.id} className={tab === t.id ? 'on' : ''} onClick={() => setTab(t.id)}>
                {t.title}
              </button>
            ))}
          </nav>
          <div className="pane" hidden={tab !== 'walk'}>
            <Walkthrough lab={lab} netTopo={netTopo} onScene={setScene} onFocus={setFocus} toast={toast} />
          </div>
          <div className="pane" hidden={tab !== 'intents'}><Intents ctx={ctx} /></div>
          <div className="pane" hidden={tab !== 'inspect'}><Inspect ctx={ctx} selected={selected} onSelect={select} /></div>
          <div className="pane" hidden={tab !== 'measure'}>
            <Measure ctx={ctx} lab={lab} trials={trials} addTrials={(t) => setTrials((x) => [...x, ...t])} />
          </div>
          <div className="pane" hidden={tab !== 'log'}><Log log={log} paused={paused} setPaused={setPaused} /></div>
        </aside>
      </main>

      <div className="toasts" role="status" aria-live="polite">
        {toasts.map((t) => <p key={t.id} className={'toast ' + t.kind}>{t.text}</p>)}
      </div>
    </div>
  )
}

function Health({ ok, label }) {
  return (
    <div className={ok ? '' : 'alert'}>
      <dt>{label}</dt>
      <dd>{ok ? 'up' : 'down'}</dd>
    </div>
  )
}

/** The figure's key, drawn with the same marks as the figure. */
function Key() {
  return (
    <ul className="key" aria-label="Key">
      <li><svg width="30" height="20"><rect x="3" y="3" width="24" height="14" className="k-box" /></svg>switch</li>
      <li><svg width="30" height="20"><rect x="3" y="3" width="24" height="14" className="k-box unknown" /></svg>no contact with controller</li>
      <li><svg width="30" height="20"><line x1="2" y1="10" x2="28" y2="10" className="k-wire" /></svg>link</li>
      <li><svg width="30" height="20"><line x1="2" y1="10" x2="28" y2="10" className="k-wire down" /></svg>cut link</li>
      <li><svg width="30" height="20"><line x1="2" y1="10" x2="28" y2="10" className="k-ctl" /></svg>controller connection</li>
      <li><svg width="30" height="20"><line x1="2" y1="10" x2="28" y2="10" className="k-flow" /></svg>last packet's route</li>
    </ul>
  )
}
