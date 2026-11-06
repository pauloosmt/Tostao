# Data Model: Lançamentos

A tabela `transactions` já existe na `V1`. Esta feature acrescenta a migration **`V5__transactions.sql`** (a
`V1` a `V4` não são editadas) com restrições, a chave composta de tipo da categoria, o índice das pontas de
transferência, as políticas de RLS de escrita por papel, os privilégios por coluna e a revogação do
`DELETE`.

## Entidades

### Lançamento (`transactions`, existente)

| Campo | Regra |
|---|---|
| `id` | UUID gerado pelo banco; identifica o lançamento (e cada ponta de uma transferência) |
| `workspace_id` | obrigatório; nunca vem do cliente (vem da rota, depois de confirmada a participação); imutável |
| `account_id` | conta do lançamento; ativa e do mesmo workspace ao criar ou ao passar a usá-la; chave composta com `workspace_id` |
| `category_id` | obrigatório em `INCOME` e `EXPENSE` (regra do serviço), categoria ativa do mesmo workspace e **do mesmo tipo**; nulo em `TRANSFER` |
| `type` | `INCOME`, `EXPENSE` ou `TRANSFER`; imutável depois de criado |
| `amount_cents` | `BIGINT` com sinal: positivo em `INCOME` e na entrada de `TRANSFER`, negativo em `EXPENSE` e na saída; diferente de zero; módulo máximo `1_000_000_000_000` |
| `occurred_on` | data de ocorrência; de `2000-01-01` a `2100-12-31`; futuro aceito |
| `description` | 1 a 200 caracteres, sem espaços nas pontas |
| `notes` | nula ou de 1 a 1.000 caracteres |
| `status` | não usado nesta spec; fica `CLEARED` (padrão) |
| `transfer_group_id` | só em `TRANSFER`; o mesmo nas duas pontas; imutável |
| `created_by` | usuário autenticado que criou; preso à sessão pela política de `INSERT`; imutável |
| `created_at` | preenchido pelo banco na criação |
| `updated_at` | atualizado pelo serviço em toda edição e exclusão |
| `deleted_at` | nulo enquanto o lançamento vale; preenchido na exclusão lógica; nunca volta a nulo |

### Transferência (não é tabela)

Duas linhas de `transactions` com o mesmo `transfer_group_id`:

| Ponta | `amount_cents` | `account_id` |
|---|---|---|
| Saída | `-valor` | conta de origem |
| Entrada | `+valor` | conta de destino |

Coerência (garantida pelo serviço, em uma só transação): mesmo módulo de valor, mesma `occurred_on`, mesma
`description`, mesma `notes`, contas diferentes, `category_id` nulo, `deleted_at` igual nas duas.

### Saldo da conta (calculado, existente na 002)

`balance_cents = initial_balance_cents + soma(amount_cents)` dos lançamentos da conta com
`deleted_at IS NULL`. Nada muda na consulta; os novos lançamentos entram sozinhos.

### Relações

- Lançamento → Workspace: muitos para um (`workspace_id`).
- Lançamento → Conta: muitos para um, pela chave composta `(account_id, workspace_id)` da V1.
- Lançamento → Categoria: muitos para um, pelas chaves compostas `(category_id, workspace_id)` (V1) e
  `(category_id, workspace_id, type)` (V5, tipo igual).
- Lançamento → Usuário: `created_by`.

## Transições de estado

```text
vigente --excluir--> excluído (deleted_at preenchido; some das listas e do saldo)
excluído --editar | excluir | consultar--> não encontrado (404)
transferência: qualquer operação sobre uma ponta vale para as duas, na mesma transação
```

Não existe restauração nem exclusão física.

## Migration V5 (resumo)

