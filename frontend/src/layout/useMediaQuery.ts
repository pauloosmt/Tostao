import { useSyncExternalStore } from 'react'

/** Se a consulta de mídia vale agora, e muda sozinho quando a janela muda de tamanho (girar o celular, redimensionar). */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (notify) => {
      const list = window.matchMedia(query)
      list.addEventListener('change', notify)
      return () => list.removeEventListener('change', notify)
    },
    () => window.matchMedia(query).matches,
    () => false,
  )
}
