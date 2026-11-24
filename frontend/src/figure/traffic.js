import { portToLink } from '../api.js'

/** How long a packet's route stays drawn on the figure. */
export const TRAFFIC_WINDOW_MS = 20000

/**
 * Summarises the recent packet log:
 * - path: the route of the most recent packet, switch by switch, and how it ended;
 * - asked: switches that recently sent a packet to the controller because they had no rule for it;
 * - drops: packets dropped per switch; spread: how each switch spread packets over its links.
 */
export function summarise(entries, netTopo, now) {
  const toLink = portToLink(netTopo)
  const drops = {}
  const spread = {}
  const asked = {}
  const byPacket = new Map()
  let delivered = 0
  let dropped = 0
  for (let i = entries.length - 1; i >= 0; i--) {
    const e = entries[i]
    if (now - e.time > TRAFFIC_WINDOW_MS) break
    if (!byPacket.has(e.packetId)) byPacket.set(e.packetId, [])
    byPacket.get(e.packetId).push(e)
    if (e.event === 'FORWARDED') {
      const id = toLink[`${e.switchId}:${e.outPort}`]
      if (id) {
        spread[e.switchId] = spread[e.switchId] || {}
        spread[e.switchId][id] = (spread[e.switchId][id] || 0) + 1
      }
    } else if (e.event === 'DELIVERED') {
      delivered++
    } else if (e.event === 'TO_CONTROLLER') {
      asked[e.switchId] = true
    } else if (e.event.startsWith('DROPPED') || e.event === 'RATE_LIMITED') {
      drops[e.switchId] = (drops[e.switchId] || 0) + 1
      dropped++
    }
  }
  return { drops, spread, asked, delivered, dropped, path: latestPath(byPacket, netTopo) }
}

/** The newest packet that has finished its trip: the switches it crossed, the links between them, how it ended. */
function latestPath(byPacket, netTopo) {
  for (const hops of byPacket.values()) {   // newest packet first
    hops.sort((a, b) => a.seq - b.seq)
    const last = hops[hops.length - 1]
    const ended = last.event === 'DELIVERED' || last.event.startsWith('DROPPED') || last.event === 'RATE_LIMITED'
    if (!ended) continue
    const switches = []
    hops.forEach((h) => switches[switches.length - 1] !== h.switchId && switches.push(h.switchId))
    const host = last.event === 'DELIVERED'
      ? netTopo?.switches.find((s) => s.id === last.switchId)?.hosts.find((x) => x.port === last.outPort)
      : null
    return {
      packetId: last.packetId,
      switches,
      delivered: last.event === 'DELIVERED',
      endedAt: last.switchId,
      to: host?.subnet || null,
      viaController: hops.some((h) => h.event === 'TO_CONTROLLER'),
      askedAt: hops.find((h) => h.event === 'TO_CONTROLLER')?.switchId || null,
      time: last.time,
    }
  }
  return null
}
