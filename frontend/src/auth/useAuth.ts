// O contrato da sessão e o hook que as telas usam. O `AuthProvider` (AuthContext.tsx) é quem a mantém.
import { createContext, useContext } from 'react'
import type { LoginInput, RegisterInput } from '../api/auth'
import type { Me } from '../api/types'

export type AuthState =
  | { status: 'loading' }
  | { status: 'unavailable' }
  | { status: 'anonymous'; expired: boolean }
  | { status: 'authenticated'; me: Me }

export interface AuthValue {
  state: AuthState
  /** Entra. Falha com o erro da API (o formulário o mostra); a sessão só muda no sucesso. */
  login: (input: LoginInput) => Promise<void>
  register: (input: RegisterInput) => Promise<void>
  /** Sai deste aparelho. Só limpa a sessão depois do 204: se a API falhar, a pessoa continua dentro e o erro segue para a tela. */
  logout: () => Promise<void>
  logoutAll: () => Promise<void>
  /** Consulta o `/me` de novo (depois de `unavailable`). */
  retry: () => void
}

export const AuthContext = createContext<AuthValue | null>(null)

export function useAuth(): AuthValue {
  const value = useContext(AuthContext)
  if (value === null) throw new Error('useAuth precisa estar dentro de um AuthProvider')
  return value
}
