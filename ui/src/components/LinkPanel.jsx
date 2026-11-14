import { Trash2, X, Zap, ZapOff } from 'lucide-react'
import { net } from '../api.js'

/** One link: state, latency, set down/up, delete. */
export default function LinkPanel({ link, ctx, onClose }) {
  const inCtrl = ctx.ctrlTopo?.links?.some((l) => l.linkId === link.id)
  return (
    <div className="panel">
      <div className="panel-head">
        <span className={'badge ' + (!link.up ? 'dead' : inCtrl ? 'alive' : 'unknown')}>
          {!link.up ? 'DOWN' : inCtrl ? 'UP' : 'NOT IN CONTROLLER'}
        </span>
        <h3>{link.id} <small>{link.a} ⟷ {link.b} · {link.latencyMs} ms</small></h3>
        <div className="grow" />
        <button className="icon" title={link.up ? 'Fail link' : 'Repair link'}
                onClick={() => ctx.act(() => net(`/network/links/${link.id}`, { method: 'PATCH', body: { up: !link.up } }),
                  `${link.id} ${link.up ? 'down' : 'up'}`)}>
          {link.up ? <ZapOff size={17} /> : <Zap size={17} />}
        </button>
        <button className="icon danger" title="Remove link"
                onClick={() => confirm(`Remove link ${link.id}?`) && ctx.act(() => net(`/network/links/${link.id}`, { method: 'DELETE' }), `${link.id} removed`).then(onClose)}>
          <Trash2 size={17} />
        </button>
        <button className="icon" onClick={onClose} title="Close"><X size={17} /></button>
      </div>
    </div>
  )
}
