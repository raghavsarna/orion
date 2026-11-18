import { useEffect, useMemo, useRef, useState } from 'react'
import { clearSaved, H, layout, loadSaved, storeSaved, W } from './layout.js'

// sized for a projector: the figure is cropped to its content, so these render close to 1:1 at 1440 px
const BW = 96
const BH = 58
const HOST = 98

/**
 * Figure 1: the network as the paper draws it, live. Geometry comes from the switch network (what is physically
 * there); every mark is styled by what the controller's NIB believes, so a gap between the two is visible.
 * Packets of the last few seconds are drawn as ink-blue strokes with their count on the wire.
 */
export default function Figure({ netTopo, ctrlTopo, traffic, selected, onSelect, focus }) {
  const svg = useRef(null)
  const ids = netTopo.switches.map((s) => s.id)
  const [saved, setSaved] = useState(() => loadSaved(ids))
  const [drag, setDrag] = useState(null)
  const { pos, tiers } = useMemo(() => layout(netTopo.switches, saved), [ids.join(','), saved]) // eslint-disable-line

  const nib = useMemo(() => {
    const sw = {}
    ctrlTopo?.switches?.forEach((s) => (sw[s.id] = s))
    return { sw, links: new Set((ctrlTopo?.links || []).map((l) => l.linkId)) }
  }, [ctrlTopo])

  const focusSw = new Set(focus?.switches || [])
  const focusLinks = new Set(focus?.links || [])
  const maxCount = Math.max(1, ...Object.values(traffic.links))
  const showLinkIds = netTopo.links.length <= 6
  const showTiers = tiers.mid.length > 0 && tiers.top.length > 0

  // k: how much to enlarge text so it renders near its intended pixel size however small the figure is drawn
  const [k, setK] = useState(1)

  // crop the drawing to its content (frozen while dragging so the figure does not jump)
  const lastBox = useRef(null)
  const box = useMemo(() => {
    if (drag && lastBox.current) return lastBox.current
    const pts = Object.values(pos)
    if (!pts.length) return [0, 0, W, H]
    const x0 = Math.min(...pts.map((p) => p.x)) - (showTiers ? 175 : 110)
    const x1 = Math.max(...pts.map((p) => p.x)) + 140
    const y0 = Math.min(...pts.map((p) => p.y)) - 140
    const y1 = Math.max(...pts.map((p) => p.y)) + 145
    lastBox.current = [x0, y0, x1 - x0, y1 - y0]
    return lastBox.current
  }, [pos, drag, showTiers])

  useEffect(() => {
    const el = svg.current
    if (!el || typeof ResizeObserver === 'undefined') return undefined
    const ro = new ResizeObserver(() => {
      const scale = el.getBoundingClientRect().width / box[2]
      if (scale > 0) setK(Math.min(1.45, Math.max(1, 1 / scale)))
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [box[2]]) // eslint-disable-line

  const toSvg = (evt) => {
    const pt = svg.current.createSVGPoint()
    pt.x = evt.clientX
    pt.y = evt.clientY
    const p = pt.matrixTransform(svg.current.getScreenCTM().inverse())
    return { x: Math.max(60, Math.min(W - 60, p.x)), y: Math.max(80, Math.min(H - 80, p.y)) }
  }
  const onDown = (evt, id) => {
    evt.stopPropagation()
    svg.current.setPointerCapture(evt.pointerId)
    setDrag({ id, moved: false, start: toSvg(evt) })
  }
  const onMove = (evt) => {
    if (!drag) return
    const p = toSvg(evt)
    if (!drag.moved && Math.hypot(p.x - drag.start.x, p.y - drag.start.y) < 5) return
    setDrag({ ...drag, moved: true })
    setSaved((s) => ({ ...s, ...pos, [drag.id]: p }))
  }
  const onUp = () => {
    if (!drag) return
    if (!drag.moved) onSelect({ kind: 'switch', id: drag.id })
    else storeSaved(ids, pos)
    setDrag(null)
  }
  const resetLayout = () => {
    clearSaved(ids)
    setSaved({})
  }

  const tierOf = (id) => (tiers.top.includes(id) ? 'top' : tiers.bottom.includes(id) ? 'bottom' : 'mid')

  return (
    <div className="figure-canvas">
      <svg ref={svg} viewBox={box.join(' ')} className="topology" style={{ '--k': k }} onPointerMove={onMove} onPointerUp={onUp}
           onPointerDown={() => onSelect(null)} role="img"
           aria-label={`Network topology: ${netTopo.switches.length} switches, ${netTopo.links.length} links`}>
        {showTiers && (
          <g className="tier-labels">
            <text x={box[0] + 10} y={pos[tiers.top[0]]?.y + 6}>top</text>
            <text x={box[0] + 10} y={pos[tiers.mid[0]]?.y + 6}>middle</text>
            {tiers.bottom.length > 0 && <text x={box[0] + 10} y={pos[tiers.bottom[0]]?.y + 6}>bottom</text>}
          </g>
        )}

        {netTopo.links.map((l) => {
          const a = pos[l.a.split(':')[0]]
          const b = pos[l.b.split(':')[0]]
          if (!a || !b) return null
          const state = !l.up ? 'down' : nib.links.has(l.id) ? 'up' : 'pending'
          const n = traffic.links[l.id] || 0
          const sel = selected?.kind === 'link' && selected.id === l.id
          const mx = (a.x + b.x) / 2
          const my = (a.y + b.y) / 2
          // counts sit off the midpoint (where crossing links meet) and clear of the labels under middle-tier switches
          const [hi, lo, hiId] = a.y <= b.y ? [a, b, l.a.split(':')[0]] : [b, a, l.b.split(':')[0]]
          const f = tierOf(hiId) === 'mid' ? 0.64 : 0.38
          const cx = hi.x + (lo.x - hi.x) * f
          const cy = hi.y + (lo.y - hi.y) * f
          return (
            <g key={l.id} className={`link ${state}${sel ? ' sel' : ''}${focusLinks.has(l.id) ? ' focus' : ''}`}
               onPointerDown={(e) => { e.stopPropagation(); onSelect({ kind: 'link', id: l.id }) }}>
              <title>{`${l.id}: ${l.a} – ${l.b}, ${l.latencyMs} ms${state === 'pending' ? ', not in the NIB yet' : ''}${state === 'down' ? ', down' : ''}`}</title>
              <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="hit" />
              <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="wire" />
              {n > 0 && l.up && (
                <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="flow"
                      style={{ strokeWidth: 2.5 + 5 * (n / maxCount) }} />
              )}
              {state === 'down' && (
                <g className="cross" transform={`translate(${mx},${my})`}>
                  <path d="M-10 -10L10 10M10 -10L-10 10" />
                </g>
              )}
              {n > 0 && l.up && (
                <g className="count" transform={`translate(${cx},${cy})`}>
                  <rect x={-(10 + 6.5 * String(n).length) * k} y={-15 * k} width={(20 + 13 * String(n).length) * k} height={30 * k} />
                  <text textAnchor="middle" dy={7 * k}>{n}</text>
                </g>
              )}
              {(showLinkIds || sel) && !n && state !== 'down' && (
                <text className="link-id" x={mx} y={my - 10} textAnchor="middle">{l.id} · {l.latencyMs} ms</text>
              )}
              {sel && n > 0 && (
                <text className="link-id" x={mx} y={my - 26} textAnchor="middle">{l.id} · {l.latencyMs} ms</text>
              )}
            </g>
          )
        })}

        {netTopo.switches.map((s) => {
          const p = pos[s.id]
          if (!p) return null
          const below = tierOf(s.id) === 'bottom'
          return s.hosts.map((h, i) => {
            const hx = p.x + (i - (s.hosts.length - 1) / 2) * 150
            const hy = below ? p.y + HOST : p.y - HOST
            const n = traffic.hosts[`${s.id}:${h.port}`] || 0
            return (
              <g key={s.id + h.port} className={'host' + (n ? ' hot' : '')}>
                <line x1={p.x} y1={below ? p.y + BH / 2 : p.y - BH / 2} x2={hx} y2={below ? hy - 16 : hy + 16} />
                <text x={hx} y={hy + 6} textAnchor="middle">{h.subnet}</text>
                {n > 0 && <text x={hx} y={below ? hy + 26 * k : hy - 22 * k} textAnchor="middle" className="delivered">{n} delivered</text>}
              </g>
            )
          })
        })}

        {netTopo.switches.map((s) => {
          const p = pos[s.id]
          if (!p) return null
          const c = nib.sw[s.id]
          const state = !c ? 'absent' : c.status === 'DEAD' ? 'unknown' : 'healthy'
          const sel = selected?.kind === 'switch' && selected.id === s.id
          const drops = traffic.drops[s.id] || 0
          const below = tierOf(s.id) === 'bottom'
          return (
            <g key={s.id} transform={`translate(${p.x},${p.y})`}
               className={`switch ${state}${sel ? ' sel' : ''}${focusSw.has(s.id) ? ' focus' : ''}`}
               onPointerDown={(e) => onDown(e, s.id)} tabIndex="0" role="button"
               aria-label={`switch ${s.id}, ${state}`}
               onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect({ kind: 'switch', id: s.id })}>
              {focusSw.has(s.id) && <rect className="ring" x={-BW / 2 - 8} y={-BH / 2 - 8} width={BW + 16} height={BH + 16} />}
              <rect className="box" x={-BW / 2} y={-BH / 2} width={BW} height={BH} />
              <text className="sid" textAnchor="middle" y={-4}>{s.id}</text>
              {/* second line inside the box: the switch's state when it matters, otherwise its name */}
              {state === 'unknown' ? <text className="sub bad" textAnchor="middle" y={19}>unknown</text>
                : state === 'absent' ? <text className="sub" textAnchor="middle" y={19}>not in NIB</text>
                  : !s.controlConnected ? <text className="sub" textAnchor="middle" y={19}>control cut</text>
                    : <text className="sname" textAnchor="middle" y={19}>{s.name}</text>}
              {drops > 0 && (
                <g className="drops" transform={`translate(${BW / 2 + 8},${-BH / 2})`}>
                  <rect width="92" height="28" />
                  <text x="46" y="20" textAnchor="middle">{drops} dropped</text>
                </g>
              )}
            </g>
          )
        })}
      </svg>
      {Object.keys(saved).length > 0 && (
        <button className="link-btn reset-layout" onClick={resetLayout}>Reset layout</button>
      )}
    </div>
  )
}
