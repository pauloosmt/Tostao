import { act, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Account } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { WID, makeAccount, makeBalanceReport, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const created = makeAccount({ name: 'Carteira', type: 'CASH' })
const POST = wsRoute('POST', WID, '/accounts')

/** Abre `/contas/nova`; depois de salvar, a lista (`/contas`) mostra o que o teste disser que existe. */
function open(role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER', post: Handler = ok(created, 201), accountsAfter: Account[] = [created]) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [POST]: post,
    [wsRoute('GET', WID, '/accounts')]: ok(accountsAfter),
    [wsRoute('GET', WID, '/reports/balance')]: ok(makeBalanceReport(accountsAfter)),
  })
  return { api, ...renderApp(['/contas/nova']) }
}

const name = () => screen.getByLabelText('Nome')
const type = () => screen.getByLabelText('Tipo')
const balance = () => screen.getByLabelText('Saldo inicial')
const limit = () => screen.getByLabelText('Limite (opcional)')
const closing = () => screen.getByLabelText('Dia de fechamento (opcional)')
const due = () => screen.getByLabelText('Dia de vencimento (opcional)')
const save = () => screen.getByRole('button', { name: /^(Criar conta|Salvando…)$/ })
const body = (api: ReturnType<typeof open>['api']) => api.callsTo(POST)[0].body

async function ready() {
  await screen.findByRole('heading', { name: 'Criar conta' })
}

