# Data Model: Compra parcelada

Esta feature **não adiciona tabela**. Acrescenta três colunas nulas a `transactions` (migration `V9`) e um índice
único parcial. O plano de parcelas é o conjunto de despesas com o mesmo `installment_group_id`.

## Colunas novas em `transactions`

| Coluna | Tipo | Significado |
|---|---|---|
| `installment_group_id` | `UUID` nulo | liga as parcelas de uma mesma compra; gerado no servidor |
| `installment_number` | `SMALLINT` nulo | número da parcela, de 1 ao total |
| `installment_count` | `SMALLINT` nulo | total de parcelas da compra (2 a 48) |

Lançamentos que não são parcelas (todos os existentes) ficam com as três nulas.

### Migration `V9` (esboço)

```sql
ALTER TABLE transactions
    ADD COLUMN installment_group_id UUID,
    ADD COLUMN installment_number   SMALLINT,
    ADD COLUMN installment_count    SMALLINT;

-- As três juntas ou nenhuma; só despesa é parcela; número entre 1 e o total; total de 2 a 48.
ALTER TABLE transactions ADD CONSTRAINT installments_coherent CHECK (
    (installment_group_id IS NULL AND installment_number IS NULL AND installment_count IS NULL)
    OR (installment_group_id IS NOT NULL AND installment_number IS NOT NULL AND installment_count IS NOT NULL
        AND type = 'EXPENSE'
        AND installment_count BETWEEN 2 AND 48
        AND installment_number BETWEEN 1 AND installment_count));

-- Um número por grupo; o prefixo (workspace_id, installment_group_id) também serve à busca do plano.
CREATE UNIQUE INDEX transactions_installment_key
    ON transactions (workspace_id, installment_group_id, installment_number)
    WHERE installment_group_id IS NOT NULL;
```

Sem `GRANT`: o `INSERT` já vale para a tabela inteira (`V2`) e o `UPDATE` continua restrito às colunas da `V5` e
`status` da `V8`. Grupo, número e total **nunca mudam** depois de criados, por privilégio. A política de `INSERT`
do RLS (`V5`) já exige `app_can_write` e `created_by = app_user_id()`.

## Regras de cálculo

| Regra | Definição |
|---|---|
| Divisão | `base = total / count` (inteiro, para baixo); `rest = total % count`; parcela `i` (de 1) vale `base + 1` se `i <= rest`, senão `base`. Soma = `total`; nenhuma parcela difere de outra em mais de 1 centavo |
| Valor mínimo | `total >= count` (cada parcela pelo menos 1 centavo); máximo `1.000.000.000.000` |
| Valor gravado | despesa: o valor da parcela **negativo** em `amount_cents`, como em toda despesa (spec 004) |
| Data da parcela `i` | `firstOccurredOn.plusMonths(i - 1)`, sempre a partir da primeira; o dia é limitado ao fim do mês |
| Faixa de datas | primeira e última entre 2000-01-01 e 2100-12-31 |
| Estado | parcela 1 = `CLEARED` (ou `PENDING` se pedido); parcelas 2..N = `PENDING` |
| Descrição | `descrição base + " (" + i + "/" + count + ")"`; a base tem 1 a 190 caracteres, sem espaços nas pontas |
| Observação | a mesma em todas as parcelas (ou nenhuma) |
| Conta e categoria | as mesmas em todas; conta ativa; categoria de despesa ativa; ambas do workspace |

Exemplos: R$ 100,00 em 3 → 3334, 3333, 3333. R$ 0,05 em 5 → 1, 1, 1, 1, 1. R$ 0,05 em 3 → 2, 2, 1.
Primeira em 2027-01-31, 4 parcelas → 31/01, 28/02, 31/03, 30/04 (2027 não é bissexto).

## Plano (consulta)

O plano de um grupo, para o workspace informado:

