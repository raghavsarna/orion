import { useState } from 'react'
import { ctrl, time, usePoll } from '../api.js'

const BAD = /DEAD|REMOVED|NOT_POSSIBLE|FAIL_STATIC|FAIL_CLOSED|failed/

/** Table 3: the controller's event stream (NIB changes in order). Table 4: every packet hop. */
export default function Log({ log, paused, setPaused }) {
  const [view, setView] = useState('nib')
  const [events] = usePoll(() => ctrl('/api/events?limit=150'), 1500)
  const [sw, setSw] = useState('')
  const packets = log.entries.filter((e) => !sw || e.switchId === sw).slice(-120).reverse()
  const switches = [...new Set(log.entries.map((e) => e.switchId))].sort()

  return (
    <div className="log">
      <div className="seg" role="tablist" aria-label="Log">
        <button role="tab" aria-selected={view === 'nib'} className={view === 'nib' ? 'on' : ''} onClick={() => setView('nib')}>Controller events</button>
        <button role="tab" aria-selected={view === 'pkt'} className={view === 'pkt' ? 'on' : ''} onClick={() => setView('pkt')}>Packets</button>
      </div>
      {view === 'nib' && (
        <table className="data">
          <caption><b>Table 3:</b> Controller events, newest first. NIB changes are applied, versioned, then published to subscribers in this order.</caption>
          <thead><tr><th>time</th><th>event</th><th>detail</th></tr></thead>
          <tbody>
            {(events || []).slice().reverse().map((e) => (
              <tr key={e.seq}>
                <td className="num">{time(e.time)}</td>
                <td className={BAD.test(e.type) ? 'bad' : ''}>{e.type.replace(/_/g, ' ').toLowerCase()}</td>
                <td>{e.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {view === 'pkt' && (
        <>
          <div className="row">
            <label className="inline">Switch
              <select value={sw} onChange={(e) => setSw(e.target.value)}>
                <option value="">all</option>
                {switches.map((s) => <option key={s}>{s}</option>)}
              </select>
            </label>
            <button className="btn quiet" onClick={() => setPaused(!paused)}>{paused ? 'Resume' : 'Pause'}</button>
            <button className="btn quiet" onClick={log.clear}>Clear</button>
          </div>
          <table className="data">
            <caption><b>Table 4:</b> Packets, one row per switch hop, newest first. Latency is the sum of the links' configured latencies.</caption>
            <thead><tr><th>time</th><th>switch</th><th>event</th><th>packet</th></tr></thead>
            <tbody>
              {packets.map((e) => (
                <tr key={e.seq}>
                  <td className="num">{time(e.time)}</td>
                  <td>{e.switchId}</td>
                  <td className={e.event.startsWith('DROP') || e.event === 'RATE_LIMITED' ? 'bad' : ''}>
                    {e.event.replace(/_/g, ' ').toLowerCase()}{e.outPort != null ? ` → ${e.outPort}` : ''}
                    {e.pathLatencyMs != null ? `, ${e.pathLatencyMs} ms` : ''}
                  </td>
                  <td>{e.packet}</td>
                </tr>
              ))}
              {!packets.length && <tr><td colSpan="4" className="empty-row">No packets yet. Send traffic from a switch in Inspect, or run a walkthrough.</td></tr>}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}
