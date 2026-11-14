import { useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { ctrl, usePoll } from '../api.js'

/** Named IP groups used by intents as "group:name". */
export default function GroupsPanel({ ctx }) {
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
    <div>
      <div className="form card">
        <div className="row">
          <label>name<input value={name} onChange={(e) => setName(e.target.value)} placeholder="guests" /></label>
          <label className="w3">IPs / subnets<input value={members} onChange={(e) => setMembers(e.target.value)} placeholder="10.0.1.0/24, 10.0.4.9" /></label>
          <button className="btn primary" onClick={save} disabled={!name || !members} title="Save group"><Plus size={16} /></button>
        </div>
      </div>
      <ul className="list">
        {Object.entries(groups || {}).map(([g, m]) => (
          <li key={g}>
            <code>group:{g}</code>
            <div className="grow chips">{m.map((x) => <span key={x} className="chip static">{x}</span>)}</div>
            <button className="icon tiny" onClick={() => remove(g)} title="Remove"><Trash2 size={14} /></button>
          </li>
        ))}
        {groups && !Object.keys(groups).length && <li className="muted center">No groups yet</li>}
      </ul>
    </div>
  )
}
