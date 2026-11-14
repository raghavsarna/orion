import { useState } from 'react'
import { X } from 'lucide-react'
import { net } from '../api.js'

function Modal({ title, onClose, children }) {
  return (
    <div className="modal-bg" onPointerDown={onClose}>
      <div className="modal" onPointerDown={(e) => e.stopPropagation()}>
        <div className="panel-head">
          <h3>{title}</h3>
          <div className="grow" />
          <button className="icon" onClick={onClose}><X size={17} /></button>
        </div>
        {children}
      </div>
    </div>
  )
}

export function AddSwitchDialog({ ctx, onClose }) {
  const ids = ctx.netTopo?.switches.map((s) => s.id) || []
  let n = ids.length + 1
  while (ids.includes('s' + n)) n++
  const [f, setF] = useState({ id: 's' + n, name: '', ports: 4, hostPort: 4, subnet: `10.0.${n}.0/24`, autoRegister: true })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const save = () => ctx.act(async () => {
    await net('/network/switches', {
      method: 'POST',
      body: {
        id: f.id, name: f.name || f.id, ports: Number(f.ports), autoRegister: f.autoRegister,
        hosts: f.subnet ? [{ port: Number(f.hostPort), subnet: f.subnet }] : [],
      },
    })
    onClose()
  }, `Switch ${f.id} added`)
  return (
    <Modal title="Add switch" onClose={onClose}>
      <div className="form">
        <div className="row">
          <label>id<input value={f.id} onChange={set('id')} /></label>
          <label className="w2">name<input value={f.name} onChange={set('name')} placeholder={f.id} /></label>
          <label>ports<input value={f.ports} onChange={set('ports')} /></label>
        </div>
        <div className="row">
          <label>host port<input value={f.hostPort} onChange={set('hostPort')} /></label>
          <label className="w2">host subnet<input value={f.subnet} onChange={set('subnet')} placeholder="none" /></label>
        </div>
        <label className="check" title="Off = the switch waits for the controller to contact it (manual add)">
          <input type="checkbox" checked={f.autoRegister} onChange={set('autoRegister')} /> registers itself with the controller
        </label>
        <div className="row end"><button className="btn primary" onClick={save}>Add</button></div>
      </div>
    </Modal>
  )
}

export function AddLinkDialog({ ctx, onClose }) {
  const switches = ctx.netTopo?.switches || []
  const linkIds = ctx.netTopo?.links.map((l) => l.id) || []
  let n = linkIds.length + 1
  while (linkIds.includes('l' + n)) n++
  const free = (id) => (switches.find((s) => s.id === id)?.ports || []).filter((p) => !p.linkId && !p.hostSubnet).map((p) => p.port)
  const [f, setF] = useState(() => {
    const a = switches[0]?.id || ''
    const b = switches[1]?.id || ''
    return { id: 'l' + n, a, ap: free(a)[0] || '', b, bp: free(b)[0] || '', latencyMs: 5 }
  })
  const set = (k) => (e) => {
    const v = e.target.value
    const next = { ...f, [k]: v }
    if (k === 'a') next.ap = free(v)[0] || ''
    if (k === 'b') next.bp = free(v)[0] || ''
    setF(next)
  }
  const save = () => ctx.act(async () => {
    await net('/network/links', { method: 'POST', body: { id: f.id, a: `${f.a}:${f.ap}`, b: `${f.b}:${f.bp}`, latencyMs: Number(f.latencyMs) } })
    onClose()
  }, `Link ${f.id} added`)
  const End = ({ s, p }) => (
    <div className="row">
      <label className="w2">switch
        <select value={f[s]} onChange={set(s)}>{switches.map((x) => <option key={x.id}>{x.id}</option>)}</select>
      </label>
      <label>port
        <select value={f[p]} onChange={set(p)}>
          {free(f[s]).map((x) => <option key={x}>{x}</option>)}
          {!free(f[s]).length && <option value="">none free</option>}
        </select>
      </label>
    </div>
  )
  return (
    <Modal title="Add link" onClose={onClose}>
      <div className="form">
        <div className="row">
          <label>id<input value={f.id} onChange={set('id')} /></label>
          <label>latency ms<input value={f.latencyMs} onChange={set('latencyMs')} /></label>
        </div>
        {End({ s: 'a', p: 'ap' })}
        {End({ s: 'b', p: 'bp' })}
        <div className="row end"><button className="btn primary" onClick={save}>Add</button></div>
      </div>
    </Modal>
  )
}
