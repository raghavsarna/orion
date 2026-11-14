import { useEffect, useMemo, useRef, useState } from 'react'

const W = 1000
const H = 640
const R = 30
const STORE = 'orion-node-positions'

function loadPositions() {
  try {
    return JSON.parse(localStorage.getItem(STORE)) || {}
  } catch {
    return {}
  }
}

function savePositions(p) {
  try {
    localStorage.setItem(STORE, JSON.stringify(p))
  } catch {
    /* storage unavailable */
  }
}

/** Circle layout for switches that have no saved position yet. */
function layout(ids, saved) {
  const out = {}
  const n = ids.length
  ids.forEach((id, i) => {
    if (saved[id]) {
      out[id] = saved[id]
    } else {
      const a = (2 * Math.PI * i) / Math.max(n, 1) - Math.PI / 2
      out[id] = { x: W / 2 + Math.cos(a) * 220, y: H / 2 + Math.sin(a) * 200 }
    }
  })
  return out
}

/**
 * The network drawn from the switch network (physical truth), coloured by what the controller knows:
 * switch fill = controller status, link style = in controller topology / not yet / down.
 * Links and host subnets that carried packets in the last seconds glow. Drag switches to move them.
 */
export default function Topology({ netTopo, ctrlTopo, ctrlSwitches, log, selected, onSelect }) {
  const svg = useRef(null)
  const [saved, setSaved] = useState(loadPositions)
  const [drag, setDrag] = useState(null)
  const ids = netTopo.switches.map((s) => s.id)
  const pos = useMemo(() => layout(ids, saved), [ids.join(','), saved])
  const [now, setNow] = useState(Date.now())

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(t)
  }, [])

  // zoom to fit: viewBox around the switches and their host labels (frozen while dragging)
  const lastBox = useRef(null)
  const box = useMemo(() => {
    if (drag && lastBox.current) return lastBox.current
    const pts = Object.values(pos)
    if (!pts.length) return `0 0 ${W} ${H}`
    const pad = 130
    let x0 = Math.min(...pts.map((p) => p.x)) - pad
    let y0 = Math.min(...pts.map((p) => p.y)) - pad
    let x1 = Math.max(...pts.map((p) => p.x)) + pad
    let y1 = Math.max(...pts.map((p) => p.y)) + pad
    const minW = 520
    const minH = 360
    if (x1 - x0 < minW) { const c = (x0 + x1) / 2; x0 = c - minW / 2; x1 = c + minW / 2 }
    if (y1 - y0 < minH) { const c = (y0 + y1) / 2; y0 = c - minH / 2; y1 = c + minH / 2 }
    lastBox.current = `${x0} ${y0} ${x1 - x0} ${y1 - y0}`
    return lastBox.current
  }, [pos, drag])

  const ctrlLinks = useMemo(() => new Set((ctrlTopo?.links || []).map((l) => l.linkId)), [ctrlTopo])

  // ports that forwarded / delivered packets recently -> glow
  const hot = useMemo(() => {
    const ports = new Set()
    const drops = new Set()
    for (let i = log.length - 1; i >= 0; i--) {
      const e = log[i]
      if (now - e.time > 4000) break
      if (e.event === 'FORWARDED' || e.event === 'DELIVERED') ports.add(`${e.switchId}:${e.outPort}`)
      if (e.event.startsWith('DROPPED') || e.event === 'RATE_LIMITED') drops.add(e.switchId)
    }
    return { ports, drops }
  }, [log, now])

  const toSvg = (evt) => {
    const pt = svg.current.createSVGPoint()
    pt.x = evt.clientX
    pt.y = evt.clientY
    const p = pt.matrixTransform(svg.current.getScreenCTM().inverse())
    return { x: Math.max(-W, Math.min(2 * W, p.x)), y: Math.max(-H, Math.min(2 * H, p.y)) }
  }

  const onDown = (evt, id) => {
    evt.stopPropagation()
    svg.current.setPointerCapture(evt.pointerId)
    setDrag({ id, moved: false, start: toSvg(evt) })
  }
  const onMove = (evt) => {
    if (!drag) return
    const p = toSvg(evt)
    if (!drag.moved && Math.hypot(p.x - drag.start.x, p.y - drag.start.y) < 4) return
    setDrag({ ...drag, moved: true })
    setSaved((s) => ({ ...s, ...pos, [drag.id]: p }))
  }
  const onUp = () => {
    if (!drag) return
    if (!drag.moved) onSelect({ kind: 'switch', id: drag.id })
    else savePositions({ ...pos })
    setDrag(null)
  }

  return (
    <svg ref={svg} viewBox={box} className="topology" onPointerMove={onMove} onPointerUp={onUp}
         onPointerDown={() => onSelect(null)}>
      {/* links */}
      {netTopo.links.map((l) => {
        const a = pos[l.a.split(':')[0]]
        const b = pos[l.b.split(':')[0]]
        if (!a || !b) return null
        const state = !l.up ? 'down' : ctrlLinks.has(l.id) ? 'up' : 'pending'
        const glowing = l.up && (hot.ports.has(l.a) || hot.ports.has(l.b))
        const sel = selected?.kind === 'link' && selected.id === l.id
        const mx = (a.x + b.x) / 2
        const my = (a.y + b.y) / 2
        return (
          <g key={l.id} className={`link ${state}${glowing ? ' hot' : ''}${sel ? ' sel' : ''}`}
             onPointerDown={(e) => { e.stopPropagation(); onSelect({ kind: 'link', id: l.id }) }}>
            <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="hit" />
            <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="wire" />
            <g transform={`translate(${mx},${my})`}>
              <rect x="-30" y="-12" width="60" height="24" rx="12" className="pill" />
              <text textAnchor="middle" dy="4">{l.id} · {l.latencyMs}ms</text>
            </g>
            <PortLabel from={a} to={b} text={l.a.split(':')[1]} />
            <PortLabel from={b} to={a} text={l.b.split(':')[1]} />
          </g>
        )
      })}

      {/* host subnets */}
      {netTopo.switches.map((s) => {
        const p = pos[s.id]
        if (!p) return null
        return s.hosts.map((h, i) => {
          let dx = p.x - W / 2
          let dy = p.y - H / 2
          const len = Math.hypot(dx, dy) || 1
          dx /= len
          dy /= len
          const spread = (i - (s.hosts.length - 1) / 2) * 0.6
          const ux = dx * Math.cos(spread) - dy * Math.sin(spread)
          const uy = dx * Math.sin(spread) + dy * Math.cos(spread)
          const hx = p.x + ux * 92
          const hy = p.y + uy * 78
          const glowing = hot.ports.has(`${s.id}:${h.port}`)
          return (
            <g key={s.id + h.port} className={'host' + (glowing ? ' hot' : '')}>
              <line x1={p.x} y1={p.y} x2={hx} y2={hy} />
              <rect x={hx - 52} y={hy - 13} width="104" height="26" rx="6" />
              <text x={hx} y={hy + 4} textAnchor="middle">{h.subnet}</text>
            </g>
          )
        })
      })}

      {/* switches */}
      {netTopo.switches.map((s) => {
        const p = pos[s.id]
        if (!p) return null
        const c = ctrlSwitches[s.id]
        const status = c ? c.status.toLowerCase() : 'unknown'
        const sel = selected?.kind === 'switch' && selected.id === s.id
        return (
          <g key={s.id} transform={`translate(${p.x},${p.y})`}
             className={`switch ${status}${s.controlConnected ? '' : ' cut'}${sel ? ' sel' : ''}${hot.drops.has(s.id) ? ' dropping' : ''}`}
             onPointerDown={(e) => onDown(e, s.id)}>
            <circle r={R} />
            <text dy="5" textAnchor="middle" className="sid">{s.id}</text>
            <text dy={R + 16} textAnchor="middle" className="sname">{s.name}</text>
          </g>
        )
      })}
    </svg>
  )
}

/** Small port number next to the switch end of a link. */
function PortLabel({ from, to, text }) {
  const dx = to.x - from.x
  const dy = to.y - from.y
  const len = Math.hypot(dx, dy) || 1
  const x = from.x + (dx / len) * (R + 12)
  const y = from.y + (dy / len) * (R + 12)
  return (
    <text x={x} y={y + 4} textAnchor="middle" className="port">{text}</text>
  )
}
