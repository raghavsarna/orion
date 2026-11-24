import { useEffect, useMemo, useRef, useState } from 'react'
import { clearSaved, H, layout, loadSaved, storeSaved, W } from './layout.js'

// sized for a projector: the figure is cropped to its content, so these render close to 1:1 at 1440 px
const BW = 122
const BH = 58
const CW = 230
const CH = 60
const PATH_FRESH_MS = 20000

/**
 * Figure 1: the network, live, with the controller drawn above it.
 * Switches and wires come from the switch network (what is physically there) and are styled by what the
 * controller believes. A switch the controller still lists after it left the network is drawn as a grey ghost.
 * Each switch hangs off the controller by a thin management line: red when it lost contact, blue while the switch
 * asks the controller about a packet it has no rule for. The newest packet's route is drawn with moving arrows.
 */
export default function Figure({ netTopo, ctrlTopo, traffic, selected, onSelect, focus, now }) {
  const svg = useRef(null)

  // everything physically present, plus switches/links only the controller still believes in
  const { switches, links } = useMemo(() => {
    const present = new Set(netTopo.switches.map((s) => s.id))
    const ghosts = (ctrlTopo?.switches || []).filter((s) => !present.has(s.id)).map((s) => ({
      id: s.id, name: s.name, hosts: [], controlConnected: false, ghost: true,
    }))
    const wired = new Set(netTopo.links.map((l) => l.id))
    const ghostLinks = (ctrlTopo?.links || []).filter((l) => !wired.has(l.linkId)).map((l) => ({
      id: l.linkId, a: `${l.fromSwitch}:${l.fromPort}`, b: `${l.toSwitch}:${l.toPort}`, latencyMs: l.latencyMs, up: true, ghost: true,
    }))
    return { switches: [...netTopo.switches, ...ghosts], links: [...netTopo.links, ...ghostLinks] }
  }, [netTopo, ctrlTopo])

  const ids = switches.map((s) => s.id)
  const [saved, setSaved] = useState(() => loadSaved(ids))
  const [drag, setDrag] = useState(null)
  const { pos } = useMemo(() => layout(switches, saved), [ids.join(','), saved]) // eslint-disable-line

  const nib = useMemo(() => {
    const sw = {}
    ctrlTopo?.switches?.forEach((s) => (sw[s.id] = s))
    return { sw, links: new Set((ctrlTopo?.links || []).map((l) => l.linkId)) }
  }, [ctrlTopo])

  const focusSw = new Set(focus?.switches || [])
  const focusLinks = new Set(focus?.links || [])
  const [k, setK] = useState(1)

  const pts = Object.values(pos)
  const minX = pts.length ? Math.min(...pts.map((p) => p.x)) : 0
  const maxX = pts.length ? Math.max(...pts.map((p) => p.x)) : W
  const minY = pts.length ? Math.min(...pts.map((p) => p.y)) : 0
  const maxY = pts.length ? Math.max(...pts.map((p) => p.y)) : H
  const ctl = { x: (minX + maxX) / 2, y: minY - 165 }
  const busY = minY - 85
  const center = { x: (minX + maxX) / 2, y: (minY + maxY) / 2 }

  // crop to the content, controller included (frozen while dragging so the figure does not jump)
  const lastBox = useRef(null)
  const box = useMemo(() => {
    if (drag && lastBox.current) return lastBox.current
    const x0 = minX - 190
    const x1 = maxX + 190
    const y0 = ctl.y - CH / 2 - 20
    const y1 = maxY + 120
    lastBox.current = [x0, y0, x1 - x0, y1 - y0]
    return lastBox.current
  }, [minX, maxX, minY, maxY, drag]) // eslint-disable-line

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
    return { x: Math.max(60, Math.min(W - 60, p.x)), y: Math.max(80, Math.min(H - 60, p.y)) }
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

  // where a switch's host subnet label goes: outward, away from the middle of the drawing
  const hostSpot = (p) => {
    const dx = p.x - center.x
    const dy = p.y - center.y
    const len = Math.hypot(dx, dy) || 1
    return { x: p.x + (dx / len) * 125, y: p.y + (dy / len) * 70 }
  }

  const path = traffic.path && now - traffic.path.time < PATH_FRESH_MS ? traffic.path : null
  const pathD = path ? routeD(path, pos, switches, hostSpot) : null
  const ctlSel = selected?.kind === 'controller'

  return (
    <div className="figure-canvas">
      <svg ref={svg} viewBox={box.join(' ')} className="topology" style={{ '--k': k }} onPointerMove={onMove} onPointerUp={onUp}
           onPointerDown={() => onSelect(null)} role="img"
           aria-label={`Controller and ${switches.length} switches, ${links.length} links`}>
        <defs>
          <marker id="arrow" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="4.2" markerHeight="4.2" orient="auto-start-reverse">
            <path d="M0 0L10 5L0 10z" className="arrow-head" />
          </marker>
        </defs>

        {/* management network: controller -> bus -> one drop per switch */}
        <g className="mgmt">
          <line className="ctl-wire" x1={ctl.x} y1={ctl.y + CH / 2} x2={ctl.x} y2={busY} />
          <line className="ctl-wire" x1={minX} y1={busY} x2={maxX} y2={busY} />
          {switches.map((s) => {
            const p = pos[s.id]
            if (!p) return null
            const cut = !s.ghost && !s.controlConnected
            const ask = traffic.asked[s.id]
            return (
              <g key={s.id} className={'drop' + (cut ? ' cut' : '') + (ask ? ' ask' : '') + (s.ghost ? ' ghost' : '')}>
                <line className="ctl-wire" x1={p.x} y1={busY} x2={p.x} y2={p.y - BH / 2} />
                {cut && <path className="ctl-x" d={`M${p.x - 9} ${p.y - BH / 2 - 34}l18 18m0 -18l-18 18`} />}
                {ask && (
                  <text className="ctl-note" x={p.x + 10} y={(busY + p.y - BH / 2) / 2}>asks the controller</text>
                )}
              </g>
            )
          })}
        </g>

        <g className={'controller' + (ctlSel ? ' sel' : '') + (focus?.controller ? ' focus' : '')}
           transform={`translate(${ctl.x},${ctl.y})`} role="button" tabIndex="0" aria-label="the controller"
           onPointerDown={(e) => { e.stopPropagation(); onSelect({ kind: 'controller' }) }}
           onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect({ kind: 'controller' })}>
          {focus?.controller && <rect className="ring" x={-CW / 2 - 8} y={-CH / 2 - 8} width={CW + 16} height={CH + 16} />}
          <rect className="box" x={-CW / 2} y={-CH / 2} width={CW} height={CH} />
          <text className="ctl-name" textAnchor="middle" y={-2}>Controller</text>
          <text className="ctl-sub" textAnchor="middle" y={20}>knows {ctrlTopo?.switches?.length ?? 0} switches</text>
        </g>

        {links.map((l) => {
          const a = pos[l.a.split(':')[0]]
          const b = pos[l.b.split(':')[0]]
          if (!a || !b) return null
          const state = l.ghost ? 'ghost' : !l.up ? 'down' : nib.links.has(l.id) ? 'up' : 'pending'
          const sel = selected?.kind === 'link' && selected.id === l.id
          const mx = (a.x + b.x) / 2
          const my = (a.y + b.y) / 2
          return (
            <g key={l.id} className={`link ${state}${sel ? ' sel' : ''}${focusLinks.has(l.id) ? ' focus' : ''}`}
               onPointerDown={(e) => { e.stopPropagation(); if (!l.ghost) onSelect({ kind: 'link', id: l.id }) }}>
              <title>{`link ${l.a.split(':')[0]}–${l.b.split(':')[0]}${state === 'down' ? ', cut' : ''}`}</title>
              <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="hit" />
              <line x1={a.x} y1={a.y} x2={b.x} y2={b.y} className="wire" />
              {state === 'down' && (
                <g className="cross" transform={`translate(${mx},${my})`}>
                  <path d="M-11 -11L11 11M11 -11L-11 11" />
                </g>
              )}
            </g>
          )
        })}

        {switches.map((s) => {
          const p = pos[s.id]
          if (!p) return null
          return s.hosts.map((h) => {
            const q = hostSpot(p)
            const hot = path?.delivered && path.to === h.subnet
            return (
              <g key={s.id + h.port} className={'host' + (hot ? ' hot' : '')}>
                <line x1={p.x} y1={p.y} x2={q.x} y2={q.y} />
                <rect x={q.x - 62} y={q.y - 17} width="124" height="34" />
                <text x={q.x} y={q.y + 6} textAnchor="middle">{h.subnet.replace('.0/24', '.x')}</text>
              </g>
            )
          })
        })}

        {pathD && (
          <g className={'route' + (path.delivered ? '' : ' failed')}>
            <path d={pathD} className="route-line" markerMid="url(#arrow)" markerEnd="url(#arrow)" />
          </g>
        )}

        {switches.map((s) => {
          const p = pos[s.id]
          if (!p) return null
          const c = nib.sw[s.id]
          const state = s.ghost ? 'ghost' : !c ? 'absent' : c.status === 'DEAD' ? 'unknown' : 'healthy'
          const sel = selected?.kind === 'switch' && selected.id === s.id
          const drops = traffic.drops[s.id] || 0
          return (
            <g key={s.id} transform={`translate(${p.x},${p.y})`}
               className={`switch ${state}${sel ? ' sel' : ''}${focusSw.has(s.id) ? ' focus' : ''}`}
               onPointerDown={(e) => (s.ghost ? e.stopPropagation() : onDown(e, s.id))} tabIndex="0" role="button"
               aria-label={`switch ${s.id}, ${state}`}
               onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && onSelect({ kind: 'switch', id: s.id })}>
              {focusSw.has(s.id) && <rect className="ring" x={-BW / 2 - 8} y={-BH / 2 - 8} width={BW + 16} height={BH + 16} />}
              <rect className="box" x={-BW / 2} y={-BH / 2} width={BW} height={BH} />
              <text className="sid" textAnchor="middle" y={-6}>{s.id}</text>
              {state === 'unknown' ? <StateWord k={k} bad>no contact</StateWord>
                : state === 'ghost' ? <StateWord k={k}>gone</StateWord>
                  : state === 'absent' ? <StateWord k={k}>not known</StateWord>
                    : !s.controlConnected ? <StateWord k={k}>no contact</StateWord>
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

/**
 * The packet's route as an SVG path: from its source hosts, through each switch, to where it ended.
 * Each link is split at its midpoint so an arrow sits in the middle of every hop.
 */
function routeD(path, pos, switches, hostSpot) {
  const pts = path.switches.map((id) => pos[id]).filter(Boolean)
  if (!pts.length) return null
  const first = switches.find((s) => s.id === path.switches[0])
  const lerp = (p, q, t) => ({ x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t })
  // start beside the sender's host label, stop just short of the receiver's, so no label is covered
  const start = first?.hosts?.length ? lerp(hostSpot(pts[0]), pts[0], 0.38) : null
  const last = switches.find((s) => s.id === path.endedAt)
  const end = path.delivered && last?.hosts?.length ? lerp(pos[path.endedAt], hostSpot(pos[path.endedAt]), 0.6) : null
  const seq = [start, ...pts, end].filter(Boolean)
  let d = `M${seq[0].x} ${seq[0].y}`
  for (let i = 1; i < seq.length; i++) {
    const a = seq[i - 1]
    const b = seq[i]
    d += ` L${(a.x + b.x) / 2} ${(a.y + b.y) / 2} L${b.x} ${b.y}`
  }
  return d
}

/** The switch's state inside its box, as large as the labels around it; squeezed to the box width if needed. */
function StateWord({ k, bad, children }) {
  const size = 17 * k
  const tooWide = children.length * size * 0.56 > BW - 10
  return (
    <text className={'sub' + (bad ? ' bad' : '')} textAnchor="middle" y={13 + 6 * k} style={{ fontSize: size }}
          {...(tooWide ? { textLength: BW - 10, lengthAdjust: 'spacingAndGlyphs' } : {})}>
      {children}
    </text>
  )
}
