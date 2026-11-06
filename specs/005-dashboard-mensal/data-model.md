# Data Model: Dashboard mensal e semanal

Esta feature **não muda o schema**: nenhuma migration, tabela, coluna ou índice. O painel é um modelo de
leitura calculado a cada consulta sobre `transactions` (spec 004), `accounts` (002) e `categories` (003).
Nada é guardado.

## Fontes

| Tabela | Colunas lidas | Filtros fixos |
|---|---|---|
| `transactions` | `workspace_id`, `account_id`, `category_id`, `type`, `amount_cents`, `occurred_on`, `deleted_at` | `workspace_id = :w`, `deleted_at is null` |
| `accounts` | `id`, `workspace_id`, `name`, `type`, `initial_balance_cents`, `archived` | `workspace_id = :w`, `not archived` (saldos) |
| `categories` | `id`, `workspace_id`, `parent_id`, `name` | junção por `id` e `workspace_id` iguais ao do lançamento; sem filtro de `archived` |

O RLS de leitura (`*_read`) vale em todas; o filtro por `workspace_id` é repetido nas consultas e nas
junções (segunda camada).

## Período

| Campo | Regra |
|---|---|
| `type` | `MONTH` ou `WEEK` |
| `id` | `AAAA-MM` ou `AAAA-Www` (semana ISO 8601) |
| `startDate` / `endDate` | mês: dia 1 e último dia; semana: segunda e domingo; ambos incluídos |
| faixa aceita | mês de `2000-01` a `2100-12`; semana com ano ISO de 2000 a 2100 e número existente nesse ano |
| anterior | mês: o mês anterior; semana: 7 dias antes; pode cair antes de 2000 (sem lançamentos) |

## Resposta (`DashboardResponse`)

| Bloco | Campos |
|---|---|
| `period` | `type`, `id`, `startDate`, `endDate` |
| `totals` | `incomeCents`, `expenseCents` (positivos), `resultCents` = receitas − despesas |
| `incomeByCategory`, `expenseByCategory` | lista de **grupo**: `categoryId` (nulo = "Sem categoria"), `name`, `directCents`, `totalCents`, `percent`, `children` |
| grupo → `children` | lista de **item**: `categoryId`, `name`, `totalCents`, `percent` (subcategorias usadas no período) |
| `daily` | um **dia** por data do período: `date`, `incomeCents`, `expenseCents`, `balanceCents` (saldo total ao fim do dia) |
| `comparison` | `previousPeriod` (`type`, `id`, `startDate`, `endDate`) e `income`, `expense`, `result`: cada um com `previousCents`, `changeCents`, `changePercent` (nulo quando o anterior é zero) |
| `balances` | `accounts` (por conta ativa: `id`, `name`, `type`, `balanceCents` de hoje, `openingBalanceCents`, `closingBalanceCents`), `totalBalanceCents`, `openingTotalCents`, `closingTotalCents` |

Todos os valores em centavos inteiros (`long`); `percent` e `changePercent` são números com duas casas
decimais.

## Regras de cálculo

1. **Receita e despesa**: lançamentos com `type` `INCOME` e `EXPENSE` na faixa de datas do período.
   `TRANSFER` e `deleted_at` preenchido nunca entram. Despesa sai positiva (`-amount_cents`).
2. **Totais, dias e comparação** vêm da mesma consulta por dia, somados em Java com `addExact`. Dia sem
   linha vale zero. A soma de `daily.incomeCents` e a de `daily.expenseCents` são iguais aos totais.
3. **Grupo**: agrupa pela categoria principal (`coalesce(parent_id, id)`). `directCents` é o lançado direto
   na principal; `totalCents = directCents + soma dos children`. Lançamento sem categoria forma o grupo
   "Sem categoria" (`categoryId` nulo, `children` vazio). A soma dos `totalCents` dos grupos de um tipo é
   igual ao total do tipo.
4. **Ordem**: grupos e itens por `totalCents` decrescente, depois nome (minúsculas), depois identificador.
5. **Percentual do grupo/item** = `totalCents × 100 / total do tipo`, escala 2, metade para cima. Sem
   lançamentos do tipo não há grupos.
6. **Variação** = atual − anterior; **percentual** = `variação × 100 / |anterior|`, escala 2, metade para
   cima; nulo se o anterior for zero. O resultado também é comparado (pode ser negativo).
7. **Saldos** (só contas ativas): `opening = inicial + soma(lançamentos com data < início)`;
   `closing = inicial + soma(lançamentos com data ≤ fim)`; `balance = inicial + soma(todos)`. Os totais são
   somas Java das contas ativas, como o `GET /reports/balance` da `002`.
8. **Saldo por dia**: `balanceCents(d) = openingTotal + movimento acumulado até d`, com movimento diário =
   soma de `amount_cents` dos lançamentos de contas ativas no dia (transferências incluídas). O saldo do
   último dia é igual a `closingTotalCents`.
9. **Leitura única**: tudo roda na mesma transação `READ ONLY` com `REPEATABLE READ`.

## Consultas (esboço, parâmetros nomeados)

```sql
-- 1) por dia, intervalo [início do período anterior, fim do período]
select occurred_on,
       coalesce(sum(amount_cents) filter (where type = 'INCOME'), 0)::bigint   as income,
       coalesce(-sum(amount_cents) filter (where type = 'EXPENSE'), 0)::bigint as expense
from transactions
where workspace_id = :workspaceId and deleted_at is null and type <> 'TRANSFER'
  and occurred_on between :from and :to
group by occurred_on;

-- 2) por categoria no período
select t.type, root.id as root_id, root.name as root_name, c.id as cat_id, c.name as cat_name,
       sum(case t.type when 'EXPENSE' then -t.amount_cents else t.amount_cents end)::bigint as total
from transactions t
left join categories c    on c.id = t.category_id and c.workspace_id = t.workspace_id
left join categories root on root.id = coalesce(c.parent_id, c.id) and root.workspace_id = t.workspace_id
where t.workspace_id = :workspaceId and t.deleted_at is null and t.type <> 'TRANSFER'
  and t.occurred_on between :start and :end
group by t.type, root.id, root.name, c.id, c.name;

-- 3a) saldos por conta ativa
select a.id, a.name, a.type, a.initial_balance_cents,
       coalesce(sum(t.amount_cents) filter (where t.occurred_on <  :start), 0)::bigint as before_start,
       coalesce(sum(t.amount_cents) filter (where t.occurred_on <= :end),   0)::bigint as until_end,
       coalesce(sum(t.amount_cents), 0)::bigint                                          as all_time
from accounts a
left join transactions t on t.account_id = a.id and t.workspace_id = a.workspace_id and t.deleted_at is null
where a.workspace_id = :workspaceId and not a.archived
group by a.id order by lower(a.name), a.id;

-- 3b) movimento por dia das contas ativas no período
select t.occurred_on, sum(t.amount_cents)::bigint as movement
from transactions t
join accounts a on a.id = t.account_id and a.workspace_id = t.workspace_id
where t.workspace_id = :workspaceId and t.deleted_at is null and not a.archived
  and t.occurred_on between :start and :end
group by t.occurred_on;
```

## Segurança dos dados

- Nenhuma escrita: o serviço não tem método que grave, e a transação é `READ ONLY`.
- Isolamento: aspecto `@WorkspaceScoped` (404 para não-membro), RLS de leitura e filtro explícito por
  `workspace_id` em todas as consultas e junções.
- Entrada: só `month` ou `week`, validados antes de qualquer consulta; nenhum valor do cliente entra em SQL
  fora de parâmetro nomeado.
