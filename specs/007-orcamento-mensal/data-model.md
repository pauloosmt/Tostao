# Data Model: Orçamento mensal

Esta feature adiciona **uma tabela**, `monthly_budgets` (migration `V7`). O acompanhamento do mês é um modelo
de leitura calculado a cada consulta sobre `monthly_budgets`, `transactions` (spec 004) e `categories` (spec
003); nada do gasto é guardado. A tabela `weekly_budgets` (spec 006) não muda.

## Tabela `monthly_budgets`

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `UUID` | chave primária, `gen_random_uuid()` |
| `workspace_id` | `UUID NOT NULL` | `REFERENCES workspaces (id) ON DELETE CASCADE` |
| `month_start` | `DATE NOT NULL` | primeiro dia do mês: `extract(day from month_start) = 1` e entre `2000-01-01` e `2100-12-01` |
| `category_id` | `UUID` | nulo = limite geral do mês |
| `kind` | `TEXT NOT NULL DEFAULT 'EXPENSE'` | `CHECK (kind = 'EXPENSE')`; existe só para a chave estrangeira abaixo |
| `amount_cents` | `BIGINT NOT NULL` | `CHECK (amount_cents BETWEEN 0 AND 1000000000000)`; zero é válido |
| `created_by` | `UUID NOT NULL` | `REFERENCES users (id)`; nunca muda |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | nunca muda |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | atualizado a cada mudança de valor |

Restrições:

- `FOREIGN KEY (category_id, workspace_id, kind) REFERENCES categories (id, workspace_id, kind)`: categoria do
  mesmo workspace e de despesa (usa a chave única da `V4`). Com `category_id` nulo, não se aplica.
- `UNIQUE NULLS NOT DISTINCT (workspace_id, month_start, category_id)`: um limite por escopo e mês, inclusive o
  geral. É o alvo do `ON CONFLICT` e serve a consulta por `(workspace_id, month_start)`.
- `ROW LEVEL SECURITY` ligado.

### Segurança (RLS e privilégios)

| Operação | Política | Privilégio |
|---|---|---|
| `SELECT` | `workspace_id = app_workspace_id()` | tabela |
| `INSERT` | `workspace_id = app_workspace_id() AND app_can_write(workspace_id) AND created_by = app_user_id()` | tabela |
| `UPDATE` | `workspace_id = app_workspace_id() AND app_can_write(workspace_id)` (usando e verificando) | só `amount_cents`, `updated_at` |
| `DELETE` | `workspace_id = app_workspace_id() AND app_can_write(workspace_id)` | tabela |

A participação do usuário no workspace é conferida pelo aspecto `@WorkspaceScoped` antes de definir o
contexto; o papel de escrita é conferido de novo no banco por `app_can_write`.

## Fontes de leitura do acompanhamento

| Tabela | Colunas lidas | Filtros fixos |
|---|---|---|
| `monthly_budgets` | `id`, `category_id`, `amount_cents`, `created_by`, `created_at`, `updated_at` | `workspace_id = :w`, `month_start = :s` |
| `categories` | `id`, `workspace_id`, `parent_id`, `name`, `archived` | junção por `id` e `workspace_id`; sem filtro de `archived` |
| `transactions` | `workspace_id`, `category_id`, `type`, `amount_cents`, `occurred_on`, `deleted_at` | `workspace_id = :w`, `deleted_at is null`, `type = 'EXPENSE'`, `occurred_on` entre o dia 1 e o último dia do mês |

## Regras de cálculo

São as da [`006`](../006-orcamento-semanal/data-model.md) (`BudgetRules`), sem mudança, aplicadas ao mês:

| Regra | Definição |
|---|---|
| Gasto de um limite geral | soma de todas as despesas do mês, de qualquer categoria, inclusive sem categoria |
| Gasto de um limite de categoria principal `X` | despesas com `category_id = X` ou cujo pai é `X` |
| Gasto de um limite de subcategoria `Y` | despesas com `category_id = Y` |
| Restante | `amountCents − spentCents` (negativo quando estourado) |
| Percentual usado | `spent × 100 / amount`, duas casas, metade para cima; nulo quando `amount = 0` |
| Estado | `amount = 0`: sem gasto `WITHIN`, com gasto `EXCEEDED`. Senão `spent × 5 < amount × 4` → `WITHIN`; `spent ≤ amount` → `WARNING`; acima → `EXCEEDED` (inteiros exatos) |
| Total gasto do mês | soma de todas as despesas do mês |
| Gasto fora dos limites de categoria | despesas cuja categoria não tem limite de categoria no mês nem tem pai com limite (sem categoria sempre entra) |
| Ordem dos limites | estado (`EXCEEDED`, `WARNING`, `WITHIN`), maior gasto, nome da categoria em minúsculas (o geral primeiro entre iguais), `id` |

Nenhum total soma os valores dos limites entre si. O mês vai do dia 1 ao último dia do mês civil, ambos
incluídos (fevereiro de 28 ou 29 dias, meses de 30 e 31).

## Resposta do acompanhamento (`MonthBudgetResponse`)

| Bloco | Campos |
|---|---|
| `month` | `id` (`AAAA-MM`), `startDate`, `endDate` |
| `totalSpentCents` | todas as despesas do mês |
| `spentOutsideCategoryLimitsCents` | despesas que nenhum limite de categoria cobre |
| `budgets[]` | `id`, `categoryId` (nulo = geral), `categoryName` (nulo = geral), `categoryArchived`, `amountCents`, `spentCents`, `remainingCents`, `percentUsed`, `status` (`WITHIN`, `WARNING`, `EXCEEDED`), `createdBy`, `createdAt`, `updatedAt` |

O item de `budgets[]` é o mesmo da `006` (`BudgetItem`). Valores em centavos inteiros (`long`); `percentUsed`
é número com duas casas ou nulo.

## Respostas de escrita

| Rota | Resposta |
|---|---|
| `PUT /monthly-budgets`, `PUT /monthly-budgets/{id}` | `MonthlyBudgetResponse`: `id`, `month`, `categoryId`, `categoryName`, `amountCents`, `createdBy`, `createdAt`, `updatedAt` |
| `POST /monthly-budgets/copy` | `{ created, skippedExisting, skippedArchivedCategory }` (o mesmo da `006`) |
| `DELETE /monthly-budgets/{id}` | sem corpo |

## Transições

Um limite não tem estados: existe ou não existe. Definir cria ou substitui o valor; ajustar substitui o valor;
remover apaga a linha. Arquivar a categoria não muda o limite: ele continua aparecendo e sendo acompanhado, não
aceita novo valor e pode ser removido.

## Relação com o orçamento semanal

Nenhuma. Não há chave estrangeira, soma nem conciliação entre `weekly_budgets` e `monthly_budgets`. Um limite
semanal e um mensal para a mesma categoria coexistem, cada um com o seu gasto calculado sobre o seu período.
