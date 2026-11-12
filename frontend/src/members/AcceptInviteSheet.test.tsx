import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Me } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute } from '../test/fakeApi'
import { WID, makeMe } from '../test/fixtures'
import { homeRoutes } from '../test/homeRoutes'
import { renderApp } from '../test/renderApp'

const ACCEPT = 'POST /api/v1/invitations/accept'

function open(me = makeMe('OWNER')) {
  const api = fakeApi({ 'GET /api/v1/me': ok(me) })
  return { api, ...renderApp(['/perfil']) }
}
const openSheet = async (user: ReturnType<typeof open>['user']) => {
  await user.click(await screen.findByRole('button', { name: 'Entrar com um código de convite' }))
  return screen.findByLabelText('Código do convite')
}

describe('entrar com um código de convite', () => {
  it('aceita, atualiza os workspaces, mostra onde entrou e leva ao Início do novo; o código não fica na tela nem no endereço', async () => {
    const { api, user } = open()
    const joined: Me = { ...makeMe('OWNER'), workspaces: [...makeMe('OWNER').workspaces, { id: 'w-viagem', name: 'Viagem', baseCurrency: 'BRL', role: 'EDITOR' }] }
    api.on(ACCEPT, ok({ workspaceId: 'w-viagem', name: 'Viagem', role: 'EDITOR' }))
    for (const [route, reply] of Object.entries(homeRoutes([], 'w-viagem'))) api.on(route, reply as never)
    const field = await openSheet(user)

    await user.type(field, '  CODIGO-SECRETO-123  ')
    api.on('GET /api/v1/me', ok(joined)) // depois de aceitar, a API devolve os workspaces com o novo
    await user.click(screen.getByRole('button', { name: 'Aceitar convite' }))

    expect(await screen.findByText(/Você entrou em/)).toHaveTextContent('Você entrou em Viagem como Editor.')
    expect(api.callsTo(ACCEPT)[0].body).toEqual({ code: 'CODIGO-SECRETO-123' })
    expect(document.body.textContent).not.toContain('CODIGO-SECRETO-123')
    await user.click(screen.getByRole('button', { name: 'Ir para o Início' }))

    expect(await screen.findByText('Crie sua primeira conta')).toBeInTheDocument() // o Início do Viagem
    expect(screen.getByTestId('location').textContent).toBe('/') // nada do código no endereço
    expect(localStorage.getItem(`tostao.workspace.${joined.id}`)).toBe('w-viagem') // o novo workspace passou a ser o atual
  })

  it('vazio pede o código; código inválido, vencido ou usado aparece como um só aviso e o código digitado fica', async () => {
    const { api, user } = open()
    api.on(ACCEPT, problem(404, 'Não encontrado', { detail: 'Convite inválido ou vencido' }))
    const field = await openSheet(user)

    await user.click(screen.getByRole('button', { name: 'Aceitar convite' }))
    expect(await screen.findByText('Informe o código do convite.')).toBeInTheDocument()
    expect(api.callsTo(ACCEPT)).toHaveLength(0)

    await user.type(field, 'ERRADO')
    await user.click(screen.getByRole('button', { name: 'Aceitar convite' }))

    expect(await screen.findByText('Código de convite inválido ou vencido.')).toBeInTheDocument()
    expect(field).toHaveValue('ERRADO')
  })

  it('quem já participa ou workspace cheio mostram a mensagem da API; sem rede, avisa com "Tentar de novo"; a folha passa no axe', async () => {
    const first = open()
    first.api.on(ACCEPT, problem(409, 'Conflito de estado', { detail: 'Você já participa deste workspace' }))
    const field = await openSheet(first.user)
    await first.user.type(field, 'X')
    await first.user.click(screen.getByRole('button', { name: 'Aceitar convite' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Você já participa deste workspace')
    await expectNoSeriousViolations(first.container)
    first.unmount()

    const second = open()
    second.api.on(ACCEPT, networkError())
    await second.user.type(await openSheet(second.user), 'X')
    await second.user.click(screen.getByRole('button', { name: 'Aceitar convite' }))
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})

describe('o link "Membros" do Perfil', () => {
  it('leva aos membros do workspace atual', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok(makeMe('EDITOR')), [wsRoute('GET', WID, '/members')]: ok([]) })
    const { user } = renderApp(['/perfil'])

    await user.click(await screen.findByRole('link', { name: 'Membros' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Membros' })).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', WID, '/members'))).toHaveLength(1)
  })
})
