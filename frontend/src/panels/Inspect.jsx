import { useState } from 'react'
import { Plug, Send, Trash2, Unplug, UserPlus, Wrench, Zap, ZapOff } from 'lucide-react'
import { ctrl, hostIp, net, usePoll } from '../api.js'

const PRESETS = {
  HTTP: { l4Protocol: 'TCP', appProtocol: 'HTTP', dstPort: 80, host: '' },
  SSH: { l4Protocol: 'TCP', appProtocol: 'SSH', dstPort: 22, host: '' },
  DNS: { l4Protocol: 'UDP', appProtocol: 'DNS', dstPort: 53, host: '' },
  YouTube: { l4Protocol: 'TCP', appProtocol: 'HTTPS', dstPort: 443, host: 'youtube.com' },
}

/** Details and actions for what is selected in Figure 1; with nothing selected, add switches and links. */
export default function Inspect({ ctx, selected, onSelect }) {
  const sw = selected?.kind === 'switch' && ctx.netTopo?.switches.find((s) => s.id === selected.id)
  const link = selected?.kind === 'link' && ctx.netTopo?.links.find((l) => l.id === selected.id)
  if (sw) return <SwitchView key={sw.id} sw={sw} ctx={ctx} onSelect={onSelect} />
  if (link) return <LinkView key={link.id} link={link} ctx={ctx} onSelect={onSelect} />
  return (
    <div className="inspect">
      <p className="lede">Select a switch or a link in Figure 1 to see its flow table, send traffic, cut its controller connection or fail it. Drag switches to rearrange the figure.</p>
      <AddSwitch ctx={ctx} />
      <AddLink ctx={ctx} />
    </div>
  )
}

