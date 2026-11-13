import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Category, CategoryTree } from '../api/types'
import { FAILURE_MESSAGES } from '../forms/apiErrors'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { WID, makeCategory, makeCategoryTree, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const mercado = makeCategory({ id: 'c-merc', name: 'Mercado', parentId: 'c-alim', color: '#2F6FDE', icon: null })
const alimentacao: CategoryTree = makeCategoryTree({ id: 'c-alim', name: 'Alimentação', kind: 'EXPENSE', color: '#4fb3b1', icon: 'bowl' }, [mercado])
const lazer: CategoryTree = makeCategoryTree({ id: 'c-lazer', name: 'Lazer', kind: 'EXPENSE', color: '#ff8a78', icon: null })
const salario: CategoryTree = makeCategoryTree({ id: 'c-sal', name: 'Salário', kind: 'INCOME', color: '#e0a93a', icon: null })
const antiga: CategoryTree = makeCategoryTree({ id: 'c-antiga', name: 'Antiga', kind: 'EXPENSE', archived: true })
const tree: CategoryTree[] = [alimentacao, lazer, salario]
const all: Category[] = [alimentacao, mercado, lazer, salario, antiga]

const POST = wsRoute('POST', WID, '/categories')
const put = (id: string) => wsRoute('PUT', WID, `/categories/${id}`)

type Role = 'OWNER' | 'EDITOR' | 'VIEWER'

function open(path: string, { role = 'OWNER' as Role, post = ok(makeCategory({ name: 'Nova' }), 201) as Handler }: { role?: Role; post?: Handler } = {}) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [POST]: post,
    ...Object.fromEntries(all.map((category) => [put(category.id), ok(category)])),
    [wsRoute('GET', WID, '/categories')]: (call) => ok(call.path.includes('includeArchived=true') ? [...tree, antiga] : tree),
  })
  api.onMatch('GET', /\/categories\/([\w-]+)$/, (_call, match) => {
    const found = all.find((category) => category.id === match[1])
    return found ? ok(found) : problem(404, 'Não encontrado')
  })
  return { api, ...renderApp([path]) }
}
type Ctx = ReturnType<typeof open>

// A folha abre e, em seguida, carrega a categoria (ou a mãe): espera o formulário aparecer, não só o título.
const sheet = async (title: string) => {
  const heading = await screen.findByRole('heading', { name: title })
  await screen.findByLabelText('Nome')
  return heading
}
const name = () => screen.getByLabelText('Nome')
const save = () => screen.getByRole('button', { name: /^(Criar categoria|Criar subcategoria|Salvar|Salvando…)$/ })
const icons = () => within(screen.getByRole('radiogroup', { name: 'Ícone' }))
const body = (api: Ctx['api']) => api.callsTo(POST)[0].body as Record<string, unknown>

