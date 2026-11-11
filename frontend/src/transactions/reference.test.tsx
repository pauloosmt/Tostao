import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { fakeApi, networkError, ok, wsRoute } from '../test/fakeApi'
import { makeAccount, makeCategory, makeCategoryTree } from '../test/fixtures'
import { buildReference, useReferenceData } from './reference'

const carteira = makeAccount({ id: 'a1', name: 'Carteira' })
const antiga = makeAccount({ id: 'a2', name: 'Antiga', archived: true })
const alimentacao = makeCategoryTree({ id: 'p1', name: 'Alimentação', kind: 'EXPENSE' }, [
  makeCategory({ id: 's1', name: 'Mercado' }),
  makeCategory({ id: 's2', name: 'Restaurante', archived: true }),
])
const salario = makeCategoryTree({ id: 'p2', name: 'Salário', kind: 'INCOME' })
const velha = makeCategoryTree({ id: 'p3', name: 'Velha', kind: 'EXPENSE', archived: true }, [makeCategory({ id: 's3', name: 'Sub velha', archived: true })])
// (makeCategoryTree gera os próprios ids; os testes usam os ids reais abaixo)
const principal = alimentacao
const mercado = alimentacao.subcategories[0]
const restaurante = alimentacao.subcategories[1]
const ref = buildReference([carteira, antiga], [principal, salario, velha])

describe('os nomes', () => {
  it('conta: o nome, com "(arquivada)" quando é; uma que não existe mais vira "Conta removida"', () => {
    expect(ref.names.accountName('a1')).toBe('Carteira')
    expect(ref.names.accountName('a2')).toBe('Antiga (arquivada)')
    expect(ref.names.accountName('sumiu')).toBe('Conta removida')
  })

  it('categoria: a principal pelo nome; a subcategoria como "Principal › Sub"; arquivadas marcadas; desconhecida é nula', () => {
    expect(ref.names.categoryName(principal.id)).toBe('Alimentação')
    expect(ref.names.categoryName(mercado.id)).toBe('Alimentação › Mercado')
    expect(ref.names.categoryName(restaurante.id)).toBe('Alimentação › Restaurante (arquivada)')
    expect(ref.names.categoryName(velha.id)).toBe('Velha (arquivada)')
    expect(ref.names.categoryName('sumiu')).toBeNull()
  })
})

describe('as opções de conta', () => {
  it('só as ativas', () => {
    expect(ref.accountOptions()).toEqual([{ value: 'a1', label: 'Carteira' }])
  })

  it('a conta arquivada que o lançamento já usa continua oferecida, marcada', () => {
    expect(ref.accountOptions('a2')).toEqual([{ value: 'a1', label: 'Carteira' }, { value: 'a2', label: 'Antiga (arquivada)' }])
  })
})

describe('as opções de categoria', () => {
  it('só as ativas do tipo, a principal seguida das subcategorias como "Principal › Sub"', () => {
    expect(ref.categoryOptions('EXPENSE')).toEqual([
      { value: principal.id, label: 'Alimentação' },
      { value: mercado.id, label: 'Alimentação › Mercado' },
    ])
    expect(ref.categoryOptions('INCOME')).toEqual([{ value: salario.id, label: 'Salário' }])
  })

  it('a categoria arquivada que o lançamento já usa continua oferecida, marcada', () => {
    const options = ref.categoryOptions('EXPENSE', restaurante.id)

    expect(options.map((o) => o.label)).toEqual(['Alimentação', 'Alimentação › Mercado', 'Alimentação › Restaurante (arquivada)'])
    expect(ref.categoryOptions('EXPENSE', velha.id).map((o) => o.label)).toContain('Velha (arquivada)')
  })

  it('um tipo sem categorias não tem opções', () => {
    expect(buildReference([], []).categoryOptions('INCOME')).toEqual([])
  })
})

