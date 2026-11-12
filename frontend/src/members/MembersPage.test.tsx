import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { Me } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, noContent, ok, problem, wsRoute } from '../test/fakeApi'
import { homeRoutes } from '../test/homeRoutes'
import { WID, makeInvitation, makeMe, makeMember } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const ana = makeMember({ userId: 'u-ana', name: 'Ana Maria', email: 'ana@exemplo.com', role: 'OWNER', joinedAt: '2026-01-10T12:00:00Z' })
const bia = makeMember({ userId: 'u-bia', name: 'Bia Costa', email: 'bia@exemplo.com', role: 'EDITOR', joinedAt: '2026-02-20T12:00:00Z' })
const caio = makeMember({ userId: 'u-caio', name: 'Caio Lima', email: 'caio@exemplo.com', role: 'VIEWER', joinedAt: '2026-03-30T12:00:00Z' })

const LIST = wsRoute('GET', WID, '/members')
const INVITES = wsRoute('GET', WID, '/invitations')
const CREATE = wsRoute('POST', WID, '/invitations')
const PUT_BIA = wsRoute('PUT', WID, '/members/u-bia')
const DEL_BIA = wsRoute('DELETE', WID, '/members/u-bia')

function open(me: Me, members = [ana, bia, caio], path = '/membros', invitations = [] as ReturnType<typeof makeInvitation>[]) {
  const api = fakeApi({ 'GET /api/v1/me': ok(me), [LIST]: ok(members), [INVITES]: ok(invitations) })
  return { api, ...renderApp([path]) }
}
const owner = () => makeMe('OWNER')
const asBia = () => makeMe('EDITOR', { id: 'u-bia', name: 'Bia Costa' })
const rows = () => within(screen.getByRole('list', { name: 'Membros' })).getAllByRole('listitem')

