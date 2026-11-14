import { useMemo, useState } from 'react'
import { Activity, Cable, Grid3x3, RefreshCw, ScrollText, Server, Target, Users } from 'lucide-react'
import { ctrl, net, usePacketLog, usePoll } from './api.js'
import Topology from './topology/Topology.jsx'
import SwitchPanel from './sidebar/SwitchPanel.jsx'
import LinkPanel from './sidebar/LinkPanel.jsx'
import IntentsPanel from './sidebar/IntentsPanel.jsx'
import GroupsPanel from './sidebar/GroupsPanel.jsx'
import LogsPanel from './sidebar/LogsPanel.jsx'
import EventsPanel from './sidebar/EventsPanel.jsx'
import { AddLinkDialog, AddSwitchDialog } from './topology/Dialogs.jsx'
import Matrix from './topology/Matrix.jsx'

const TABS = [
  { id: 'intents', icon: Target, title: 'Intents' },
  { id: 'groups', icon: Users, title: 'IP groups' },
  { id: 'packets', icon: Activity, title: 'Packet log' },
  { id: 'events', icon: ScrollText, title: 'Controller activity' },
]

export default function App() {
  const [netTopo, netErr, refreshNet] = usePoll(() => net('/network/topology'), 1500)
  const [ctrlTopo, ctrlErr, refreshCtrl] = usePoll(() => ctrl('/api/topology'), 1500)
  const [status] = usePoll(() => ctrl('/api/status'), 4000)
  const [paused, setPaused] = useState(false)
  const log = usePacketLog(paused)
  const [selected, setSelected] = useState(null)
  const [tab, setTab] = useState('intents')
  const [dialog, setDialog] = useState(null)
  const [matrix, setMatrix] = useState(false)
  const [toasts, setToasts] = useState([])

  const toast = (text, kind = 'ok') => {
    const id = Math.random()
    setToasts((t) => [...t, { id, text, kind }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === 'err' ? 6000 : 2500)
  }
  const refresh = () => {
    refreshNet()
    refreshCtrl()
  }
  /** Runs an API call, shows the result, refreshes the topology. */
  const act = async (fn, okText) => {
    try {
      const r = await fn()
      if (okText) toast(okText)
      refresh()
      return r
    } catch (e) {
      toast(e.message, 'err')
      return undefined
    }
  }

  const ctrlSwitches = useMemo(() => {
    const m = {}
    ctrlTopo?.switches?.forEach((s) => (m[s.id] = s))
    return m
  }, [ctrlTopo])

  const ctx = { netTopo, ctrlTopo, ctrlSwitches, status, act, toast, log }
  const selSwitch = selected?.kind === 'switch' && netTopo?.switches.find((s) => s.id === selected.id)
  const selLink = selected?.kind === 'link' && netTopo?.links.find((l) => l.id === selected.id)

  return (
    <div className="app">
      <header>
        <div className="brand">
          <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden>
            <circle cx="16" cy="16" r="13" fill="none" stroke="var(--accent)" strokeWidth="3" />
            <circle cx="16" cy="16" r="4" fill="var(--accent)" />
          </svg>
          Orion
        </div>
        <div className="health">
          <Dot ok={!ctrlErr && ctrlTopo} label="Controller" />
          <Dot ok={!netErr && netTopo} label="Switch network" />
          <Dot ok={status?.databaseReady} label="Database" />
          <Dot ok={status?.llm?.configured} label={status?.llm?.configured ? `LLM ${status.llm.model}` : 'LLM not configured'} warn />
        </div>
        <div className="actions">
          <button className="btn" onClick={() => setDialog('switch')} title="Add switch">
            <Server size={16} /> +
          </button>
          <button className="btn" onClick={() => setDialog('link')} title="Add link">
            <Cable size={16} /> +
          </button>
          <button className={'icon' + (matrix ? ' on' : '')} onClick={() => setMatrix((m) => !m)} title="Adjacency matrix (controller NIB)">
            <Grid3x3 size={18} />
          </button>
          <button className="icon" onClick={() => act(() => ctrl('/api/recompute', { method: 'POST' }), 'Safety check started')} title="Re-check topology and rules now">
            <RefreshCw size={18} />
          </button>
        </div>
      </header>

      <main>
        <section className="canvas">
          {netTopo ? (
            <Topology netTopo={netTopo} ctrlTopo={ctrlTopo} ctrlSwitches={ctrlSwitches} log={log.entries}
                      selected={selected} onSelect={setSelected} />
          ) : (
            <div className="empty">{netErr ? 'Switch network unreachable' : 'Loading…'}</div>
          )}
          {matrix && ctrlTopo && <Matrix topo={ctrlTopo} onClose={() => setMatrix(false)} />}
          <Legend />
        </section>

        <aside>
          {selSwitch && <SwitchPanel key={selSwitch.id} sw={selSwitch} ctx={ctx} onClose={() => setSelected(null)} />}
          {selLink && <LinkPanel key={selLink.id} link={selLink} ctx={ctx} onClose={() => setSelected(null)} />}
          <nav className="tabs">
            {TABS.map((t) => (
              <button key={t.id} className={tab === t.id ? 'on' : ''} onClick={() => setTab(t.id)} title={t.title}>
                <t.icon size={17} />
                <span>{t.title}</span>
              </button>
            ))}
          </nav>
          <div className="tab-body">
            {tab === 'intents' && <IntentsPanel ctx={ctx} />}
            {tab === 'groups' && <GroupsPanel ctx={ctx} />}
            {tab === 'packets' && <LogsPanel ctx={ctx} paused={paused} setPaused={setPaused} />}
            {tab === 'events' && <EventsPanel />}
          </div>
        </aside>
      </main>

      {dialog === 'switch' && <AddSwitchDialog ctx={ctx} onClose={() => setDialog(null)} />}
      {dialog === 'link' && <AddLinkDialog ctx={ctx} onClose={() => setDialog(null)} />}

      <div className="toasts">
        {toasts.map((t) => (
          <div key={t.id} className={'toast ' + t.kind}>{t.text}</div>
        ))}
      </div>
    </div>
  )
}

function Dot({ ok, label, warn }) {
  return (
    <span className="dot-wrap" title={label}>
      <span className={'dot ' + (ok ? 'ok' : warn ? 'warn' : 'bad')} />
      <span className="dot-label">{label}</span>
    </span>
  )
}

function Legend() {
  return (
    <div className="legend">
      <span><i className="sw alive" /> alive</span>
      <span><i className="sw dead" /> dead</span>
      <span><i className="sw unknown" /> not registered</span>
      <span><i className="ln up" /> link</span>
      <span><i className="ln pending" /> not in controller</span>
      <span><i className="ln down" /> down</span>
    </div>
  )
}
