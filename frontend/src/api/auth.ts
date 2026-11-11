// As chamadas de autenticação da spec 001. A sessão é o cookie HttpOnly que a API define: nada aqui a lê ou a guarda.
import { request } from './client'
import type { Me } from './types'

export interface RegisterInput {
  name: string
  email: string
  password: string
}

export interface LoginInput {
  email: string
  password: string
}

// O 401 do cadastro e da entrada é "credencial inválida" (uma resposta normal), não "a sessão terminou".
export const register = (input: RegisterInput) => request<Me>('POST', '/auth/register', { body: input, notifyUnauthorized: false })
export const login = (input: LoginInput) => request<Me>('POST', '/auth/login', { body: input, notifyUnauthorized: false })

/** Quem sou eu. O 401 da consulta inicial é "ainda sem sessão", esperado: não avisa. */
export const me = () => request<Me>('GET', '/me', { notifyUnauthorized: false })

/** Sai deste aparelho (204, também sem sessão). */
export const logout = () => request<void>('POST', '/auth/logout', { notifyUnauthorized: false })

/** Sai de todos os aparelhos (204). */
export const logoutAll = () => request<void>('POST', '/auth/logout-all')