describe('a lista de membros', () => {
  it('cada pessoa com nome, e-mail, o papel em palavras e desde quando; o selo "Você" na própria linha', async () => {
    const { container } = open(owner())

    await screen.findByRole('list', { name: 'Membros' })
    expect(screen.getByText('3 pessoas em Casa')).toBeInTheDocument()
    expect(rows()).toHaveLength(3)
    expect(rows()[0]).toHaveTextContent(/Ana Maria.*Você.*ana@exemplo.com.*Dono.*Desde 10\/01\/2026/)
    expect(rows()[1]).toHaveTextContent(/Bia Costa.*Editor.*Desde 20\/02\/2026/)
    expect(rows()[2]).toHaveTextContent(/Caio Lima.*Leitor/)
    expect(rows()[1]).not.toHaveTextContent('Você')
    await expectNoSeriousViolations(container)
  })

  it('falha ao carregar avisa com "Tentar de novo"; o Perfil leva a "Membros"', async () => {
    const api = fakeApi({ 'GET /api/v1/me': ok(owner()), [LIST]: networkError(), [INVITES]: ok([]) })
    const { user } = renderApp(['/perfil'])

    await user.click(await screen.findByRole('link', { name: 'Membros' }))

    expect(api.callsTo(LIST).length).toBeGreaterThan(0)
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})

describe('o dono muda o papel e remove', () => {
  it('só o dono vê as ações, e só nos outros membros; mudar o papel envia o papel e atualiza a lista', async () => {
    const { api, user } = open(owner())
    api.on(PUT_BIA, ok({ ...bia, role: 'VIEWER' }))
    await screen.findByRole('list', { name: 'Membros' })
    expect(within(rows()[0]).queryByRole('button')).toBeNull() // o dono não tem ações na própria linha

    await user.click(screen.getByRole('button', { name: 'Mudar o papel de Bia Costa' }))
    expect(await screen.findByRole('radio', { name: 'Editor' })).toBeChecked()
    await user.click(screen.getByRole('radio', { name: 'Leitor' }))
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Bia Costa agora é Leitor.')).toBeInTheDocument()
    expect(api.callsTo(PUT_BIA)[0].body).toEqual({ role: 'VIEWER' })
    expect(api.callsTo(LIST)).toHaveLength(2)
  })

  it('remover pede confirmação (cancelar não remove); confirmar remove e avisa', async () => {
    const { api, user } = open(owner())
    api.on(DEL_BIA, noContent())
    await screen.findByRole('list', { name: 'Membros' })

    await user.click(screen.getByRole('button', { name: 'Remover Bia Costa' }))
    const confirm = await screen.findByRole('dialog', { name: 'Remover Bia Costa?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancelar' }))
    expect(api.callsTo(DEL_BIA)).toHaveLength(0)

    await user.click(screen.getByRole('button', { name: 'Remover Bia Costa' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Remover Bia Costa?' })).getByRole('button', { name: 'Remover' }))

    expect(await screen.findByText('O acesso de Bia Costa foi removido.')).toBeInTheDocument()
    expect(api.callsTo(DEL_BIA)).toHaveLength(1)
    expect(api.callsTo(LIST)).toHaveLength(2)
  })

  it('uma recusa da API aparece como ela disse', async () => {
    const { api, user } = open(owner())
    api.on(PUT_BIA, problem(409, 'Conflito de estado', { detail: 'O papel do dono não pode ser mudado' }))
    await screen.findByRole('list', { name: 'Membros' })

    await user.click(screen.getByRole('button', { name: 'Mudar o papel de Bia Costa' }))
    await user.click(await screen.findByRole('radio', { name: 'Leitor' }))
    await user.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('O papel do dono não pode ser mudado')
  })
})

describe('quem não é dono', () => {
  it('só vê "Sair do workspace" na própria linha; sem ações nos outros', async () => {
    open(asBia())
    await screen.findByRole('list', { name: 'Membros' })

    expect(screen.queryByRole('button', { name: /Mudar o papel|Remover/ })).toBeNull()
    expect(within(rows()[1]).getByRole('button', { name: 'Sair do workspace' })).toBeInTheDocument()
    expect(within(rows()[0]).queryByRole('button')).toBeNull()
    expect(within(rows()[2]).queryByRole('button')).toBeNull()
  })

  it('sair pede confirmação, atualiza os workspaces e leva ao Início do que sobrar', async () => {
    const { api, user } = open(asBia())
    const outro: Me = { ...asBia(), workspaces: [{ id: 'w-outro', name: 'Viagem', baseCurrency: 'BRL', role: 'OWNER' }] }
    api.on(DEL_BIA, noContent())
    await screen.findByRole('list', { name: 'Membros' })
    api.on('GET /api/v1/me', ok(outro)) // depois de sair, a API devolve só o workspace que sobrou
    for (const [route, reply] of Object.entries(homeRoutes([], 'w-outro'))) api.on(route, reply as never)
    api.on(wsRoute('GET', 'w-outro', '/invitations'), ok([]))
    api.on(wsRoute('GET', 'w-outro', '/members'), ok([ana])) // a tela de membros ainda aparece por um instante, já no workspace novo

    await user.click(screen.getByRole('button', { name: 'Sair do workspace' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Sair de "Casa"?' })).getByRole('button', { name: 'Sair' }))

    expect(await screen.findByText('Crie sua primeira conta')).toBeInTheDocument() // o Início do Viagem
    expect(screen.getByTestId('location').textContent).toBe('/')
    expect(api.callsTo(DEL_BIA)).toHaveLength(1)
  })
})

describe('os convites', () => {
  const pendente = makeInvitation({ id: 'inv-1', email: 'dani@exemplo.com', role: 'EDITOR', expiresAt: '2026-11-24T12:00:00Z' })

  it('o dono vê os pendentes (e-mail, papel, validade) e revoga com confirmação; quem não é dono nem os consulta', async () => {
    const { api, user } = open(owner(), [ana, bia, caio], '/membros', [pendente])
    const del = wsRoute('DELETE', WID, '/invitations/inv-1')
    api.on(del, noContent())

    const list = await screen.findByRole('list', { name: 'Convites pendentes' })
    expect(list).toHaveTextContent(/dani@exemplo.com.*Editor.*Expira em 24\/11\/2026/)
    await user.click(screen.getByRole('button', { name: 'Revogar o convite de dani@exemplo.com' }))
    const confirm = await screen.findByRole('dialog', { name: 'Revogar este convite?' })
    await user.click(within(confirm).getByRole('button', { name: 'Cancelar' }))
    expect(api.callsTo(del)).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Revogar o convite de dani@exemplo.com' }))
    await user.click(within(await screen.findByRole('dialog', { name: 'Revogar este convite?' })).getByRole('button', { name: 'Revogar' }))

    expect(await screen.findByText('O convite para dani@exemplo.com foi revogado.')).toBeInTheDocument()
    expect(api.callsTo(del)).toHaveLength(1)
    expect(api.callsTo(INVITES)).toHaveLength(2)
  })

  it('quem não é dono não vê convites nem "Convidar", e a API de convites nem é chamada', async () => {
    const { api } = open(asBia())
    await screen.findByRole('list', { name: 'Membros' })

    expect(screen.queryByRole('button', { name: 'Convidar' })).toBeNull()
    expect(screen.queryByRole('heading', { name: 'Convites pendentes' })).toBeNull()
    expect(api.callsTo(INVITES)).toHaveLength(0)
  })

  it('convidar mostra o código uma só vez, com "Copiar"; ao concluir o código some e a lista de pendentes se atualiza', async () => {
    const { api, user } = open(owner())
    const writeText = vi.spyOn(navigator.clipboard, 'writeText') // (o userEvent já trocou a área de transferência por uma de teste)
    api.on(CREATE, ok({ ...makeInvitation({ id: 'inv-9', email: 'nova@exemplo.com', role: 'VIEWER' }), code: 'ABCD-1234-SEGREDO' }, 201))
    await screen.findByRole('list', { name: 'Membros' })

    await user.click(screen.getByRole('button', { name: 'Convidar' }))
    await user.click(await screen.findByRole('button', { name: 'Criar convite' }))
    expect(await screen.findByText('Informe o e-mail da pessoa.')).toBeInTheDocument() // vazio: nada é enviado
    expect(api.callsTo(CREATE)).toHaveLength(0)
    await user.type(screen.getByLabelText('E-mail da pessoa'), '  nova@exemplo.com ')
    await user.click(screen.getByRole('button', { name: 'Criar convite' }))

    expect(await screen.findByLabelText('Código do convite')).toHaveTextContent('ABCD-1234-SEGREDO')
    expect(api.callsTo(CREATE)[0].body).toEqual({ email: 'nova@exemplo.com', role: 'VIEWER' })
    await user.click(screen.getByRole('button', { name: 'Copiar código' }))
    expect(writeText).toHaveBeenCalledWith('ABCD-1234-SEGREDO')
    expect(await screen.findByText('Código copiado.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Concluir' }))
    expect(await screen.findByText('Convite criado para nova@exemplo.com.')).toBeInTheDocument()
    expect(screen.queryByText('ABCD-1234-SEGREDO')).toBeNull() // o código não fica na tela
    expect(document.body.textContent).not.toContain('ABCD-1234-SEGREDO')
    expect(api.callsTo(INVITES)).toHaveLength(2)
  })

  it('uma recusa da API aparece como ela disse e o e-mail digitado fica', async () => {
    const { api, user } = open(owner())
    api.on(CREATE, problem(409, 'Conflito de estado', { detail: 'Esta pessoa já participa do workspace' }))
    await screen.findByRole('list', { name: 'Membros' })

    await user.click(screen.getByRole('button', { name: 'Convidar' }))
    await user.type(await screen.findByLabelText('E-mail da pessoa'), 'bia@exemplo.com')
    await user.click(screen.getByRole('radio', { name: 'Editor' }))
    await user.click(screen.getByRole('button', { name: 'Criar convite' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Esta pessoa já participa do workspace')
    expect(screen.getByLabelText('E-mail da pessoa')).toHaveValue('bia@exemplo.com')
    expect(api.callsTo(CREATE)[0].body).toEqual({ email: 'bia@exemplo.com', role: 'EDITOR' })
  })
})
