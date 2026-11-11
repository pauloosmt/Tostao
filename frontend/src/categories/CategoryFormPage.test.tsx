import { act, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Category, CategoryTree } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { WID, makeCategory, makeCategoryTree, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const POST = wsRoute('POST', WID, '/categories')
const listAfter = (): CategoryTree[] => [makeCategoryTree({ name: 'Alimentação', kind: 'EXPENSE' }), makeCategoryTree({ name: 'Salário', kind: 'INCOME' })]

function open(
  entry: Parameters<typeof renderApp>[0],
  { role = 'OWNER' as 'OWNER' | 'EDITOR' | 'VIEWER', post = ok(makeCategory({ name: 'Nova' }), 201) as Handler, parent }: { role?: 'OWNER' | 'EDITOR' | 'VIEWER'; post?: Handler; parent?: Handler } = {},
  parentId = 'parent-1',
) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [POST]: post,
    [wsRoute('GET', WID, '/categories')]: ok(listAfter()),
    ...(parent ? { [wsRoute('GET', WID, `/categories/${parentId}`)]: parent } : {}),
  })
  return { api, ...renderApp(entry) }
}

const name = () => screen.getByLabelText('Nome')
const kind = () => screen.getByLabelText('Tipo')
const save = () => screen.getByRole('button', { name: /^(Criar categoria|Criar subcategoria|Salvando…)$/ })
const color = (label: string) => screen.getByRole('radio', { name: label })
const body = (api: ReturnType<typeof open>['api']) => api.callsTo(POST)[0].body
const ready = () => screen.findByRole('heading', { name: 'Criar categoria' })

const parentOf = (extra: Partial<Category> = {}) => makeCategory({ id: 'parent-1', name: 'Alimentação', kind: 'EXPENSE', ...extra })

describe('criar uma categoria principal', () => {
  it('abre com o tipo de despesa escolhido, sem cor, para o dono', async () => {
    open(['/categorias/nova'])
    await ready()

    expect(name()).toHaveValue('')
    expect(kind()).toHaveValue('EXPENSE')
    expect(kind()).toBeEnabled()
    expect(color('Sem cor')).toBeChecked()
    expect(screen.getByRole('link', { name: 'Cancelar' })).toHaveAttribute('href', '/categorias')
  })

  it('já vem no tipo que estava aberto na lista (receita), e pode ser trocado', async () => {
    const { api, user } = open([{ pathname: '/categorias/nova', state: { kind: 'INCOME' } }])
    await ready()
    expect(kind()).toHaveValue('INCOME')

    await user.selectOptions(kind(), 'EXPENSE')
    await user.type(name(), 'Moradia')
    await user.click(save())

    await screen.findByText('Categoria criada.')
    expect(body(api)).toEqual({ name: 'Moradia', kind: 'EXPENSE' })
  })

  it('cria com nome e cor, volta à lista no tipo certo e anuncia o resultado', async () => {
    const { api, user } = open([{ pathname: '/categorias/nova', state: { kind: 'INCOME' } }])
    await ready()

    await user.type(name(), '  Salário  ')
    await user.click(color('Verde'))
    await user.click(save())

    expect(await screen.findByText('Categoria criada.')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Receitas' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Receitas' })).toBeChecked()
    expect(body(api)).toEqual({ name: 'Salário', kind: 'INCOME', color: '#1A9E5C' })
    expect(api.callsTo(POST)).toHaveLength(1)
  })

  it('o editor também cria; sem cor, nenhuma cor é enviada', async () => {
    const { api, user } = open(['/categorias/nova'], { role: 'EDITOR' })
    await ready()

    await user.type(name(), 'Transporte')
    await user.click(save())

    await screen.findByText('Categoria criada.')
    expect(body(api)).toEqual({ name: 'Transporte', kind: 'EXPENSE' })
    expect('color' in (body(api) as object)).toBe(false)
  })

  it('cancelar volta à lista, no tipo em que estava, sem criar nada', async () => {
    const { api, user } = open([{ pathname: '/categorias/nova', state: { kind: 'INCOME' } }])
    await ready()
    await user.type(name(), 'Esquecida')

    await user.click(screen.getByRole('link', { name: 'Cancelar' }))

    expect(await screen.findByRole('list', { name: 'Receitas' })).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(0)
  })
})

