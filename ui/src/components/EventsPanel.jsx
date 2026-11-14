import { useEffect, useRef, useState } from 'react'
import { ctrl, time } from '../api.js'

const KIND = (t) => (t.includes('REMOVED') || t.includes('DEAD') || t.includes('NOT_POSSIBLE') ? 'bad'
  : t.includes('ADDED') || t.includes('ALIVE') || t.includes('ACTIVE') || t.includes('READY') ? 'ok' : 'fwd')

/** Controller activity: topology changes, intent status, rule pushes, packet-ins. */
export default function EventsPanel() {
  const [events, setEvents] = useState([])
  const seq = useRef(0)
  useEffect(() => {
    let alive = true
    let timer
    const run = async () => {
      try {
        const fresh = await ctrl(`/api/events?after=${seq.current}`)
        if (alive && fresh.length) {
          seq.current = fresh[fresh.length - 1].seq
          setEvents((old) => [...old, ...fresh].slice(-400))
        }
      } catch {
        /* controller down */
      }
      if (alive) timer = setTimeout(run, 1500)
    }
    run()
    return () => {
      alive = false
      clearTimeout(timer)
    }
  }, [])
  return (
    <div className="log-rows events">
      {events.slice().reverse().map((e) => (
        <div key={e.seq} className="log-row">
          <span className="mono muted">{time(e.time)}</span>
          <span className={'ev ' + KIND(e.type)}>{e.type.replaceAll('_', ' ').toLowerCase()}</span>
          <span className="pkt">{e.message}</span>
        </div>
      ))}
      {!events.length && <div className="muted center">No activity yet</div>}
    </div>
  )
}
