import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { applyTheme, readStoredTheme, rememberTheme, ThemeContext, type ThemeChoice, type ThemeValue } from './theme'

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [choice, setChoiceState] = useState<ThemeChoice>(readStoredTheme)

  // Mantém o <html> igual à escolha (o `main.tsx` já aplicou a guardada; isto cobre qualquer outro ponto de entrada).
  useEffect(() => applyTheme(choice), [choice])

  const setChoice = useCallback((next: ThemeChoice) => {
    setChoiceState(next)
    rememberTheme(next)
  }, [])

  const value = useMemo<ThemeValue>(() => ({ choice, setChoice }), [choice, setChoice])

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}
