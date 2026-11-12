import { useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { Alert, Avatar, Badge, Button, Card, Segmented, PageHeader } from '../design-system/src'
import { useTheme, type ThemeChoice } from '../theme/theme'
import { ButtonLink } from '../ui/ButtonLink'
import { AcceptInviteSheet } from '../members/AcceptInviteSheet'
import { ConfirmDialog } from '../ui/ConfirmDialog'
import { roleLabel } from '../workspace/roles'
import { useWorkspace } from '../workspace/useWorkspace'

const THEME_OPTIONS: { value: ThemeChoice; label: string }[] = [
  { value: 'system', label: 'Sistema' },
  { value: 'light', label: 'Claro' },
  { value: 'dark', label: 'Escuro' },
]

type Busy = 'one' | 'all' | null

export function ProfilePage() {
  const { state, logout, logoutAll } = useAuth()
  const { current, workspaces, select } = useWorkspace()
  const { choice, setChoice } = useTheme()
  const [busy, setBusy] = useState<Busy>(null)
  const [confirming, setConfirming] = useState(false)
  const [joining, setJoining] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (state.status !== 'authenticated') return null
  const { me } = state

  async function signOut() {
    if (busy) return
    setError(null)
    setBusy('one')
    try {
      await logout() // só depois do 204 a sessão some e o aplicativo leva à entrada
    } catch {
      setError('Não foi possível sair. Tente de novo.')
    } finally {
      setBusy(null)
    }
  }

  async function signOutEverywhere() {
    if (busy) return
    setError(null)
    setBusy('all')
    try {
      await logoutAll()
    } catch {
      setConfirming(false)
      setError('Não foi possível sair de todos os aparelhos. Tente de novo.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <>
      <PageHeader title="Perfil" />

      {error ? <Alert tone="danger">{error}</Alert> : null}

      <Card title="Seus dados">
        <div className="profile-id">
          <Avatar name={me.name} size={56} />
        </div>
        <dl className="profile-data">
          <dt className="text-label muted">Nome</dt>
          <dd className="text-body">{me.name}</dd>
          <dt className="text-label muted">E-mail</dt>
          <dd className="text-body">{me.email}</dd>
        </dl>
      </Card>

      <Card title="Workspaces">
        <ul className="profile-workspaces">
          {workspaces.map((workspace) => {
            const isCurrent = workspace.id === current?.id
            return (
              <li key={workspace.id} className="profile-workspaces__item">
                <div className="stack stack--tight">
                  <p className="title">{workspace.name}</p>
                  <div className="profile-workspaces__badges">
                    <Badge tone="neutral">{roleLabel(workspace.role)}</Badge>
                    {isCurrent ? <Badge tone="brand">Atual</Badge> : null}
                  </div>
                </div>
                {isCurrent ? <ButtonLink size="sm" variant="secondary" to="/membros">Membros</ButtonLink> : null}
                {workspaces.length > 1 && !isCurrent ? (
                  <Button variant="secondary" size="sm" aria-label={`Usar ${workspace.name}`} onClick={() => select(workspace.id)}>
                    Usar este
                  </Button>
                ) : null}
              </li>
            )
          })}
        </ul>
        <div className="profile-actions">
          <Button variant="secondary" size="sm" onClick={() => setJoining(true)}>
            Entrar com um código de convite
          </Button>
        </div>
      </Card>

      {joining ? <AcceptInviteSheet onClose={() => setJoining(false)} /> : null}

      <Card title="Aparência">
        <Segmented label="Tema" value={choice} options={THEME_OPTIONS} onChange={setChoice} />
      </Card>

      <Card title="Sessão">
        <div className="profile-actions">
          <Button variant="secondary" onClick={() => void signOut()} disabled={busy !== null}>
            {busy === 'one' ? 'Saindo…' : 'Sair'}
          </Button>
          <Button variant="danger" onClick={() => setConfirming(true)} disabled={busy !== null}>
            Sair de todos os aparelhos
          </Button>
        </div>
      </Card>

      <ConfirmDialog
        open={confirming}
        title="Sair de todos os aparelhos?"
        confirmLabel="Sair de todos"
        busy={busy === 'all'}
        onConfirm={() => void signOutEverywhere()}
        onCancel={() => setConfirming(false)}
      >
        Você terá de entrar de novo em cada aparelho em que usa o Tostão, inclusive neste.
      </ConfirmDialog>
    </>
  )
}
