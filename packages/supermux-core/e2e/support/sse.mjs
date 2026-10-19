/**
 * Opens an SSE stream and exposes `next(predicate, timeoutMs)` over the parsed JSON messages. `headers` go with the
 * request (a server's token, say).
 *
 * `next` resolves with the first message `predicate` accepts, among those already seen and those still to come. It rejects,
 * rather than leaving the test to wait out its timeout, when the stream ends, when a message is not JSON, and when
 * `predicate` throws.
 */
export async function openEvents(url, { headers = {} } = {}) {
  const controller = new AbortController()
  const res = await fetch(url, { signal: controller.signal, headers: { accept: "text/event-stream", ...headers } })
  if (!res.ok) {
    controller.abort()
    throw new Error(`${url} ${res.status}`)
  }
  const seen = []
  const waiters = new Set()
  let ended = null // Once the stream has stopped: why, as an Error.
  const recent = () => JSON.stringify(seen.slice(-10))
  const closed = () => new Error(`event stream closed; saw ${recent()}`)

  const publish = message => {
    seen.push(message)
    for (const waiter of [...waiters]) {
      try {
        if (waiter.predicate(message)) waiter.resolve(message)
      } catch (error) {
        waiter.reject(error)
      }
    }
  }
  const end = error => {
    ended = error
    for (const waiter of [...waiters]) waiter.reject(error)
  }

  ;(async () => {
    const decoder = new TextDecoder()
    let buffer = ""
    try {
      for await (const chunk of res.body) {
        buffer += decoder.decode(chunk, { stream: true })
        let index
        while ((index = buffer.indexOf("\n\n")) >= 0) {
          const block = buffer.slice(0, index)
          buffer = buffer.slice(index + 2)
          const data = block.split("\n").filter(line => line.startsWith("data:")).map(line => line.slice(5).trimStart()).join("\n")
          if (!data) continue
          let message
          try {
            message = JSON.parse(data)
          } catch {
            throw new Error(`event data is not JSON: ${data}`)
          }
          publish(message)
        }
      }
      end(closed())
    } catch (error) {
      // Our own close() aborts the read; anything else is a reason worth reporting.
      end(controller.signal.aborted ? closed() : error)
    }
  })()

  return {
    seen,
    next(predicate, timeoutMs = 15_000) {
      return new Promise((resolve, reject) => {
        const index = seen.findIndex(message => predicate(message))
        if (index >= 0) return resolve(seen[index])
        if (ended) return reject(ended)
        // However the wait ends, its timer goes with it: a pending one would keep the process alive for timeoutMs.
        const settle = (done, value) => {
          clearTimeout(timer)
          waiters.delete(waiter)
          done(value)
        }
        const waiter = { predicate, resolve: message => settle(resolve, message), reject: error => settle(reject, error) }
        const timer = setTimeout(() => waiter.reject(new Error(`timed out after ${timeoutMs} ms; saw ${recent()}`)), timeoutMs)
        waiters.add(waiter)
      })
    },
    close: () => controller.abort(),
  }
}
