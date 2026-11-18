import { portToLink } from '../api.js'

/** How long traffic stays drawn on the figure after its last packet. */
export const TRAFFIC_WINDOW_MS = 6000

/**
 * Summarises the recent packet log: packets per link (counted once per hop), per host port,
 * drops per switch, and how each switch spread its packets over its links (ECMP).
 */
export function summarise(entries, netTopo, now) {
  const toLink = portToLink(netTopo)
  const links = {}
  const hosts = {}
  const drops = {}
  const spread = {}
  let delivered = 0
  let dropped = 0
  for (let i = entries.length - 1; i >= 0; i--) {
    const e = entries[i]
    if (now - e.time > TRAFFIC_WINDOW_MS) break
    if (e.event === 'FORWARDED') {
      const id = toLink[`${e.switchId}:${e.outPort}`]
      if (id) {
        links[id] = (links[id] || 0) + 1
        spread[e.switchId] = spread[e.switchId] || {}
        spread[e.switchId][id] = (spread[e.switchId][id] || 0) + 1
      }
    } else if (e.event === 'DELIVERED') {
      hosts[`${e.switchId}:${e.outPort}`] = (hosts[`${e.switchId}:${e.outPort}`] || 0) + 1
      delivered++
    } else if (e.event.startsWith('DROPPED') || e.event === 'RATE_LIMITED') {
      drops[e.switchId] = (drops[e.switchId] || 0) + 1
      dropped++
    }
  }
  return { links, hosts, drops, spread, delivered, dropped }
}
