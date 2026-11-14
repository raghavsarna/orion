import { useState } from 'react'
import { Plug, PlugZap, Plus, Send, Trash2, UserPlus, X } from 'lucide-react'
import { ctrl, net, usePoll } from '../api.js'

const PRESETS = {
  HTTP: { l4Protocol: 'TCP', appProtocol: 'HTTP', dstPort: 80, host: '' },
  HTTPS: { l4Protocol: 'TCP', appProtocol: 'HTTPS', dstPort: 443, host: '' },
  SSH: { l4Protocol: 'TCP', appProtocol: 'SSH', dstPort: 22, host: '' },
  DNS: { l4Protocol: 'UDP', appProtocol: 'DNS', dstPort: 53, host: '' },
  ICMP: { l4Protocol: 'ICMP', appProtocol: '', dstPort: '', host: '' },
  YouTube: { l4Protocol: 'TCP', appProtocol: 'HTTPS', dstPort: 443, host: 'youtube.com' },
}

/** Everything about one switch: status, send packets, its flow table, ports. */
export default function SwitchPanel({ sw, ctx, onClose }) {
  const [view, setView] = useState('send')
  const c = ctx.ctrlSwitches[sw.id]
  const status = c ? c.status : 'NOT REGISTERED'

  return (
    <div className="panel">
      <div className="panel-head">
        <span className={'badge ' + (c ? c.status.toLowerCase() : 'unknown')}>{status}</span>
        <h3>{sw.id} <small>{sw.name}</small></h3>
        <div className="grow" />
        <button className="icon" title={sw.controlConnected ? 'Cut control channel (keeps forwarding)' : 'Reconnect to controller'}
                onClick={() => ctx.act(() => net(`/network/switches/${sw.id}`, { method: 'PATCH', body: { connected: !sw.controlConnected } }),
                  sw.controlConnected ? `${sw.id} disconnected from controller` : `${sw.id} reconnected`)}>
          {sw.controlConnected ? <PlugZap size={17} /> : <Plug size={17} className="muted" />}
        </button>
        {!c && (
          <button className="icon" title="Add to controller manually"
                  onClick={() => ctx.act(() => ctrl('/api/switches', { method: 'POST', body: { switchId: sw.id, address: sw.address } }),
                    `Controller contacted ${sw.id}`)}>
            <UserPlus size={17} />
          </button>
        )}
        <button className="icon danger" title="Drop switch"
                onClick={() => confirm(`Drop switch ${sw.id}?`) && ctx.act(() => net(`/network/switches/${sw.id}`, { method: 'DELETE' }), `${sw.id} dropped`).then(onClose)}>
          <Trash2 size={17} />
        </button>
        <button className="icon" onClick={onClose} title="Close"><X size={17} /></button>
      </div>
      <div className="seg">
        {['send', 'flows', 'ports'].map((v) => (
          <button key={v} className={view === v ? 'on' : ''} onClick={() => setView(v)}>{v}</button>
        ))}
      </div>
      {view === 'send' && <SendForm sw={sw} ctx={ctx} />}
      {view === 'flows' && <Flows sw={sw} ctx={ctx} />}
      {view === 'ports' && <Ports sw={sw} c={c} />}
    </div>
  )
}

