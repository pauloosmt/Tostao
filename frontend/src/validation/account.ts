// As regras de cadastro e entrada, espelhando as da API (spec 001) para dar retorno imediato. A API continua sendo a autoridade:
// o que passar aqui e a API recusar volta como erro de campo e é mostrado no campo.

export const NAME_MAX = 100
export const EMAIL_MAX = 254
export const PASSWORD_MIN_BYTES = 8
export const PASSWORD_MAX_BYTES = 72

export type FieldMessages<K extends string> = Partial<Record<K, string>>

/** O tamanho em bytes, em UTF-8: é o que o limite de 72 da API mede (acentos e emojis ocupam mais de um). */
export function utf8Bytes(text: string): number {
  return new TextEncoder().encode(text).length
}

export function validateName(value: string): string | undefined {
  const name = value.trim()
  if (name === '') return 'Informe seu nome.'
  if (name.length > NAME_MAX) return `Use no máximo ${NAME_MAX} caracteres.`
  return undefined
}

/** Um e-mail tem uma arroba, algo antes e algo depois, e nenhum espaço. (A API usa a mesma regra permissiva.) */
export function validateEmail(value: string): string | undefined {
  const email = value.trim()
  if (email === '') return 'Informe seu e-mail.'
  if (email.length > EMAIL_MAX) return `Use no máximo ${EMAIL_MAX} caracteres.`
  if (!/^[^\s@]+@[^\s@]+$/.test(email)) return 'Informe um e-mail válido.'
  return undefined
}

/** A regra da senha do cadastro: 8 a 72 bytes em UTF-8, com ao menos uma letra e um número. */
export function validateNewPassword(value: string): string | undefined {
  if (value === '') return 'Informe uma senha.'
  const bytes = utf8Bytes(value)
  if (bytes < PASSWORD_MIN_BYTES) return `Use ao menos ${PASSWORD_MIN_BYTES} caracteres.`
  if (bytes > PASSWORD_MAX_BYTES) return `Use no máximo ${PASSWORD_MAX_BYTES} bytes (acentos e emojis contam mais de um).`
  if (!/\p{L}/u.test(value) || !/\p{Nd}/u.test(value)) return 'Inclua ao menos uma letra e um número.'
  return undefined
}

/** Na entrada só se exige a senha: quem decide se está certa é a API. */
export function validateCurrentPassword(value: string): string | undefined {
  return value === '' ? 'Informe sua senha.' : undefined
}

export interface RegisterValues {
  name: string
  email: string
  password: string
}

export interface LoginValues {
  email: string
  password: string
}

function compact<K extends string>(messages: FieldMessages<K>): FieldMessages<K> {
  return Object.fromEntries(Object.entries(messages).filter(([, message]) => message !== undefined)) as FieldMessages<K>
}

export function validateRegister(values: RegisterValues): FieldMessages<keyof RegisterValues> {
  return compact({ name: validateName(values.name), email: validateEmail(values.email), password: validateNewPassword(values.password) })
}

export function validateLogin(values: LoginValues): FieldMessages<keyof LoginValues> {
  return compact({ email: validateEmail(values.email), password: validateCurrentPassword(values.password) })
}

/** O primeiro campo com erro, na ordem em que aparecem na tela (para levar o foco até ele). */
export function firstInvalid<K extends string>(messages: FieldMessages<K>, order: readonly K[]): K | undefined {
  return order.find((field) => messages[field] !== undefined)
}
