import { act, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Category } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { WID, makeCategory, makeCategoryTree, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const mercado = makeCategory({ id: 'cat-mercado', name: 'Mercado', kind: 'EXPENSE', color: '#2F6FDE', icon: 'cart', parentId: 'cat-alim' })
const alimentacao = makeCategory({ id: 'cat-alim', name: 'Alimentação', kind: 'EXPENSE', color: '#1A9E5C', icon: null })

function open(category: Category, { role = 'OWNER' as 'OWNER' | 'EDITOR' | 'VIEWER', get = ok(category) as Handler, put = ok(category) as Handler } = {}) {
  const PUT = wsRoute('PUT', WID, `/categories/${category.id}`)
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, `/categories/${category.id}`)]: get,
    [PUT]: put,
    [wsRoute('GET', WID, '/categories')]: ok([makeCategoryTree({ name: 'Alimentação', kind: category.kind })]),
  })
  return { api, PUT, ...renderApp([`/categorias/${category.id}`]) }
}

const name = () => screen.getByLabelText('Nome')
const kind = () => screen.getByLabelText('Tipo')
const save = () => screen.getByRole('button', { name: /^(Salvar|Salvando…)$/ })
const color = (label: string) => screen.getByRole('radio', { name: label })
const ready = (title = 'Editar categoria') => screen.findByRole('heading', { name: title })

describe('o caminho até a edição', () => {
  const tree = [makeCategoryTree({ name: 'Alimentação', kind: 'EXPENSE' }, [makeCategory({ name: 'Mercado' })]), makeCategoryTree({ name: 'Antiga', kind: 'EXPENSE', archived: true })]
  const openList = (role: 'OWNER' | 'EDITOR' | 'VIEWER') => {
    fakeApi({
      'GET /api/v1/me': ok(makeMe(role)),
      [wsRoute('GET', WID, '/categories')]: (call) => ok(call.path.includes('includeArchived=true') ? tree : tree.filter((c) => !c.archived)),
    })
    return renderApp(['/categorias'])
  }

  it('dono e editor veem "Editar" na principal e na subcategoria ativas; o leitor, não', async () => {
    for (const role of ['OWNER', 'EDITOR'] as const) {
      const view = openList(role)
      await screen.findByRole('list', { name: 'Despesas' })
      expect(screen.getByRole('link', { name: 'Editar Alimentação' })).toHaveAttribute('href', `/categorias/${tree[0].id}`)
      expect(screen.getByRole('link', { name: 'Editar Mercado' })).toHaveAttribute('href', `/categorias/${tree[0].subcategories[0].id}`)
      view.unmount()
    }
    openList('VIEWER')
    await screen.findByRole('list', { name: 'Despesas' })
    expect(screen.queryByRole('link', { name: /^Editar / })).toBeNull()
  })

  it('uma categoria arquivada não tem "Editar"', async () => {
    const { user } = openList('OWNER')
    await screen.findByRole('list', { name: 'Despesas' })

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))

    expect(await screen.findByText('Antiga')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Editar Antiga' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Editar Alimentação' })).toBeInTheDocument()
  })
})

