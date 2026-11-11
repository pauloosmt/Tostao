// matchMedia controlável para os testes: o jsdom não calcula layout. `setViewportWidth(px)` decide o que
// `(min-width: Npx)` e `(max-width: Npx)` respondem e avisa quem está ouvindo, como o navegador faria.
type Listener = (event: { matches: boolean; media: string }) => void

let width = 1024
const queries = new Set<{ media: string; listeners: Set<Listener>; matches: boolean }>()

function evaluate(media: string): boolean {
  const min = /\(min-width:\s*(\d+)px\)/.exec(media)
  const max = /\(max-width:\s*(\d+)px\)/.exec(media)
  if (min && width < Number(min[1])) return false
  if (max && width > Number(max[1])) return false
  return Boolean(min || max)
}

export function setViewportWidth(px: number): void {
  width = px
  for (const query of queries) {
    const matches = evaluate(query.media)
    if (matches !== query.matches) {
      query.matches = matches
      query.listeners.forEach((listener) => listener({ matches, media: query.media }))
    }
  }
}

export function installMatchMedia(): void {
  queries.clear()
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (media: string) => {
      const query = { media, listeners: new Set<Listener>(), matches: evaluate(media) }
      queries.add(query)
      return {
        get matches() {
          return query.matches
        },
        media,
        onchange: null,
        addEventListener: (_type: string, listener: Listener) => query.listeners.add(listener),
        removeEventListener: (_type: string, listener: Listener) => query.listeners.delete(listener),
        addListener: (listener: Listener) => query.listeners.add(listener),
        removeListener: (listener: Listener) => query.listeners.delete(listener),
        dispatchEvent: () => false,
      }
    },
  })
}

export function resetViewport(): void {
  width = 1024
}
