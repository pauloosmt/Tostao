# Data Model: Lançamento pendente

Esta feature **não adiciona tabela nem coluna**. Usa `transactions.status`, que já existe desde a `V1`. A migration
`V8` só concede o privilégio de atualizar essa coluna.

## Coluna `transactions.status`

| Aspecto | Valor |
|---|---|
| Tipo | `TEXT NOT NULL DEFAULT 'CLEARED'` |
| Valores | `CLEARED` (efetivado) e `PENDING` (pendente), com `CHECK (status IN ('PENDING', 'CLEARED'))` |
| Quem muda | só a aplicação, por `UPDATE (status)` liberado na `V8`, sob o RLS de escrita por papel |
| Transferência | as duas pontas (mesmo `transfer_group_id`) têm sempre o mesmo valor |
| Excluído | `deleted_at` continua mandando: excluído não conta em lugar nenhum, qualquer que seja o estado |

### Migration `V8` (segurança)

```sql
GRANT UPDATE (status) ON transactions TO tostao_app;
```

Política de `UPDATE` do RLS (já existe, `V5`): `workspace_id = app_workspace_id() AND app_can_write(workspace_id)`.
`workspace`, `type`, `transfer_group_id`, `created_by` e `created_at` continuam sem privilégio de `UPDATE`.

## A regra única

| Consulta | Antes | Depois |
|---|---|---|
| Saldo de conta (`AccountService`) | soma de todos os não excluídos | soma dos **efetivados** não excluídos; mais `pendingCents` (pendentes) e `projectedBalanceCents` |
| Dashboard: receitas e despesas por dia, por categoria, saldos e movimento diário | todos os não excluídos | só **efetivados**; mais o bloco `pending` do período |
| Gasto de orçamento (semanal e mensal) | todas as despesas não excluídas | só **efetivadas** |
| Fatura: linhas, total, pagamentos e lista | todos os não excluídos | só **efetivados** |
| Listagem de lançamentos | todos os não excluídos | todos os não excluídos, com `status` e filtro opcional |

Onde a consulta só precisa dos efetivados (saldos do dashboard), o filtro vai na condição do `left join` (`and
t.status = 'CLEARED'`), nunca no `where`. No saldo da conta, a junção fica sem filtro e o estado entra em cada
soma (`filter (where t.status = 'CLEARED')` e `'PENDING'`). Nos dois casos a conta sem lançamentos efetivados
continua aparecendo com saldo zero.

## Regras de cálculo novas

| Regra | Definição |
|---|---|
| Pendente da conta | soma de `amount_cents` dos lançamentos **pendentes** não excluídos da conta, com o sinal que já têm |
| Saldo previsto da conta | `balanceCents + pendingCents` |
| Previsto total | soma dos previstos das contas **ativas** (`addExact`) |
| Bloco pendente do dashboard | no período: `incomeCents` = soma das receitas pendentes; `expenseCents` = soma (positiva) das despesas pendentes; `count` = quantidade de lançamentos pendentes (receita e despesa); sem transferências e sem excluídos |

## Respostas (campos novos, todos aditivos)

| Resposta | Campos novos |
|---|---|
| `TransactionResponse` | `status` (`CLEARED` ou `PENDING`) |
| `AccountResponse` | `pendingCents`, `projectedBalanceCents` |
| `BalanceReportResponse.Item` | `pendingCents`, `projectedBalanceCents` |
| `BalanceReportResponse` | `totalProjectedCents` |
| `DashboardResponse` | `pending`: `{ incomeCents, expenseCents, count }` |

## Entradas (campos novos, opcionais)

| Entrada | Campo | Regra |
|---|---|---|
| `POST /transactions` | `status` | opcional; ausente = `CLEARED`; valor desconhecido = 400 no campo `status` |
| `PUT /transactions/{id}` | `status` | opcional; ausente = mantém o atual; valor desconhecido = 400 no campo `status` |
| `GET /transactions` | `?status=` | opcional; `PENDING` ou `CLEARED`; combinado por "e"; inválido = 400 no campo `status` |

## Rota nova

`POST /api/v1/workspaces/{wid}/transactions/{id}/clear`: sem corpo; efetiva o lançamento (as duas pontas, se for
transferência); responde 200 com o lançamento. Já efetivado: 200 sem nenhum efeito. Excluído, de outro workspace,
inexistente ou malformado: 404. VIEWER: 403.

## Transições

| De | Para | Como | Efeito |
|---|---|---|---|
| `PENDING` | `CLEARED` | `POST /{id}/clear` ou edição com `status` | passa a contar no realizado; `updated_at` muda |
| `CLEARED` | `PENDING` | edição com `status` | deixa de contar no realizado e passa a contar no previsto; `updated_at` muda |
| `CLEARED` | `CLEARED` (clear) | `POST /{id}/clear` | nenhum efeito, nem em `updated_at` |

O estado nunca muda sozinho (nenhuma regra por data).
