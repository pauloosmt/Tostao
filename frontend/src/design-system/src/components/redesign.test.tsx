import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { expectNoSeriousViolations } from '../../../test/axe'
import { AmountInput } from './AmountInput'
import { CategoryCoin } from './CategoryCoin'
import { CoinPicker } from './CoinPicker'
import { OptionTile } from './OptionTile'
import { PageHeader, MonthSwitcher } from './PageHeader'
import { Sheet } from './Sheet'
import { WalletGate } from './WalletGate'
import { WalletHero } from './WalletHero'

// Os componentes novos do redesign: o que um leitor de tela e o teclado precisam encontrar.
describe('componentes do redesign', () => {
  it('Sheet é um diálogo com nome, o botão de fechar e o botão principal no rodapé', async () => {
    const onClose = vi.fn()
    const { container } = render(<Sheet title="Novo lançamento" onClose={onClose} footer={<button>Salvar</button>}>corpo</Sheet>)

    expect(screen.getByRole('dialog', { name: 'Novo lançamento' })).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Fechar' }))
    expect(onClose).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Salvar' })).toBeInTheDocument()
    await expectNoSeriousViolations(container)
  })

  it('AmountInput tem rótulo ligado ao campo e mostra o texto digitado', () => {
    render(<AmountInput kind="expense" value="50,00" onChange={() => {}} />)

    expect(screen.getByLabelText('Valor')).toHaveValue('50,00')
  })

  it('OptionTile e CoinPicker são botões de rádio com estado', async () => {
    const onChange = vi.fn()
    render(
      <>
        <div role="radiogroup" aria-label="Quando"><OptionTile compact selected>Hoje</OptionTile><OptionTile compact>Ontem</OptionTile></div>
        <CoinPicker label="Categoria" value="Mercado" options={[{ name: 'Mercado', color: '#3a7' }, { name: 'Lazer', color: '#a37' }]} onChange={onChange} />
      </>,
    )

    expect(screen.getByRole('radio', { name: 'Hoje' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'Ontem' })).not.toBeChecked()
    await userEvent.setup().click(screen.getByRole('radio', { name: /Lazer/ }))
    expect(onChange).toHaveBeenCalledWith('Lazer')
  })

  it('CategoryCoin é decoração: some do leitor de tela', () => {
    const { container } = render(<CategoryCoin name="Mercado" color="#3a7" />)

    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true')
    expect(container).toHaveTextContent('M')
  })

  it('WalletHero mostra o saldo, ou a frase de vazio quando não há saldo', () => {
    const { rerender } = render(<WalletHero label="Saldo total" cents={123456} clasp="Sobram R$ 10,00" />)
    expect(screen.getByText('Saldo total')).toBeInTheDocument()
    expect(screen.getByText('Sobram R$ 10,00')).toBeInTheDocument()
    expect(screen.getByText(/1\.234/)).toBeInTheDocument()

    rerender(<WalletHero label="Saldo total" cents={null} empty="Crie sua primeira conta" />)
    expect(screen.getByText('Crie sua primeira conta')).toBeInTheDocument()
  })

  it('PageHeader tem um h1 e MonthSwitcher tem os dois botões com nome', () => {
    const { container } = render(<PageHeader title="Lançamentos" context="6 lançamentos" action={<MonthSwitcher label="Novembro de 2026" />} />)

    expect(screen.getByRole('heading', { level: 1, name: 'Lançamentos' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Mês anterior' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Próximo mês' })).toBeInTheDocument()
    expect(container).toHaveTextContent('Novembro de 2026')
  })

  it('WalletGate é um formulário: o fecho dourado envia', async () => {
    const onSubmit = vi.fn()
    render(<WalletGate title="Entrar" submitLabel="Entrar na conta" onSubmit={onSubmit}><input aria-label="E-mail" /></WalletGate>)

    expect(screen.getByRole('heading', { level: 1, name: 'Entrar' })).toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Entrar na conta' }))
    expect(onSubmit).toHaveBeenCalledTimes(1)
  })
})