function SendForm({ sw, ctx }) {
  const hostPort = sw.hosts[0]?.port || 1
  const subnets = ctx.ctrlTopo?.hosts?.map((h) => h.subnet) || []
  const own = sw.hosts[0]?.subnet
  const other = subnets.find((s) => s !== own)
  const ip = (subnet, last) => (subnet ? subnet.split('/')[0].replace(/\.\d+$/, '.' + last) : '')
  const [f, setF] = useState({
    inPort: hostPort, srcIp: ip(own, 5) || '10.0.1.5', srcPort: 51000, dstIp: ip(other, 7) || '10.0.3.7',
    dstPort: 80, l4Protocol: 'TCP', appProtocol: 'HTTP', host: '', sizeBytes: 512, packetsPerSecond: 5, durationSec: 3,
  })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const num = (v) => (v === '' || v === null ? null : Number(v))

  const send = () => ctx.act(async () => {
    const body = {
      ...f, inPort: num(f.inPort), srcPort: num(f.srcPort), dstPort: num(f.dstPort), sizeBytes: num(f.sizeBytes),
      packetsPerSecond: num(f.packetsPerSecond), durationSec: num(f.durationSec),
    }
    const job = await net(`/switch/${sw.id}/send`, { method: 'POST', body })
    return job
  }, `Sending ${f.packetsPerSecond * f.durationSec} packets`)

  return (
    <div className="form">
      <div className="chips">
        {Object.keys(PRESETS).map((p) => (
          <button key={p} className="chip" onClick={() => setF({ ...f, ...PRESETS[p] })}>{p}</button>
        ))}
      </div>
      <div className="row">
        <label title="Port the packet arrives on">in port
          <select value={f.inPort} onChange={set('inPort')}>
            {sw.ports.map((p) => <option key={p.port} value={p.port}>{p.port}{p.hostSubnet ? ' · hosts' : p.linkId ? ' · ' + p.linkId : ''}</option>)}
          </select>
        </label>
        <label>size B<input value={f.sizeBytes} onChange={set('sizeBytes')} /></label>
      </div>
      <div className="row">
        <label className="w2">source IP<input value={f.srcIp} onChange={set('srcIp')} /></label>
        <label>port<input value={f.srcPort} onChange={set('srcPort')} /></label>
      </div>
      <div className="row">
        <label className="w2">destination IP<input value={f.dstIp} onChange={set('dstIp')} list="dst-ips" /></label>
        <label>port<input value={f.dstPort} onChange={set('dstPort')} /></label>
      </div>
      <datalist id="dst-ips">{subnets.map((s) => <option key={s} value={ip(s, 7)} />)}</datalist>
      <div className="row">
        <label>L4
          <select value={f.l4Protocol} onChange={set('l4Protocol')}>
            {['TCP', 'UDP', 'ICMP'].map((p) => <option key={p}>{p}</option>)}
          </select>
        </label>
        <label>app<input value={f.appProtocol} onChange={set('appProtocol')} placeholder="HTTP" /></label>
        <label>website<input value={f.host} onChange={set('host')} placeholder="—" /></label>
      </div>
      <div className="row">
        <label>packets/s<input value={f.packetsPerSecond} onChange={set('packetsPerSecond')} /></label>
        <label>seconds<input value={f.durationSec} onChange={set('durationSec')} /></label>
        <button className="btn primary" onClick={send} title="Send"><Send size={16} /></button>
      </div>
    </div>
  )
}

export function matchText(m) {
  if (!m) return '*'
  const parts = []
  if (m.inPort) parts.push(`in ${m.inPort}`)
  if (m.srcIp) parts.push(`src ${m.srcIp}`)
  if (m.dstIp) parts.push(`dst ${m.dstIp}`)
  if (m.dstPort) parts.push(`:${m.dstPort}`)
  ;['l2Protocol', 'l3Protocol', 'l4Protocol', 'appProtocol'].forEach((k) => m[k] && parts.push(m[k]))
  if (m.host) parts.push(m.host)
  if (m.minSize || m.maxSize) parts.push(`${m.minSize || 0}-${m.maxSize || '∞'}B`)
  if (m.timeWindow) parts.push(`🕒 ${m.timeWindow.from || ''}-${m.timeWindow.to || ''}${m.timeWindow.until ? ' until ' + m.timeWindow.until : ''}`)
  return parts.length ? parts.join(' ') : '*'
}

