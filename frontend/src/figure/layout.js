/**
 * Places switches in tiers, the way the paper draws its Figure 1: switches with host subnets split between a
 * top and a bottom tier, transit switches (no hosts) in the middle. Saved drag positions win.
 */
export const W = 1000
export const H = 640
const TIER_Y = { top: 130, mid: 320, bottom: 510 }

export function tiers(switches) {
  // edge switches ordered by their first host subnet, so 10.0.1.x and 10.0.2.x sit on top, the rest below
  const ipKey = (s) => s.hosts[0].subnet.split(/[./]/).slice(0, 4).map((n) => n.padStart(3, '0')).join('.')
  const edge = switches.filter((s) => s.hosts?.length).sort((a, b) => ipKey(a).localeCompare(ipKey(b))).map((s) => s.id)
  const core = switches.filter((s) => !s.hosts?.length).map((s) => s.id).sort()
  const half = Math.ceil(edge.length / 2)
  return { top: edge.slice(0, half), mid: core, bottom: edge.slice(half) }
}

function spread(ids, y, out, offset = 0) {
  const n = ids.length
  const span = Math.min(W - 160, 210 * Math.max(n - 1, 1))
  ids.forEach((id, i) => {
    const x = n === 1 ? W / 2 + offset : (W - span) / 2 + (span * i) / (n - 1)
    out[id] = { x, y }
  })
}

export function layout(switches, saved) {
  const t = tiers(switches)
  const out = {}
  const lonely = t.top.length <= 1 && t.mid.length <= 1 && t.bottom.length <= 1
  spread(t.top, TIER_Y.top, out, lonely ? -120 : 0)
  spread(t.mid, TIER_Y.mid, out, lonely ? 160 : 0)
  spread(t.bottom, TIER_Y.bottom, out, lonely ? -120 : 0)
  for (const id of Object.keys(out)) {
    if (saved[id]) out[id] = saved[id]
  }
  return { pos: out, tiers: t }
}

const key = (ids) => 'orion-figure-' + ids.slice().sort().join(',')

export function loadSaved(ids) {
  try {
    return JSON.parse(localStorage.getItem(key(ids))) || {}
  } catch {
    return {}
  }
}

export function storeSaved(ids, pos) {
  try {
    localStorage.setItem(key(ids), JSON.stringify(pos))
  } catch {
    /* storage unavailable: positions last for this page only */
  }
}

export function clearSaved(ids) {
  try {
    localStorage.removeItem(key(ids))
  } catch {
    /* nothing to clear */
  }
}