describe('as opções de filtro (incluem as arquivadas)', () => {
  it('todas as contas, as arquivadas marcadas', () => {
    expect(ref.accountFilterOptions()).toEqual([{ value: 'a1', label: 'Carteira' }, { value: 'a2', label: 'Antiga (arquivada)' }])
  })

  it('todas as categorias e subcategorias, com o tipo no rótulo (o mesmo nome de despesa e de receita não se confunde)', () => {
    const labels = buildReference([], [principal, salario, velha, makeCategoryTree({ id: 'p9', name: 'Outros', kind: 'INCOME' }), makeCategoryTree({ id: 'p8', name: 'Outros', kind: 'EXPENSE' })]).categoryFilterOptions().map((o) => o.label)

    expect(labels).toEqual([
      'Despesa · Alimentação',
      'Despesa · Alimentação › Mercado',
      'Despesa · Alimentação › Restaurante (arquivada)',
      'Receita · Salário',
      'Despesa · Velha (arquivada)',
      'Despesa · Velha › Sub velha (arquivada)',
      'Receita · Outros',
      'Despesa · Outros',
    ])
  })
})

describe('categoryFits: serve ao tipo e está ativa', () => {
  it('confere o tipo e o estado', () => {
    expect(ref.categoryFits(principal.id, 'EXPENSE')).toBe(true)
    expect(ref.categoryFits(principal.id, 'INCOME')).toBe(false)
    expect(ref.categoryFits(mercado.id, 'EXPENSE')).toBe(true)
    expect(ref.categoryFits(restaurante.id, 'EXPENSE')).toBe(false) // arquivada
    expect(ref.categoryFits(velha.id, 'EXPENSE')).toBe(false)
    expect(ref.categoryFits('sumiu', 'EXPENSE')).toBe(false)
  })
})

function Probe({ workspaceId }: { workspaceId: string }) {
  const reference = useReferenceData(workspaceId)
  return (
    <div>
      <p data-testid="status">{reference.status}</p>
      {reference.status === 'ready' ? <p data-testid="name">{reference.names.accountName('a1')}</p> : null}
      {reference.status !== 'loading' ? (
        <button type="button" onClick={reference.reload}>
          de novo
        </button>
      ) : null}
    </div>
  )
}

describe('useReferenceData', () => {
  it('carrega as contas e as categorias COM as arquivadas, do workspace dado', async () => {
    const api = fakeApi({
      [wsRoute('GET', 'w1', '/accounts')]: ok([carteira]),
      [wsRoute('GET', 'w1', '/categories')]: ok([principal]),
    })

    render(<Probe workspaceId="w1" />)
    expect(screen.getByTestId('status')).toHaveTextContent('loading')

    expect(await screen.findByTestId('name')).toHaveTextContent('Carteira')
    expect(api.calls.map((c) => c.path)).toEqual(['/api/v1/workspaces/w1/accounts?includeArchived=true', '/api/v1/workspaces/w1/categories?includeArchived=true'])
  })

  it('uma falha de qualquer das duas é erro, e "de novo" consulta outra vez', async () => {
    const user = userEvent.setup()
    const api = fakeApi({
      [wsRoute('GET', 'w1', '/accounts')]: networkError(),
      [wsRoute('GET', 'w1', '/categories')]: ok([]),
    })
    render(<Probe workspaceId="w1" />)
    await screen.findByText('error')

    api.on(wsRoute('GET', 'w1', '/accounts'), ok([carteira]))
    await user.click(screen.getByRole('button', { name: 'de novo' }))

    expect(await screen.findByTestId('name')).toHaveTextContent('Carteira')
  })

  it('trocar de workspace volta a "carregando" e mostra só os dados do novo; a resposta atrasada do anterior é descartada', async () => {
    let release!: () => void
    const wait = new Promise<void>((resolve) => {
      release = resolve
    })
    fakeApi({
      [wsRoute('GET', 'w1', '/accounts')]: async () => (await wait, ok([makeAccount({ id: 'a1', name: 'Do workspace 1' })])),
      [wsRoute('GET', 'w1', '/categories')]: ok([]),
      [wsRoute('GET', 'w2', '/accounts')]: ok([makeAccount({ id: 'a1', name: 'Do workspace 2' })]),
      [wsRoute('GET', 'w2', '/categories')]: ok([]),
    })
    const view = render(<Probe workspaceId="w1" />)

    view.rerender(<Probe workspaceId="w2" />)
    expect(screen.getByTestId('status')).toHaveTextContent('loading')
    expect(await screen.findByTestId('name')).toHaveTextContent('Do workspace 2')
    await act(async () => release())

    expect(screen.getByTestId('name')).toHaveTextContent('Do workspace 2')
    expect(screen.queryByText('Do workspace 1')).toBeNull()
  })
})
