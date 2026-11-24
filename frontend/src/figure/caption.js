/**
 * Figure 1's caption, rewritten from the live state in plain words.
 * Returns [{key, text}]: sentences are keyed by their text, so a sentence that changes is drawn fresh and briefly
 * highlighted.
 */
export function caption({ netTopo, ctrlTopo, failMode, traffic, now }) {
  if (!ctrlTopo) return [{ key: 'wait', text: 'Waiting for the controller to answer.' }]
  const parts = []
  const say = (text) => parts.push({ key: text, text })

  const path = traffic.path && now - traffic.path.time < 20000 ? traffic.path : null
  if (path) {
    const route = path.switches.join(' → ')
    if (path.viaController && path.delivered) {
      say(`Last packet: ${route}. ${path.askedAt} had no rule for it, so it asked the controller, which told ${path.askedAt} where to send it.`)
    } else if (path.delivered) {
      say(`Last packet: ${route}, delivered to ${path.to?.replace('.0/24', '.x') ?? 'its hosts'}.`)
    } else {
      say(`Last packet: ${route}, dropped at ${path.endedAt}.`)
    }
  }

  const present = new Set((netTopo?.switches || []).map((s) => s.id))
  const gone = ctrlTopo.switches.filter((s) => !present.has(s.id)).map((s) => s.id)
  if (gone.length) {
    say(`${list(gone)} left the network. The controller still lists ${gone.length === 1 ? 'it' : 'them'} until it misses 3 check-ins in a row.`)
  }

  const down = (netTopo?.links || []).filter((l) => !l.up).map((l) => l.a.split(':')[0] + '–' + l.b.split(':')[0])
  if (down.length) say(`Cut ${down.length === 1 ? 'link' : 'links'}: ${down.join(', ')}.`)

  if (failMode?.mode === 'FAIL_CLOSED') {
    say(`The controller lost contact with ${list(failMode.unknown)}. It assumes ${failMode.unknown.length === 1 ? 'that switch has' : 'they have'} failed and sends traffic around ${failMode.unknown.length === 1 ? 'it' : 'them'} (Fail Closed).`)
  } else if (failMode?.mode === 'FAIL_STATIC') {
    say(`The controller lost contact with ${list(failMode.unknown)}. Going around all of them would ${failMode.disconnects ? 'cut hosts off' : 'remove too much of the network'}, so it keeps the routes it already had (Fail Static).`)
  }

  if (!parts.length) say(`The controller knows ${ctrlTopo.switches.length} switches and ${ctrlTopo.links.length} links.`)
  return parts
}

const list = (xs) => (xs.length <= 1 ? xs.join('') : `${xs.slice(0, -1).join(', ')} and ${xs[xs.length - 1]}`)

export const pct = (x) => `${Math.round((x || 0) * 100)}%`
