import { useState } from 'react'
import { listInvitations, revokeInvitation } from '../api/invitations'
import type { Invitation } from '../api/types'
import { useResource } from '../data/useResource'
import { formatDate } from '../dates/dates'
import { Alert, Badge, Button } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { roleLabel } from '../workspace/roles'

export interface InvitationsSectionProps {
  workspaceId: string
  /** Muda quando um convite novo é criado: a lista se atualiza. */
  version: number
}

/** Os convites pendentes (só o dono os vê) com "Revogar". Nunca traz o código: ele só aparece uma vez, na criação. */
export function InvitationsSection({ workspaceId, version }: InvitationsSectionProps) {
  const { submitting, run } = useSubmit()
  const [revoking, setRevoking] = useState<Invitation | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  // A chave leva o workspace e a versão: criar um convite volta a consultar, e a resposta atrasada é descartada.
  const resource = useResource(`invitations:${workspaceId}:${version}`, () => listInvitations(workspaceId))
  const { reload } = resource

  async function revoke(invitation: Invitation) {
    await run(async () => {
      setNotice(null)
      setProblem(null)
      try {
        await revokeInvitation(workspaceId, invitation.id)
        setNotice(`O convite para ${invitation.email} foi revogado.`)
        reload()
      } catch (error) {
        const failure = describeFailure(error)
        setProblem(failure.general ?? null)
        if (failure.kind === 'notFound') reload()
      } finally {
        setRevoking(null)
      }
    })
  }

  return (
    <section className="stack" aria-labelledby="invitations-title">
      <h2 id="invitations-title" className="heading-2">Convites pendentes</h2>
      {notice ? <Alert tone="info">{notice}</Alert> : null}
      {problem ? <Alert tone="danger">{problem}</Alert> : null}
      {resource.status === 'loading' ? <LoadingNote>Carregando convites…</LoadingNote> : null}
      {resource.status === 'error' ? <ErrorNotice onRetry={resource.reload}>Não foi possível carregar os convites. Tente de novo.</ErrorNotice> : null}
      {resource.status === 'ready' ? (
        resource.data.length === 0 ? (
          <p className="text-body muted">Nenhum convite pendente.</p>
        ) : (
          <ul className="member-list" aria-label="Convites pendentes">
            {resource.data.map((invitation) => (
              <li key={invitation.id} className="member-row">
                <div className="member-row__main">
                  <p className="title">{invitation.email}</p>
                  <p className="text-body-sm muted">
                    <Badge tone="neutral">{roleLabel(invitation.role)}</Badge> Expira em {formatDate(invitation.expiresAt.slice(0, 10))}
                  </p>
                </div>
                <div className="item-actions">
                  <Button size="sm" variant="danger-quiet" disabled={submitting} aria-label={`Revogar o convite de ${invitation.email}`} onClick={() => setRevoking(invitation)}>
                    Revogar
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )
      ) : null}

      <ConfirmDialog
        open={revoking !== null}
        title="Revogar este convite?"
        confirmLabel="Revogar"
        busy={submitting}
        onConfirm={() => {
          if (revoking) void revoke(revoking)
        }}
        onCancel={() => setRevoking(null)}
      >
        O código do convite para {revoking?.email} deixa de valer.
      </ConfirmDialog>
    </section>
  )
}
