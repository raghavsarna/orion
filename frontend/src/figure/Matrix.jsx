/** The NIB's adjacency matrix: cell [i][j] is the link between switch i and switch j, as the controller holds it. */
export default function Matrix({ ctrlTopo }) {
  const { nodes, cells } = ctrlTopo.matrix
  const status = {}
  ctrlTopo.switches.forEach((s) => (status[s.id] = s.status))
  return (
    <div className="matrix-wrap">
      <table className="matrix">
        <thead>
          <tr>
            <th />
            {nodes.map((n) => <th key={n} scope="col">{n}</th>)}
          </tr>
        </thead>
        <tbody>
          {nodes.map((n, i) => (
            <tr key={n}>
              <th scope="row" className={status[n] === 'DEAD' ? 'bad' : ''}>{n}</th>
              {nodes.map((m, j) => (
                <td key={m} className={i === j ? 'diag' : cells[i][j] ? 'on' : ''}>{i === j ? '' : cells[i][j] || '·'}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