describe('criar uma conta', () => {
  it('mostra o formulário com nome, tipo e saldo inicial (já em 0,00) para o dono', async () => {
    open()
    await ready()

    expect(name()).toHaveValue('')
    expect(type()).toHaveValue('')
    expect(balance()).toHaveValue('0,00')
    expect(screen.queryByLabelText('Limite (opcional)')).toBeNull()
    expect(screen.getByRole('link', { name: 'Cancelar' })).toHaveAttribute('href', '/contas')
  })

  it('com dados válidos cria a conta, volta à lista com o aviso e o saldo', async () => {
    const { api, user } = open()
    await ready()

    await user.type(name(), '  Carteira  ')
    await user.selectOptions(type(), 'CASH')
    await user.clear(balance())
    await user.type(balance(), '1.500,00')
    await user.click(save())

    expect(await screen.findByText('Conta criada.')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Contas' })).toBeInTheDocument() // (a lista carrega logo depois do aviso)
    expect(screen.getByTestId('location')).toHaveTextContent('/contas')
    expect(body(api)).toEqual({ name: 'Carteira', type: 'CASH', initialBalanceCents: 150000 })
    expect(api.callsTo(POST)).toHaveLength(1)
  })

  it('o editor também cria', async () => {
    const { api, user } = open('EDITOR')
    await ready()

    await user.type(name(), 'Carteira')
    await user.selectOptions(type(), 'CHECKING')
    await user.click(save())

    expect(await screen.findByText('Conta criada.')).toBeInTheDocument()
    expect(body(api)).toMatchObject({ type: 'CHECKING', initialBalanceCents: 0 })
  })

  it('o saldo negativo vai em centavos, com sinal', async () => {
    const { api, user } = open()
    await ready()

    await user.type(name(), 'Cheque especial')
    await user.selectOptions(type(), 'CHECKING')
    await user.clear(balance())
    await user.type(balance(), '-200,5')
    await user.click(save())

    await screen.findByText('Conta criada.')
    expect(body(api)).toMatchObject({ initialBalanceCents: -20050 })
  })

  it('cancelar volta à lista sem criar nada', async () => {
    const { api, user } = open()
    await ready()
    await user.type(name(), 'Esquecida')

    await user.click(screen.getByRole('link', { name: 'Cancelar' }))

    expect(await screen.findByRole('heading', { name: 'Contas' })).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(0)
    expect(screen.queryByText('Conta criada.')).toBeNull()
  })
})

describe('o cartão de crédito', () => {
  it('mostra limite, fechamento e vencimento só para o cartão, e os envia', async () => {
    const { api, user } = open()
    await ready()
    await user.type(name(), 'Nubank')

    await user.selectOptions(type(), 'CREDIT_CARD')
    await user.type(limit(), '5.000,00')
    await user.type(closing(), '10')
    await user.type(due(), '17')
    await user.click(save())

    await screen.findByText('Conta criada.')
    expect(body(api)).toEqual({ name: 'Nubank', type: 'CREDIT_CARD', initialBalanceCents: 0, creditLimitCents: 500000, closingDay: 10, dueDay: 17 })
  })

  it('os campos do cartão são opcionais: só o que foi preenchido é enviado', async () => {
    const { api, user } = open()
    await ready()
    await user.type(name(), 'Nubank')
    await user.selectOptions(type(), 'CREDIT_CARD')
    await user.type(closing(), '10')

    await user.click(save())

    await screen.findByText('Conta criada.')
    expect(body(api)).toEqual({ name: 'Nubank', type: 'CREDIT_CARD', initialBalanceCents: 0, closingDay: 10 })
  })

  it('trocar para outro tipo some com os campos do cartão e descarta o que foi digitado, mesmo se o cartão voltar', async () => {
    const { api, user } = open()
    await ready()
    await user.type(name(), 'Mudou de ideia')
    await user.selectOptions(type(), 'CREDIT_CARD')
    await user.type(limit(), '1.000,00')
    await user.type(closing(), '5')

    await user.selectOptions(type(), 'CASH')
    expect(screen.queryByLabelText('Limite (opcional)')).toBeNull()
    expect(screen.queryByLabelText('Dia de fechamento (opcional)')).toBeNull()
    await user.selectOptions(type(), 'CREDIT_CARD')
    expect(limit()).toHaveValue('')
    expect(closing()).toHaveValue('')

    await user.selectOptions(type(), 'SAVINGS')
    await user.click(save())

    await screen.findByText('Conta criada.')
    expect(body(api)).toEqual({ name: 'Mudou de ideia', type: 'SAVINGS', initialBalanceCents: 0 })
  })
})

describe('as regras no campo, antes de enviar', () => {
  it('vazio: erro no nome e no tipo, o foco vai ao nome e nada é enviado', async () => {
    const { api, user } = open()
    await ready()

    await user.click(save())

    expect(name()).toHaveAccessibleDescription('Informe o nome.')
    expect(type()).toHaveAccessibleDescription('Escolha o tipo da conta.')
    expect(name()).toHaveFocus()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('cada campo mostra o seu erro e o foco vai ao primeiro (nome longo, saldo ruim, limite negativo, dias fora de 1 a 31)', async () => {
    const { api, user } = open()
    await ready()
    await user.click(name())
    await user.paste('a'.repeat(101))
    await user.selectOptions(type(), 'CREDIT_CARD')
    await user.clear(balance())
    await user.type(balance(), 'abc')
    await user.type(limit(), '-1')
    await user.type(closing(), '0')
    await user.type(due(), '32')

    await user.click(save())

    expect(name()).toHaveAccessibleDescription('Use no máximo 100 caracteres.')
    expect(balance()).toHaveAccessibleDescription('Informe um valor em reais, como 1.234,56.')
    expect(limit()).toHaveAccessibleDescription('O limite não pode ser negativo.')
    expect(closing()).toHaveAccessibleDescription('Use um dia de 1 a 31.')
    expect(due()).toHaveAccessibleDescription('Use um dia de 1 a 31.')
    expect(name()).toHaveFocus()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('o foco vai ao primeiro campo com erro, mesmo que não seja o nome', async () => {
    const { user } = open()
    await ready()
    await user.type(name(), 'Boa')
    await user.selectOptions(type(), 'CASH')
    await user.clear(balance())
    await user.type(balance(), '12.5')

    await user.click(save())

    expect(balance()).toHaveFocus()
    expect(balance()).toHaveAccessibleDescription('Use vírgula para os centavos, por exemplo 12,50.')
  })

  it('consertar o erro e enviar de novo funciona', async () => {
    const { api, user } = open()
    await ready()
    await user.click(save())
    await user.type(name(), 'Agora vai')
    await user.selectOptions(type(), 'CASH')

    await user.click(save())

    await screen.findByText('Conta criada.')
    expect(api.callsTo(POST)).toHaveLength(1)
  })
})

describe('quando a API recusa', () => {
  async function fill(user: ReturnType<typeof open>['user']) {
    await user.type(name(), 'Carteira')
    await user.selectOptions(type(), 'CASH')
    await user.clear(balance())
    await user.type(balance(), '10,00')
  }

  it('409: nome em uso aparece no campo do nome, os outros valores ficam e o foco vai ao nome', async () => {
    const { user } = open('OWNER', problem(409, 'Nome em uso'))
    await ready()
    await fill(user)

    await user.click(save())

    await waitFor(() => expect(name()).toHaveAccessibleDescription('Já existe uma conta ativa com este nome.'))
    expect(name()).toHaveFocus()
    expect(name()).toHaveValue('Carteira')
    expect(type()).toHaveValue('CASH')
    expect(balance()).toHaveValue('10,00')
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('400 com erro de campo: vai ao campo certo (saldo), o que não é da tela vira o aviso geral', async () => {
    const { user } = open(
      'OWNER',
      problem(400, 'Requisição inválida', {
        errors: [
          { field: 'initialBalanceCents', message: 'fora do limite' },
          { field: 'currency', message: 'moeda inválida' },
        ],
      }),
    )
    await ready()
    await fill(user)

    await user.click(save())

    await waitFor(() => expect(balance()).toHaveAccessibleDescription('fora do limite'))
    expect(screen.getByRole('alert')).toHaveTextContent('moeda inválida')
    expect(balance()).toHaveFocus()
  })

  it('403: mostra a mensagem de permissão e continua no formulário', async () => {
    const { user } = open('OWNER', problem(403, 'Proibido'))
    await ready()
    await fill(user)

    await user.click(save())

    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para alterar isto neste workspace.')
    expect(name()).toHaveValue('Carteira')
    expect(screen.queryByRole('button', { name: 'Tentar de novo' })).toBeNull()
  })

  it('servidor fora do ar: avisa com "Tentar de novo", mantém os valores e envia outra vez', async () => {
    const { api, user } = open('OWNER', networkError())
    await ready()
    await fill(user)
    await user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')
    expect(name()).toHaveValue('Carteira')

    api.on(POST, ok(created, 201))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByText('Conta criada.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(2)
  })

  it('o envio duplicado (cliques e Enter seguidos) chama a API uma vez só e mostra que está salvando', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open('OWNER', async () => (await wait, ok(created, 201)))
    await ready()
    await fill(user)

    await user.click(save())
    await user.click(save())
    await user.keyboard('{Enter}')

    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => release())
    expect(await screen.findByText('Conta criada.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(1)
  })
})

describe('quem só consulta', () => {
  it('o leitor vê o aviso de papel e a volta, nunca o formulário', async () => {
    const { api } = open('VIEWER')

    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar às contas' })).toHaveAttribute('href', '/contas')
    expect(screen.queryByLabelText('Nome')).toBeNull()
    expect(api.callsTo(POST)).toHaveLength(0)
  })
})

describe('acessibilidade', () => {
  it('passa no axe: em repouso, com cartão e com erros', async () => {
    const { container, user } = open()
    await ready()
    await expectNoSeriousViolations(container)

    await user.selectOptions(type(), 'CREDIT_CARD')
    await expectNoSeriousViolations(container)

    await user.click(save())
    await waitFor(() => expect(name()).toHaveAttribute('aria-invalid', 'true'))
    await expectNoSeriousViolations(container)
  })
})
