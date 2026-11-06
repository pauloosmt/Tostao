# Data Model: Contas

A tabela `accounts` já existe na `V1`. Esta feature acrescenta a migration **`V3__accounts.sql`** (a `V1` e a
`V2` não são editadas) com restrições, um índice único parcial, as políticas de RLS de escrita por papel e a
revogação do `DELETE`.

## Entidades

### Conta (`accounts`, existente)

| Campo | Regra |
|---|---|
| `id` | UUID gerado pelo banco; junto com `workspace_id` forma a chave usada pelas chaves compostas de `transactions` |
| `workspace_id` | obrigatório; nunca vem do cliente (vem da rota, depois de confirmada a participação) |
| `name` | 1 a 100 caracteres, sem espaços nas pontas; único entre contas ativas do workspace, sem diferenciar maiúsculas |
| `type` | `CHECKING`, `SAVINGS`, `CASH`, `CREDIT_CARD` ou `INVESTMENT`; imutável depois de criada |
| `currency` | `BRL`, a moeda base do workspace; não aceita escolha nem alteração |
| `initial_balance_cents` | `BIGINT`, pode ser negativo, zero ou positivo; módulo máximo `1_000_000_000_000` |
| `credit_limit_cents` | `BIGINT` nulo; só em `CREDIT_CARD`; maior ou igual a zero; máximo `1_000_000_000_000` |
| `closing_day` | `SMALLINT` nulo; só em `CREDIT_CARD`; de 1 a 31 |
| `due_day` | `SMALLINT` nulo; só em `CREDIT_CARD`; de 1 a 31 |
| `archived` | booleano, `false` ao criar; só muda pelas ações de arquivar e restaurar |
| `created_at` | preenchido pelo banco na criação |
| `updated_at` | atualizado pelo serviço em toda edição, arquivamento e restauração |

### Saldo da conta (calculado, não é coluna)

`balance_cents = initial_balance_cents + soma(amount_cents)` dos lançamentos da conta com
`deleted_at IS NULL`. Nunca é gravado.

### Saldo total (calculado)

Soma dos `balance_cents` das contas com `archived = false` do workspace, calculada em memória a partir das
mesmas linhas da consulta de saldos. Sem contas ativas, é zero.

### Relações

- Conta → Workspace: muitas para uma (`workspace_id`).
- Lançamento → Conta: muitas para uma, pela chave composta `(account_id, workspace_id)` da V1.

## Transições de estado

```text
ativa --arquivar--> arquivada --restaurar--> ativa
ativa --arquivar (já arquivada)--> sem efeito (sucesso)
arquivada --restaurar (nome em uso por outra ativa)--> recusado (409)
arquivada --editar--> recusado (409)
```

Não existe transição de exclusão.

## Migration V3 (resumo)

1. `CHECK accounts_card_fields_only_on_cards`: `type = 'CREDIT_CARD'` OU (`credit_limit_cents`,
   `closing_day` e `due_day` todos nulos).
2. `CHECK accounts_amount_limits`: `abs(initial_balance_cents) <= 1000000000000` e
   `credit_limit_cents <= 1000000000000`.
3. `CHECK accounts_name_trimmed`: `name = btrim(name)` e `char_length(name)` de 1 a 100.
4. `CREATE UNIQUE INDEX accounts_active_name_key ON accounts (workspace_id, lower(name)) WHERE NOT archived`.
5. Função `app_can_write(workspace UUID)` (SQL, estável): verdadeira quando o `app_user_id()` é `OWNER` ou
   `EDITOR` na associação daquele workspace.
6. Remove a política `accounts_tenant` (FOR ALL) e cria `accounts_read` (SELECT), `accounts_insert` e
   `accounts_update`, as duas últimas exigindo `workspace_id = app_workspace_id()` e `app_can_write(workspace_id)`
   em `USING` e `WITH CHECK`. Sem política de `DELETE`.
7. `REVOKE DELETE ON accounts FROM tostao_app`.

## Políticas de RLS resultantes

| Política | Operação | Condição |
|---|---|---|
| `accounts_read` | SELECT | `workspace_id = app_workspace_id()` |
| `accounts_insert` | INSERT | `workspace_id = app_workspace_id()` e `app_can_write(workspace_id)` |
| `accounts_update` | UPDATE | mesma condição em `USING` e em `WITH CHECK` |

## Validações de entrada (aplicadas na API, antes do banco)

| Campo | Regra |
|---|---|
| `name` | obrigatório, 1 a 100 caracteres após `strip` |
| `type` | obrigatório no cadastro; um dos cinco valores; na edição é opcional e, se vier, tem que ser igual ao atual |
| `initialBalanceCents` | obrigatório, inteiro, de -1.000.000.000.000 a 1.000.000.000.000 |
| `creditLimitCents` | opcional, inteiro, de 0 a 1.000.000.000.000, só em cartão |
| `closingDay`, `dueDay` | opcionais, inteiros de 1 a 31, só em cartão |
| campos extras | rejeitados (`workspaceId`, `archived`, `balanceCents`, datas e qualquer outro) |
| valor não inteiro (por exemplo `10.5`) | rejeitado |

## Índices relevantes

- Existentes: `accounts_workspace_idx` e `UNIQUE (id, workspace_id)` (V1), e os índices parciais de
  `transactions` que atendem a soma do saldo.
- Novo: `accounts_active_name_key` (nome único entre ativas).
