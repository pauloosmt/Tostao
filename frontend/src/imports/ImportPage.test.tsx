import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ImportLinePreview } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute } from '../test/fakeApi'
import { installTransactions } from '../test/fakeTransactions'
import { WID, makeAccount, makeBalanceReport, makeCategory, makeCategoryTree, makeImportLine, makeImportPreview, makeMe, makeTransaction } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const carteira = makeAccount({ id: 'a-carteira', name: 'Carteira' })
const antiga = makeAccount({ id: 'a-antiga', name: 'Antiga', archived: true })
const accounts = [carteira, antiga]
const mercado = makeCategory({ id: 'c-mercado', name: 'Mercado', parentId: 'c-alim' })
const alimentacao = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE' }, [mercado])
const salario = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME' })
const categories = [alimentacao, salario]

const PREVIEW = wsRoute('POST', WID, '/accounts/a-carteira/import/preview')
const CONFIRM = wsRoute('POST', WID, '/accounts/a-carteira/import')

const lines = (): ImportLinePreview[] => [
  makeImportLine({ line: 1, key: 'k1', description: 'Padaria', amountCents: -2350, suggestedCategoryId: 'c-mercado' }),
  makeImportLine({ line: 2, key: 'k2', description: 'Salário', amountCents: 500000, type: 'INCOME', occurredOn: '2026-11-05', suggestedCategoryId: 'c-sal' }),
  makeImportLine({ line: 3, key: 'k3', description: 'Café', amountCents: -800, existing: true }),
  makeImportLine({ line: 4, key: null, description: 'Linha ruim', amountCents: null, occurredOn: null, type: null, problems: ['data inválida'] }),
  makeImportLine({ line: 5, key: 'k5', description: 'Mercado do Zé', amountCents: -9900, possibleDuplicate: true, suggestedCategoryId: 'c-sal' }), // uma sugestão que não serve a uma saída
]

function open(path = '/contas/a-carteira/importar', role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER', preview = ok(makeImportPreview(lines())), list = accounts) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/accounts')]: (call) => ok(call.path.includes('includeArchived=true') ? list : list.filter((account) => !account.archived)),
    [wsRoute('GET', WID, '/categories')]: ok(categories),
    [wsRoute('GET', WID, '/accounts/a-carteira')]: ok(carteira),
    [wsRoute('GET', WID, '/accounts/a-antiga')]: ok(antiga),
    [wsRoute('GET', WID, '/reports/balance')]: ok(makeBalanceReport(list)),
    [PREVIEW]: preview,
  })
  return { api, ...renderApp([path]) }
}
type Ctx = ReturnType<typeof open>

const csv = (name = 'extrato.csv', size = 80) => new File([new Uint8Array(size)], name, { type: 'text/csv' })
const fileInput = () => screen.getByLabelText('Arquivo do extrato') as HTMLInputElement
const row = (description: string) => screen.getByRole('checkbox', { name: `Importar ${description}` }).closest('li') as HTMLElement

async function toPreview(ctx: Ctx) {
  await screen.findByLabelText('Arquivo do extrato')
  await ctx.user.upload(fileInput(), csv())
  await ctx.user.click(screen.getByRole('button', { name: 'Ler extrato' }))
  await screen.findByRole('list', { name: 'Linhas do extrato' })
}