describe('as regras no campo, antes de enviar', () => {
  it('nome vazio ou longo: erro no campo, o foco vai ao nome e nada é enviado', async () => {
    const { api, user } = open(['/categorias/nova'])
    await ready()

    await user.click(save())
    expect(name()).toHaveAccessibleDescription('Informe o nome.')
    expect(name()).toHaveFocus()

    await user.click(name())
    await user.paste('a'.repeat(61))
    await user.click(save())
    expect(name()).toHaveAccessibleDescription('Use no máximo 60 caracteres.')
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('sem tipo escolhido, pede o tipo', async () => {
    const { user } = open(['/categorias/nova'])
    await ready()
    await user.type(name(), 'Boa')
    await user.selectOptions(kind(), '')

    await user.click(save())

    expect(kind()).toHaveAccessibleDescription('Escolha o tipo.')
    expect(kind()).toHaveFocus()
  })
})

describe('quando a API recusa', () => {
  async function fill(user: ReturnType<typeof open>['user']) {
    await ready()
    await user.type(name(), 'Alimentação')
    await user.click(color('Azul'))
  }

  it('409 de nome em uso: aparece no campo do nome, os outros valores ficam', async () => {
    const { user } = open(['/categorias/nova'], { post: problem(409, 'Conflito', { detail: 'Já existe uma categoria ativa com este nome neste nível' }) })
    await fill(user)

    await user.click(save())

    await waitFor(() => expect(name()).toHaveAccessibleDescription('Já existe uma categoria ativa com este nome aqui.'))
    expect(name()).toHaveFocus()
    expect(name()).toHaveValue('Alimentação')
    expect(color('Azul')).toBeChecked()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('400 com erro de campo vai ao campo; o que não é da tela (cor) vira o aviso geral', async () => {
    const { user } = open(['/categorias/nova'], {
      post: problem(400, 'Requisição inválida', {
        errors: [
          { field: 'name', message: 'é obrigatório' },
          { field: 'color', message: 'cor inválida' },
        ],
      }),
    })
    await fill(user)

    await user.click(save())

    await waitFor(() => expect(name()).toHaveAccessibleDescription('é obrigatório'))
    expect(screen.getByRole('alert')).toHaveTextContent('cor inválida')
  })

  it('403 mostra a mensagem de permissão e continua no formulário', async () => {
    const { user } = open(['/categorias/nova'], { post: problem(403, 'Proibido') })
    await fill(user)

    await user.click(save())

    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para alterar isto neste workspace.')
    expect(name()).toHaveValue('Alimentação')
  })

  it('servidor fora do ar: avisa com "Tentar de novo", mantém os valores e envia outra vez', async () => {
    const { api, user } = open(['/categorias/nova'], { post: networkError() })
    await fill(user)
    await user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')

    api.on(POST, ok(makeCategory({ name: 'Alimentação' }), 201))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await screen.findByText('Categoria criada.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(2)
  })

  it('o envio duplicado chama a API uma vez só e mostra que está salvando', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, user } = open(['/categorias/nova'], { post: async () => (await wait, ok(makeCategory(), 201)) })
    await fill(user)

    await user.click(save())
    await user.click(save())
    await user.keyboard('{Enter}')

    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => release())
    expect(await screen.findByText('Categoria criada.')).toBeInTheDocument()
    expect(api.callsTo(POST)).toHaveLength(1)
  })
})

describe('criar uma subcategoria', () => {
  const sub = (extra: Parameters<typeof open>[1] = {}) => open(['/categorias/parent-1/subcategorias/nova'], { parent: ok(parentOf()), ...extra })
  const subReady = () => screen.findByRole('heading', { name: 'Nova subcategoria' })

  it('mostra a principal, herda o tipo (visível, não editável, sem ser perguntado) e envia a principal sem o tipo', async () => {
    const { api, user } = sub()
    await subReady()

    expect(screen.getByText('Em Alimentação')).toBeInTheDocument()
    expect(kind()).toHaveValue('EXPENSE')
    expect(kind()).toBeDisabled()
    expect(kind()).toHaveAccessibleDescription('Herdado da categoria principal.')

    await user.type(name(), 'Mercado')
    await user.click(color('Laranja'))
    await user.click(save())

    expect(await screen.findByText('Subcategoria criada.')).toBeInTheDocument()
    expect(body(api)).toEqual({ name: 'Mercado', parentId: 'parent-1', color: '#E8822A' })
    expect('kind' in (body(api) as object)).toBe(false)
    expect(await screen.findByRole('list', { name: 'Despesas' })).toBeInTheDocument()
  })

  it('uma principal de receita cria a subcategoria na lista de receitas', async () => {
    const { user } = open(['/categorias/parent-1/subcategorias/nova'], { parent: ok(parentOf({ name: 'Salário', kind: 'INCOME' })) })
    await subReady()
    expect(kind()).toHaveValue('INCOME')

    await user.type(name(), 'Bônus')
    await user.click(save())

    expect(await screen.findByRole('list', { name: 'Receitas' })).toBeInTheDocument()
  })

  it('a principal que não existe (ou é de outro workspace) é "não encontrada"', async () => {
    sub({ parent: problem(404, 'Não encontrada') })

    expect(await screen.findByRole('heading', { name: 'Categoria não encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar às categorias' })).toHaveAttribute('href', '/categorias')
    expect(screen.queryByLabelText('Nome')).toBeNull()
  })

  it('uma subcategoria não tem subcategorias: o endereço dela é "não encontrada"', async () => {
    sub({ parent: ok(parentOf({ parentId: 'outra' })) })

    expect(await screen.findByRole('heading', { name: 'Categoria não encontrada' })).toBeInTheDocument()
  })

  it('uma principal arquivada pede para restaurar antes, sem formulário', async () => {
    sub({ parent: ok(parentOf({ archived: true })) })

    expect(await screen.findByText('Categoria arquivada')).toBeInTheDocument()
    expect(screen.getByText(/Restaure a categoria principal antes/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Nome')).toBeNull()
  })

  it('falha ao carregar a principal: aviso com "Tentar de novo", que consulta outra vez', async () => {
    const { api, user } = sub({ parent: networkError() })
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar a categoria. Tente de novo.')

    api.on(wsRoute('GET', WID, '/categories/parent-1'), ok(parentOf()))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await subReady()).toBeInTheDocument()
  })

  it('409 de nome em uso vai ao campo; 409 de principal arquivada (corrida) vai ao aviso geral', async () => {
    const used = sub({ post: problem(409, 'Conflito', { detail: 'Já existe uma categoria ativa com este nome neste nível' }) })
    await subReady()
    await used.user.type(name(), 'Mercado')
    await used.user.click(save())
    await waitFor(() => expect(name()).toHaveAccessibleDescription('Já existe uma categoria ativa com este nome aqui.'))
    used.unmount()

    const archived = sub({ post: problem(409, 'Conflito', { detail: 'A categoria principal está arquivada' }) })
    await subReady()
    await archived.user.type(name(), 'Mercado')
    await archived.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('A categoria principal está arquivada. Restaure-a antes.')
    expect(name()).not.toHaveAttribute('aria-invalid')
  })

  it('o leitor vê o aviso de papel, e a principal nem é consultada', async () => {
    const { api } = sub({ role: 'VIEWER' })

    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', WID, '/categories/parent-1'))).toHaveLength(0)
  })
})

describe('o caminho até a criação, os papéis e a acessibilidade', () => {
  it('na lista, cada principal ativa tem "Adicionar subcategoria" (só para quem altera)', async () => {
    const tree = [makeCategoryTree({ name: 'Alimentação', kind: 'EXPENSE' }), makeCategoryTree({ name: 'Antiga', kind: 'EXPENSE', archived: true })]
    const owner = fakeApi({
      'GET /api/v1/me': ok(makeMe('OWNER')),
      [wsRoute('GET', WID, '/categories')]: (call) => ok(call.path.includes('includeArchived=true') ? tree : tree.filter((c) => !c.archived)),
    })
    const view = renderApp(['/categorias'])
    await screen.findByRole('list', { name: 'Despesas' })
    expect(screen.getByRole('link', { name: 'Adicionar subcategoria em Alimentação' })).toHaveAttribute('href', `/categorias/${tree[0].id}/subcategorias/nova`)
    await view.user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Antiga')
    expect(screen.queryByRole('link', { name: 'Adicionar subcategoria em Antiga' })).toBeNull()
    view.unmount()

    owner.on('GET /api/v1/me', ok(makeMe('VIEWER')))
    renderApp(['/categorias'])
    await screen.findByRole('list', { name: 'Despesas' })
    expect(screen.queryByRole('link', { name: /Adicionar subcategoria/ })).toBeNull()
  })

  it('o leitor que abre /categorias/nova vê o aviso de papel, nunca o formulário', async () => {
    const { api } = open(['/categorias/nova'], { role: 'VIEWER' })

    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Nome')).toBeNull()
    expect(api.callsTo(POST)).toHaveLength(0)
  })

  it('passa no axe: criação, subcategoria e com erros', async () => {
    const principal = open(['/categorias/nova'])
    await ready()
    await expectNoSeriousViolations(principal.container)
    await principal.user.click(save())
    await waitFor(() => expect(name()).toHaveAttribute('aria-invalid', 'true'))
    await expectNoSeriousViolations(principal.container)
    principal.unmount()

    const sub = open(['/categorias/parent-1/subcategorias/nova'], { parent: ok(parentOf()) })
    await screen.findByRole('heading', { name: 'Nova subcategoria' })
    await expectNoSeriousViolations(sub.container)
  })
})
