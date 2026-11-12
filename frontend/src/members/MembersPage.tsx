import { useState } from 'react'
import { useNavigate } from 'react-router'
import { listMembers, removeMember } from '../api/members'
import type { Member } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { useResource } from '../data/useResource'
import { formatDate } from '../dates/dates'
import { Alert, Avatar, Badge, Button, Card, PageHeader } from '../design-system/src'
import { describeFailure } from '../forms/apiErrors'
import { useSubmit } from '../forms/useSubmit'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { ErrorNotice } from '../ui/ErrorNotice'
import { LoadingNote } from '../ui/LoadingNote'
import { roleLabel } from '../workspace/roles'
import { useWorkspace } from '../workspace/useWorkspace'
import { InvitationsSection } from './InvitationsSection'
import { InviteSheet } from './InviteSheet'
import { RoleSheet } from './RoleSheet'

export function MembersPage() {
  const { current } = useWorkspace()
  return current ? (
    <MembersOf workspaceId={current.id} workspaceName={current.name} isOwner={current.role === 'OWNER'} />
  ) : (
    <>
      <PageHeader title="Membros" />
      <Card><p className="text-body">Você não participa de nenhum workspace.</p></Card>
    </>
  )
}

type Target = { kind: 'role'; member: Member } | { kind: 'remove'; member: Member } | { kind: 'leave'; member: Member } | null

function MembersOf({ workspaceId, workspaceName, isOwner }: { workspaceId: string; workspaceName: string; isOwner: boolean }) {
  const { state, refresh } = useAuth()
  const navigate = useNavigate()
  const { submitting, run } = useSubmit()
  const [target, setTarget] = useState<Target>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [inviting, setInviting] = useState(false)
  // Muda a cada convite criado: a lista de pendentes consulta de novo.
  const [invitationsVersion, setInvitationsVersion] = useState(0)
  // A chave leva o workspace: trocar volta a "carregando" e descarta a resposta que ficou para trás.
  const resource = useResource(`members:${workspaceId}`, () => listMembers(workspaceId))
  const myId = state.status === 'authenticated' ? state.me.id : ''
  const { reload } = resource

  async function act(member: Member, leaving: boolean) {
    await run(async () => {
      setNotice(null)
      setProblem(null)
      try {
        await removeMember(workspaceId, member.userId)
        if (leaving) {
          // Saiu: os workspaces da pessoa mudaram. O aplicativo os relê e a leva ao Início do que sobrar.
          await refresh()
          void navigate('/')
          return
        }
        setNotice(`O acesso de ${member.name} foi removido.`)
        reload()
      } catch (error) {
        const failure = describeFailure(error)
        setProblem(failure.general ?? null)
        if (failure.kind === 'notFound') reload()
      } finally {
        setTarget(null)
      }
    })
  }

  const members = resource.status === 'ready' ? resource.data : []

  return (
    <>
      <PageHeader
        title="Membros"
        context={resource.status === 'ready' ? `${members.length} ${members.length === 1 ? 'pessoa' : 'pessoas'} em ${workspaceName}` : undefined}
        action={isOwner ? <Button onClick={() => setInviting(true)}>Convidar</Button> : undefined}
      />
      {notice ? <Alert tone="info">{notice}</Alert> : null}
      {problem ? <Alert tone="danger">{problem}</Alert> : null}

      {resource.status === 'loading' ? <LoadingNote>Carregando membros…</LoadingNote> : null}
      {resource.status === 'error' ? <ErrorNotice onRetry={resource.reload}>Não foi possível carregar os membros. Tente de novo.</ErrorNotice> : null}

      {resource.status === 'ready' ? (
        <ul className="member-list" aria-label="Membros">
          {members.map((member) => {
            const me = member.userId === myId
            const owner = member.role === 'OWNER'
            return (
              <li key={member.userId} className="member-row">
                <Avatar name={member.name} size={44} />
                <div className="member-row__main">
                  <p className="title">
                    {member.name} {me ? <Badge tone="brand">Você</Badge> : null}
                  </p>
                  <p className="text-body-sm muted">{member.email}</p>
                  <p className="text-body-sm muted">
                    <Badge tone={owner ? 'brand' : 'neutral'}>{roleLabel(member.role)}</Badge> Desde {formatDate(member.joinedAt.slice(0, 10))}
                  </p>
                </div>
                <div className="item-actions">
                  {isOwner && !owner ? (
                    <>
                      <Button size="sm" variant="ghost" disabled={submitting} aria-label={`Mudar o papel de ${member.name}`} onClick={() => setTarget({ kind: 'role', member })}>
                        Mudar papel
                      </Button>
                      <Button size="sm" variant="danger-quiet" disabled={submitting} aria-label={`Remover ${member.name}`} onClick={() => setTarget({ kind: 'remove', member })}>
                        Remover
                      </Button>
                    </>
                  ) : null}
                  {me && !owner ? (
                    <Button size="sm" variant="danger-quiet" disabled={submitting} onClick={() => setTarget({ kind: 'leave', member })}>
                      Sair do workspace
                    </Button>
                  ) : null}
                </div>
              </li>
            )
          })}
        </ul>
      ) : null}

      {isOwner ? <InvitationsSection workspaceId={workspaceId} version={invitationsVersion} /> : null}

      {inviting ? (
        <InviteSheet
          workspaceId={workspaceId}
          onClose={() => setInviting(false)}
          onCreated={(email) => {
            setInviting(false)
            setProblem(null)
            setNotice(`Convite criado para ${email}.`)
            setInvitationsVersion((version) => version + 1)
          }}
        />
      ) : null}

      {target?.kind === 'role' ? (
        <RoleSheet
          workspaceId={workspaceId}
          member={target.member}
          onClose={() => setTarget(null)}
          onSaved={(text) => {
            setTarget(null)
            setProblem(null)
            setNotice(text)
            reload()
          }}
        />
      ) : null}

      <ConfirmDialog
        open={target?.kind === 'remove' || target?.kind === 'leave'}
        title={target?.kind === 'leave' ? `Sair de "${workspaceName}"?` : `Remover ${target?.member.name ?? ''}?`}
        confirmLabel={target?.kind === 'leave' ? 'Sair' : 'Remover'}
        busy={submitting}
        onConfirm={() => {
          if (target && (target.kind === 'remove' || target.kind === 'leave')) void act(target.member, target.kind === 'leave')
        }}
        onCancel={() => setTarget(null)}
      >
        {target?.kind === 'leave'
          ? 'Você perde o acesso a este workspace. Os lançamentos que você fez continuam nele, e só o dono pode convidar você de novo.'
          : `${target?.member.name ?? 'A pessoa'} perde o acesso ao workspace. Os lançamentos feitos por ela continuam.`}
      </ConfirmDialog>
    </>
  )
}
