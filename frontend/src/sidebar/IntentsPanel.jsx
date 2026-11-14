import { useState } from 'react'
import { Ban, Clock, FilterX, Gauge, Plus, ShieldX, Sparkles, Timer, Trash2, Unlink, X } from 'lucide-react'
import { ctrl, usePoll } from '../api.js'

export const TYPES = {
  BLOCK_TRAFFIC: { icon: Ban, label: 'Block', fields: ['src', 'dst', 'port', 'website'] },
  BLOCK_PROTOCOL: { icon: FilterX, label: 'Block protocol', fields: ['protocol', 'src', 'dst', 'port'] },
  ISOLATE_GROUPS: { icon: ShieldX, label: 'Isolate', fields: ['src', 'dst'] },
  RATE_LIMIT: { icon: Gauge, label: 'Rate limit', fields: ['src', 'dst', 'packetsPerSecond', 'protocol'] },
  AVOID_LINK: { icon: Unlink, label: 'Avoid link', fields: ['linkId'] },
  MAX_LATENCY: { icon: Timer, label: 'Max latency', fields: ['src', 'dst', 'maxLatencyMs'] },
}

const EMPTY = { type: 'BLOCK_PROTOCOL', src: '', dst: '', protocol: '', port: '', website: '', linkId: '',
  maxLatencyMs: '', packetsPerSecond: '', timeWindow: null, note: '' }

export function summary(s) {
  const ep = (v) => v || 'any'
  switch (s.type) {
    case 'AVOID_LINK': return `avoid ${s.linkId}`
    case 'MAX_LATENCY': return `${ep(s.src)} → ${ep(s.dst)} < ${s.maxLatencyMs} ms`
    case 'ISOLATE_GROUPS': return `${ep(s.src)} ⇸ ${ep(s.dst)}`
    case 'RATE_LIMIT': return `${ep(s.src)}${s.dst ? ' → ' + s.dst : ''} ≤ ${s.packetsPerSecond}/s${s.protocol ? ' ' + s.protocol : ''}`
    default: {
      const what = [s.protocol, s.website, s.port && ':' + s.port].filter(Boolean).join(' ')
      return `${what ? what + ' ' : ''}${ep(s.src)} → ${ep(s.dst)}`
    }
  }
}

/** Intents: list with live status, form to add one, natural-language box that fills the form. */
export default function IntentsPanel({ ctx }) {
  const [intents, , refresh] = usePoll(() => ctrl('/api/intents'), 2000)
  const [form, setForm] = useState(null)

  const remove = (id) => ctx.act(() => ctrl(`/api/intents/${id}`, { method: 'DELETE' }), 'Intent removed').then(refresh)

  return (
    <div className="intents">
      {!form && (
        <button className="btn wide" onClick={() => setForm({ ...EMPTY })}><Plus size={16} /> New intent</button>
      )}
      {form && <IntentForm ctx={ctx} initial={form} onDone={() => { setForm(null); refresh() }} />}

      <ul className="list">
        {(intents || []).slice().reverse().map((i) => {
          const T = TYPES[i.spec.type]
          return (
            <li key={i.id}>
              <T.icon size={16} className="type-icon" />
              <div className="grow">
                <div>{summary(i.spec)}{i.spec.timeWindow && <Clock size={13} className="inline-icon" />}</div>
                {i.reason && <small className={i.status === 'NOT_POSSIBLE' ? 'bad' : 'muted'}>{i.reason}</small>}
                {i.spec.note && <small className="muted note">“{i.spec.note}”</small>}
              </div>
              <span className={'badge ' + i.status.toLowerCase()}>{i.status.replace('_', ' ')}</span>
              <button className="icon tiny" onClick={() => remove(i.id)} title="Remove"><Trash2 size={14} /></button>
            </li>
          )
        })}
        {intents && !intents.length && <li className="muted center">No intents: traffic takes the shortest path</li>}
      </ul>
    </div>
  )
}