describe('a entrada: "Importar extrato" nos cabeçalhos e a escolha da conta', () => {
  const reserva = makeAccount({ id: 'a-reserva', name: 'Reserva' })

  it('Lançamentos e Contas têm o botão para quem escreve (e só para ele)', async () => {
    const owner = open('/lancamentos')
    owner.api.on(wsRoute('GET', WID, '/transactions'), ok({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 }))
    expect(await screen.findByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', '/importar')
    owner.unmount()

    const accountsPage = open('/contas')
    expect(await screen.findByRole('link', { name: 'Importar extrato' })).toHaveAttribute('href', '/importar')
    accountsPage.unmount()

    const viewer = open('/contas', 'VIEWER')
    await screen.findByText('Carteira')
    expect(screen.queryByRole('link', { name: 'Importar extrato' })).toBeNull()
    viewer.unmount()
  })

  it('com várias contas ativas pergunta de qual é o extrato (sem as arquivadas); com uma só, vai direto à importação dela', async () => {
    const many = open('/importar', 'OWNER', undefined, [carteira, reserva, antiga])
    expect(await screen.findByText('De qual conta é o extrato?')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Importar o extrato de Carteira' })).toHaveAttribute('href', '/contas/a-carteira/importar')
    expect(screen.getByRole('link', { name: 'Importar o extrato de Reserva' })).toHaveAttribute('href', '/contas/a-reserva/importar')
    expect(screen.queryByRole('link', { name: /Antiga/ })).toBeNull()
    many.unmount()

    open('/importar') // só a Carteira está ativa
    expect(await screen.findByLabelText('Arquivo do extrato')).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).toBe('/contas/a-carteira/importar')
  })

  it('o leitor vê o aviso de papel', async () => {
    open('/importar', 'VIEWER')

    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
  })
})

describe('enviar o extrato', () => {
  it('a lista de contas oferece "Importar extrato" só para quem escreve e só nas contas ativas', async () => {
    const owner = open('/contas')
    expect(await screen.findByRole('link', { name: 'Importar extrato em Carteira' })).toHaveAttribute('href', '/contas/a-carteira/importar')
    expect(screen.queryByRole('link', { name: 'Importar extrato em Antiga' })).toBeNull() // arquivada
    owner.unmount()

    open('/contas', 'VIEWER')
    await screen.findByText('Carteira')
    expect(screen.queryByRole('link', { name: /Importar extrato/ })).toBeNull()
  })

  it('sem arquivo ou maior que 2 MB: recusa antes de enviar; o arquivo certo vai em multipart no campo "file"', async () => {
    const ctx = open()
    await screen.findByLabelText('Arquivo do extrato')

    await ctx.user.click(screen.getByRole('button', { name: 'Ler extrato' }))
    expect(await screen.findByText('Escolha o arquivo do extrato.')).toBeInTheDocument()

    await ctx.user.upload(fileInput(), csv('grande.csv', 2 * 1024 * 1024 + 1))
    await ctx.user.click(screen.getByRole('button', { name: 'Ler extrato' }))
    expect(await screen.findByText('O arquivo passa de 2 MB.')).toBeInTheDocument()
    expect(ctx.api.callsTo(PREVIEW)).toHaveLength(0)

    await ctx.user.upload(fileInput(), csv('extrato.csv'))
    await ctx.user.click(screen.getByRole('button', { name: 'Ler extrato' }))
    await screen.findByRole('list', { name: 'Linhas do extrato' })
    const sent = ctx.api.callsTo(PREVIEW)[0].body as FormData
    expect(sent).toBeInstanceOf(FormData)
    expect((sent.get('file') as File).name).toBe('extrato.csv')
  })

  it('a API recusando o arquivo (formato, vazio, muitos movimentos) aparece no campo e permite escolher outro', async () => {
    const ctx = open('/contas/a-carteira/importar', 'OWNER', problem(400, 'Requisição inválida', { errors: [{ field: 'file', message: 'formato não reconhecido: envie um OFX, um CSV com as colunas data, descrição e valor ou um PDF' }] }) as never)
    await screen.findByLabelText('Arquivo do extrato')
    await ctx.user.upload(fileInput(), csv('texto.csv'))
    await ctx.user.click(screen.getByRole('button', { name: 'Ler extrato' }))

    expect(await screen.findByText(/formato não reconhecido/)).toBeInTheDocument()
    ctx.api.on(PREVIEW, ok(makeImportPreview(lines())))
    await ctx.user.upload(fileInput(), csv('certo.csv'))
    await ctx.user.click(screen.getByRole('button', { name: 'Ler extrato' }))
    expect(await screen.findByRole('list', { name: 'Linhas do extrato' })).toBeInTheDocument()
  })

  it('o leitor, a conta arquivada e a que não existe não importam', async () => {
    open('/contas/a-carteira/importar', 'VIEWER')
    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    document.body.innerHTML = ''

    open('/contas/a-antiga/importar')
    expect(await screen.findByText(/A conta está arquivada/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Arquivo do extrato')).toBeNull()
    document.body.innerHTML = ''

    const gone = open('/contas/a-fantasma/importar')
    gone.api.on(wsRoute('GET', WID, '/accounts/a-fantasma'), problem(404, 'Não encontrado'))
    expect(await screen.findByRole('heading', { name: 'Conta não encontrada' })).toBeInTheDocument()
  })
})

describe('a prévia e a confirmação', () => {
  it('o resumo, e cada linha no seu estado: nova marcada, já existente e com problema indisponíveis, possível duplicata marcada com aviso', async () => {
    const ctx = open()
    await toPreview(ctx)

    expect(screen.getByText(/5 linhas/)).toHaveTextContent(/CSV/)
    expect(screen.getByText(/3 novas · 1 já existente · 1 com problema/)).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Importar Padaria' })).toBeChecked()
    expect(row('Padaria')).toHaveTextContent(/−R\$\s*23,50/)
    expect(row('Salário')).toHaveTextContent(/\+R\$\s*5\.000,00/)
    expect(screen.getByRole('checkbox', { name: 'Importar Café' })).toBeDisabled()
    expect(screen.getByRole('checkbox', { name: 'Importar Café' })).not.toBeChecked()
    expect(row('Café')).toHaveTextContent('Já existe')
    expect(screen.getByRole('checkbox', { name: 'Importar Linha ruim' })).toBeDisabled()
    expect(row('Linha ruim')).toHaveTextContent('data inválida')
    expect(screen.getByRole('checkbox', { name: 'Importar Mercado do Zé' })).toBeChecked()
    expect(row('Mercado do Zé')).toHaveTextContent('Possível duplicata')
    expect(screen.getByRole('button', { name: 'Importar 3 lançamentos' })).toBeEnabled()
  })

  it('a categoria vem da sugestão (só se serve ao tipo da linha) e só oferece categorias do tipo', async () => {
    const ctx = open()
    await toPreview(ctx)

    expect(screen.getByLabelText('Categoria de Padaria')).toHaveValue('c-mercado')
    expect(screen.getByLabelText('Categoria de Salário')).toHaveValue('c-sal')
    expect(screen.getByLabelText('Categoria de Mercado do Zé')).toHaveValue('') // a sugestão era de receita: não serve a uma saída
    const options = (label: string) => Array.from(within(screen.getByLabelText(label)).getAllByRole('option')).map((o) => o.textContent)
    expect(options('Categoria de Padaria')).toEqual(['Sem categoria', 'Alimentação', 'Alimentação › Mercado'])
    expect(options('Categoria de Salário')).toEqual(['Sem categoria', 'Salário'])
    expect(screen.queryByLabelText('Categoria de Café')).toBeNull() // linha indisponível: sem categoria
  })

  it('confirmar envia só as marcadas, como vieram da prévia (chave, data, descrição, valor com sinal, categoria se houver) e volta à lista com o aviso', async () => {
    const ctx = open()
    const memory = installTransactions(ctx.api, [], { workspaceId: WID, categories })
    ctx.api.on(CONFIRM, ok({ created: [makeTransaction({ id: 't1' }), makeTransaction({ id: 't2' })], skipped: 1 }, 201))
    await toPreview(ctx)

    await ctx.user.click(screen.getByRole('checkbox', { name: 'Importar Mercado do Zé' })) // desmarca a possível duplicata
    await ctx.user.selectOptions(screen.getByLabelText('Categoria de Padaria'), 'c-alim')
    expect(screen.getByRole('button', { name: 'Importar 2 lançamentos' })).toBeInTheDocument()
    await ctx.user.click(screen.getByRole('button', { name: 'Importar 2 lançamentos' }))

    expect(await screen.findByText('2 lançamentos importados. 1 já existia e foi ignorada.')).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).toBe('/lancamentos')
    expect(ctx.api.callsTo(CONFIRM)[0].body).toEqual({
      lines: [
        { key: 'k1', occurredOn: '2026-11-03', description: 'Padaria', amountCents: -2350, categoryId: 'c-alim' },
        { key: 'k2', occurredOn: '2026-11-05', description: 'Salário', amountCents: 500000, categoryId: 'c-sal' },
      ],
    })
    expect(memory.store).toHaveLength(0) // quem criou os lançamentos foi a API de verdade; aqui só conferimos o pedido
  })

  it('marcar todas as novas e desmarcar todas; sem nenhuma marcada o botão fica indisponível', async () => {
    const ctx = open()
    await toPreview(ctx)

    await ctx.user.click(screen.getByRole('button', { name: 'Desmarcar todas' }))
    expect(screen.getByRole('button', { name: 'Importar 0 lançamentos' })).toBeDisabled()
    await ctx.user.click(screen.getByRole('button', { name: 'Marcar todas as novas' }))
    expect(screen.getByRole('button', { name: 'Importar 3 lançamentos' })).toBeEnabled()
  })

  it('uma recusa da API na confirmação aparece e a seleção fica; o envio duplicado chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const ctx = open()
    ctx.api.on(CONFIRM, async () => (await wait, problem(409, 'Conflito de estado', { detail: 'A categoria está arquivada' })))
    await toPreview(ctx)
    await ctx.user.click(screen.getByRole('checkbox', { name: 'Importar Salário' }))

    await ctx.user.dblClick(screen.getByRole('button', { name: 'Importar 2 lançamentos' }))
    expect(screen.getByRole('button', { name: 'Importando…' })).toBeDisabled()
    await act(async () => release())

    expect(await screen.findByRole('alert')).toHaveTextContent('A categoria está arquivada')
    expect(ctx.api.callsTo(CONFIRM)).toHaveLength(1)
    expect(screen.getByRole('checkbox', { name: 'Importar Salário' })).not.toBeChecked() // o que a pessoa escolheu ficou
    expect(screen.getByRole('button', { name: 'Importar 2 lançamentos' })).toBeEnabled()
  })

  it('"Escolher outro arquivo" volta ao primeiro passo; sem rede avisa com "Tentar de novo"; a prévia passa no axe', async () => {
    const ctx = open()
    await toPreview(ctx)
    await expectNoSeriousViolations(ctx.container)
    await ctx.user.click(screen.getByRole('button', { name: 'Escolher outro arquivo' }))
    expect(await screen.findByLabelText('Arquivo do extrato')).toBeInTheDocument()
    ctx.unmount()

    const offline = open('/contas/a-carteira/importar', 'OWNER', networkError() as never)
    await screen.findByLabelText('Arquivo do extrato')
    await offline.user.upload(fileInput(), csv())
    await offline.user.click(screen.getByRole('button', { name: 'Ler extrato' }))
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })
})
