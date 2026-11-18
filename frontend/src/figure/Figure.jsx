import { useMemo, useRef, useState } from 'react'
import { clearSaved, H, layout, loadSaved, storeSaved, W } from './layout.js'

const BW = 76
const BH = 48

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

  const toSvg = (evt) => {
    const pt = svg.current.createSVGPoint()
    pt.x = evt.clientX
    pt.y = evt.clientY
    const p = pt.matrixTransform(svg.current.getScreenCTM().inverse())
    return { x: Math.max(60, Math.min(W - 60, p.x)), y: Math.max(60, Math.min(H - 60, p.y)) }
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
      <svg ref={svg} viewBox={`0 0 ${W} ${H}`} className="topology" onPointerMove={onMove} onPointerUp={onUp}
           onPointerDown={() => onSelect(null)} role="img"
           aria-label={`Network topology: ${netTopo.switches.length} switches, ${netTopo.links.length} links`}>
        {tiers.mid.length > 0 && tiers.top.length > 0 && (
          <g className="tier-labels">
            <text x="18" y={pos[tiers.top[0]]?.y + 5}>top</text>
            <text x="18" y={pos[tiers.mid[0]]?.y + 5}>middle</text>
            {tiers.bottom.length > 0 && <text x="18" y={pos[tiers.bottom[0]]?.y + 5}>bottom</text>}
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
                  <path d="M-8 -8L8 8M8 -8L-8 8" />
                </g>
              )}
              {n > 0 && l.up && (
                <g className="count" transform={`translate(${cx},${cy})`}>
                  <rect x="-17" y="-12" width="34" height="24" rx="3" />
                  <text textAnchor="middle" dy="5.5">{n}</text>
                </g>
              )}
              {(showLinkIds || sel) && !n && state !== 'down' && (
                <text className="link-id" x={mx} y={my - 8} textAnchor="middle">{l.id} · {l.latencyMs} ms</text>
              )}
              {sel && n > 0 && (
                <text className="link-id" x={mx} y={my - 18} textAnchor="middle">{l.id} · {l.latencyMs} ms</text>
              )}
            </g>
          )
        })}

        {netTopo.switches.map((s) => {
          const p = pos[s.id]
          if (!p) return null
          const below = tierOf(s.id) === 'bottom'
          return s.hosts.map((h, i) => {
            const hx = p.x + (i - (s.hosts.length - 1) / 2) * 130
            const hy = below ? p.y + 78 : p.y - 78
            const n = traffic.hosts[`${s.id}:${h.port}`] || 0
            return (
              <g key={s.id + h.port} className={'host' + (n ? ' hot' : '')}>
                <line x1={p.x} y1={below ? p.y + BH / 2 : p.y - BH / 2} x2={hx} y2={below ? hy - 13 : hy + 13} />
                <text x={hx} y={hy + 5} textAnchor="middle">{h.subnet}</text>
                {n > 0 && <text x={hx} y={below ? hy + 28 : hy - 20} textAnchor="middle" className="delivered">{n} delivered</text>}
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
          const noteY = below ? -BH / 2 - 12 : BH / 2 + 38
          return (
            <g key={s.id} transform={`translate(${p.x},${p.y})`}
               className={`switch ${state}${sel ? ' sel' : ''}${focusSw.has(s.id) ? ' focus' : ''}`}
               onPointerDown={(e) => onDown(e, s.id)} tabIndex="0" role="button"
               aria-label={`switch ${s.id}, ${state}`}
               onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect({ kind: 'switch', id: s.id })}>
              {focusSw.has(s.id) && <rect className="ring" x={-BW / 2 - 9} y={-BH / 2 - 9} width={BW + 18} height={BH + 18} rx="4" />}
              <rect className="box" x={-BW / 2} y={-BH / 2} width={BW} height={BH} rx="3" />
              <text className="sid" textAnchor="middle" dy="7">{s.id}</text>
              <text className="sname" textAnchor="middle" y={below ? BH / 2 + 20 : BH / 2 + 20}>{s.name}</text>
              {state === 'unknown' && <text className="note bad" textAnchor="middle" y={noteY}>unknown</text>}
              {state === 'absent' && <text className="note" textAnchor="middle" y={noteY}>not in NIB</text>}
              {state === 'healthy' && !s.controlConnected && <text className="note" textAnchor="middle" y={noteY}>control cut</text>}
              {drops > 0 && (
                <g className="drops" transform={`translate(${BW / 2 + 8},${-BH / 2})`}>
                  <rect width="58" height="22" rx="3" />
                  <text x="29" y="16" textAnchor="middle">{drops} drop</text>
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