1. `CHECK transactions_description_trimmed`: `description = btrim(description)` e `char_length` de 1 a 200.
2. `CHECK transactions_notes_length`: `notes IS NULL OR char_length(notes) BETWEEN 1 AND 1000`.
3. `CHECK transactions_amount_limit`: `abs(amount_cents) <= 1000000000000`.
4. `CHECK transactions_date_range`: `occurred_on BETWEEN '2000-01-01' AND '2100-12-31'`.
5. `FOREIGN KEY (category_id, workspace_id, type) REFERENCES categories (id, workspace_id, kind)`
   (`transactions_category_kind_fkey`): categoria do mesmo tipo do lançamento.
6. `CREATE UNIQUE INDEX transactions_transfer_leg_key ON transactions (transfer_group_id, (amount_cents > 0))
   WHERE transfer_group_id IS NOT NULL`: no máximo uma saída e uma entrada por grupo.
7. Remove a política `transactions_tenant` (FOR ALL) e cria `transactions_read` (SELECT),
   `transactions_insert` (`workspace_id = app_workspace_id() AND app_can_write(workspace_id) AND created_by
   = app_user_id()`) e `transactions_update` (`workspace_id = app_workspace_id() AND
   app_can_write(workspace_id)` em `USING` e em `WITH CHECK`). Sem política de `DELETE`.
8. `REVOKE DELETE ON transactions FROM tostao_app`; `REVOKE UPDATE ON transactions FROM tostao_app` e
   `GRANT UPDATE (account_id, category_id, amount_cents, occurred_on, description, notes, updated_at,
   deleted_at) ON transactions TO tostao_app`.

## Políticas de RLS resultantes

| Política | Operação | Condição |
|---|---|---|
| `transactions_read` | SELECT | `workspace_id = app_workspace_id()` |
| `transactions_insert` | INSERT | `workspace_id = app_workspace_id()`, `app_can_write(workspace_id)` e `created_by = app_user_id()` |
| `transactions_update` | UPDATE | `workspace_id = app_workspace_id()` e `app_can_write(workspace_id)`, em `USING` e `WITH CHECK` |

## Validações de entrada (aplicadas na API, antes do banco)

| Campo | Regra |
|---|---|
| `type` | obrigatório no cadastro (`INCOME`, `EXPENSE`, `TRANSFER`); na edição, opcional e, se vier, igual ao atual |
| `accountId` | obrigatório; conta do workspace; ativa (se mudou). Na transferência, é a origem |
| `destinationAccountId` | obrigatório só em `TRANSFER`, diferente de `accountId`; proibido nos outros tipos |
| `amountCents` | obrigatório, inteiro de 1 a 1.000.000.000.000 |
| `occurredOn` | obrigatório, data válida de 2000 a 2100 |
| `description` | obrigatória, 1 a 200 caracteres após `strip` |
| `notes` | opcional, até 1.000 caracteres após `strip`; texto vazio vira ausente |
| `categoryId` | obrigatório em `INCOME`/`EXPENSE` (do mesmo tipo, ativa); proibido em `TRANSFER` |
| campos extras | rejeitados (`workspaceId`, `createdBy`, `transferGroupId`, `deletedAt`, datas, sinal) |
| valor não inteiro (por exemplo `10.5`) | rejeitado |

### Parâmetros de listagem

| Parâmetro | Regra |
|---|---|
| `page` | inteiro a partir de 1; padrão 1 |
| `size` | inteiro de 1 a 100; padrão 20 |
| `from`, `to` | datas ISO (`AAAA-MM-DD`), inclusivas; `from <= to` |
| `accountId`, `categoryId` | UUID |
| `type` | `INCOME`, `EXPENSE` ou `TRANSFER` |
| `q` | até 100 caracteres; `%`, `_` e `\` valem literalmente |

## Índices relevantes

- Existentes (V1): `transactions_workspace_date_idx`, `transactions_workspace_category_date_idx`,
  `transactions_workspace_account_date_idx` e `transactions_transfer_group_idx`, todos parciais
  (`deleted_at IS NULL`, e o do grupo só para transferências).
- Novo: `transactions_transfer_leg_key` (uma saída e uma entrada por grupo).
