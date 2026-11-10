# Data Model: Recorrências e efetivação automática

Esta feature **adiciona uma tabela** (`recurrences`) e três colunas a `transactions` (migration `V10`), e duas
funções de banco (migration `V11`).

## Colunas novas em `transactions` (V10)

| Coluna | Tipo | Significado |
|---|---|---|
| `auto_clear` | `BOOLEAN NOT NULL DEFAULT false` | efetivar sozinho quando a data chegar; só vale para pendente; de uso único |
| `recurrence_id` | `UUID` nulo | recorrência de origem (só nas ocorrências) |
| `occurrence_number` | `INTEGER` nulo | número da ocorrência, a partir de 0 |

Lançamentos existentes ficam com `auto_clear = false` e as outras duas nulas.

### Restrições e índices em `transactions`

```sql
ALTER TABLE transactions
    ADD COLUMN auto_clear        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN recurrence_id     UUID,
    ADD COLUMN occurrence_number INTEGER;

-- O marcador só vale para pendente: efetivado nunca o tem ligado (uso único).
ALTER TABLE transactions ADD CONSTRAINT transactions_auto_clear_pending
    CHECK (NOT auto_clear OR status = 'PENDING');

-- Recorrência de origem e número juntos ou nenhum; só receita ou despesa; nunca parcela e ocorrência ao mesmo tempo.
ALTER TABLE transactions ADD CONSTRAINT transactions_occurrence_coherent CHECK (
    (recurrence_id IS NULL AND occurrence_number IS NULL)
    OR (recurrence_id IS NOT NULL AND occurrence_number IS NOT NULL AND occurrence_number >= 0
        AND type IN ('INCOME', 'EXPENSE') AND installment_group_id IS NULL));

ALTER TABLE transactions ADD CONSTRAINT transactions_recurrence_fkey
    FOREIGN KEY (recurrence_id, workspace_id) REFERENCES recurrences (id, workspace_id);

-- Uma ocorrência por número (inclui as excluídas: uma ocorrência excluída não volta).
CREATE UNIQUE INDEX transactions_recurrence_occurrence_key
    ON transactions (recurrence_id, occurrence_number) WHERE recurrence_id IS NOT NULL;

-- Só os lançamentos marcados: a rotina de efetivação não varre a tabela inteira.
CREATE INDEX transactions_auto_clear_idx
    ON transactions (occurred_on) WHERE auto_clear AND deleted_at IS NULL;

GRANT UPDATE (auto_clear) ON transactions TO tostao_app;
```

`recurrence_id` e `occurrence_number` **sem** `GRANT UPDATE`: nascem na inserção e nunca mudam.

