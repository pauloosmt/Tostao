import { useState } from 'react'
import { createInvitation } from '../api/invitations'
import { Alert, Button, Field, OptionTile } from '../design-system/src'
import { describeFailure, type Failure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { validateEmail } from '../validation/account'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SheetDialog } from '../ui/SheetDialog'

type Role = 'EDITOR' | 'VIEWER'
type Field = 'email' | 'role'
const EMAIL_ID = 'invite-email'
const ROLES: { value: Role; label: string; hint: string }[] = [
  { value: 'EDITOR', label: 'Editor', hint: 'Registra e edita lançamentos, contas e categorias.' },
  { value: 'VIEWER', label: 'Leitor', hint: 'Só consulta: não altera nada.' },
]

export interface InviteSheetProps {
  workspaceId: string
  onClose: () => void
  /** O convite foi criado (e a folha fechada depois de mostrar o código): quem chama atualiza a lista de pendentes. */
  onCreated: (email: string) => void
}

/**
 * A folha de convidar: e-mail e papel; depois de criar, o código do convite aparece **uma só vez**, aqui. O aplicativo não envia e-mail:
 * o dono combina a entrega do código com a pessoa. O código vive só neste estado, enquanto a folha existe: nunca vai a endereço, a
 * armazenamento ou a registro.
 */
export function InviteSheet({ workspaceId, onClose, onCreated }: InviteSheetProps) {
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<Role>('VIEWER')
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [failure, setFailure] = useState<Failure<Field> | null>(null)
  const [created, setCreated] = useState<{ email: string; code: string } | null>(null)
  const [copied, setCopied] = useState<'yes' | 'no' | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    setFailure(null)
    const problem = email.trim() === '' ? 'Informe o e-mail da pessoa.' : validateEmail(email)
    if (problem) {
      setErrors({ email: problem })
      document.getElementById(EMAIL_ID)?.focus()
      return
    }
    setErrors({})
    await run(async () => {
      try {
        const invitation = await createInvitation(workspaceId, { email: email.trim(), role })
        setCreated({ email: invitation.email, code: invitation.code })
      } catch (error) {
        const described = describeFailure<Field>(error, { fieldMap: { email: 'email', role: 'role' } })
        setErrors(described.fields)
        setFailure(described)
        if (described.fields.email) document.getElementById(EMAIL_ID)?.focus()
      }
    })
  }

  async function copy(code: string) {
    try {
      await navigator.clipboard.writeText(code)
      setCopied('yes')
    } catch {
      setCopied('no') // sem permissão da área de transferência: a pessoa copia o código à mão
    }
  }

  if (created) {
    return (
      <SheetDialog
        title="Convite criado"
        onClose={() => onCreated(created.email)}
        footer={
          <Button size="lg" full onClick={() => onCreated(created.email)}>
            Concluir
          </Button>
        }
      >
        <p className="text-body">
          Envie este código para <strong>{created.email}</strong>. Ele aparece só agora e vale para um convite com prazo.
        </p>
        <p className="invite-code" aria-label="Código do convite">
          <code>{created.code}</code>
        </p>
        <div>
          <Button variant="secondary" icon="check" onClick={() => void copy(created.code)}>
            Copiar código
          </Button>
        </div>
        {copied === 'yes' ? <Alert tone="info">Código copiado.</Alert> : null}
        {copied === 'no' ? <Alert tone="warning">Não foi possível copiar. Selecione o código acima e copie à mão.</Alert> : null}
        <p className="text-body-sm muted">O aplicativo não envia e-mail: combine a entrega do código com a pessoa. Depois de fechar esta folha, o código não aparece de novo.</p>
      </SheetDialog>
    )
  }

  return (
    <SheetDialog
      title="Convidar"
      onClose={onClose}
      onSubmit={() => void submit()}
      initialFocusId={EMAIL_ID}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Criando…' : 'Criar convite'}
        </Button>
      }
    >
      {failure?.general ? failure.retry ? <ErrorNotice onRetry={() => void submit()}>{failure.general}</ErrorNotice> : <Alert tone="danger">{failure.general}</Alert> : null}
      <Field
        id={EMAIL_ID}
        label="E-mail da pessoa"
        type="email"
        autoComplete="off"
        inputMode="email"
        autoCapitalize="off"
        spellCheck={false}
        value={email}
        onChange={(event) => setEmail(event.target.value)}
        error={errors.email}
      />
      <div className="tx-sheet__section">
        <span className="text-label">Papel</span>
        <div className="tile-row tile-row--two" role="radiogroup" aria-label="Papel">
          {ROLES.map((option) => (
            <OptionTile key={option.value} selected={role === option.value} onClick={() => setRole(option.value)}>
              {option.label}
            </OptionTile>
          ))}
        </div>
        <p className="text-body-sm muted">{ROLES.find((option) => option.value === role)?.hint}</p>
      </div>
    </SheetDialog>
  )
}
