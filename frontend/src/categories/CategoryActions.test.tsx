import { act, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { Category, CategoryTree } from '../api/types'
import { expectNoSeriousViolations } from '../test/axe'
import { fakeApi, networkError, ok, problem, wsRoute, type Handler } from '../test/fakeApi'
import { WID, makeCategory, makeCategoryTree, makeMe } from '../test/fixtures'
import { renderApp } from '../test/renderApp'

interface Options {
  role?: 'OWNER' | 'EDITOR' | 'VIEWER'
  archive?: Handler
  restore?: Handler
}

/** Uma API de mentira com memória, que se comporta como a real: arquivar uma principal arquiva as subcategorias ativas; restaurar só a própria. */
function open(initial: CategoryTree[], { role = 'OWNER', archive, restore }: Options = {}) {
  const store: CategoryTree[] = structuredClone(initial)
  const all = (): Category[] => store.flatMap((c) => [c, ...c.subcategories])
  const byPath = (path: string) => all().find((c) => path.includes(`/categories/${c.id}/`))!
  const api = fakeApi({
    'GET /api/v1/me': ok(makeMe(role)),
    [wsRoute('GET', WID, '/categories')]: (call) => {
      const includeArchived = call.path.includes('includeArchived=true')
      const view = store.map((c) => ({ ...c, subcategories: c.subcategories.filter((s) => includeArchived || !s.archived) }))
      return ok(includeArchived ? view : view.filter((c) => !c.archived))
    },
  })
  for (const category of all()) {
    api.on(
      wsRoute('POST', WID, `/categories/${category.id}/archive`),
      archive ??
        ((call) => {
          const target = byPath(call.path)
          target.archived = true
          if (target.parentId === null) for (const sub of (target as CategoryTree).subcategories) sub.archived = true
          return ok(target)
        }),
    )
    api.on(
      wsRoute('POST', WID, `/categories/${category.id}/restore`),
      restore ??
        ((call) => {
          const target = byPath(call.path)
          target.archived = false
          return ok(target)
        }),
    )
  }
  return { api, store, ...renderApp(['/categorias']) }
}

const sub = (name: string, archived = false) => makeCategory({ name, archived })
const alimentacao = (extra: { archived?: boolean; subs?: Category[] } = {}) =>
  makeCategoryTree({ name: 'Alimentação', kind: 'EXPENSE', archived: extra.archived ?? false }, extra.subs ?? [sub('Mercado'), sub('Restaurante')])
const archivePath = (c: Category) => wsRoute('POST', WID, `/categories/${c.id}/archive`)
const restorePath = (c: Category) => wsRoute('POST', WID, `/categories/${c.id}/restore`)
const listed = () => screen.findByRole('list', { name: 'Despesas' })

describe('arquivar uma categoria principal', () => {
  it('pede confirmação e avisa que as subcategorias ativas também serão arquivadas, com o foco em "Cancelar"', async () => {
    const { user } = open([alimentacao()])
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))

    const dialog = screen.getByRole('dialog', { name: 'Arquivar "Alimentação"?' })
    expect(dialog).toHaveAccessibleDescription(/As 2 subcategorias ativas dela também serão arquivadas/)
    expect(screen.getByRole('button', { name: 'Cancelar' })).toHaveFocus()
    expect(within(dialog).getByRole('button', { name: 'Arquivar' })).toHaveClass('tx-btn--primary')
  })

  it('com uma só subcategoria ativa, fala no singular; sem nenhuma, não menciona subcategorias', async () => {
    const one = open([alimentacao({ subs: [sub('Mercado')] })])
    await listed()
    await one.user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    expect(screen.getByRole('dialog')).toHaveAccessibleDescription(/A subcategoria ativa dela também será arquivada/)
    one.unmount()

    const none = open([alimentacao({ subs: [] })])
    await listed()
    await none.user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    expect(screen.getByRole('dialog')).not.toHaveAccessibleDescription(/subcategoria/)
  })

  it('só conta as subcategorias ativas (as já arquivadas não entram na conta)', async () => {
    const { user } = open([alimentacao({ subs: [sub('Mercado'), sub('Antiga', true), sub('Restaurante')] })])
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))

    expect(screen.getByRole('dialog')).toHaveAccessibleDescription(/As 2 subcategorias ativas/)
  })

  it('cancelar (pelo botão ou por Esc) não chama a API e nada muda', async () => {
    const principal = alimentacao()
    const { api, user } = open([principal])
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    act(() => (screen.getByRole('dialog') as HTMLDialogElement).close())

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.callsTo(archivePath(principal))).toHaveLength(0)
    expect(screen.getByText('Alimentação')).toBeInTheDocument()
  })

  it('confirmar arquiva a principal e as subcategorias: todas saem da lista, e o resultado é anunciado', async () => {
    const principal = alimentacao()
    const { api, user } = open([principal, makeCategoryTree({ name: 'Transporte', kind: 'EXPENSE' })])
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByText('Categoria arquivada.')).toBeInTheDocument()
    await listed()
    expect(screen.queryByText('Alimentação')).toBeNull()
    expect(screen.queryByText('Mercado')).toBeNull()
    expect(screen.getByText('Transporte')).toBeInTheDocument()
    expect(api.callsTo(archivePath(principal))).toHaveLength(1)
  })

  it('com as arquivadas à vista, aparecem marcadas: a principal e as subcategorias', async () => {
    const { user } = open([alimentacao()])
    await listed()
    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Alimentação')

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    await screen.findByText('Categoria arquivada.')
    expect(await screen.findAllByText('Arquivada')).toHaveLength(3)
    expect(screen.getByRole('button', { name: 'Restaurar Alimentação' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restaurar Mercado' })).toBeInTheDocument()
  })
})

describe('arquivar uma subcategoria', () => {
  it('a confirmação é simples e só ela sai da lista', async () => {
    const mercado = sub('Mercado')
    const { api, user } = open([alimentacao({ subs: [mercado, sub('Restaurante')] })])
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Mercado' }))
    expect(screen.getByRole('dialog', { name: 'Arquivar "Mercado"?' })).toHaveAccessibleDescription('A subcategoria sai da lista. Você pode restaurá-la quando quiser.')
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByText('Subcategoria arquivada.')).toBeInTheDocument()
    await listed()
    expect(screen.queryByText('Mercado')).toBeNull()
    expect(screen.getByText('Restaurante')).toBeInTheDocument()
    expect(screen.getByText('Alimentação')).toBeInTheDocument()
    expect(api.callsTo(archivePath(mercado))).toHaveLength(1)
  })
})

