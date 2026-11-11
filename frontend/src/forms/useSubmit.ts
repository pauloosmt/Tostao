import { useCallback, useRef, useState } from 'react'

/**
 * O guarda de envio duplicado dos formulários e das ações: só uma ação roda por vez. O controle de "em andamento" é uma referência
 * (muda na hora), porque dois cliques seguidos chegam antes de o React pintar de novo o botão desabilitado; o estado `submitting` serve
 * para a tela mostrar o andamento. Se a ação falhar, o guarda é liberado e o erro segue para quem chamou.
 */
export function useSubmit() {
  const inFlight = useRef(false)
  const [submitting, setSubmitting] = useState(false)

  const run = useCallback(async (action: () => Promise<void>): Promise<void> => {
    if (inFlight.current) return
    inFlight.current = true
    setSubmitting(true)
    try {
      await action()
    } finally {
      inFlight.current = false
      setSubmitting(false)
    }
  }, [])

  return { submitting, run }
}
