import { useCallback, useEffect, useRef, useState } from 'react'

async function request(base, path, { method = 'GET', body } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  const text = await res.text()
  let data = null
  try {
    data = text ? JSON.parse(text) : null
  } catch {
    data = text
  }
  if (!res.ok) throw new Error((data && data.error) || `${res.status} ${res.statusText}`)
  return data
}

/** Controller API (northbound + southbound). */
export const ctrl = (path, opts) => request('/ctrl', path, opts)
/** Switch network API. */
export const net = (path, opts) => request('/net', path, opts)

/** Calls fn every `ms` milliseconds. Returns [data, error, refreshNow]. */
export function usePoll(fn, ms, deps = []) {
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [tick, setTick] = useState(0)
  useEffect(() => {
    let alive = true
    let timer
    const run = async () => {
      try {
        const d = await fn()
        if (alive) {
          setData(d)
          setError(null)
        }
      } catch (e) {
        if (alive) setError(e)
      }
      if (alive) timer = setTimeout(run, ms)
    }
    run()
    return () => {
      alive = false
      clearTimeout(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick])
  const refresh = useCallback(() => setTick((t) => t + 1), [])
  return [data, error, refresh]
}

/** Streams the packet log from the switch network (keeps the latest entries). */
export function usePacketLog(paused) {
  const [entries, setEntries] = useState([])
  const seq = useRef(0)
  useEffect(() => {
    if (paused) return undefined
    let alive = true
    let timer
    const run = async () => {
      try {
        const fresh = await net(`/network/logs?after=${seq.current}&limit=500`)
        if (alive && fresh.length) {
          seq.current = fresh[fresh.length - 1].seq
          setEntries((old) => [...old, ...fresh].slice(-800))
        }
      } catch {
        /* network not up yet */
      }
      if (alive) timer = setTimeout(run, 1000)
    }
    run()
    return () => {
      alive = false
      clearTimeout(timer)
    }
  }, [paused])
  const clear = async () => {
    await net('/network/logs', { method: 'DELETE' })
    setEntries([])
  }
  return { entries, clear }
}

export const time = (ms) => new Date(ms).toLocaleTimeString([], { hour12: false })