describe('quando arquivar falha', () => {
  it.each([
    ['falha de rede', networkError(), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['erro do servidor', problem(500, 'Erro'), 'Não foi possível falar com o servidor. Tente de novo.'],
    ['sem permissão', problem(403, 'Proibido'), 'Você não tem permissão para alterar isto neste workspace.'],
  ])('%s: a categoria continua como estava e a pessoa é avisada', async (_name, failure, message) => {
    const { user } = open([alimentacao()], { archive: failure })
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByText('Alimentação')).toBeInTheDocument()
    expect(screen.queryByText('Categoria arquivada.')).toBeNull()
  })

  it('404 (já não existe): avisa e a lista é recarregada', async () => {
    const { api, user } = open([alimentacao()], { archive: problem(404, 'Não encontrada') })
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não encontrada. A lista foi atualizada.')
    await listed()
    expect(api.callsTo(wsRoute('GET', WID, '/categories')).length).toBeGreaterThanOrEqual(2)
  })

  it('o envio duplicado (confirmar duas vezes) chama a API uma vez só', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    const principal = alimentacao()
    const { api, user } = open([principal], { archive: async () => (await wait, ok({ ...principal, archived: true })) })
    await listed()

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))
    expect(screen.getByRole('button', { name: 'Cancelar' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Arquivar' }))

    await act(async () => release())
    expect(await screen.findByText('Categoria arquivada.')).toBeInTheDocument()
    expect(api.callsTo(archivePath(principal))).toHaveLength(1)
  })
})

