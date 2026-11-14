import { useState } from 'react'
import { Eraser, Pause, Play } from 'lucide-react'
import { time } from '../api.js'

const KIND = (e) => (e === 'DELIVERED' ? 'ok' : e === 'FORWARDED' ? 'fwd' : e === 'TO_CONTROLLER' ? 'ctl' : 'bad')

/** Every packet each switch handled (one line per hop), newest first. */
export default function LogsPanel({ ctx, paused, setPaused }) {
  const [filter, setFilter] = useState('')
  const [event, setEvent] = useState('')
  const rows = ctx.log.entries
    .filter((e) => (!filter || e.switchId === filter) && (!event || e.event === event))
    .slice(-300)
    .reverse()
  const events = [...new Set(ctx.log.entries.map((e) => e.event))]

  return (
    <div className="logs">
      <div className="row">
        <select value={filter} onChange={(e) => setFilter(e.target.value)} title="Switch">
          <option value="">all switches</option>
          {(ctx.netTopo?.switches || []).map((s) => <option key={s.id}>{s.id}</option>)}
        </select>
        <select value={event} onChange={(e) => setEvent(e.target.value)} title="Event">
          <option value="">all events</option>
          {events.map((e) => <option key={e}>{e}</option>)}
        </select>
        <div className="grow" />
        <button className="icon" onClick={() => setPaused(!paused)} title={paused ? 'Resume' : 'Pause'}>
          {paused ? <Play size={17} /> : <Pause size={17} />}
        </button>
        <button className="icon" onClick={() => ctx.act(ctx.log.clear, 'Log cleared')} title="Clear"><Eraser size={17} /></button>
      </div>
      <div className="log-rows">
        {rows.map((e) => (
          <div key={e.seq} className="log-row" title={e.ruleId ? 'rule ' + e.ruleId : ''}>
            <span className="mono muted">{time(e.time)}</span>
            <span className="mono">{e.switchId}</span>
            <span className={'ev ' + KIND(e.event)}>{e.event.replace('_', ' ').toLowerCase()}</span>
            <span className="mono muted">{e.inPort || '·'}→{e.outPort ?? '·'}</span>
            <span className="pkt">{e.packet}{e.latencyMs != null && <b> · {e.latencyMs} ms</b>}</span>
          </div>
        ))}
        {!rows.length && <div className="muted center">Send packets from a switch to see them here</div>}
      </div>
    </div>
  )
}