function SwitchView({ sw, ctx, onSelect }) {
  const nib = ctx.ctrlTopo?.switches.find((s) => s.id === sw.id)
  const health = !nib ? 'not in the NIB' : nib.status === 'DEAD' ? 'unknown' : 'healthy'
  const toggle = () => ctx.act(() => net(`/network/switches/${sw.id}`, { method: 'PATCH', body: { connected: !sw.controlConnected } }),
    sw.controlConnected ? `${sw.id} cut from the controller; it keeps forwarding` : `${sw.id} reconnected`)
  return (
    <div className="inspect">
      <h2>{sw.id} <span className="sub">{sw.name}</span></h2>
      <dl className="facts">
        <dt>Health in the NIB</dt><dd className={health === 'healthy' ? '' : 'bad'}>{health}{nib?.missedProbes ? `, ${nib.missedProbes} missed probes` : ''}</dd>
        <dt>Controller connection</dt><dd>{sw.controlConnected ? 'connected' : 'cut (still forwarding)'}</dd>
        <dt>Hosts</dt><dd>{sw.hosts.length ? sw.hosts.map((h) => `${h.subnet} on port ${h.port}`).join(', ') : 'none (transit switch)'}</dd>
      </dl>
      <div className="row wrap">
        <button className="btn" onClick={toggle}>{sw.controlConnected ? <><Unplug size={16} /> Cut controller connection</> : <><Plug size={16} /> Reconnect</>}</button>
        {!nib && (
          <button className="btn" onClick={() => ctx.act(() => ctrl('/api/switches', { method: 'POST', body: { switchId: sw.id, address: sw.address } }), `Controller contacted ${sw.id}`)}>
            <UserPlus size={16} /> Add to controller
          </button>
        )}
        <button className="btn danger" onClick={() => confirm(`Remove switch ${sw.id} from the network?`) &&
          ctx.act(() => net(`/network/switches/${sw.id}`, { method: 'DELETE' }), `${sw.id} removed`).then(() => onSelect(null))}>
          <Trash2 size={16} /> Remove switch
        </button>
      </div>
      {sw.hosts.length > 0 && <SendForm sw={sw} ctx={ctx} />}
      <Flows sw={sw} ctx={ctx} />
      <table className="data">
        <caption><b>Ports</b> of {sw.id}.</caption>
        <tbody>
          {sw.ports.map((p) => (
            <tr key={p.port}>
              <td className="num">{p.port}</td>
              <td>{p.hostSubnet ? `hosts ${p.hostSubnet}` : p.linkId ? `${p.linkId} to ${p.peerSwitch}:${p.peerPort}` : <span className="hint">free</span>}</td>
              <td>{p.linkId && <span className={p.up ? '' : 'bad'}>{p.up ? 'up' : 'down'}</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SendForm({ sw, ctx }) {
  const subnets = (ctx.ctrlTopo?.hosts || []).map((h) => h.subnet)
  const own = sw.hosts[0].subnet
  const [f, setF] = useState({ dst: subnets.find((s) => s !== own) || own, preset: 'HTTP', pps: 50, count: 100, flows: 20 })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const send = () => ctx.act(async () => {
    const p = PRESETS[f.preset]
    const pps = Number(f.pps)
    const count = Number(f.count)
    await net(`/switch/${sw.id}/send`, { method: 'POST', body: {
      inPort: sw.hosts[0].port, srcIp: hostIp(own, 5), srcPort: 40000, dstIp: hostIp(f.dst, 7), ...p,
      sizeBytes: 512, packetsPerSecond: pps, durationSec: Math.max(1, Math.ceil(count / pps)), count, flows: Number(f.flows),
    } })
  }, `Sending ${f.count} ${f.preset} packets from ${sw.id}`)
  return (
    <form className="send" onSubmit={(e) => { e.preventDefault(); send() }}>
      <h3>Send traffic from {own}</h3>
      <div className="fields">
        <label>To<select value={f.dst} onChange={set('dst')}>{subnets.map((s) => <option key={s}>{s}</option>)}</select></label>
        <label>Kind<select value={f.preset} onChange={set('preset')}>{Object.keys(PRESETS).map((p) => <option key={p}>{p}</option>)}</select></label>
        <label>Packets<input inputMode="numeric" value={f.count} onChange={set('count')} /></label>
        <label>Per second<input inputMode="numeric" value={f.pps} onChange={set('pps')} /></label>
        <label>Flows<input inputMode="numeric" value={f.flows} onChange={set('flows')} /></label>
      </div>
      <button type="submit" className="btn primary"><Send size={16} /> Send</button>
    </form>
  )
}

export function matchText(m) {
  if (!m) return 'any'
  const parts = []
  if (m.inPort) parts.push(`in port ${m.inPort}`)
  if (m.srcIp) parts.push(`from ${m.srcIp}`)
  if (m.dstIp) parts.push(`to ${m.dstIp}`)
  if (m.dstPort) parts.push(`port ${m.dstPort}`)
  ;['l4Protocol', 'appProtocol'].forEach((k) => m[k] && parts.push(m[k]))
  if (m.host) parts.push(m.host)
  if (m.timeWindow) parts.push(`${m.timeWindow.from || ''}–${m.timeWindow.to || ''}`)
  return parts.length ? parts.join(', ') : 'any'
}

const actionText = (a) => (a.type === 'DROP' ? 'drop' : a.ports ? `ports ${a.ports.join(', ')}` : `port ${a.port}`)

function Flows({ sw, ctx }) {
  const [table, , refresh] = usePoll(() => net(`/switch/${sw.id}/flow`), 2000, [sw.id])
  const [desired] = usePoll(() => ctrl(`/api/switches/${sw.id}/flows`).catch(() => null), 3000, [sw.id])
  const rules = table?.rules || []
  const want = new Set((desired?.desired || []).map((r) => r.ruleId))
  const inSync = desired && rules.length === want.size && rules.every((r) => want.has(r.ruleId))
  const remove = (id) => ctx.act(() => net(`/switch/${sw.id}/flow`, { method: 'POST', body: { delete: [id] } }),
    `Rule deleted on ${sw.id}; the next packet goes to the controller and the safety check restores it`).then(refresh)
  return (
    <table className="data flows">
      <caption>
        <b>Flow table</b> of {sw.id}, highest priority first.{' '}
        {desired ? (inSync ? 'Matches what the controller intends.' : <span className="bad">Differs from what the controller intends; reconciliation will fix it.</span>) : ''}
      </caption>
      <thead><tr><th className="num">prio</th><th>match</th><th>action</th><th className="num">hits</th><th /></tr></thead>
      <tbody>
        {rules.map((r) => (
          <tr key={r.ruleId} title={r.ruleId + (r.intentId ? ', from intent ' + r.intentId : '')}>
            <td className="num">{r.priority}</td>
            <td>{matchText(r.match)}{r.rateLimit && <span className="hint">, ≤ {r.rateLimit.packetsPerSecond}/s</span>}</td>
            <td className={r.action.type === 'DROP' ? 'bad' : ''}>{actionText(r.action)}</td>
            <td className="num">{table.hits[r.ruleId]}</td>
            <td><button className="icon" onClick={() => remove(r.ruleId)} aria-label={`Delete rule ${r.ruleId}`}><Trash2 size={15} /></button></td>
          </tr>
        ))}
        {!rules.length && <tr><td colSpan="5" className="empty-row">No rules. Packets that arrive here go to the controller (Packet-In).</td></tr>}
      </tbody>
    </table>
  )
}

function LinkView({ link, ctx, onSelect }) {
  const inNib = ctx.ctrlTopo?.links?.some((l) => l.linkId === link.id)
  const toggle = () => ctx.act(() => net(`/network/links/${link.id}`, { method: 'PATCH', body: { up: !link.up } }),
    `${link.id} ${link.up ? 'failed' : 'repaired'}`)
  return (
    <div className="inspect">
      <h2>{link.id} <span className="sub">{link.a} – {link.b}</span></h2>
      <dl className="facts">
        <dt>Latency</dt><dd>{link.latencyMs} ms</dd>
        <dt>In the network</dt><dd className={link.up ? '' : 'bad'}>{link.up ? 'up' : 'down'}</dd>
        <dt>In the NIB</dt><dd>{inNib ? 'yes' : 'no'}</dd>
      </dl>
      <div className="row wrap">
        <button className="btn" onClick={toggle}>{link.up ? <><ZapOff size={16} /> Fail link</> : <><Zap size={16} /> Repair link</>}</button>
        <button className="btn danger" onClick={() => confirm(`Remove link ${link.id}?`) &&
          ctx.act(() => net(`/network/links/${link.id}`, { method: 'DELETE' }), `${link.id} removed`).then(() => onSelect(null))}>
          <Trash2 size={16} /> Remove link
        </button>
      </div>
    </div>
  )
}

function AddSwitch({ ctx }) {
  const [f, setF] = useState({ id: '', name: '', ports: 4, subnet: '', auto: true })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })
  const save = () => ctx.act(() => net('/network/switches', { method: 'POST', body: {
    id: f.id, name: f.name || f.id, ports: Number(f.ports), autoRegister: f.auto,
    hosts: f.subnet ? [{ port: Number(f.ports), subnet: f.subnet }] : [],
  } }), `Switch ${f.id} added`)
  return (
    <form className="add" onSubmit={(e) => { e.preventDefault(); save() }}>
      <h3><Wrench size={16} /> Add a switch</h3>
      <div className="fields">
        <label>Id<input value={f.id} onChange={set('id')} placeholder="s9" /></label>
        <label>Name<input value={f.name} onChange={set('name')} placeholder="lab" /></label>
        <label>Ports<input inputMode="numeric" value={f.ports} onChange={set('ports')} /></label>
        <label>Host subnet (last port)<input value={f.subnet} onChange={set('subnet')} placeholder="10.0.9.0/24" /></label>
      </div>
      <label className="check"><input type="checkbox" checked={f.auto} onChange={set('auto')} /> Registers with the controller by itself</label>
      <button type="submit" className="btn" disabled={!f.id}>Add switch</button>
    </form>
  )
}

function AddLink({ ctx }) {
  const [f, setF] = useState({ id: '', a: '', b: '', latencyMs: 5 })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const save = () => ctx.act(() => net('/network/links', { method: 'POST', body: { ...f, latencyMs: Number(f.latencyMs) } }), `Link ${f.id} added`)
  return (
    <form className="add" onSubmit={(e) => { e.preventDefault(); save() }}>
      <h3><Wrench size={16} /> Add a link</h3>
      <div className="fields">
        <label>Id<input value={f.id} onChange={set('id')} placeholder="l9" /></label>
        <label>End A<input value={f.a} onChange={set('a')} placeholder="s9:1" /></label>
        <label>End B<input value={f.b} onChange={set('b')} placeholder="m1:5" /></label>
        <label>Latency (ms)<input inputMode="numeric" value={f.latencyMs} onChange={set('latencyMs')} /></label>
      </div>
      <button type="submit" className="btn" disabled={!f.id || !f.a || !f.b}>Add link</button>
    </form>
  )
}