function IntentForm({ ctx, initial, onDone }) {
  const [f, setF] = useState(initial)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const llm = ctx.status?.llm
  const [groups] = usePoll(() => ctrl('/api/groups'), 10000)
  const endpoints = ['any', ...Object.keys(groups || {}).map((g) => 'group:' + g), ...(ctx.ctrlTopo?.hosts || []).map((h) => h.subnet)]
  const links = (ctx.ctrlTopo?.links || []).map((l) => l.linkId)
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const fields = TYPES[f.type].fields

  const translate = async () => {
    setBusy(true)
    try {
      const r = await ctrl('/api/intents/translate', { method: 'POST', body: { text } })
      const s = r.intent
      setF({ ...EMPTY, ...Object.fromEntries(Object.entries(s).map(([k, v]) => [k, v ?? (k === 'timeWindow' ? null : '')])) })
      ctx.toast('Translated, check and save')
    } catch (e) {
      ctx.toast(e.message, 'err')
    } finally {
      setBusy(false)
    }
  }

  const save = () => ctx.act(async () => {
    const num = (v) => (v === '' || v == null ? null : Number(v))
    const body = {
      ...f, port: num(f.port), maxLatencyMs: num(f.maxLatencyMs), packetsPerSecond: num(f.packetsPerSecond),
      src: f.src || null, dst: f.dst || null,
    }
    await ctrl('/api/intents', { method: 'POST', body })
    onDone()
  }, 'Intent saved')

  const tw = f.timeWindow
  const setTw = (k) => (e) => setF({ ...f, timeWindow: { ...tw, [k]: e.target.value || null } })

  return (
    <div className="form card">
      <div className="nl">
        <textarea rows="2" value={text} onChange={(e) => setText(e.target.value)}
                  placeholder="हिंदी / Hinglish / English… e.g. guest network se SSH block karo" />
        <button className="btn" onClick={translate} disabled={busy || !text.trim() || !llm?.configured}
                title={llm?.configured ? `Translate with ${llm.model}` : llm?.problem || 'LLM not configured'}>
          <Sparkles size={16} className={busy ? 'spin' : ''} />
        </button>
      </div>

      <div className="types">
        {Object.entries(TYPES).map(([k, t]) => (
          <button key={k} className={f.type === k ? 'on' : ''} onClick={() => setF({ ...f, type: k })} title={t.label}>
            <t.icon size={17} /><span>{t.label}</span>
          </button>
        ))}
      </div>

      <datalist id="endpoints">{endpoints.map((e) => <option key={e} value={e} />)}</datalist>
      <div className="row wrap">
        {fields.includes('src') && <label className="w2">from<input list="endpoints" value={f.src} onChange={set('src')} placeholder="any" /></label>}
        {fields.includes('dst') && <label className="w2">to<input list="endpoints" value={f.dst} onChange={set('dst')} placeholder="any" /></label>}
        {fields.includes('protocol') && (
          <label>protocol<input list="protocols" value={f.protocol} onChange={set('protocol')} placeholder="SSH" /></label>
        )}
        {fields.includes('port') && <label>port<input value={f.port} onChange={set('port')} placeholder="any" /></label>}
        {fields.includes('website') && <label className="w2">website<input value={f.website} onChange={set('website')} placeholder="youtube.com" /></label>}
        {fields.includes('linkId') && (
          <label className="w2">link
            <select value={f.linkId} onChange={set('linkId')}>
              <option value="">choose…</option>
              {links.map((l) => <option key={l}>{l}</option>)}
            </select>
          </label>
        )}
        {fields.includes('maxLatencyMs') && <label>max ms<input value={f.maxLatencyMs} onChange={set('maxLatencyMs')} /></label>}
        {fields.includes('packetsPerSecond') && <label>packets/s<input value={f.packetsPerSecond} onChange={set('packetsPerSecond')} /></label>}
      </div>
      <datalist id="protocols">{['TCP', 'UDP', 'ICMP', 'HTTP', 'HTTPS', 'SSH', 'DNS', 'FTP'].map((p) => <option key={p} value={p} />)}</datalist>

      <div className="row">
        <button className={'icon' + (tw ? ' on' : '')} title="Only during a time window"
                onClick={() => setF({ ...f, timeWindow: tw ? null : { from: '09:00', to: '18:00', until: null } })}>
          <Clock size={17} />
        </button>
        {tw && (
          <>
            <label>from<input type="time" value={tw.from || ''} onChange={setTw('from')} /></label>
            <label>to<input type="time" value={tw.to || ''} onChange={setTw('to')} /></label>
            <label className="w2">until<input type="datetime-local" value={tw.until || ''} onChange={setTw('until')} /></label>
          </>
        )}
      </div>
      {f.note && <small className="muted note">“{f.note}”</small>}
      <div className="row end">
        <button className="btn" onClick={onDone} title="Cancel"><X size={16} /></button>
        <button className="btn primary" onClick={save}>Save</button>
      </div>
    </div>
  )
}
