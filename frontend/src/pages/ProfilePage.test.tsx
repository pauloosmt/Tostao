import { act, screen, within } from '@testing-library/react'
import { Route } from 'react-router'
import { describe, expect, it } from 'vitest'
import type { Me, WorkspaceMembership } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, noContent, ok, problem, type Handler, type Reply } from '../test/fakeApi'
import { renderRoutes } from '../test/renderRoutes'
import { ThemeProvider } from '../theme/ThemeContext'
import { WorkspaceProvider } from '../workspace/WorkspaceContext'
import { ProfilePage } from './ProfilePage'

const casa: WorkspaceMembership = { id: 'w-casa', name: 'Casa', baseCurrency: 'BRL', role: 'OWNER' }
const viagem: WorkspaceMembership = { id: 'w-viagem', name: 'Viagem', baseCurrency: 'BRL', role: 'VIEWER' }
const obra: WorkspaceMembership = { id: 'w-obra', name: 'Obra', baseCurrency: 'BRL', role: 'EDITOR' }
const ana = (workspaces: WorkspaceMembership[]): Me => ({ id: 'u-ana', name: 'Ana Maria', email: 'ana@exemplo.com', workspaces })

// O que o aplicativo faz por fora da tela: só renderiza o perfil com sessão, dentro dos providers.
function WithSession() {
  const { state } = useAuth()
  return state.status === 'authenticated' ? (
    <ThemeProvider>
      <WorkspaceProvider me={state.me}>
        <ProfilePage />
      </WorkspaceProvider>
    </ThemeProvider>
  ) : (
    <p>fora</p>
  )
}
const routes = <Route path="/" element={<WithSession />} />

function gate(reply: Reply) {
  let release!: () => void
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  return { handler: async () => (await wait, reply), release }
}

async function open(me: Me, extra: Record<string, Handler> = {}) {
  const api = fakeApi({ 'GET /api/v1/me': ok(me), ...extra })
  const view = renderRoutes(routes, ['/'])
  await screen.findByRole('heading', { name: 'Perfil' })
  return { api, ...view }
}

describe('os dados', () => {
  it('mostra o nome e o e-mail, e os workspaces com o papel em português', async () => {
    await open(ana([casa, viagem, obra]))

    expect(screen.getByText('Ana Maria')).toBeInTheDocument()
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument()
    const items = screen.getAllByRole('listitem')
    expect(items.map((item) => item.textContent)).toEqual([
      expect.stringContaining('CasaDonoAtual'),
      expect.stringContaining('ViagemLeitor'),
      expect.stringContaining('ObraEditor'),
    ])
  })

  it('passa no axe', async () => {
    const { container } = await open(ana([casa, viagem]))

    await expectNoSeriousViolations(container)
  })
})

describe('trocar de workspace', () => {
  it('muda o atual, guarda a escolha e ela continua depois de recarregar', async () => {
    const { user, unmount } = await open(ana([casa, viagem]))
    expect(within(screen.getAllByRole('listitem')[0]).getByText('Atual')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Usar Viagem' }))

    expect(within(screen.getAllByRole('listitem')[1]).getByText('Atual')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Usar Viagem' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Usar Casa' })).toBeInTheDocument()
    expect(localStorage.getItem('tostao.workspace.u-ana')).toBe('w-viagem')

    unmount()
    fakeApi({ 'GET /api/v1/me': ok(ana([casa, viagem])) })
    renderRoutes(routes, ['/'])
    await screen.findByRole('heading', { name: 'Perfil' })
    expect(within(screen.getAllByRole('listitem')[1]).getByText('Atual')).toBeInTheDocument()
  })

  it('com um workspace só, não há o que trocar', async () => {
    await open(ana([casa]))

    expect(screen.queryByRole('button', { name: /^Usar / })).toBeNull()
    expect(screen.getByText('Atual')).toBeInTheDocument()
  })
})

describe('o tema', () => {
  it('escolher aplica ao app e lembra neste aparelho', async () => {
    const { user } = await open(ana([casa]))
    expect(screen.getByRole('radio', { name: 'Sistema' })).toBeChecked()

    await user.click(screen.getByRole('radio', { name: 'Escuro' }))

    expect(screen.getByRole('radio', { name: 'Escuro' })).toBeChecked()
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem('tostao.theme')).toBe('dark')

    await user.click(screen.getByRole('radio', { name: 'Sistema' }))
    expect(document.documentElement.getAttribute('data-theme')).toBeNull()
  })
})