describe('criar uma categoria na folha', () => {
  it('a moeda ao vivo, o ícone acompanha o nome, a cor escolhida e o envio com a chave do ícone; a lista atualiza com o aviso', async () => {
    const { api, user } = open('/categorias')
    await user.click(await screen.findByRole('link', { name: 'Criar categoria' }))
    await sheet('Nova categoria')
    expect(screen.getByText('Nome da categoria')).toBeInTheDocument()

    await user.type(name(), 'Academia')
    expect(screen.getAllByText('Academia').length).toBeGreaterThan(0) // a pré-visualização acompanha o nome
    expect(icons().getAllByRole('radio', { name: 'Academia' })[0]).toBeChecked() // sugerido pelo nome (haltere)
    await user.click(screen.getByRole('radio', { name: 'Cor 4' }))
    await user.click(save())

    expect(await screen.findByText('Categoria criada.')).toBeInTheDocument()
    expect(body(api)).toEqual({ name: 'Academia', kind: 'EXPENSE', color: '#ff8a78', icon: 'dumbbell' })
    expect(screen.queryByLabelText('Nome')).toBeNull()
    expect(api.callsTo(wsRoute('GET', WID, '/categories'))).toHaveLength(2)
  })

  it('o ícone escolhido vence a sugestão mesmo se o nome mudar; "sem ícone" não envia ícone', async () => {
    const { api, user } = open('/categorias/nova')
    await sheet('Nova categoria')

    await user.type(name(), 'Academia')
    await user.click(icons().getByRole('radio', { name: 'Mercado' })) // o carrinho
    await user.clear(name())
    await user.type(name(), 'Xodó')
    expect(icons().getByRole('radio', { name: 'Mercado' })).toBeChecked()
    await user.click(icons().getByRole('radio', { name: 'Sem ícone, usar a inicial' }))
    await user.click(save())

    await screen.findByText('Categoria criada.')
    expect(body(api)).toEqual({ name: 'Xodó', kind: 'EXPENSE', color: '#4fb3b1' })
  })

  it('o tipo troca as mães oferecidas; escolher uma mãe vira subcategoria: moeda vazada, sem cor e sem tipo no pedido', async () => {
    const { api, user } = open('/categorias/nova')
    await sheet('Nova categoria')
    const parents = () => within(screen.getByRole('radiogroup', { name: 'Categoria mãe' }))

    expect(parents().getAllByRole('radio').map((r) => r.textContent)).toEqual(['Nenhuma', 'Alimentação', 'Lazer'])
    await user.click(screen.getByRole('radio', { name: 'Receita' }))
    expect(parents().getAllByRole('radio').map((r) => r.textContent)).toEqual(['Nenhuma', 'Salário'])
    await user.click(screen.getByRole('radio', { name: 'Despesa' }))

    await user.click(parents().getByRole('radio', { name: 'Lazer' }))
    expect(screen.getByText('Subcategoria de Lazer')).toBeInTheDocument()
    expect(screen.getByText(/Usa a cor de Lazer/)).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Cor 1' })).toBeNull()
    expect(document.querySelector('.category-preview .tx-coin--outline')).not.toBeNull()
    await user.type(name(), 'Viagens')
    await user.click(save())

    expect(await screen.findByText('Subcategoria criada.')).toBeInTheDocument()
    expect(body(api)).toEqual({ name: 'Viagens', parentId: 'c-lazer', icon: 'plane' })
  })

  it('"Adicionar subcategoria" abre a folha já com a mãe, que não se troca', async () => {
    const { api, user } = open('/categorias')
    await user.click(await screen.findByRole('link', { name: 'Adicionar subcategoria em Lazer' }))
    await sheet('Nova subcategoria')

    expect(await screen.findByText('Subcategoria de Lazer')).toBeInTheDocument() // a folha carrega a mãe depois de abrir
    expect(screen.queryByRole('radiogroup', { name: 'Categoria mãe' })).toBeNull()
    await user.type(name(), 'Cinema')
    await user.click(save())

    expect(await screen.findByText('Subcategoria criada.')).toBeInTheDocument()
    expect(body(api)).toMatchObject({ name: 'Cinema', parentId: 'c-lazer', icon: 'ticket' })
    expect(body(api)).not.toHaveProperty('kind')
    expect(body(api)).not.toHaveProperty('color')
  })

  it('nome vazio pede o nome com o foco nele; nome em uso vai ao campo; o envio duplicado chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open('/categorias/nova', { post: async () => (await wait, problem(409, 'Conflito', { detail: 'Já existe uma categoria ativa com este nome neste nível' })) })
    await sheet('Nova categoria')

    await user.click(save())
    expect(await screen.findByText('Informe o nome.')).toBeInTheDocument()
    expect(name()).toHaveFocus()
    expect(api.callsTo(POST)).toHaveLength(0)

    await user.type(name(), 'Alimentação')
    await user.dblClick(save())
    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => release())

    await waitFor(() => expect(name()).toHaveAccessibleDescription('Já existe uma categoria ativa com este nome aqui.'))
    expect(api.callsTo(POST)).toHaveLength(1)
    expect(name()).toHaveValue('Alimentação')
  })

  it('o servidor recusando o ícone aparece no aviso geral; sem rede, avisa com "Tentar de novo"', async () => {
    const first = open('/categorias/nova', { post: problem(400, 'Requisição inválida', { errors: [{ field: 'icon', message: 'deve ser um dos ícones de categoria' }] }) })
    await sheet('Nova categoria')
    await first.user.type(name(), 'Academia')
    await first.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('deve ser um dos ícones de categoria')
    first.unmount()

    const second = open('/categorias/nova', { post: networkError() })
    await sheet('Nova categoria')
    await second.user.type(name(), 'Academia')
    await second.user.click(save())
    expect(await screen.findByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })

  it('o leitor é levado de volta à lista com o aviso, sem formulário', async () => {
    open('/categorias/nova', { role: 'VIEWER' })

    expect(await screen.findByText(/só para consulta/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Nome')).toBeNull()
  })

  it('passa no axe: a folha de criar com a moeda, as cores e os ícones', async () => {
    const { container, user } = open('/categorias/nova')
    await sheet('Nova categoria')
    await user.type(name(), 'Academia')

    await expectNoSeriousViolations(container)
  })
})

describe('editar na folha', () => {
  it('abre preenchida (nome, cor e ícone gravados, tipo fixo) e envia nome, cor e ícone sem o tipo', async () => {
    const { api, user } = open('/categorias')
    await user.click(await screen.findByRole('link', { name: 'Editar Alimentação' }))
    await sheet('Editar categoria')
    await screen.findByLabelText('Nome') // a folha carrega a categoria depois de abrir

    expect(name()).toHaveValue('Alimentação')
    expect(screen.getByRole('radio', { name: 'Cor 1' })).toBeChecked()
    expect(icons().getAllByRole('radio', { name: 'Alimentação' })[0]).toBeChecked() // o ícone gravado (prato), também entre as sugestões
    expect(screen.getByText(/O tipo não pode ser mudado/)).toBeInTheDocument()
    expect(screen.queryByRole('radiogroup', { name: 'Tipo da categoria' })).toBeNull()

    await user.clear(name())
    await user.type(name(), 'Comida')
    await user.click(screen.getByRole('radio', { name: 'Cor 3' }))
    await user.click(save())

    expect(await screen.findByText('Categoria salva.')).toBeInTheDocument()
    const sent = api.callsTo(put('c-alim'))[0].body as Record<string, unknown>
    expect(sent).toEqual({ name: 'Comida', color: '#6fd1a1', icon: 'bowl' })
  })

  it('a subcategoria usa a cor da mãe: sem escolha de cor e sem cor no pedido', async () => {
    const { api, user } = open('/categorias/c-merc')
    await sheet('Editar subcategoria')

    expect(screen.getByText(/Usa a cor de Alimentação/)).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: 'Cor 1' })).toBeNull()
    expect(screen.getByText('Subcategoria de Alimentação')).toBeInTheDocument()
    await user.click(save())

    expect(await screen.findByText('Subcategoria salva.')).toBeInTheDocument()
    expect(api.callsTo(put('c-merc'))[0].body).toEqual({ name: 'Mercado', icon: 'cart' }) // sem ícone gravado: a sugestão do nome
  })

  it('arquivada pede restaurar antes; a que não existe volta à lista com o aviso', async () => {
    const archived = open('/categorias/c-antiga')
    expect(await screen.findByText(/Restaure a categoria antes de editá-la/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Nome')).toBeNull()
    archived.unmount()

    open('/categorias/nao-existe')
    expect(await screen.findByText(FAILURE_MESSAGES.notFound)).toBeInTheDocument()
    expect(screen.getByTestId('location').textContent).toBe('/categorias')
  })
})
