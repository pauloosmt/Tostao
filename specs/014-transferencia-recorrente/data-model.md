# Data Model: Transferência recorrente

Migration única `V15__recurring_transfer.sql`. Sem tabela nova.

## `recurrences`

```sql
ALTER TABLE recurrences ADD COLUMN destination_account_id UUID;
ALTER TABLE recurrences ALTER COLUMN category_id DROP NOT NULL;

ALTER TABLE recurrences DROP CONSTRAINT recurrences_type_check;
ALTER TABLE recurrences ADD CONSTRAINT recurrences_type_check CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER'));

-- o destino é do mesmo workspace (como a origem)
ALTER TABLE recurrences ADD CONSTRAINT recurrences_destination_fkey
    FOREIGN KEY (destination_account_id, workspace_id) REFERENCES accounts (id, workspace_id);

-- a forma: transferência tem destino diferente da origem e nenhuma categoria; receita e despesa têm categoria e nenhum destino
ALTER TABLE recurrences ADD CONSTRAINT recurrences_shape CHECK (
    (type = 'TRANSFER' AND destination_account_id IS NOT NULL AND destination_account_id <> account_id
        AND category_id IS NULL)
    OR (type <> 'TRANSFER' AND destination_account_id IS NULL AND category_id IS NOT NULL));
```

- A chave estrangeira da categoria `(category_id, workspace_id, type)` da V10 não precisa mudar: com categoria nula ela não é aplicada.
- Privilégios e políticas da V10 já cobrem a coluna (`GRANT SELECT, INSERT` na tabela; `UPDATE` só de `ended_at` e `updated_at`).
  O destino, como o resto da regra, nunca muda depois de criado.

## `transactions`

```sql
-- a ocorrência pode ser de qualquer tipo; continuam valendo: recorrência e número juntos, número >= 0, nunca parcela
ALTER TABLE transactions DROP CONSTRAINT transactions_occurrence_coherent;
ALTER TABLE transactions ADD CONSTRAINT transactions_occurrence_coherent CHECK (
    (recurrence_id IS NULL AND occurrence_number IS NULL)
    OR (recurrence_id IS NOT NULL AND occurrence_number IS NOT NULL AND occurrence_number >= 0
        AND installment_group_id IS NULL));

-- uma saída e uma entrada por número (para receita e despesa, uma linha por número, como antes)
DROP INDEX transactions_recurrence_occurrence_key;
CREATE UNIQUE INDEX transactions_recurrence_occurrence_key
    ON transactions (recurrence_id, occurrence_number, (amount_cents > 0)) WHERE recurrence_id IS NOT NULL;
```

Inclui as linhas excluídas, como antes: uma ocorrência (as duas pontas) que a pessoa excluiu continua ocupando o número.

## Função `generate_recurrence_occurrences(p_today DATE, p_recurrence_id UUID DEFAULT NULL)`

`CREATE OR REPLACE` na mesma assinatura (privilégios e dono preservados). Mudanças em relação à V11:

- Junta `accounts` da origem e, por `LEFT JOIN`, `accounts` do destino e `categories`. Ativa quando: `ended_at` nulo, origem não
  arquivada e, para `TRANSFER`, destino existente e não arquivado, ou, para os demais, categoria existente e não arquivada.
- O calendário (`n`) e o horizonte (12 meses, fim, 2100-12-31) são os mesmos.
- Gera **uma ou duas linhas** por número:
  - `INCOME` / `EXPENSE`: como antes (um `INSERT` com o valor com sinal do tipo).
  - `TRANSFER`: saída (`-amount_cents`) na conta de origem e entrada (`+amount_cents`) na conta de destino; ambas com `type =
    'TRANSFER'`, `category_id` nulo, `status = 'PENDING'`, o mesmo `auto_clear`, a mesma descrição, observação, data e autoria, e o mesmo
    `transfer_group_id = md5(recorrência || ':' || número)::uuid`.
- `ON CONFLICT (recurrence_id, occurrence_number, (amount_cents > 0)) WHERE recurrence_id IS NOT NULL DO NOTHING`.
- Retorna quantas **linhas** inseriu (a criação ignora o valor; a execução automática o registra).
- `ORDER BY` (recorrência, número, ponta) antes de inserir.

## Regras de dados

| Regra | Onde é garantida |
|---|---|
| Transferência: destino obrigatório, diferente da origem, sem categoria | `CHECK recurrences_shape` + serviço (mensagem por campo) |
| Receita e despesa: categoria obrigatória, sem destino | `CHECK recurrences_shape` + serviço |
| Destino do mesmo workspace | chave estrangeira composta |
| Contas ativas na criação | `UsableRefs` (travadas, em ordem de id) |
| Uma saída e uma entrada por número | índice único com `(amount_cents > 0)` |
| Par sempre completo e com o mesmo grupo | uma instrução, grupo determinístico, `transfer_leg_key` da V5 |
| Excluída não volta | índice inclui as excluídas |
| Cada ponta pendente com o mesmo marcador | geração |

## Respostas

`RecurrenceResponse` e `RecurrenceDetailResponse` ganham `destinationAccountId` (logo depois de `accountId`); `categoryId` é nulo para
transferência e `destinationAccountId` é nulo para receita e despesa. O detalhe traz `occurrences` com as duas pontas; `totalCents`,
`clearedCents` e `pendingCents` somam a saída de cada ocorrência de transferência uma vez. `EndRecurrenceResponse.cancelled` conta
ocorrências (números distintos). O pedido de criação ganha `destinationAccountId`; `categoryId` deixa de ser obrigatório na anotação (a
obrigatoriedade por tipo é do serviço, que aponta o campo).

## Estados

Os da spec 011: ativa e encerrada; ocorrência pendente ou efetivada, vigente ou excluída. Nenhum estado novo.
