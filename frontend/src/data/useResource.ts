import { useCallback, useEffect, useRef, useState } from 'react'

export type Resource<T> = { status: 'loading' } | { status: 'error'; error: unknown } | { status: 'ready'; data: T }

type Outcome<T> = { status: 'error'; error: unknown } | { status: 'ready'; data: T }

/**
 * Carrega um dado da API pela chave dada (o workspace e as opções da tela, por exemplo). Quando a chave muda, a tela volta a "carregando"
 * na hora, sem o dado da chave anterior; a resposta de uma chave que ficou para trás (ou de uma tela já fechada) é descartada. `reload`
 * consulta de novo (o "Tentar de novo"). `load` deve depender só do que está na chave.
 */
export function useResource<T>(key: string, load: () => Promise<T>): Resource<T> & { reload: () => void } {
  const [attempt, setAttempt] = useState(0)
  const [settled, setSettled] = useState<{ key: string; outcome: Outcome<T> } | null>(null)
  const loadRef = useRef(load)
  const fullKey = `${key}#${attempt}`

  // (declarado antes do efeito de carga: no mesmo commit, a carga já enxerga a função mais recente)
  useEffect(() => {
    loadRef.current = load
  })

  useEffect(() => {
    const run = { active: true }
    loadRef.current().then(
      (data) => {
        if (run.active) setSettled({ key: fullKey, outcome: { status: 'ready', data } })
      },
      (error: unknown) => {
        if (run.active) setSettled({ key: fullKey, outcome: { status: 'error', error } })
      },
    )
    return () => {
      run.active = false
    }
  }, [fullKey])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])

  // Só vale o resultado feito com a chave de agora; qualquer outro é "carregando".
  const view: Resource<T> = settled !== null && settled.key === fullKey ? settled.outcome : { status: 'loading' }
  return { ...view, reload }
}
