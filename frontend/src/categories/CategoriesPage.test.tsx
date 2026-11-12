import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { CategoryTree } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, wsRoute } from '../test/fakeApi'
import { WID, makeCategory, makeCategoryTree, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

const alimentacao = () =>
  makeCategoryTree({ name: 'Alimentação', kind: 'EXPENSE', color: '#1A9E5C' }, [
    makeCategory({ name: 'Mercado', color: '#2F6FDE' }),
    makeCategory({ name: 'Restaurante' }),
  ])
const transporte = () => makeCategoryTree({ name: 'Transporte', kind: 'EXPENSE' })
const salario = () => makeCategoryTree({ name: 'Salário', kind: 'INCOME', color: '#E0B100' }, [makeCategory({ name: 'Bônus' })])
const antiga = () => makeCategoryTree({ name: 'Antiga', kind: 'EXPENSE', archived: true })

function open(tree: CategoryTree[], role: 'OWNER' | 'EDITOR' | 'VIEWER' = 'OWNER', entry: Parameters<typeof renderApp>[0] = ['/categorias']) {
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/categories')]: (call) => ok(call.path.includes('includeArchived=true') ? tree : tree.filter((c) => !c.archived)),
  })
  return { api, ...renderApp(entry) }
}
// (o jsdom devolve a cor no formato rgb(...))
const rgb = (hex: string) => `rgb(${[1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)).join(', ')})`
const tree = () => screen.findByRole('list', { name: /^(Despesas|Receitas)$/ })

describe('a árvore de categorias', () => {
  it('mostra Despesas por padrão, com as principais e as subcategorias sob cada uma', async () => {
    open([alimentacao(), transporte(), salario()])

    const list = await tree()

    expect(list).toHaveAccessibleName('Despesas')
    expect(within(list).getByText('Alimentação')).toBeInTheDocument()
    expect(within(list).getByText('Transporte')).toBeInTheDocument()
    expect(within(list).queryByText('Salário')).toBeNull()
    const subs = within(list).getByRole('list', { name: 'Subcategorias de Alimentação' })
    expect(within(subs).getAllByRole('listitem').map((li) => li.textContent)).toEqual([expect.stringContaining('Mercado'), expect.stringContaining('Restaurante')])
  })

  it('alternar para Receitas mostra só as de receita (sem nova chamada à API)', async () => {
    const { api, user } = open([alimentacao(), salario()])
    await tree()
    expect(screen.getByRole('radio', { name: 'Despesas' })).toBeChecked()

    await user.click(screen.getByRole('radio', { name: 'Receitas' }))

    const list = await screen.findByRole('list', { name: 'Receitas' })
    expect(within(list).getByText('Salário')).toBeInTheDocument()
    expect(within(list).getByText('Bônus')).toBeInTheDocument()
    expect(screen.queryByText('Alimentação')).toBeNull()
    expect(api.callsTo(wsRoute('GET', WID, '/categories'))).toHaveLength(1)
  })

  it('a principal tem a moeda cheia e cada subcategoria o chip com a moeda vazada na cor da mãe; o nome da cor vai para o leitor de tela', async () => {
    const { container } = open([alimentacao()])
    await tree()

    const coins = container.querySelectorAll<HTMLElement>('.tx-coin')
    expect(coins).toHaveLength(3) // Alimentação (cheia) e as duas subcategorias (vazadas)
    expect(coins[0].style.background).toBe(rgb('#1A9E5C'))
    expect(coins[0]).toHaveAttribute('aria-hidden', 'true')
    expect(coins[0].querySelector('svg')).not.toBeNull() // "Alimentação" sugere o ícone pelo nome
    for (const coin of [coins[1], coins[2]]) {
      expect(coin).toHaveClass('tx-coin--outline')
      expect(coin.style.borderColor).toBe(rgb('#1A9E5C')) // a cor da mãe, mesmo a Mercado tendo outra
    }
    expect(container.querySelectorAll('.tx-subchip')).toHaveLength(2)
    expect(screen.getAllByText(/Cor Verde/).length).toBeGreaterThan(0)
  })

  it('uma cor que não é da paleta aparece na moeda, sem inventar nome', async () => {
    const { container } = open([makeCategoryTree({ name: 'Outra', kind: 'EXPENSE', color: '#123456' })])
    await tree()

    expect((container.querySelector('.tx-coin') as HTMLElement).style.background).toBe(rgb('#123456'))
    expect(screen.queryByText(/^Cor /)).toBeNull()
  })

  it('o tipo aberto vem do estado do roteador (ao voltar de criar uma receita, abre em Receitas) e o aviso aparece', async () => {
    open([alimentacao(), salario()], 'OWNER', [{ pathname: '/categorias', state: { notice: 'Categoria criada.', kind: 'INCOME' } }])

    expect(await screen.findByRole('list', { name: 'Receitas' })).toBeInTheDocument()
    expect(screen.getByText('Categoria criada.')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Receitas' })).toBeChecked()
  })
})

describe('as categorias arquivadas', () => {
  it('só aparecem com "Mostrar arquivadas", marcadas; ao desligar, somem', async () => {
    const { api, user } = open([alimentacao(), antiga()])
    await tree()
    expect(screen.queryByText('Antiga')).toBeNull()

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))

    const row = (await screen.findByText('Antiga')).closest('.category-name') as HTMLElement
    expect(within(row).getByText('Arquivada')).toBeInTheDocument()
    expect(api.callsTo(wsRoute('GET', WID, '/categories')).map((c) => c.path.includes('includeArchived=true'))).toEqual([false, true])

    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await tree()
    expect(screen.queryByText('Antiga')).toBeNull()
  })
})

