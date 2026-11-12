import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { expectNoSeriousViolations } from '../../../test/axe'
import { AccountCard, type AccountType } from './AccountCard'

describe('AccountCard', () => {
  it.each([
    ['corrente', 'Conta corrente'],
    ['poupanca', 'Poupança'],
    ['dinheiro', 'Dinheiro'],
    ['cartao', 'Cartão de crédito'],
    ['investimento', 'Investimento'],
  ] as const)('o tipo %s aparece como "%s"', (type, label) => {
    render(<AccountCard name="Minha conta" type={type} balanceCents={150000} />)

    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.getByText('Minha conta')).toBeInTheDocument()
  })

  it('um tipo desconhecido (outra versão da API) aparece como "Conta", não como um tipo que não é', () => {
    render(<AccountCard name="Estranha" type={'CRIPTO' as AccountType} balanceCents={0} />)

    expect(screen.getByText('Conta')).toBeInTheDocument()
    expect(screen.queryByText('Conta corrente')).toBeNull()
  })

  it('a conta arquivada é marcada', () => {
    render(<AccountCard name="Antiga" type="poupanca" balanceCents={0} archived />)

    expect(screen.getByText(/Poupança · arquivada/)).toBeInTheDocument()
  })

  it('o saldo negativo mostra o sinal e é anunciado como negativo', () => {
    render(<AccountCard name="Cheque especial" type="corrente" balanceCents={-20000} />)

    expect(screen.getByText(/Saldo negativo/)).toBeInTheDocument()
    expect(document.body).toHaveTextContent(/−R\$\s*200,00/) // (os centavos ficam num span à parte, em tamanho menor)
  })

  it('a linha de apoio aparece quando há', () => {
    render(<AccountCard name="Cartão" type="cartao" balanceCents={-5000} note="Fecha dia 10" />)

    expect(screen.getByText('Fecha dia 10')).toBeInTheDocument()
  })

  it('passa no axe', async () => {
    const { container } = render(<AccountCard name="Casa" type="dinheiro" balanceCents={1000} />)

    await expectNoSeriousViolations(container)
  })
})
