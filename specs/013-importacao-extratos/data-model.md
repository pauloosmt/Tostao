# Data Model: Importação de extratos

Migration única `V14__statement_import.sql`. Sem tabela nova: o extrato não é guardado e o lançamento importado é um
lançamento comum.

## Coluna nova em `transactions`

```sql
ALTER TABLE transactions ADD COLUMN import_key TEXT;

-- Chave: 1 a 200 caracteres, sem espaços nas pontas.
ALTER TABLE transactions ADD CONSTRAINT transactions_import_key_format
    CHECK (import_key IS NULL OR (import_key = btrim(import_key) AND char_length(import_key) BETWEEN 1 AND 200));

-- Só receita e despesa avulsas são importadas: nunca transferência, parcela nem ocorrência de recorrência.
ALTER TABLE transactions ADD CONSTRAINT transactions_import_key_coherent
    CHECK (import_key IS NULL
           OR (type IN ('INCOME', 'EXPENSE') AND installment_group_id IS NULL AND recurrence_id IS NULL));

-- Uma chave por conta, inclusive nas linhas excluídas: importar de novo não recria o que a pessoa excluiu.
CREATE UNIQUE INDEX transactions_import_key_key
    ON transactions (account_id, import_key) WHERE import_key IS NOT NULL;
```

- A aplicação (`tostao_app`) já tem `INSERT` na tabela; **não** ganha `UPDATE (import_key)`: a chave é gravada na criação
  e nunca muda (a edição da spec 004 não a toca).
- O RLS de `transactions` (por `workspace_id`) já cobre a coluna. Não há política nova.
- O índice é parcial: as linhas que não vêm de importação (`NULL`) ficam fora dele.
- Sem `DEFAULT`: linhas antigas ficam com `NULL`, o que significa "não importado".

## Regras de dados

| Regra | Onde é garantida |
|---|---|
| Chave de 1 a 200 caracteres sem espaços nas pontas | `CHECK` no banco + validação do DTO |
| Uma chave por conta (inclusive excluídas) | índice único parcial |
| Importado só se receita ou despesa avulsa | `CHECK` no banco |
| Importado nasce `CLEARED`, sem observação e sem `auto_clear` | serviço (colunas fixas na inserção) |
| Receita se valor > 0, despesa se valor < 0 | serviço (tipo pelo sinal) + `CHECK` de sinal da V1 |
| Categoria do tipo da linha, ativa, do workspace | `UsableRefs` + chave estrangeira de tipo da V5 |
| Conta ativa e do workspace | `UsableRefs` + chave estrangeira composta da V1 |
| Chave nunca muda | sem `GRANT UPDATE (import_key)` |

## Consultas novas

Todas com `workspace_id = :wid` explícito (camada de serviço) além do RLS.

1. **Chaves existentes** (prévia): `select import_key from transactions where workspace_id = :wid and account_id = :a and
   import_key in (:keys)` (inclui excluídas).
2. **Possíveis duplicatas** (prévia): `select occurred_on, amount_cents from transactions where workspace_id = :wid and
   account_id = :a and deleted_at is null and import_key is null and occurred_on between :min and :max`.
3. **Sugestões** (prévia): `select t.description, t.type, t.category_id from transactions t join categories c on
   c.id = t.category_id and c.workspace_id = t.workspace_id where t.workspace_id = :wid and t.deleted_at is null and
   not c.archived and t.type in ('INCOME', 'EXPENSE') order by t.occurred_on desc, t.created_at desc`.
4. **Inserção** (confirmação): `insert into transactions (workspace_id, account_id, category_id, type, amount_cents,
   occurred_on, description, status, created_by, import_key) values ... on conflict (account_id, import_key) where
   import_key is not null do nothing returning id`, em blocos de 100 linhas, depois da trava
   `pg_advisory_xact_lock(hashtextextended(:account::text, 1))`.
5. **Listagem**: o filtro `uncategorized=true` soma `and t.category_id is null and t.type <> 'TRANSFER'`.

## Respostas e pedidos

**`ImportPreviewResponse`** (200 da prévia):

| Campo | Tipo | Observação |
|---|---|---|
| `format` | `OFX` ou `CSV` | formato reconhecido |
| `lines` | lista de `ImportLinePreview` | na ordem do arquivo |
| `total` | inteiro | quantidade de linhas |
| `valid` | inteiro | linhas sem problema e que ainda não existem |
| `existing` | inteiro | linhas que já existem na conta |
| `invalid` | inteiro | linhas com problema |

**`ImportLinePreview`**:

| Campo | Tipo | Observação |
|---|---|---|
| `line` | inteiro | número da linha no arquivo (OFX: ordem do movimento) |
| `occurredOn` | data ou nulo | nulo se a data é inválida |
| `description` | texto | já normalizada e cortada em 200 |
| `amountCents` | inteiro ou nulo | com sinal; nulo se ilegível |
| `type` | `INCOME`, `EXPENSE` ou nulo | pelo sinal |
| `key` | texto ou nulo | chave de importação; nulo se a linha tem problema |
| `existing` | booleano | já existe na conta (inclusive excluída) |
| `possibleDuplicate` | booleano | mesma data e valor de um lançamento sem chave |
| `suggestedCategoryId` | UUID ou nulo | regra do FR-011 |
| `problems` | lista de texto | vazia se a linha está boa |

**`ImportConfirmRequest`**: `{ lines: [ImportLineRequest] }`, de 1 a 1.000 linhas. **`ImportLineRequest`**:
`key` (1 a 200), `occurredOn`, `description` (1 a 200), `amountCents` (inteiro com sinal, não zero, módulo até
1.000.000.000.000), `categoryId` (opcional). Campos fora dessa lista são recusados.

**`ImportConfirmResponse`**: `{ created: [TransactionResponse], skipped: inteiro }`.

**`TransactionResponse`** (spec 004) ganha `imported` (booleano), último campo.

## Estados e transições

O lançamento importado é um lançamento comum; seu ciclo é o da spec 004: criado efetivado, pode ser editado (a chave fica),
devolvido a pendente e efetivado de novo (spec 009) e excluído logicamente (a chave continua ocupada). Não há estado novo.