describe('os estados da tela', () => {
  it('carrega com "Carregando categorias…"', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const { api } = open([alimentacao()])
    api.on(wsRoute('GET', WID, '/categories'), async () => (await wait, ok([alimentacao()])))

    expect(await screen.findByText('Carregando categorias…')).toBeInTheDocument()
    release()
    await tree()
    expect(screen.queryByText('Carregando categorias…')).toBeNull()
  })

  it('sem categorias do tipo, explica o que fazer; o dono vê "Criar categoria" (que leva o tipo aberto)', async () => {
    const { user } = open([alimentacao()])
    await tree()

    await user.click(screen.getByRole('radio', { name: 'Receitas' }))

    expect(await screen.findByText('Nenhuma categoria de receita ainda')).toBeInTheDocument()
    expect(screen.getByText(/Crie a primeira categoria de receita/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Criar categoria' })).toHaveAttribute('href', '/categorias/nova')
  })

  it('sem nenhuma categoria, o leitor vê a explicação e nenhuma ação', async () => {
    open([], 'VIEWER')

    expect(await screen.findByText('Nenhuma categoria de despesa ainda')).toBeInTheDocument()
    expect(screen.getByText(/Quem pode editar este workspace cria as categorias/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Criar categoria' })).toBeNull()
  })

  it('o erro de carga mostra o aviso com "Tentar de novo", que consulta outra vez', async () => {
    const { api, user } = open([alimentacao()])
    api.on(wsRoute('GET', WID, '/categories'), networkError())
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as categorias. Tente de novo.')

    api.on(wsRoute('GET', WID, '/categories'), ok([alimentacao()]))
    await user.click(screen.getByRole('button', { name: 'Tentar de novo' }))

    expect(await tree()).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('quem pode o quê, navegação e acessibilidade', () => {
  it('dono e editor veem "Criar categoria"; o leitor, não', async () => {
    for (const role of ['OWNER', 'EDITOR'] as const) {
      const view = open([alimentacao()], role)
      await tree()
      expect(screen.getByRole('link', { name: 'Criar categoria' })).toBeInTheDocument()
      view.unmount()
    }
    open([alimentacao()], 'VIEWER')
    await tree()
    expect(screen.queryByRole('link', { name: 'Criar categoria' })).toBeNull()
  })

  it('"Categorias" está na navegação e é o item atual', async () => {
    open([alimentacao()])
    await tree()

    expect(screen.getByRole('link', { name: 'Categorias' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Contas' })).not.toHaveAttribute('aria-current')
  })

  it('passa no axe, com a árvore, vazia e com erro', async () => {
    const view = open([alimentacao(), antiga()])
    await tree()
    await expectNoSeriousViolations(view.container)
    view.unmount()

    const empty = open([])
    await screen.findByText('Nenhuma categoria de despesa ainda')
    await expectNoSeriousViolations(empty.container)
    empty.unmount()

    const failing = open([alimentacao()])
    failing.api.on(wsRoute('GET', WID, '/categories'), networkError())
    await screen.findByRole('alert')
    await expectNoSeriousViolations(failing.container)
  })
})
