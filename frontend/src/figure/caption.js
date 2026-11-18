/** Writes Figure 1's caption from the live state, the way a paper caption explains its figure. */
export function caption({ netTopo, ctrlTopo, failMode, traffic }) {
  const parts = []
  if (!ctrlTopo) return ['Waiting for the controller to answer.']
  const nSw = ctrlTopo.switches.length
  const nLinks = ctrlTopo.links.length
  parts.push(`${nSw} switches and ${nLinks} links in the controller's NIB (version ${ctrlTopo.version}).`)

  const senders = Object.entries(traffic.spread)
    .map(([sw, byLink]) => ({ sw, byLink, total: Object.values(byLink).reduce((a, b) => a + b, 0) }))
    .sort((a, b) => b.total - a.total)
  const firstHop = senders.find((s) => Object.keys(s.byLink).length > 1) || senders[0]
  if (firstHop) {
    const entries = Object.entries(firstHop.byLink).sort(([a], [b]) => a.localeCompare(b))
    if (entries.length > 1) {
      parts.push(`${firstHop.sw} spread its last ${firstHop.total} packets over ${entries.length} equal-cost links `
        + `(${entries.map(([l, n]) => `${l} ${n}`).join(', ')}).`)
    } else {
      parts.push(`${firstHop.sw} sent its last ${firstHop.total} packets over ${entries[0][0]}.`)
    }
  }
  if (traffic.dropped) parts.push(`${traffic.dropped} packets dropped in the last few seconds.`)

  const down = (netTopo?.links || []).filter((l) => !l.up).map((l) => l.id)
  if (down.length) parts.push(`Failed: ${down.join(', ')}.`)

  if (failMode && failMode.mode === 'FAIL_STATIC') {
    parts.push(`Fail Static: ${failMode.unknown.join(', ')} ${failMode.unknown.length === 1 ? 'is' : 'are'} in unknown state, `
      + (failMode.disconnects
        ? `and routing around would cut off ${failMode.disconnects}, so the controller keeps using them.`
        : `and routing around would cost ${pct(failMode.degradation)} of link capacity (threshold ${pct(failMode.threshold)}), so the controller keeps using them.`))
  } else if (failMode && failMode.mode === 'FAIL_CLOSED') {
    parts.push(`Fail Closed: ${failMode.unknown.join(', ')} ${failMode.unknown.length === 1 ? 'is' : 'are'} in unknown state; `
      + `routing around costs ${pct(failMode.degradation)} of link capacity (threshold ${pct(failMode.threshold)}), so traffic avoids ${failMode.unknown.length === 1 ? 'it' : 'them'}.`)
  }
  return parts
}

export const pct = (x) => `${Math.round((x || 0) * 100)}%`
