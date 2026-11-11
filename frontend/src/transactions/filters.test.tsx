import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it } from 'vitest'
import type { Me } from '../api/types'
import { WorkspaceProvider } from '../workspace/WorkspaceContext'
import { useWorkspace } from '../workspace/useWorkspace'
import { TransactionFiltersProvider } from './filters'
import { countActive, normalizeFilters, useTransactionFilters } from './useFilters'

const me: Me = {
  id: 'u1',
  name: 'Ana',
  email: 'ana@exemplo.com',
  workspaces: [
    { id: 'w1', name: 'Casa', baseCurrency: 'BRL', role: 'OWNER' },
    { id: 'w2', name: 'Viagem', baseCurrency: 'BRL', role: 'OWNER' },
  ],
}

function Probe() {
  const { filters, page, activeCount, apply, clear, setPage } = useTransactionFilters()
  const { select } = useWorkspace()
  return (
    <div>
      <p data-testid="state">{JSON.stringify({ filters, page, activeCount })}</p>
      <button type="button" onClick={() => apply({ q: '  mercado  ', accountId: 'a1', from: '', type: 'EXPENSE' })}>aplicar</button>
      <button type="button" onClick={() => clear()}>limpar</button>
      <button type="button" onClick={() => setPage(3)}>página 3</button>
      <button type="button" onClick={() => setPage(0)}>página 0</button>
      <button type="button" onClick={() => select('w2')}>ir para Viagem</button>
      <button type="button" onClick={() => select('w1')}>ir para Casa</button>
    </div>
  )
}

/** Uma tela que o teste monta e desmonta (como abrir um lançamento e voltar à lista). */
function Harness() {
  const [shown, setShown] = useState(true)
  return (
    <WorkspaceProvider me={me}>
      <TransactionFiltersProvider>
        <button type="button" onClick={() => setShown((s) => !s)}>alternar tela</button>
        {shown ? <Probe /> : <p>outra tela</p>}
      </TransactionFiltersProvider>
    </WorkspaceProvider>
  )
}

const state = () => JSON.parse(screen.getByTestId('state').textContent ?? '{}') as { filters: Record<string, string>; page: number; activeCount: number }

describe('os filtros e a página', () => {
  it('o padrão é nenhum filtro e a página 1', () => {
    render(<Harness />)

    expect(state()).toEqual({ filters: {}, page: 1, activeCount: 0 })
  })

  it('aplicar guarda só os filtros preenchidos (busca sem os espaços das pontas), conta os ativos e volta à página 1', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'página 3' }))
    expect(state().page).toBe(3)

    await user.click(screen.getByRole('button', { name: 'aplicar' }))

    expect(state()).toEqual({ filters: { q: 'mercado', accountId: 'a1', type: 'EXPENSE' }, page: 1, activeCount: 3 })
  })

  it('limpar tira tudo e volta à página 1', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'aplicar' }))
    await user.click(screen.getByRole('button', { name: 'página 3' }))

    await user.click(screen.getByRole('button', { name: 'limpar' }))

    expect(state()).toEqual({ filters: {}, page: 1, activeCount: 0 })
  })

  it('trocar de página mantém os filtros; a página nunca fica abaixo de 1', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'aplicar' }))

    await user.click(screen.getByRole('button', { name: 'página 3' }))
    expect(state()).toMatchObject({ page: 3, filters: { q: 'mercado' } })
    await user.click(screen.getByRole('button', { name: 'página 0' }))
    expect(state().page).toBe(1)
  })

  it('sobrevive a trocar de tela dentro do provider (abrir um lançamento e voltar)', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'aplicar' }))
    await user.click(screen.getByRole('button', { name: 'página 3' }))

    await user.click(screen.getByRole('button', { name: 'alternar tela' }))
    expect(screen.getByText('outra tela')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'alternar tela' }))

    expect(state()).toMatchObject({ page: 3, filters: { q: 'mercado', accountId: 'a1', type: 'EXPENSE' } })
  })
})

describe('por workspace', () => {
  it('trocar de workspace zera tudo, e voltar ao anterior não traz os filtros de volta', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'aplicar' }))
    await user.click(screen.getByRole('button', { name: 'página 3' }))

    await user.click(screen.getByRole('button', { name: 'ir para Viagem' }))
    expect(state()).toEqual({ filters: {}, page: 1, activeCount: 0 })

    await user.click(screen.getByRole('button', { name: 'ir para Casa' }))
    expect(state()).toEqual({ filters: {}, page: 1, activeCount: 0 })
  })

  it('os filtros aplicados no workspace novo valem só para ele', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    await user.click(screen.getByRole('button', { name: 'ir para Viagem' }))

    await user.click(screen.getByRole('button', { name: 'aplicar' }))

    expect(state().activeCount).toBe(3)
  })
})

describe('nada vai para fora da memória', () => {
  it('nem para o armazenamento do navegador nem para o endereço', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const before = window.location.href

    await act(async () => {
      await user.click(screen.getByRole('button', { name: 'aplicar' }))
    })

    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(window.location.href).toBe(before)
  })
})

describe('as funções puras', () => {
  it('normalizeFilters tira o vazio e as pontas da busca', () => {
    expect(normalizeFilters({ from: '', to: undefined, q: '   ', accountId: 'a' })).toEqual({ accountId: 'a' })
    expect(normalizeFilters({ q: ' x ' })).toEqual({ q: 'x' })
  })

  it('countActive conta só os preenchidos', () => {
    expect(countActive({})).toBe(0)
    expect(countActive({ from: '2026-01-01', q: '', status: 'PENDING' })).toBe(2)
  })

  it('fora do provider é um erro de uso, dito com clareza', () => {
    expect(() => render(<Probe />)).toThrow()
  })
})
