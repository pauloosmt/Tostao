// O tema: seguir o sistema (padrão), ou forçar claro ou escuro. O `tokens.css` do design system já trata os três casos:
// `data-theme="light|dark"` no <html> força um; sem o atributo, vale o tema do sistema.
import { createContext, useContext } from 'react'
import { getItem, setItem, THEME_KEY } from '../storage/local'

export type ThemeChoice = 'system' | 'light' | 'dark'
export const THEME_CHOICES: readonly ThemeChoice[] = ['system', 'light', 'dark']

const isThemeChoice = (value: unknown): value is ThemeChoice => THEME_CHOICES.includes(value as ThemeChoice)

/** A escolha guardada neste aparelho; qualquer coisa que não seja uma das três (ou nada) é "sistema". */
export function readStoredTheme(): ThemeChoice {
  const stored = getItem(THEME_KEY)
  return isThemeChoice(stored) ? stored : 'system'
}

export function applyTheme(choice: ThemeChoice): void {
  const root = document.documentElement
  if (choice === 'system') root.removeAttribute('data-theme')
  else root.setAttribute('data-theme', choice)
}

/** Lê e aplica a escolha guardada. Roda em `main.tsx`, antes da primeira pintura, para não piscar o tema errado. */
export function applyStoredTheme(): ThemeChoice {
  const choice = readStoredTheme()
  applyTheme(choice)
  return choice
}

export function rememberTheme(choice: ThemeChoice): void {
  setItem(THEME_KEY, choice)
}

export interface ThemeValue {
  choice: ThemeChoice
  setChoice: (choice: ThemeChoice) => void
}

export const ThemeContext = createContext<ThemeValue | null>(null)

export function useTheme(): ThemeValue {
  const value = useContext(ThemeContext)
  if (value === null) throw new Error('useTheme precisa estar dentro de um ThemeProvider')
  return value
}
