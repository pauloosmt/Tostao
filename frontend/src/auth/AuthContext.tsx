// A sessão do aplicativo. A prova de quem a pessoa é fica no cookie HttpOnly da API: aqui só existe o resultado de "quem sou eu"
// (`/me`) em memória. Nenhum token e nenhuma senha passam por este estado: a senha vai direto da tela para a chamada e some.
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import * as authApi from '../api/auth'
import { onUnauthorized } from '../api/client'
import { ApiError } from '../api/errors'
import type { Me } from '../api/types'
import { AuthContext, type AuthState, type AuthValue } from './useAuth'

const signedOut: AuthState = { status: 'anonymous', expired: false }

export function AuthProvider({ children }: { children: ReactNode }) {
  // Começa em "carregando": a primeira consulta ao /me é disparada logo abaixo, pelo efeito.
  const [state, setState] = useState<AuthState>({ status: 'loading' })
  // Cada consulta ao /me tem um número; só a mais recente pode mudar o estado (a que ficou para trás é descartada).
  const lookups = useRef({ latest: 0 })

  const fetchMe = useCallback(() => {
    const guard = lookups.current
    const mine = ++guard.latest
    authApi.me().then(
      (me) => {
        if (guard.latest === mine) setState({ status: 'authenticated', me })
      },
      (error: unknown) => {
        if (guard.latest !== mine) return
        // 401 na abertura é "ainda sem sessão" (esperado); o resto é o servidor fora do ar.
        setState(error instanceof ApiError && error.status === 401 ? signedOut : { status: 'unavailable' })
      },
    )
  }, [])

  useEffect(() => {
    const guard = lookups.current
    fetchMe()
    return () => {
      guard.latest++ // desmontado (ou o efeito reiniciado): a consulta em andamento não escreve mais
    }
  }, [fetchMe])

  // Um 401 de uma chamada autenticada depois da abertura é "a sessão terminou" (expirou, ou saiu em outro aparelho).
  useEffect(
    () =>
      onUnauthorized(() => {
        setState((current) => (current.status === 'authenticated' ? { status: 'anonymous', expired: true } : current))
      }),
    [],
  )

  const retry = useCallback(() => {
    setState({ status: 'loading' })
    fetchMe()
  }, [fetchMe])

  const enter = useCallback(async (call: () => Promise<Me>) => {
    const me = await call()
    lookups.current.latest++ // uma consulta inicial atrasada não pode desfazer a entrada
    setState({ status: 'authenticated', me })
  }, [])

  const login = useCallback((input: authApi.LoginInput) => enter(() => authApi.login(input)), [enter])
  const register = useCallback((input: authApi.RegisterInput) => enter(() => authApi.register(input)), [enter])

  const logout = useCallback(async () => {
    try {
      await authApi.logout()
    } catch (error) {
      // 401 ao sair: a sessão já não existia, então estar fora é o que se queria.
      if (!(error instanceof ApiError && error.status === 401)) throw error
    }
    setState(signedOut)
  }, [])

  const logoutAll = useCallback(async () => {
    try {
      await authApi.logoutAll()
    } catch (error) {
      // 401: o ouvinte já levou a pessoa à entrada com o aviso; não há mais nada a desfazer.
      if (error instanceof ApiError && error.status === 401) return
      throw error
    }
    setState(signedOut)
  }, [])

  const value = useMemo<AuthValue>(
    () => ({ state, login, register, logout, logoutAll, retry }),
    [state, login, register, logout, logoutAll, retry],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