describe('editar uma categoria', () => {
  it('abre preenchida: nome, tipo visível e não editável, e a cor atual marcada', async () => {
    open(alimentacao)
    await ready()

    expect(name()).toHaveValue('Alimentação')
    expect(kind()).toHaveValue('EXPENSE')
    expect(kind()).toBeDisabled()
    expect(kind()).toHaveAccessibleDescription('O tipo não pode ser mudado depois de criada.')
    expect(color('Verde')).toBeChecked()
  })

  it('uma subcategoria abre como "Editar subcategoria"', async () => {
    open(mercado)

    expect(await ready('Editar subcategoria')).toBeInTheDocument()
    expect(color('Azul')).toBeChecked()
  })

  it('salvar envia só nome, cor e o ícone atual (nunca o tipo nem a principal) e volta à lista com o aviso', async () => {
    const { api, PUT, user } = open(mercado)
    await ready('Editar subcategoria')

    await user.clear(name())
    await user.type(name(), '  Supermercado  ')
    await user.click(color('Laranja'))
    await user.click(save())

    expect(await screen.findByText('Subcategoria salva.')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Despesas' })).toBeInTheDocument()
    const sent = api.callsTo(PUT)[0].body as Record<string, unknown>
    expect(sent).toEqual({ name: 'Supermercado', color: '#E8822A', icon: 'cart' })
    expect('kind' in sent).toBe(false)
    expect('parentId' in sent).toBe(false)
  })

  it('o ícone existente é reenviado mesmo quando só o nome muda; sem ícone, nenhum é enviado', async () => {
    const withIcon = open(mercado)
    await ready('Editar subcategoria')
    await withIcon.user.type(name(), ' 2')
    await withIcon.user.click(save())
    await screen.findByText('Subcategoria salva.')
    expect(withIcon.api.callsTo(withIcon.PUT)[0].body).toMatchObject({ icon: 'cart' })
    withIcon.unmount()

    const noIcon = open(alimentacao)
    await ready()
    await noIcon.user.type(name(), ' 2')
    await noIcon.user.click(save())
    await screen.findByText('Categoria salva.')
    expect('icon' in (noIcon.api.callsTo(noIcon.PUT)[0].body as object)).toBe(false)
  })

  it('uma cor gravada fora da paleta aparece como "Cor atual" e volta como está se a pessoa não mexer', async () => {
    const odd = makeCategory({ id: 'cat-odd', name: 'Estranha', color: '#123456' })
    const { api, PUT, user } = open(odd)
    await ready()
    expect(color('Cor atual')).toBeChecked()

    await user.click(save())

    await screen.findByText('Categoria salva.')
    expect((api.callsTo(PUT)[0].body as { color: string }).color).toBe('#123456')
  })

  it('"Sem cor" tira a cor do corpo; escolher outra a troca', async () => {
    const { api, PUT, user } = open(alimentacao)
    await ready()

    await user.click(color('Sem cor'))
    await user.click(save())

    await screen.findByText('Categoria salva.')
    expect('color' in (api.callsTo(PUT)[0].body as object)).toBe(false)
  })

  it('as mesmas regras do cadastro valem: nome vazio ou longo, foco no nome, nada enviado', async () => {
    const { api, PUT, user } = open(alimentacao)
    await ready()
    await user.clear(name())

    await user.click(save())
    expect(name()).toHaveAccessibleDescription('Informe o nome.')
    expect(name()).toHaveFocus()

    await user.type(name(), 'a'.repeat(61))
    await user.click(save())
    expect(name()).toHaveAccessibleDescription('Use no máximo 60 caracteres.')
    expect(api.callsTo(PUT)).toHaveLength(0)
  })

  it('409 de nome em uso vai ao campo; 409 de categoria arquivada (corrida) vai ao aviso geral', async () => {
    const used = open(alimentacao, { put: problem(409, 'Conflito', { detail: 'Já existe uma categoria ativa com este nome neste nível' }) })
    await ready()
    await used.user.click(save())
    await waitFor(() => expect(name()).toHaveAccessibleDescription('Já existe uma categoria ativa com este nome aqui.'))
    used.unmount()

    const archived = open(alimentacao, { put: problem(409, 'Conflito', { detail: 'A categoria está arquivada; restaure-a antes de editar' }) })
    await ready()
    await archived.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('A categoria está arquivada. Restaure-a antes de editar.')
  })

  it('403 e falha de rede ao salvar avisam e mantêm o formulário', async () => {
    const forbidden = open(alimentacao, { put: problem(403, 'Proibido') })
    await ready()
    await forbidden.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para alterar isto neste workspace.')
    forbidden.unmount()

    const offline = open(alimentacao, { put: networkError() })
    await ready()
    await offline.user.click(save())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor. Tente de novo.')
    expect(screen.getByRole('button', { name: 'Tentar de novo' })).toBeInTheDocument()
  })

  it('404 ao salvar (a categoria deixou de existir): volta à lista atualizada, com o aviso', async () => {
    const { user } = open(alimentacao, { put: problem(404, 'Não encontrada') })
    await ready()

    await user.click(save())

    expect(await screen.findByText('Não encontrada. A lista foi atualizada.')).toBeInTheDocument()
    expect(await screen.findByRole('list', { name: 'Despesas' })).toBeInTheDocument()
  })

  it('o envio duplicado chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api, PUT, user } = open(alimentacao, { put: async () => (await wait, ok(alimentacao)) })
    await ready()

    await user.click(save())
    await user.click(save())

    expect(screen.getByRole('button', { name: 'Salvando…' })).toBeDisabled()
    await act(async () => release())
    expect(await screen.findByText('Categoria salva.')).toBeInTheDocument()
    expect(api.callsTo(PUT)).toHaveLength(1)
  })
})

describe('os outros casos de /categorias/:id', () => {
  it('uma categoria arquivada pede para restaurar antes, sem formulário', async () => {
    open({ ...alimentacao, archived: true })

    expect(await screen.findByText('Categoria arquivada')).toBeInTheDocument()
    expect(screen.getByText(/Restaure a categoria antes de editá-la/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Nome')).toBeNull()
  })

  it('uma categoria que não existe (ou é de outro workspace) é "não encontrada"', async () => {
    open(alimentacao, { get: problem(404, 'Não encontrada') })

    expect(await screen.findByRole('heading', { name: 'Categoria não encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Voltar às categorias' })).toHaveAttribute('href', '/categorias')
  })

  it('falha ao carregar: aviso com "Tentar de novo", que consulta outra vez', async () => {
    const { api, user } = open(alimentacao, { get: networkError() })
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar a categoria. Tente de novo.')

    api.on(wsRoute('GET', WID, `/categories/${alimentacao.id}`), ok(alimentacao))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await ready()).toBeInTheDocument()
  })

  it('o leitor vê o aviso de papel, e a categoria nem é consultada', async () => {
    const { api } = open(alimentacao, { role: 'VIEWER' })

    expect(await screen.findByRole('heading', { name: 'Somente consulta' })).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', WID, `/categories/${alimentacao.id}`))).toHaveLength(0)
  })

  it('passa no axe (formulário e categoria arquivada)', async () => {
    const form = open(mercado)
    await ready('Editar subcategoria')
    await expectNoSeriousViolations(form.container)
    form.unmount()

    const archived = open({ ...alimentacao, archived: true })
    await screen.findByText('Categoria arquivada')
    await expectNoSeriousViolations(archived.container)
  })
})