| Aspecto | Definição |
|---|---|
| Existe | há ao menos uma linha do grupo no workspace, **excluída ou não**; senão 404 |
| Lista | as parcelas **não excluídas**, em ordem de `installment_number` |
| `installmentCount` | o `installment_count` gravado (o original, mesmo com parcelas excluídas) |
| `description` | a descrição da parcela de menor número (mesmo excluída) sem o sufixo ` (N/T)`; se ela não termina com o sufixo esperado (editada à mão), a descrição como está |
| `accountId` e `categoryId` | os da parcela de menor número (mesmo excluída) |
| `totalCents` | soma dos valores (positivos) das parcelas não excluídas |
| `clearedCents` | idem, só as `CLEARED` |
| `pendingCents` | idem, só as `PENDING` |

Lista e totais saem da mesma leitura consistente (`REPEATABLE READ`, somente leitura).

## Cancelamento

| Passo | Efeito |
|---|---|
| Existência | plano inexistente, de outro workspace ou malformado = 404; plano só com linhas excluídas existe |
| Travar | as parcelas `PENDING` e vivas do grupo, `FOR UPDATE`, em ordem de número |
| Excluir | `deleted_at = now()`, `updated_at = now()` nas travadas (e `status = 'PENDING' AND deleted_at IS NULL` repetido) |
| Resposta | `cancelled` = linhas alteradas; sem pendentes ou repetido = 0 |

Efetivadas e já excluídas ficam intactas. Nenhum número realizado muda.

## Respostas

### `TransactionResponse` (campos novos, aditivos)

| Campo | Tipo | Observação |
|---|---|---|
| `installmentGroupId` | UUID ou nulo | o grupo da compra parcelada |
| `installmentNumber` | inteiro ou nulo | número da parcela |
| `installmentCount` | inteiro ou nulo | total de parcelas |

### `InstallmentPlanResponse` (novo)

| Campo | Tipo | Observação |
|---|---|---|
| `groupId` | UUID | |
| `description` | texto | descrição base |
| `installmentCount` | inteiro | total original |
| `accountId`, `categoryId` | UUID | |
| `totalCents`, `clearedCents`, `pendingCents` | inteiro (centavos, positivos) | das parcelas não excluídas |
| `installments` | lista de `TransactionResponse` | em ordem de número, sem as excluídas |

### `CancelInstallmentsResponse` (novo)

| Campo | Tipo |
|---|---|
| `cancelled` | inteiro |

### `CreateInstallmentPurchaseRequest` (entrada, campos fechados)

| Campo | Regra |
|---|---|
| `accountId` | obrigatório; conta ativa do workspace |
| `categoryId` | obrigatório; categoria de despesa ativa do workspace |
| `totalCents` | obrigatório; inteiro de 1 a 1.000.000.000.000 e `>= installmentCount` |
| `installmentCount` | obrigatório; inteiro de 2 a 48 |
| `firstOccurredOn` | obrigatório; data ISO; primeira e última datas entre 2000 e 2100 |
| `description` | obrigatório; sem espaços nas pontas; 1 a 190 caracteres |
| `notes` | opcional; até 1.000; vazio vira nulo |
| `firstInstallmentStatus` | opcional; `CLEARED` (padrão) ou `PENDING` |

## Erros

| Situação | Resposta | Campo |
|---|---|---|
| Campo obrigatório ausente, tipo errado ou extra | 400 | o campo |
| `installmentCount` fora de 2..48 | 400 | `installmentCount` |
| `totalCents` fora da faixa ou menor que o número de parcelas | 400 | `totalCents` |
| Datas fora de 2000..2100 | 400 | `firstOccurredOn` |
| Descrição vazia ou acima de 190 | 400 | `description` |
| Estado inválido | 400 | `firstInstallmentStatus` |
| Categoria de receita | 400 | `categoryId` |
| Conta ou categoria arquivada | 409 | |
| Conta ou categoria inexistente ou de outro workspace | 404 | |
| Plano inexistente, de outro workspace ou malformado | 404 | |
| VIEWER escrevendo | 403 | |
| Sem sessão | 401 | |
