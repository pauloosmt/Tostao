import { useNavigate } from 'react-router'
import type { Dashboard as DashboardData } from '../api/types'
import { toCardType } from '../accounts/accountTypes'
import { AccountCard, AttentionDot, Button, Card, Money, ProgressBar, StatTile, WalletHero, formatBRL } from '../design-system/src'
import { useWalletClosed } from '../hooks/useWalletClosed'
import { useMediaQuery } from '../layout/useMediaQuery'
import { ButtonLink } from '../ui/ButtonLink'
import { useTransactionFilters } from '../transactions/useFilters'
import { claspText, changeText, percentText } from './format'

export interface DashboardProps {
  data: DashboardData
  month: string
  workspaceLine: string
  canWrite: boolean
}

/** O painel do mês: a Carteira com o fecho, os totais com a variação, o pendente, os gastos por categoria e as contas. */
export function Dashboard({ data, month, workspaceLine, canWrite }: DashboardProps) {
  const stacked = useMediaQuery('(max-width: 719px)')
  // A pessoa pode fechar a carteira (esconde o saldo e a frase do fecho); a escolha fica neste navegador.
  const [closed, toggle] = useWalletClosed()
  const navigate = useNavigate()
  const { apply } = useTransactionFilters()
  const { totals, comparison, pending, balances } = data
  const noAccounts = balances.accounts.length === 0
  const emptyMonth = totals.incomeCents === 0 && totals.expenseCents === 0 && pending.count === 0
  const previous = comparison.previousPeriod.id

  return (
    <>
      <WalletHero
        label="Saldo total"
        cents={noAccounts ? null : balances.totalBalanceCents}
        empty="Crie sua primeira conta"
        sub={workspaceLine}
        clasp={noAccounts ? undefined : claspText(totals.resultCents, month)}
        closed={!noAccounts && closed}
        onToggle={noAccounts ? undefined : toggle}
        className={stacked ? 'tx-wallet--stack' : undefined}
      />

      {noAccounts ? (
        <Card>
          <div className="stack stack--tight">
            <p className="text-body muted">
              {canWrite ? 'Com a primeira conta, os lançamentos e o saldo começam a aparecer aqui.' : 'Quem pode editar este workspace cria as contas.'}
            </p>
            {canWrite ? <ButtonLink variant="primary" to="/contas/nova">Criar conta</ButtonLink> : null}
          </div>
        </Card>
      ) : (
        <>
          <section className="home-totals" aria-label="Resumo do mês">
            <StatTile label="Receitas" cents={totals.incomeCents} kind="income" hint={changeText(comparison.income, previous)} />
            <StatTile label="Despesas" cents={totals.expenseCents} kind="expense" hint={changeText(comparison.expense, previous)} />
            <StatTile label="Resultado" cents={totals.resultCents} kind="balance" hint={changeText(comparison.result, previous)} />
          </section>

          {emptyMonth ? (
            <Card>
              <div className="stack stack--tight">
                <p className="title">Nenhum lançamento neste mês</p>
                {canWrite ? <ButtonLink variant="primary" to="/lancamentos/novo">Novo lançamento</ButtonLink> : null}
              </div>
            </Card>
          ) : null}

          {pending.count > 0 ? (
            <section className="home-pending" aria-label="Pendentes">
              <AttentionDot>{pending.count === 1 ? '1 pendente' : `${pending.count} pendentes`}</AttentionDot>
              <span className="text-body-sm muted">
                {pending.expenseCents > 0 ? <>a pagar <Money cents={pending.expenseCents} kind="expense" size="sm" /></> : null}
                {pending.expenseCents > 0 && pending.incomeCents > 0 ? ' · ' : null}
                {pending.incomeCents > 0 ? <>a receber <Money cents={pending.incomeCents} kind="income" size="sm" /></> : null}
              </span>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => {
                  apply({ status: 'PENDING' })
                  void navigate('/lancamentos')
                }}
              >
                Ver pendentes
              </Button>
            </section>
          ) : null}

          <section className="stack" aria-labelledby="home-categories">
            <h2 id="home-categories" className="heading-2">Gastos por categoria</h2>
            {data.expenseByCategory.length === 0 ? (
              <p className="text-body muted">Nenhuma despesa neste mês.</p>
            ) : (
              <ul className="home-categories">
                {data.expenseByCategory.map((group) => (
                  <li key={group.categoryId ?? 'sem-categoria'}>
                    <ProgressBar
                      label={group.name}
                      value={group.percent}
                      tone="brand"
                      valueLabel={`${formatBRL(group.totalCents)} · ${percentText(group.percent)}%`}
                    />
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="stack" aria-labelledby="home-accounts">
            <h2 id="home-accounts" className="heading-2">Contas</h2>
            <ul className="account-list">
              {balances.accounts.map((account) => (
                <li key={account.id}>
                  <AccountCard name={account.name} type={toCardType(account.type)} balanceCents={account.balanceCents} />
                </li>
              ))}
            </ul>
          </section>
        </>
      )}
    </>
  )
}