describe('restaurar', () => {
  // (com a principal arquivada, a lista padrão fica vazia: espera-se pela caixa, que sempre existe)
  async function showArchived(user: ReturnType<typeof open>['user']) {
    await user.click(await screen.findByRole('checkbox', { name: 'Mostrar arquivadas' }))
  }

  it('restaurar uma principal é direto e diz que as subcategorias não voltam junto', async () => {
    const principal = alimentacao({ archived: true, subs: [sub('Mercado', true), sub('Restaurante', true)] })
    const { api, user } = open([principal])
    await showArchived(user)
    await screen.findByText('Alimentação')

    await user.click(screen.getByRole('button', { name: 'Restaurar Alimentação' }))

    expect(await screen.findByText('Categoria restaurada. As subcategorias dela continuam arquivadas; restaure cada uma à parte.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.callsTo(restorePath(principal))).toHaveLength(1)
    // a principal voltou; as subcategorias seguem arquivadas, cada uma com o seu "Restaurar"
    expect(await screen.findByRole('button', { name: 'Arquivar Alimentação' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restaurar Mercado' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restaurar Restaurante' })).toBeInTheDocument()
  })

  it('restaurar uma principal sem subcategorias arquivadas diz só que foi restaurada', async () => {
    const principal = alimentacao({ archived: true, subs: [] })
    const { user } = open([principal])
    await showArchived(user)
    await screen.findByText('Alimentação')

    await user.click(screen.getByRole('button', { name: 'Restaurar Alimentação' }))

    expect(await screen.findByText('Categoria restaurada.')).toBeInTheDocument()
  })

  it('restaurar uma subcategoria é direto', async () => {
    const mercado = sub('Mercado', true)
    const { user } = open([alimentacao({ subs: [mercado] })])
    await showArchived(user)
    await screen.findByText('Mercado')

    await user.click(screen.getByRole('button', { name: 'Restaurar Mercado' }))

    expect(await screen.findByText('Subcategoria restaurada.')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Arquivar Mercado' })).toBeInTheDocument()
  })

  it('409: subcategoria com a principal arquivada pede restaurar a principal antes', async () => {
    const { user } = open([alimentacao({ archived: true, subs: [sub('Mercado', true)] })], { restore: problem(409, 'Conflito', { detail: 'Restaure antes a categoria principal' }) })
    await showArchived(user)
    await screen.findByText('Mercado')

    await user.click(screen.getByRole('button', { name: 'Restaurar Mercado' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Restaure antes a categoria principal.')
    expect(screen.getByRole('button', { name: 'Restaurar Mercado' })).toBeEnabled()
  })

  it('409: o nome já tomado por uma categoria ativa é explicado e a categoria continua arquivada', async () => {
    const { user } = open([alimentacao({ archived: true, subs: [] })], { restore: problem(409, 'Conflito', { detail: 'Já existe uma categoria ativa com este nome neste nível' }) })
    await showArchived(user)
    await screen.findByText('Alimentação')

    await user.click(screen.getByRole('button', { name: 'Restaurar Alimentação' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe uma categoria ativa com este nome aqui.')
    expect(screen.getByRole('button', { name: 'Restaurar Alimentação' })).toBeInTheDocument()
  })

  it('falha de rede ao restaurar: avisa e nada muda', async () => {
    const { user } = open([alimentacao({ archived: true, subs: [] })], { restore: networkError() })
    await showArchived(user)
    await screen.findByText('Alimentação')

    await user.click(screen.getByRole('button', { name: 'Restaurar Alimentação' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível falar com o servidor.')
    expect(screen.getByRole('button', { name: 'Restaurar Alimentação' })).toBeInTheDocument()
  })
})

describe('quem só consulta e a acessibilidade', () => {
  it('o leitor não vê "Arquivar", "Restaurar", "Editar" nem "Adicionar subcategoria"', async () => {
    const { user } = open([alimentacao(), alimentacao({ archived: true, subs: [sub('Velha', true)] })], { role: 'VIEWER' })
    await listed()
    await user.click(screen.getByRole('checkbox', { name: 'Mostrar arquivadas' }))
    await screen.findAllByText('Alimentação')

    expect(screen.queryByRole('button', { name: /^(Arquivar|Restaurar)/ })).toBeNull()
    expect(screen.queryByRole('link', { name: /^(Editar|Adicionar subcategoria)/ })).toBeNull()
  })

  it('a lista com as ações e a confirmação aberta passam no axe', async () => {
    const { container, user } = open([alimentacao()])
    await listed()
    await expectNoSeriousViolations(container)

    await user.click(screen.getByRole('button', { name: 'Arquivar Alimentação' }))
    await expectNoSeriousViolations(container)
  })
})
