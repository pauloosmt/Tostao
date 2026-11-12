import { useState } from 'react'
import { Link, Navigate, useLocation } from 'react-router'
import { ApiError } from '../api/errors'
import { safeRedirect } from '../auth/safeRedirect'
import { useAuth } from '../auth/useAuth'
import { Alert, Field, Logo, WalletGate } from '../design-system/src'
import { useSubmit } from '../forms/useSubmit'
import { useMediaQuery } from '../layout/useMediaQuery'
import { firstInvalid, validateRegister, type FieldMessages } from '../validation/account'
import { ErrorNotice } from '../ui/ErrorNotice'
import { PasswordField } from '../ui/PasswordField'

type RegisterField = 'name' | 'email' | 'password'
const FIELD_ORDER: readonly RegisterField[] = ['name', 'email', 'password']
const FIELD_ID: Record<RegisterField, string> = { name: 'register-name', email: 'register-email', password: 'register-password' }

const isRegisterField = (name: string): name is RegisterField => name === 'name' || name === 'email' || name === 'password'
const focusField = (field: RegisterField) => document.getElementById(FIELD_ID[field])?.focus()

interface Notice {
  text: string
  /** O erro é do servidor ou da rede: vale oferecer "Tentar de novo". */
  retry?: boolean
}

export function RegisterPage() {
  const { state, register } = useAuth()
  const location = useLocation()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<FieldMessages<RegisterField>>({})
  const [notice, setNotice] = useState<Notice | null>(null)
  const { submitting, run } = useSubmit()
  const narrow = useMediaQuery('(max-width: 719px)')

  // Com sessão (inclusive a recém-criada pelo cadastro), vai para onde a pessoa queria ir ou para o início.
  if (state.status === 'authenticated') {
    return <Navigate to={safeRedirect((location.state as { from?: unknown } | null)?.from)} replace />
  }

  function fail(error: unknown) {
    if (error instanceof ApiError && error.status === 409) {
      // O e-mail já existe: o erro é do campo, os outros valores ficam e a senha, que já não vale, é limpa.
      setErrors({ email: 'Este e-mail já está cadastrado.' })
      setPassword('')
      focusField('email')
      return
    }
    if (error instanceof ApiError && error.status === 400) {
      const byField: FieldMessages<RegisterField> = {}
      const others: string[] = []
      for (const item of error.errors) {
        if (isRegisterField(item.field)) byField[item.field] ??= item.message
        else others.push(item.message)
      }
      setErrors(byField)
      const first = firstInvalid(byField, FIELD_ORDER)
      if (first) focusField(first)
      setNotice(others.length > 0 || !first ? { text: others.join(' ') || 'Não foi possível criar a conta. Confira os dados e tente de novo.' } : null)
      return
    }
    setNotice({ text: 'Não foi possível falar com o servidor. Tente de novo.', retry: true })
  }

  async function submit() {
    const found = validateRegister({ name, email, password })
    setErrors(found)
    setNotice(null)
    const first = firstInvalid(found, FIELD_ORDER)
    if (first) {
      focusField(first)
      return
    }
    await run(async () => {
      try {
        await register({ name: name.trim(), email: email.trim(), password })
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
          title="Criar conta"
          subtitle="Comece a controlar suas finanças."
          submitLabel={submitting ? 'Criando conta…' : 'Criar conta'}
          disabled={submitting}
          onSubmit={() => void submit()}
          className={narrow ? 'tx-gate--compact' : undefined}
        >
          {notice?.retry ? (
            <ErrorNotice onRetry={() => void submit()}>{notice.text}</ErrorNotice>
          ) : notice ? (
            <Alert tone="danger">{notice.text}</Alert>
          ) : null}
          <Field
            id={FIELD_ID.name}
            label="Nome"
            autoComplete="name"
            value={name}
            onChange={(event) => setName(event.target.value)}
            error={errors.name}
          />
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
            autoComplete="new-password"
            hint="Use de 8 a 72 caracteres, com letra e número."
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            error={errors.password}
          />
        </WalletGate>

        <p className="text-body-sm muted auth-page__footer">
          Já tem conta?{' '}
          <Link className="link" to="/entrar" state={location.state}>
            Entrar
          </Link>
        </p>
      </div>
    </main>
  )
}
