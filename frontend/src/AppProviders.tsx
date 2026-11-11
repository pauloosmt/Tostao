import type { ReactNode } from 'react'
import { AuthProvider } from './auth/AuthContext'
import { ThemeProvider } from './theme/ThemeContext'

/** O que o aplicativo inteiro compartilha: o tema e a sessão. (O workspace só existe para quem tem sessão: ver `RequireAuth`.) */
export function AppProviders({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider>
      <AuthProvider>{children}</AuthProvider>
    </ThemeProvider>
  )
}
