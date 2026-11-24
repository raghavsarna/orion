import { useState } from 'react'
import { Clock, Languages, Plus, Trash2, X } from 'lucide-react'
import { ctrl, usePoll } from '../api.js'

export const TYPES = {
  BLOCK_TRAFFIC: { label: 'Block', fields: ['src', 'dst', 'port', 'website'] },
  BLOCK_PROTOCOL: { label: 'Block protocol', fields: ['protocol', 'src', 'dst', 'port'] },
  ISOLATE_GROUPS: { label: 'Isolate', fields: ['src', 'dst'] },
  RATE_LIMIT: { label: 'Rate limit', fields: ['src', 'dst', 'packetsPerSecond', 'protocol'] },
  AVOID_LINK: { label: 'Avoid link', fields: ['linkId'] },
  MAX_LATENCY: { label: 'Max latency', fields: ['src', 'dst', 'maxLatencyMs'] },
}

const EMPTY = { type: 'MAX_LATENCY', src: '', dst: '', protocol: '', port: '', website: '', linkId: '',
  maxLatencyMs: '', packetsPerSecond: '', timeWindow: null, note: '' }

export function summary(s) {
  const ep = (v) => v || 'any'
  switch (s.type) {
    case 'AVOID_LINK': return `avoid ${s.linkId}`
    case 'MAX_LATENCY': return `${ep(s.src)} → ${ep(s.dst)} within ${s.maxLatencyMs} ms`
    case 'ISOLATE_GROUPS': return `${ep(s.src)} never reaches ${ep(s.dst)}`
    case 'RATE_LIMIT': return `${ep(s.src)}${s.dst ? ' → ' + s.dst : ''} at most ${s.packetsPerSecond} packets/s${s.protocol ? ' ' + s.protocol : ''}`
    default: {
      const what = [s.protocol, s.website, s.port && 'port ' + s.port].filter(Boolean).join(' ')
      return `block ${what ? what + ' ' : ''}${ep(s.src)} → ${ep(s.dst)}`
    }
  }
}

/** Table 2 (intents with live status), the form that adds one, IP groups. */
export default function Intents({ ctx }) {
  const [intents, , refresh] = usePoll(() => ctrl('/api/intents'), 2000)
  const [form, setForm] = useState(false)
  const remove = (id) => ctx.act(() => ctrl(`/api/intents/${id}`, { method: 'DELETE' }), 'Intent removed').then(refresh)

  return (
    <div className="intents">
      <p className="lede">An intent states the end state; the controller derives every flow rule from intents plus the NIB, and recomputes them whenever either changes.</p>
      {form ? (
        <IntentForm ctx={ctx} onDone={() => { setForm(false); refresh() }} />
      ) : (
        <button className="btn primary" onClick={() => setForm(true)}><Plus size={16} /> New intent</button>
      )}
      <table className="data">
        <caption><b>Table 2:</b> Intents and what the controller made of them.</caption>
        <thead><tr><th>intent</th><th>status</th><th /></tr></thead>
        <tbody>
          {(intents || []).slice().reverse().map((i) => (
            <tr key={i.id}>
              <td>
                <div className="intent-type">{TYPES[i.spec.type]?.label || i.spec.type}</div>
                <div>{summary(i.spec)}{i.spec.timeWindow && <span className="tw"><Clock size={14} /> {i.spec.timeWindow.from}–{i.spec.timeWindow.to}</span>}</div>
                {i.reason && <div className={'reason' + (i.status === 'NOT_POSSIBLE' ? ' bad' : '')}>{i.reason}</div>}
              </td>
              <td><span className={'status ' + i.status.toLowerCase()}>{i.status.replace('_', ' ').toLowerCase()}</span></td>
              <td><button className="icon" onClick={() => remove(i.id)} aria-label="Remove intent"><Trash2 size={16} /></button></td>
            </tr>
          ))}
          {intents && !intents.length && (
            <tr><td colSpan="3" className="empty-row">No intents. Every switch forwards on the multipath shortest-path baseline.</td></tr>
          )}
        </tbody>
      </table>
      <Groups ctx={ctx} />
    </div>
  )
}