function Flows({ sw, ctx }) {
  const [table, , refresh] = usePoll(() => net(`/switch/${sw.id}/flow`), 2000, [sw.id])
  const [desired] = usePoll(() => ctrl(`/api/switches/${sw.id}/flows`).catch(() => null), 3000, [sw.id])
  const [adding, setAdding] = useState(false)
  const rules = table?.rules || []
  const desiredIds = new Set((desired?.desired || []).map((r) => r.ruleId))
  const inSync = desired && rules.length === desiredIds.size && rules.every((r) => desiredIds.has(r.ruleId))

  const remove = (id) => ctx.act(() => net(`/switch/${sw.id}/flow`, { method: 'POST', body: { delete: [id] } }), `Rule ${id} deleted`).then(refresh)

  return (
    <div className="flows">
      <div className="flows-head">
        <span className={'badge ' + (inSync ? 'alive' : 'unknown')} title="Compared with the rules the controller wants here">
          {inSync ? 'in sync with controller' : desired ? 'differs from controller' : 'not managed'}
        </span>
        <div className="grow" />
        <button className="icon" onClick={() => setAdding(!adding)} title="Add rule by hand"><Plus size={17} /></button>
      </div>
      {adding && <AddRule sw={sw} ctx={ctx} done={() => { setAdding(false); refresh() }} />}
      <table>
        <thead><tr><th>prio</th><th>match</th><th>action</th><th title="packets matched">hits</th><th /></tr></thead>
        <tbody>
          {rules.map((r) => (
            <tr key={r.ruleId} title={r.ruleId + (r.intentId ? ' · intent ' + r.intentId : '')}>
              <td className="mono">{r.priority}</td>
              <td>{matchText(r.match)}{r.rateLimit && <span className="tag">≤{r.rateLimit.packetsPerSecond}/s</span>}</td>
              <td className={r.action.type === 'DROP' ? 'drop' : ''}>{r.action.type === 'DROP' ? 'drop' : `→ ${r.action.port}`}</td>
              <td className="mono">{table.hits[r.ruleId]}</td>
              <td><button className="icon tiny" onClick={() => remove(r.ruleId)} title="Delete"><Trash2 size={14} /></button></td>
            </tr>
          ))}
          {!rules.length && <tr><td colSpan="5" className="muted">no rules: packets go to the controller</td></tr>}
        </tbody>
      </table>
    </div>
  )
}

function AddRule({ sw, ctx, done }) {
  const [f, setF] = useState({ priority: 60000, dstIp: '', srcIp: '', proto: '', action: 'DROP', port: 1 })
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const save = () => ctx.act(async () => {
    const match = { srcIp: f.srcIp || null, dstIp: f.dstIp || null, appProtocol: f.proto || null }
    const rule = {
      ruleId: 'manual-' + Date.now().toString(36), priority: Number(f.priority), match,
      action: f.action === 'DROP' ? { type: 'DROP' } : { type: 'FORWARD', port: Number(f.port) },
    }
    await net(`/switch/${sw.id}/flow`, { method: 'POST', body: { add: [rule] } })
    done()
  }, 'Rule added')
  return (
    <div className="form inset">
      <div className="row">
        <label>priority<input value={f.priority} onChange={set('priority')} /></label>
        <label>src<input value={f.srcIp} onChange={set('srcIp')} placeholder="any" /></label>
        <label>dst<input value={f.dstIp} onChange={set('dstIp')} placeholder="any" /></label>
      </div>
      <div className="row">
        <label>app<input value={f.proto} onChange={set('proto')} placeholder="any" /></label>
        <label>action
          <select value={f.action} onChange={set('action')}><option>DROP</option><option>FORWARD</option></select>
        </label>
        {f.action === 'FORWARD' && <label>port<input value={f.port} onChange={set('port')} /></label>}
        <button className="btn primary" onClick={save}>Add</button>
      </div>
    </div>
  )
}

function Ports({ sw, c }) {
  return (
    <div className="ports">
      <table>
        <tbody>
          {sw.ports.map((p) => (
            <tr key={p.port}>
              <td className="mono">{p.port}</td>
              <td>{p.hostSubnet ? `hosts ${p.hostSubnet}` : p.linkId ? `${p.linkId} → ${p.peerSwitch}:${p.peerPort}` : <span className="muted">free</span>}</td>
              <td>{p.linkId && <span className={'badge ' + (p.up ? 'alive' : 'dead')}>{p.up ? 'up' : 'down'}</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="kv">
        <span>address</span><code>{sw.address}</code>
        <span>controller</span><span>{sw.controllerReachable ? 'reachable' : 'unreachable'} · {sw.registered ? 'registered' : 'not registered'}</span>
        {c && <><span>missed probes</span><span>{c.missedProbes}</span></>}
      </div>
    </div>
  )
}
