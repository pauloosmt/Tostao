import { useState } from 'react'
import { changeMemberRole } from '../api/members'
import type { Member } from '../api/types'
import { Alert, Button, OptionTile } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ErrorNotice } from '../ui/ErrorNotice'
import { SheetDialog } from '../ui/SheetDialog'

type Choice = 'EDITOR' | 'VIEWER'
const CHOICES: { value: Choice; label: string; hint: string }[] = [
  { value: 'EDITOR', label: 'Editor', hint: 'Registra e edita lançamentos, contas e categorias.' },
  { value: 'VIEWER', label: 'Leitor', hint: 'Só consulta: não altera nada.' },
]

export interface RoleSheetProps {
  workspaceId: string
  member: Member
  onClose: () => void
  /** Salvou: quem chama fecha a folha, mostra o aviso e atualiza a lista. */
  onSaved: (notice: string) => void
}

/** A folha que muda o papel de um membro (só Editor ou Leitor: o dono não se dá nem se tira). */
export function RoleSheet({ workspaceId, member, onClose, onSaved }: RoleSheetProps) {
  const [role, setRole] = useState<Choice>(member.role === 'VIEWER' ? 'VIEWER' : 'EDITOR')
  const [problem, setProblem] = useState<{ text: string; retry: boolean } | null>(null)
  const { submitting, run } = useSubmit()

  async function submit() {
    setProblem(null)
    await run(async () => {
      try {
        await changeMemberRole(workspaceId, member.userId, role)
        onSaved(`${member.name} agora é ${role === 'EDITOR' ? 'Editor' : 'Leitor'}.`)
      } catch (error) {
        const failure = describeFailure(error)
        setProblem({ text: failure.general ?? 'Não foi possível mudar o papel.', retry: failure.retry })
      }
    })
  }

  return (
    <SheetDialog
      title="Mudar papel"
      onClose={onClose}
      onSubmit={() => void submit()}
      footer={
        <Button type="submit" size="lg" full disabled={submitting}>
          {submitting ? 'Salvando…' : 'Salvar'}
        </Button>
      }
    >
      {problem ? problem.retry ? <ErrorNotice onRetry={() => void submit()}>{problem.text}</ErrorNotice> : <Alert tone="danger">{problem.text}</Alert> : null}
      <p className="text-body">{member.name}</p>
      <div className="tile-row tile-row--two" role="radiogroup" aria-label="Papel">
        {CHOICES.map((choice) => (
          <OptionTile key={choice.value} selected={role === choice.value} onClick={() => setRole(choice.value)}>
            {choice.label}
          </OptionTile>
        ))}
      </div>
      <p className="text-body-sm muted">{CHOICES.find((choice) => choice.value === role)?.hint}</p>
    </SheetDialog>
  )
}