describe('sair', () => {
  it('só depois do 204 a sessão some; enquanto a chamada roda o botão avisa e não duplica', async () => {
    const pending = gate({ status: 204 })
    const { api, user } = await open(ana([casa]), { 'POST /api/v1/auth/logout': pending.handler })

    await user.click(screen.getByRole('button', { name: 'Sair' }))
    expect(screen.getByRole('button', { name: 'Saindo…' })).toBeDisabled()
    expect(screen.getByRole('heading', { name: 'Perfil' })).toBeInTheDocument()

    await act(async () => pending.release())

    expect(await screen.findByText('fora')).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/logout')).toBe(1)
  })

  it('se a saída falha, a pessoa continua dentro e o aviso aparece', async () => {
    const { user, api } = await open(ana([casa]), { 'POST /api/v1/auth/logout': problem(500, 'Erro') })

    await user.click(screen.getByRole('button', { name: 'Sair' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível sair. Tente de novo.')
    expect(screen.getByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Sair' })).toBeEnabled()

    api.on('POST /api/v1/auth/logout', noContent())
    await user.click(screen.getByRole('button', { name: 'Sair' }))
    expect(await screen.findByText('fora')).toBeInTheDocument()
  })

  it('sem rede também mantém a sessão e avisa', async () => {
    const { user } = await open(ana([casa]), { 'POST /api/v1/auth/logout': networkError() })

    await user.click(screen.getByRole('button', { name: 'Sair' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível sair.')
    expect(screen.getByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
  })
})

describe('sair de todos os aparelhos', () => {
  it('pede confirmação, e cancelar não chama a API', async () => {
    const { user, api } = await open(ana([casa]), { 'POST /api/v1/auth/logout-all': noContent() })

    await user.click(screen.getByRole('button', { name: 'Sair de todos os aparelhos' }))
    expect(screen.getByRole('dialog', { name: 'Sair de todos os aparelhos?' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.count('POST /api/v1/auth/logout-all')).toBe(0)
    expect(screen.getByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
  })

  it('Esc também cancela', async () => {
    const { user, api } = await open(ana([casa]), { 'POST /api/v1/auth/logout-all': noContent() })
    await user.click(screen.getByRole('button', { name: 'Sair de todos os aparelhos' }))

    act(() => (screen.getByRole('dialog') as HTMLDialogElement).close())

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.count('POST /api/v1/auth/logout-all')).toBe(0)
  })

  it('confirmar encerra a sessão depois do 204', async () => {
    const { user, api } = await open(ana([casa]), { 'POST /api/v1/auth/logout-all': noContent() })

    await user.click(screen.getByRole('button', { name: 'Sair de todos os aparelhos' }))
    await user.click(screen.getByRole('button', { name: 'Sair de todos' }))

    expect(await screen.findByText('fora')).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/logout-all')).toBe(1)
  })

  it('se falha, a sessão fica, o diálogo fecha e o aviso aparece', async () => {
    const { user } = await open(ana([casa]), { 'POST /api/v1/auth/logout-all': problem(500, 'Erro') })

    await user.click(screen.getByRole('button', { name: 'Sair de todos os aparelhos' }))
    await user.click(screen.getByRole('button', { name: 'Sair de todos' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível sair de todos os aparelhos.')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('heading', { name: 'Perfil' })).toBeInTheDocument()
  })

  it('durante a chamada os botões do diálogo ficam indisponíveis (sem confirmar duas vezes)', async () => {
    const pending = gate({ status: 204 })
    const { user, api } = await open(ana([casa]), { 'POST /api/v1/auth/logout-all': pending.handler })
    await user.click(screen.getByRole('button', { name: 'Sair de todos os aparelhos' }))

    await user.click(screen.getByRole('button', { name: 'Sair de todos' }))

    expect(screen.getByRole('button', { name: 'Sair de todos' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    await act(async () => pending.release())
    expect(await screen.findByText('fora')).toBeInTheDocument()
    expect(api.count('POST /api/v1/auth/logout-all')).toBe(1)
  })
})
