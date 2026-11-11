import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { expectNoSeriousViolations } from '../../../test/axe'
import { renderWithUser } from '../../../test/render'
import { TransactionRow } from './TransactionRow'

const row = { description: 'Padaria do Zé', cents: 2350, account: 'Corrente', category: 'Alimentação', date: '6 nov' }

describe('TransactionRow', () => {
  it('sem ação é só uma linha de leitura, não um botão', () => {
    render(<TransactionRow {...row} />)

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByText('Padaria do Zé')).toBeInTheDocument()
  })

  it('com ação é um botão com nome acessível, valor e contexto', () => {
    render(<TransactionRow {...row} onClick={() => {}} pending />)

    const button = screen.getByRole('button', { name: /Padaria do Zé/ })
    expect(button).toHaveAttribute('type', 'button')
    expect(button).toHaveTextContent('Corrente · Alimentação · 6 nov')
    expect(button).toHaveTextContent('Pendente')
    // o valor traz a palavra para leitor de tela e o sinal: despesa, menos
    expect(button).toHaveTextContent(/Despesa\s*−R\$\s*23,50/)
  })

  it('o clique, o Enter e o Espaço acionam a ação uma vez cada', async () => {
    const onClick = vi.fn()
    const { user } = renderWithUser(<TransactionRow {...row} onClick={onClick} />)
    const button = screen.getByRole('button', { name: /Padaria do Zé/ })

    await user.tab()
    expect(button).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(onClick).toHaveBeenCalledTimes(1)
    await user.keyboard(' ')
    expect(onClick).toHaveBeenCalledTimes(2)
    await user.click(button)
    expect(onClick).toHaveBeenCalledTimes(3)
  })

  it('uma lista de linhas com ação é percorrida por Tab, uma parada por linha', async () => {
    const { user } = renderWithUser(
      <div>
        <TransactionRow {...row} description="Primeira" onClick={() => {}} />
        <TransactionRow {...row} description="Segunda" onClick={() => {}} />
      </div>,
    )

    await user.tab()
    expect(screen.getByRole('button', { name: /Primeira/ })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('button', { name: /Segunda/ })).toHaveFocus()
  })

  it('receita e transferência têm os seus nomes e sinais', () => {
    render(
      <div>
        <TransactionRow description="Salário" cents={500000} kind="income" onClick={() => {}} />
        <TransactionRow description="Reserva" cents={50000} kind="transfer" onClick={() => {}} />
      </div>,
    )

    expect(screen.getByRole('button', { name: /Salário/ })).toHaveTextContent(/Receita\s*\+R\$\s*5\.000,00/)
    expect(screen.getByRole('button', { name: /Reserva/ })).toHaveTextContent(/Transferência\s*R\$\s*500,00/)
  })

  it('não tem violação de acessibilidade, com e sem ação', async () => {
    const { container } = render(
      <main>
        <h1>Lançamentos</h1>
        <TransactionRow {...row} />
        <TransactionRow {...row} onClick={() => {}} pending />
      </main>,
    )

    await expectNoSeriousViolations(container)
  })
})

describe('TransactionRow: selos extras', () => {
  it('sem tags nada muda', () => {
    const { container } = render(<TransactionRow {...row} />)

    expect(container.querySelectorAll('.tx-badge')).toHaveLength(0)
  })

  it('as tags aparecem como selos ao lado de "Pendente", dentro da mesma linha (um botão só)', () => {
    render(<TransactionRow {...row} onClick={() => {}} pending tags={['Parcela 2 de 10', 'Recorrente']} />)

    const button = screen.getByRole('button', { name: /Padaria do Zé/ })
    expect(button).toHaveTextContent('Pendente')
    expect(button).toHaveTextContent('Parcela 2 de 10')
    expect(button).toHaveTextContent('Recorrente')
    expect(screen.getAllByRole('button')).toHaveLength(1) // os selos não são controles
  })

  it('uma lista de tags vazia não desenha nada', () => {
    const { container } = render(<TransactionRow {...row} tags={[]} />)

    expect(container.querySelectorAll('.tx-badge')).toHaveLength(0)
  })

  it('passa no axe com as tags', async () => {
    const { container } = render(<TransactionRow {...row} onClick={() => {}} tags={['Importado']} />)

    await expectNoSeriousViolations(container)
  })
})