## Tabela `recurrences` (V10)

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `UUID` PK | gerado no banco |
| `workspace_id` | `UUID NOT NULL` | `REFERENCES workspaces ON DELETE CASCADE` |
| `account_id` | `UUID NOT NULL` | chave composta `(account_id, workspace_id)` → `accounts` |
| `category_id` | `UUID NOT NULL` | chave composta `(category_id, workspace_id, type)` → `categories (id, workspace_id, kind)` |
| `type` | `TEXT NOT NULL` | `CHECK (type IN ('INCOME','EXPENSE'))` |
| `amount_cents` | `BIGINT NOT NULL` | `CHECK (amount_cents BETWEEN 1 AND 1000000000000)`; sempre positivo |
| `description` | `TEXT NOT NULL` | `CHECK (description = btrim(description) AND char_length BETWEEN 1 AND 200)` |
| `notes` | `TEXT` | nulo ou de 1 a 1.000 caracteres |
| `frequency` | `TEXT NOT NULL` | `CHECK (frequency IN ('WEEKLY','MONTHLY','YEARLY'))` |
| `start_on` | `DATE NOT NULL` | `CHECK (start_on BETWEEN '2000-01-01' AND '2100-12-31')` |
| `end_on` | `DATE` | nulo, ou `>= start_on` e `<= '2100-12-31'` |
| `auto_clear` | `BOOLEAN NOT NULL DEFAULT false` | as ocorrências nascem com este valor |
| `ended_at` | `TIMESTAMPTZ` | nulo enquanto ativa |
| `created_by` | `UUID NOT NULL` | `REFERENCES users`; sempre a pessoa da sessão |
| `created_at`, `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |

`UNIQUE (id, workspace_id)` (alvo da chave composta de `transactions`). Índice `(workspace_id, created_at)`.

### Segurança (V10)

```sql
ALTER TABLE recurrences ENABLE ROW LEVEL SECURITY;
CREATE POLICY recurrences_read   ON recurrences FOR SELECT TO tostao_app USING (workspace_id = app_workspace_id());
CREATE POLICY recurrences_insert ON recurrences FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id) AND created_by = app_user_id());
CREATE POLICY recurrences_update ON recurrences FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));
GRANT SELECT, INSERT ON recurrences TO tostao_app;
GRANT UPDATE (ended_at, updated_at) ON recurrences TO tostao_app;
```

Sem política nem privilégio de `DELETE`. O teste de cobertura de RLS (`RlsCoverageTest`) já exige RLS em toda
tabela com `workspace_id`.

## Funções de banco (V11)

Ambas `SECURITY DEFINER`, `SET search_path = public, pg_temp`, `REVOKE ALL ... FROM PUBLIC`, `GRANT EXECUTE ... TO
tostao_app`.

### `generate_recurrence_occurrences(p_today DATE, p_recurrence_id UUID DEFAULT NULL) RETURNS INTEGER`

Gera as ocorrências que faltam e devolve quantas inseriu.

| Passo | Regra |
|---|---|
| Quais recorrências | `ended_at IS NULL`, conta **e** categoria **não arquivadas**, e `id = p_recurrence_id` se informado; travadas `FOR SHARE` |
| Horizonte | `p_today + interval '12 months'` |
| Número `n` | de 0 até o limite por frequência (`(horizonte - início) / 7`; diferença de meses; diferença de anos) |
| Data de `n` | semanal `start_on + 7 * n`; mensal `start_on + n * interval '1 month'`; anual `start_on + n * interval '1 year'` |
| Filtro | `data <= horizonte AND (end_on IS NULL OR data <= end_on) AND data <= '2100-12-31'` |
| Linha gerada | `workspace_id`, `account_id`, `category_id`, `type`, `description`, `notes`, `auto_clear` e `created_by` da recorrência; `amount_cents` com sinal (despesa negativa, receita positiva); `status = 'PENDING'`; `recurrence_id`, `occurrence_number = n` |
| Conflito | `ON CONFLICT (recurrence_id, occurrence_number) WHERE recurrence_id IS NOT NULL DO NOTHING` |

### `auto_clear_due(p_today DATE) RETURNS INTEGER`

Efetiva o que venceu e devolve quantas linhas mudou.

```sql
WITH due AS (
    SELECT id FROM transactions
    WHERE auto_clear AND status = 'PENDING' AND deleted_at IS NULL AND occurred_on <= p_today
    ORDER BY id FOR UPDATE)
