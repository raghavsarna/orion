import { X } from 'lucide-react'

/** The controller's adjacency matrix (NIB): cell = link id between two switches. */
export default function Matrix({ topo, onClose }) {
  const { nodes, cells } = topo.matrix
  return (
    <div className="matrix">
      <div className="panel-head">
        <h3>Adjacency matrix <small>NIB v{topo.version}</small></h3>
        <div className="grow" />
        <button className="icon tiny" onClick={onClose}><X size={14} /></button>
      </div>
      <table>
        <thead><tr><th />{nodes.map((n) => <th key={n}>{n}</th>)}</tr></thead>
        <tbody>
          {nodes.map((r, i) => (
            <tr key={r}>
              <th>{r}</th>
              {cells[i].map((c, j) => <td key={j} className={c ? 'on' : ''}>{c || '·'}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