function IntentForm({ ctx, onDone }) {
  const [f, setF] = useState(EMPTY)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const llm = ctx.status?.llm
  const [groups] = usePoll(() => ctrl('/api/groups'), 10000)
  const endpoints = [...Object.keys(groups || {}).map((g) => 'group:' + g), ...(ctx.ctrlTopo?.hosts || []).map((h) => h.subnet)]
  const links = (ctx.ctrlTopo?.links || []).map((l) => l.linkId).sort()
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value })
  const fields = TYPES[f.type].fields

  const translate = async () => {
    setBusy(true)
    try {
      const r = await ctrl('/api/intents/translate', { method: 'POST', body: { text } })
      setF({ ...EMPTY, ...Object.fromEntries(Object.entries(r.intent).map(([k, v]) => [k, v ?? (k === 'timeWindow' ? null : '')])) })
      ctx.toast('Translated. Check the fields, then save.')
    } catch (e) {
      ctx.toast(e.message, 'err')
    } finally {
      setBusy(false)
    }
  }

  const save = () => ctx.act(async () => {
    const num = (v) => (v === '' || v == null ? null : Number(v))
    await ctrl('/api/intents', {
      method: 'POST',
      body: { ...f, port: num(f.port), maxLatencyMs: num(f.maxLatencyMs), packetsPerSecond: num(f.packetsPerSecond),
        src: f.src || null, dst: f.dst || null },
    })
    onDone()
  }, 'Intent saved')

  const tw = f.timeWindow
  const setTw = (k) => (e) => setF({ ...f, timeWindow: { ...tw, [k]: e.target.value || null } })

  return (
    <form className="intent-form" onSubmit={(e) => { e.preventDefault(); save() }}>
      <fieldset className="nl">
        <legend>In words (Hindi, Hinglish or English)</legend>
        <div className="row">
          <input value={text} onChange={(e) => setText(e.target.value)} placeholder="guest network se SSH block karo"
                 disabled={!llm?.configured} />
          <button type="button" className="btn" onClick={translate} disabled={busy || !text.trim() || !llm?.configured}>
            <Languages size={16} className={busy ? 'spin' : ''} /> Translate
          </button>
        </div>
        {!llm?.configured && <small className="hint">Put your Gemini API key in the <code>.env</code> file, then restart with <code>docker compose up</code>.</small>}
      </fieldset>

      <div className="seg" role="radiogroup" aria-label="Intent type">
        {Object.entries(TYPES).map(([k, t]) => (
          <button type="button" key={k} role="radio" aria-checked={f.type === k} className={f.type === k ? 'on' : ''}
                  onClick={() => setF({ ...f, type: k })}>{t.label}</button>
        ))}
      </div>

      <datalist id="endpoints">{endpoints.map((e) => <option key={e} value={e} />)}</datalist>
      <datalist id="protocols">{['TCP', 'UDP', 'ICMP', 'HTTP', 'HTTPS', 'SSH', 'DNS'].map((p) => <option key={p} value={p} />)}</datalist>
      <div className="fields">
        {fields.includes('src') && <label>From<input list="endpoints" value={f.src} onChange={set('src')} placeholder="any" /></label>}
        {fields.includes('dst') && <label>To<input list="endpoints" value={f.dst} onChange={set('dst')} placeholder="any" /></label>}
        {fields.includes('protocol') && <label>Protocol<input list="protocols" value={f.protocol} onChange={set('protocol')} placeholder="SSH" /></label>}
        {fields.includes('port') && <label>Port<input inputMode="numeric" value={f.port} onChange={set('port')} placeholder="any" /></label>}
        {fields.includes('website') && <label>Website<input value={f.website} onChange={set('website')} placeholder="youtube.com" /></label>}
        {fields.includes('linkId') && (
          <label>Link
            <select value={f.linkId} onChange={set('linkId')}>
              <option value="">Choose a link</option>
              {links.map((l) => <option key={l}>{l}</option>)}
            </select>
          </label>
        )}
        {fields.includes('maxLatencyMs') && <label>Within (ms)<input inputMode="numeric" value={f.maxLatencyMs} onChange={set('maxLatencyMs')} /></label>}
        {fields.includes('packetsPerSecond') && <label>Packets/s<input inputMode="numeric" value={f.packetsPerSecond} onChange={set('packetsPerSecond')} /></label>}
      </div>

      <label className="check">
        <input type="checkbox" checked={!!tw} onChange={() => setF({ ...f, timeWindow: tw ? null : { from: '09:00', to: '18:00', until: null } })} />
        Only during a time window
      </label>
      {tw && (
        <div className="fields">
          <label>From<input type="time" value={tw.from || ''} onChange={setTw('from')} /></label>
          <label>To<input type="time" value={tw.to || ''} onChange={setTw('to')} /></label>
          <label>Until<input type="datetime-local" value={tw.until || ''} onChange={setTw('until')} /></label>
        </div>
      )}
      <div className="row end">
        <button type="button" className="btn quiet" onClick={onDone}><X size={16} /> Cancel</button>
        <button type="submit" className="btn primary">Save intent</button>
      </div>
    </form>
  )
}

function Groups({ ctx }) {
  const [groups, , refresh] = usePoll(() => ctrl('/api/groups'), 5000)
  const [name, setName] = useState('')
  const [members, setMembers] = useState('')
  const save = () => ctx.act(async () => {
    await ctrl('/api/groups', { method: 'POST', body: { name, members: members.split(/[,\s]+/).filter(Boolean) } })
    setName('')
    setMembers('')
    refresh()
  }, `Group ${name} saved`)
  const remove = (g) => ctx.act(() => ctrl(`/api/groups/${g}`, { method: 'DELETE' }), `Group ${g} removed`).then(refresh)

  return (
    <section className="groups">
      <h3>IP groups</h3>
      <p className="hint">Use a group in an intent as <code>group:name</code>.</p>
      <ul>
        {Object.entries(groups || {}).map(([g, m]) => (
          <li key={g}>
            <code>group:{g}</code> <span>{m.join(', ')}</span>
            <button className="icon" onClick={() => remove(g)} aria-label={`Remove group ${g}`}><Trash2 size={16} /></button>
          </li>
        ))}
      </ul>
      <form className="row" onSubmit={(e) => { e.preventDefault(); save() }}>
        <input aria-label="Group name" value={name} onChange={(e) => setName(e.target.value)} placeholder="guests" />
        <input aria-label="Members" value={members} onChange={(e) => setMembers(e.target.value)} placeholder="10.0.1.0/24, 10.0.2.9" />
        <button type="submit" className="btn" disabled={!name || !members}>Add</button>
      </form>
    </section>
  )
}