UPDATE transactions t SET status = 'CLEARED', auto_clear = false, updated_at = now()
FROM due WHERE t.id = due.id;
```

## Regras que o serviço aplica

| Regra | Definição |
|---|---|
| Marcador na criação | `autoClear = true` com estado efetivado: `400` no campo `autoClear`. Ausente: desligado |
| Marcador na edição | ausente mantém o atual; presente muda; se o estado resultante é efetivado, o marcador sai desligado; `true` com estado resultante efetivado: `400` no campo `autoClear` |
| Transferência | as duas pontas recebem o mesmo marcador |
| Efetivação manual (`clear`) | `status = 'CLEARED', auto_clear = false` |
| Compra parcelada | `autoClear = true` liga o marcador nas parcelas **pendentes**; a primeira, se efetivada, fica sem |
| Recorrência: início | entre `hoje - 366 dias` e 2100-12-31 |
| Recorrência: fim | entre o início e 2100-12-31 |
| Recorrência: tipo | `INCOME` ou `EXPENSE`; `TRANSFER` recusado no campo `type` |
| Conta e categoria | as mesmas conferências e travas da criação de lançamentos (`UsableRefs`) |
| Criação | insere a recorrência e chama `generate_recurrence_occurrences(hoje, id)` na mesma transação |

## Encerramento

| Passo | Efeito |
|---|---|
| Existência | recorrência inexistente, de outro workspace ou malformada: 404 |
| Travar | a recorrência `FOR UPDATE`; já encerrada: `cancelled = 0`, sem efeito |
| Encerrar | `ended_at = now()`, `updated_at = now()` |
| Excluir | as ocorrências `PENDING`, vivas, com `occurred_on > hoje`, travadas em ordem de número, com `deleted_at = now()` |
| Resposta | `cancelled` = linhas excluídas |

Efetivadas e pendentes vencidas ficam. Nenhum número realizado muda.

## Respostas

### `TransactionResponse` (campos novos, aditivos)

| Campo | Tipo | Observação |
|---|---|---|
| `autoClear` | booleano | `false` nos que não usam |
| `recurrenceId` | UUID ou nulo | recorrência de origem |
| `occurrenceNumber` | inteiro ou nulo | número da ocorrência, a partir de 0 |

### `RecurrenceResponse` (resumo; a lista devolve uma lista destes)

`id`, `accountId`, `categoryId`, `type`, `amountCents` (positivo), `description`, `notes`, `frequency`, `startOn`,
`endOn`, `autoClear`, `ended` (booleano), `endedAt`, `nextOccurrenceOn` (menor data entre as ocorrências pendentes
não excluídas **de hoje em diante**; nulo se não há), `createdBy`, `createdAt`.

### `RecurrenceDetailResponse`

Os mesmos campos do resumo, mais `totalCents`, `clearedCents`, `pendingCents` (positivos, das ocorrências não
excluídas) e `occurrences` (lista de `TransactionResponse` em ordem de número).

### `EndRecurrenceResponse`

`cancelled` (inteiro).

### `CreateRecurrenceRequest` (entrada, campos fechados)

| Campo | Regra |
|---|---|
| `accountId` | obrigatório; conta ativa do workspace |
| `categoryId` | obrigatório; categoria ativa do workspace e do tipo |
| `type` | obrigatório; `INCOME` ou `EXPENSE` |
| `amountCents` | obrigatório; inteiro de 1 a 1.000.000.000.000 |
| `description` | obrigatória; sem espaços nas pontas; 1 a 200 caracteres |
| `notes` | opcional; até 1.000; vazio vira nulo |
| `frequency` | obrigatória; `WEEKLY`, `MONTHLY` ou `YEARLY` |
| `startOn` | obrigatória; data ISO entre `hoje - 366 dias` e 2100-12-31 |
| `endOn` | opcional; entre `startOn` e 2100-12-31 |
| `autoClear` | opcional; padrão `false` |

## Erros

| Situação | Resposta | Campo |
|---|---|---|
| Campo obrigatório ausente, tipo errado ou extra | 400 | o campo |
| `type` `TRANSFER` ou inválido | 400 | `type` |
| `frequency` inválida | 400 | `frequency` |
| `amountCents` fora da faixa | 400 | `amountCents` |
| `startOn` ou `endOn` fora da faixa | 400 | `startOn`, `endOn` |
| Descrição vazia ou acima de 200 | 400 | `description` |
| `autoClear = true` com estado efetivado | 400 | `autoClear` |
| Categoria de outro tipo | 400 | `categoryId` |
| Conta ou categoria arquivada | 409 | |
| Conta ou categoria inexistente ou de outro workspace | 404 | |
| Recorrência inexistente, de outro workspace ou malformada | 404 | |
| VIEWER escrevendo | 403 | |
| Sem sessão | 401 | |
