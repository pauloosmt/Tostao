import { useEffect, useState } from 'react'
import { Link, Navigate, useLocation } from 'react-router'
import { ApiError } from '../api/errors'
import { safeRedirect } from '../auth/safeRedirect'
import { useAuth } from '../auth/useAuth'
import { Alert, Field, Logo, WalletGate } from '../design-system/src'
import { useSubmit } from '../forms/useSubmit'
import { useMediaQuery } from '../layout/useMediaQuery'
import { firstInvalid, validateLogin, type FieldMessages } from '../validation/account'
import { ErrorNotice } from '../ui/ErrorNotice'
import { PasswordField } from '../ui/PasswordField'

type LoginField = 'email' | 'password'
const FIELD_ORDER: readonly LoginField[] = ['email', 'password']
const FIELD_ID: Record<LoginField, string> = { email: 'login-email', password: 'login-password' }

const isLoginField = (name: string): name is LoginField => name === 'email' || name === 'password'
const focusField = (field: LoginField) => document.getElementById(FIELD_ID[field])?.focus()

interface Notice {
  text: string
  /** O erro é do servidor ou da rede: vale oferecer "Tentar de novo". */
  retry?: boolean
}

export function LoginPage() {
  const { state, login } = useAuth()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<FieldMessages<LoginField>>({})
  const [notice, setNotice] = useState<Notice | null>(null)
  const { submitting, run } = useSubmit()
  const [blockedSeconds, setBlockedSeconds] = useState(0)
  const [blockedText, setBlockedText] = useState('')
  const narrow = useMediaQuery('(max-width: 719px)')

  // A espera depois do bloqueio (429): conta de segundo em segundo e libera o botão no fim.
  useEffect(() => {
    if (blockedSeconds <= 0) return
    const timer = setTimeout(() => setBlockedSeconds((seconds) => seconds - 1), 1000)
    return () => clearTimeout(timer)
  }, [blockedSeconds])

  // Com sessão, esta tela não tem o que mostrar: segue para onde a pessoa queria ir (só um caminho interno seguro) ou para o início.
  if (state.status === 'authenticated') {
    return <Navigate to={safeRedirect((location.state as { from?: unknown } | null)?.from)} replace />
  }

  const blocked = blockedSeconds > 0

  function fail(error: unknown) {
    if (error instanceof ApiError && error.status === 401) {
      // A mesma mensagem para e-mail inexistente e senha errada: a tela não revela qual dos dois falhou.
      setNotice({ text: 'E-mail ou senha incorretos.' })
      setPassword('')
      focusField('password')
      return
    }
    if (error instanceof ApiError && error.status === 429) {
      const wait = error.retryAfterSeconds
      setBlockedText(wait ? `Muitas tentativas. Tente de novo em ${wait} s.` : 'Muitas tentativas. Tente de novo em instantes.')
      setBlockedSeconds(wait ?? 0)
      setNotice(wait ? null : { text: 'Muitas tentativas. Tente de novo em instantes.' })
      return
    }
    if (error instanceof ApiError && error.status === 400) {
      const byField: FieldMessages<LoginField> = {}
      const others: string[] = []
      for (const item of error.errors) {
        if (isLoginField(item.field)) byField[item.field] ??= item.message
        else others.push(item.message)
      }
      setErrors(byField)
      const first = firstInvalid(byField, FIELD_ORDER)
      if (first) focusField(first)
      setNotice(others.length > 0 || !first ? { text: others.join(' ') || 'Não foi possível entrar. Confira os dados e tente de novo.' } : null)
      return
    }
    setNotice({ text: 'Não foi possível falar com o servidor. Tente de novo.', retry: true })
  }

  async function submit() {
    if (blocked) return
    const found = validateLogin({ email, password })
    setErrors(found)
    setNotice(null)
    const first = firstInvalid(found, FIELD_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await login({ email: email.trim(), password })
      } catch (error) {
        fail(error)
      }
    })
  }

  return (
    <main className="auth-page">
      <div className="auth-page__panel">
        <div className="auth-page__brand">
          <Logo variant="lockup" size={48} />
        </div>
        <WalletGate
          title="Entrar"
          subtitle="Acesse sua conta no Tostão."
          submitLabel={submitting ? 'Entrando…' : blocked ? `Aguarde ${blockedSeconds} s` : 'Entrar'}
          disabled={submitting || blocked}
          onSubmit={() => void submit()}
          className={narrow ? 'tx-gate--compact' : undefined}
        >
          {state.status === 'anonymous' && state.expired ? <Alert tone="warning">Sua sessão terminou. Entre de novo.</Alert> : null}
          {blocked ? <Alert tone="danger">{blockedText}</Alert> : null}
          {notice?.retry ? (
            <ErrorNotice onRetry={() => void submit()}>{notice.text}</ErrorNotice>
          ) : notice ? (
            <Alert tone="danger">{notice.text}</Alert>
          ) : null}
          <Field
            id={FIELD_ID.email}
            label="E-mail"
            type="email"
            autoComplete="email"
            inputMode="email"
            autoCapitalize="off"
            spellCheck={false}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={errors.email}
          />
          <PasswordField
            id={FIELD_ID.password}
            label="Senha"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            error={errors.password}
          />
        </WalletGate>

        <p className="text-body-sm muted auth-page__footer">
          Ainda não tem conta?{' '}
          <Link className="link" to="/cadastro" state={location.state}>
            Criar conta
          </Link>
        </p>
      </div>
    </main>
  )
}
