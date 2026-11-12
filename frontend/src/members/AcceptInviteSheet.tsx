import { useState } from 'react'
import { useNavigate } from 'react-router'
import { acceptInvitation } from '../api/invitations'
import { ApiError } from '../api/errors'
import type { AcceptedInvitation } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { Alert, Button, Field } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SheetDialog } from '../ui/SheetDialog'
import { roleLabel } from '../workspace/roles'
import { useWorkspace } from '../workspace/useWorkspace'

const CODE_ID = 'accept-code'
// A API responde igual para código inexistente, vencido, já usado ou revogado (de propósito): a tela diz o mesmo.
const INVALID = 'Código de convite inválido ou vencido.'

export interface AcceptInviteSheetProps {
  onClose: () => void
}

/**
 * Entrar num workspace com o código de um convite. Aceitar muda os workspaces da pessoa: o aplicativo os relê e a folha oferece ir ao
 * Início do novo. O código vive só no campo e na chamada (nunca no endereço, no armazenamento ou em registro).
 */
export function AcceptInviteSheet({ onClose }: AcceptInviteSheetProps) {
  const { refresh } = useAuth()
  const { select } = useWorkspace()
  const navigate = useNavigate()
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | undefined>()
  const [problem, setProblem] = useState<{ text: string; retry: boolean } | null>(null)
  const [joined, setJoined] = useState<AcceptedInvitation | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    setProblem(null)
    if (code.trim() === '') {
      setError('Informe o código do convite.')
      document.getElementById(CODE_ID)?.focus()
      return
    }
    setError(undefined)
    await run(async () => {
      try {
        const accepted = await acceptInvitation(code.trim())
        setCode('') // o código não fica na tela
        await refresh() // os workspaces da pessoa mudaram
        setJoined(accepted)
      } catch (failure) {
        if (failure instanceof ApiError && failure.status === 404) {
          setError(INVALID)
          document.getElementById(CODE_ID)?.focus()
          return
        }
        const described = describeFailure(failure)
        setProblem({ text: described.general ?? 'Não foi possível aceitar o convite.', retry: described.retry })
      }
    })
  }

  if (joined) {
    return (
      <SheetDialog
        title="Convite aceito"
        onClose={onClose}
        footer={
          <Button
            size="lg"
            full
            onClick={() => {
              select(joined.workspaceId)
              void navigate('/')
            }}
          >
            Ir para o Início
          </Button>
        }
      >
        <p className="text-body">
          Você entrou em <strong>{joined.name}</strong> como {roleLabel(joined.role)}.
        </p>
      </SheetDialog>
    )
  }

  return (
    <SheetDialog
      title="Entrar com um código"
      onClose={onClose}
      onSubmit={() => void submit()}
      initialFocusId={CODE_ID}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Entrando…' : 'Aceitar convite'}
        </Button>
      }
    >
      {problem ? problem.retry ? <ErrorNotice onRetry={() => void submit()}>{problem.text}</ErrorNotice> : <Alert tone="danger">{problem.text}</Alert> : null}
      <p className="text-body-sm muted">Cole o código que o dono do workspace combinou com você.</p>
      <Field
        id={CODE_ID}
        label="Código do convite"
        autoComplete="off"
        autoCapitalize="off"
        spellCheck={false}
        value={code}
        onChange={(event) => setCode(event.target.value)}
        error={error}
      />
    </SheetDialog>
  )
}
