import { sleep } from '../api.js'
import { summary } from '../panels/Intents.jsx'

/**
 * The demo scenarios, written for someone seeing the system for the first time: one idea each, a few small steps,
 * plain words. Every result is read back from the live system, not scripted.
 * They use the demo ring (config/topology.yaml): A (10.0.1.x) and B (10.0.2.x), upper route C–D, lower route E.
 */
export const REQUIRED = ['A', 'B', 'C', 'D', 'E']
const TO_B = '10.0.2.0/24'
const TO_F = '10.0.3.0/24'

/** An intent in plain words; link ids like AE read as A–E. */
const plain = (i) => (i.type === 'AVOID_LINK'
  ? `don't use the ${/^[A-Z]{2}$/.test(i.linkId) ? i.linkId[0] + '–' + i.linkId[1] : i.linkId} link`
  : summary(i))

const route = (r) => (r.route.length ? r.route.join(' → ') : 'nowhere')
const sendAB = (lab, count = 10) => lab.sendAndSettle({ from: 'A', to: TO_B, flows: 1, count, pps: 10 })
const deliveredText = (r) => (r.delivered === r.sent ? 'All arrived.' : `${r.delivered} of ${r.sent} arrived.`)

export const SCENARIOS = [
  {
    id: 'long',
    title: 'The long way round',
    blurb: 'An intent tells the controller to avoid a link.',
    paper: '§3.1',
    reset: true,
    steps: [
      {
        say: 'Tell the controller: "Do not use the link between A and E."',
        button: 'Give the instruction',
        focus: { links: ['AE'], controller: true },
        run: async (lab) => {
          await lab.addIntent({ type: 'AVOID_LINK', linkId: 'AE' })
          return 'The controller recomputed every route without that link and updated the switches.'
        },
      },
      {
        say: 'Send packets from A to B.',
        button: 'Send packets',
        focus: { switches: ['A', 'B'] },
        run: async (lab) => {
          const r = await sendAB(lab)
          return `They took the long route: ${route(r)}. ${deliveredText(r)}`
        },
      },
    ],
  },
  {
    id: 'short',
    title: 'Change the intent',
    blurb: 'Remove the instruction and traffic takes the short route.',
    paper: '§3.1',
    steps: [
      {
        say: 'Remove the instruction about the A–E link.',
        button: 'Change the instruction',
        focus: { links: ['AE'], controller: true },
        run: async (lab) => {
          const had = (await lab.intents()).length > 0
          await lab.clearIntents()
          await sleep(600)
          return had ? 'The controller recomputed the routes. The A–E link can be used again.'
            : 'There was no instruction to remove, so the short route was already allowed.'
        },
      },
      {
        say: 'Send packets from A to B again.',
        button: 'Send packets',
        focus: { switches: ['A', 'B'] },
        run: async (lab) => {
          const r = await sendAB(lab)
          return `They took the short route: ${route(r)}. ${deliveredText(r)}`
        },
      },
    ],
  },
  {
    id: 'link',
    title: 'A link breaks',
    blurb: 'The controller notices and traffic goes around it.',
    paper: '§4.1',
    reset: true,
    steps: [
      {
        say: 'Cut the wire between E and B.',
        button: 'Cut the wire',
        focus: { links: ['EB'] },
        run: async (lab) => {
          const after = await lab.lastSampleSeq()
          await lab.setLink('EB', false)
          await lab.waitLink('EB', false)
          const s = await lab.sampleFor('LINK_REMOVED', 'EB', after)
          return `E and B told the controller at once. It removed the link from its map and updated the routes`
            + (s ? ` in ${s.totalMs} ms.` : '.')
        },
      },
      {
        say: 'Send packets from A to B.',
        button: 'Send packets',
        focus: { switches: ['A', 'B'] },
        run: async (lab) => {
          const r = await sendAB(lab)
          return `They went around the break: ${route(r)}. ${deliveredText(r)}`
        },
      },
      {
        say: 'Repair the wire and send again.',
        button: 'Repair and send',
        focus: { links: ['EB'] },
        run: async (lab) => {
          await lab.setLink('EB', true)
          await lab.waitLink('EB', true)
          await sleep(600)
          const r = await sendAB(lab)
          return `The link is back on the map, so packets use the short route again: ${route(r)}.`
        },
      },
    ],
  },
  {
    id: 'switch',
    title: 'Add and remove a switch',
    blurb: "The controller's map follows the network.",
    paper: '§4.1',
    reset: true,
    steps: [
      {
        say: 'Plug a new switch F into D. F has its own hosts, 10.0.3.x.',
        button: 'Plug in F',
        focus: { switches: ['D'], controller: true },
        run: async (lab) => {
          const t = await lab.plugInF()
          return t ? `F introduced itself to the controller, which now knows ${t.switches.length} switches.`
            : 'F did not show up on the controller\'s map within 20 seconds.'
        },
      },
      {
        say: 'Send packets from A to F\'s hosts.',
        button: 'Send packets',
        focus: { switches: ['A', 'F'] },
        run: async (lab) => {
          const r = await lab.sendAndSettle({ from: 'A', to: TO_F, flows: 1, count: 10, pps: 10 })
          return `The controller already set up a route to F: ${route(r)}. ${deliveredText(r)}`
        },
      },
      {
        say: 'Unplug switch F.',
        button: 'Unplug F',
        focus: { switches: ['F'], controller: true },
        run: async (lab, ui) => {
          await lab.unplugF()
          const t = await lab.waitSwitch('F', false, (s) => ui.tick(`F is gone, but the controller still lists it. Checking every 3 s… ${s} s`))
          return t ? 'F missed 3 check-ins and D confirmed its wire is dead, so the controller removed F from its map.'
            : 'The controller still lists F.'
        },
      },
    ],
  },
  {
    id: 'contact',
    title: 'A switch loses contact',
    blurb: 'Fail Closed and Fail Static.',
    paper: '§3.2',
    reset: true,
    steps: [
      {
        say: 'E loses contact with the controller. E itself keeps working.',
        button: 'Cut E off',
        focus: { switches: ['E'], controller: true },
        run: async (lab, ui) => {
          await lab.setThreshold(0.5)
          await lab.setControl('E', false)
          const m = await lab.waitMode('FAIL_CLOSED', (s) => ui.tick(`The controller is waiting for E to answer… ${s} s`))
          if (!m) return 'The controller did not react within 30 seconds.'
          const r = await sendAB(lab)
          return `The controller assumes E has failed and sends traffic around it: ${route(r)}. This is called Fail Closed.`
        },
      },
      {
        say: 'Now C loses contact too.',
        button: 'Cut C off',
        focus: { switches: ['C', 'E'], controller: true },
        run: async (lab, ui) => {
          await lab.setControl('C', false)
          const m = await lab.waitMode('FAIL_STATIC', (s) => ui.tick(`The controller is waiting for C to answer… ${s} s`))
          if (!m) return 'The controller did not react within 30 seconds.'
          const r = await sendAB(lab)
          return 'Going around both would cut A off from B. So the controller stops reacting and keeps the routes it '
            + `had before: ${route(r)}. ${deliveredText(r)} This is called Fail Static.`
        },
      },
      {
        say: 'Reconnect C and E.',
        button: 'Reconnect',
        focus: { switches: ['C', 'E'], controller: true },
        run: async (lab, ui) => {
          await lab.reconnectAll()
          await lab.waitMode('NORMAL', (s) => ui.tick(`Waiting for C and E to answer again… ${s} s`))
          await lab.setThreshold(0.4)
          return 'Both answer the controller again, and everything is back to normal.'
        },
      },
    ],
  },
  {
    id: 'norule',
    title: 'A packet with no rule',
    blurb: 'The switch asks the controller what to do.',
    paper: '§4.1',
    reset: true,
    steps: [
      {
        say: 'Make E forget its rule for B\'s hosts (10.0.2.x).',
        button: 'Delete the rule',
        focus: { switches: ['E'] },
        run: async (lab) => {
          const rule = await lab.deleteRuleFor('E', TO_B)
          return rule ? `E no longer knows where to send packets for 10.0.2.x.` : 'E had no such rule.'
        },
      },
      {
        say: 'Send one packet from A to B.',
        button: 'Send one packet',
        focus: { switches: ['E'], controller: true },
        run: async (lab) => {
          const r = await sendAB(lab, 1)
          return r.askedController
            ? `E had no rule, so it sent the packet to the controller. The controller told E where to send it and gave E the rule back. Route: ${route(r)}.`
            : `The packet went ${route(r)} without asking the controller (E already had its rule back).`
        },
      },
      {
        say: 'Send another packet.',
        button: 'Send one packet',
        focus: { switches: ['E'] },
        run: async (lab) => {
          const r = await sendAB(lab, 1)
          return r.askedController ? `E asked the controller again. Route: ${route(r)}.`
            : `E now has the rule, so the packet went straight through without asking: ${route(r)}.`
        },
      },
    ],
  },
  {
    id: 'words',
    title: 'Say it in plain words',
    blurb: 'An LLM turns a sentence into an instruction.',
    paper: '§3.1 (our addition)',
    reset: true,
    steps: [
      {
        say: 'Write the instruction in plain words, in Hindi, Hinglish or English.',
        input: 'A aur E ke beech wala link use mat karo',
        button: 'Ask the LLM',
        focus: { controller: true },
        run: async (lab, ui) => {
          const r = await lab.translate(ui.input)
          words.intent = r.intent
          return `Gemini turned it into: "${plain(r.intent)}". The controller checked it, but has not applied it yet.`
        },
      },
      {
        say: 'Apply it and send packets from A to B.',
        button: 'Apply and send',
        focus: { switches: ['A', 'B'] },
        run: async (lab) => {
          if (!words.intent) return 'Go back one step and ask the LLM first.'
          const i = await lab.addIntent(words.intent)
          await sleep(500)
          const r = await sendAB(lab)
          return `The instruction is ${i?.status === 'ACTIVE' ? 'in force' : (i?.status || '').toLowerCase()}. Packets went ${route(r)}.`
        },
      },
      {
        say: 'Remove it again.',
        button: 'Remove and send',
        focus: { switches: ['A', 'B'] },
        run: async (lab) => {
          await lab.clearIntents()
          words.intent = null
          await sleep(600)
          const r = await sendAB(lab)
          return `Back to the short route: ${route(r)}. The LLM only wrote the instruction; the controller did the rest.`
        },
      },
    ],
  },
]

/** What the LLM produced in step 1 of "Say it in plain words", used by step 2. */
const words = { intent: null }
