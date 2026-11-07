# Quickstart: validar o Orçamento semanal

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado.
- Stack local reconstruída: `docker compose up -d --build` (a migration `V6` roda na subida; confira
  `select version from flyway_schema_history order by installed_rank desc limit 1` → `6`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 (`--data-binary @arquivo`) no
  Windows.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos os testes passam, inclusive os novos de `budget/` e o `OpenApiContractTest` com o contrato da
`006`.

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace. Pegue as categorias
   padrão (`GET .../categories`): `Alimentação` (despesa), uma subcategoria dela (`Mercado`), `Transporte`,
   `Lazer` e `Salário` (receita).
2. Crie a conta `Corrente` (`CHECKING`, saldo inicial 100000) e a `Poupança` (`SAVINGS`, 0).
3. Lance (`POST .../transactions`):
   - `EXPENSE` 10000 em `2026-11-02`, `Alimentação` direto;
   - `EXPENSE` 5000 em `2026-11-03`, `Mercado`;
   - `EXPENSE` 9000 em `2026-11-04`, `Transporte`;
   - `EXPENSE` 2000 em `2026-11-05`, `Lazer`;
   - `INCOME` 250000 em `2026-11-05`, `Salário`; `TRANSFER` 30000 em `2026-11-06`;
   - `EXPENSE` 7777 em `2026-11-04`, que você **exclui** em seguida;
   - `EXPENSE` 99999 em `2026-11-01` (domingo) e `EXPENSE` 4000 em `2026-11-09` (segunda), ambos
     `Alimentação`: ficam fora da semana 2026-W45.

## 3. Definir limites (US1)

`PUT $BASE/workspaces/$WID/budgets` com `{"week":"2026-W45","categoryId":"<Alimentação>","amountCents":16000}`
→ **201** (e `Location`). Repita o mesmo corpo com `amountCents` 16000 → **200**, mesmo `id`, `createdAt`
igual. Defina também: `Mercado` 5000, `Transporte` 8000 e o geral (sem `categoryId`) 100000.

## 4. Acompanhar a semana (US2)

`GET .../budgets?week=2026-W45` (segunda 02/11 a domingo 08/11) →

1. `totalSpentCents` **26000** (10000 + 5000 + 9000 + 2000; receita, transferência, excluído e os lançamentos
   de 01/11 e 09/11 não contam) e `spentOutsideCategoryLimitsCents` **2000** (Lazer).
2. `budgets` nesta ordem:
   - `Transporte`: limite 8000, gasto 9000, restante **-1000**, `percentUsed` 112.50, `EXCEEDED`;
   - `Alimentação`: 16000, gasto 15000 (direto + Mercado), restante 1000, 93.75, `WARNING`;
   - `Mercado`: 5000, gasto 5000, restante 0, 100.00, `WARNING`;
   - geral (`categoryId` nulo): 100000, gasto 26000, restante 74000, 26.00, `WITHIN`.
3. Defina `Lazer` com valor **0**: aparece `EXCEEDED` com `percentUsed` nulo e o fora dos limites cai para 0.
4. Sem `week` → a semana corrente (confira `week.id`). Uma semana sem limites → `budgets` vazia, total
   gasto correto, sem erro.

## 5. Ajustar e remover (US4)

1. `PUT .../budgets/{id do Transporte}` com `{"amountCents":9000}` → 200; no acompanhamento vira `WARNING`
   (100.00%) e `updatedAt` mudou, `createdAt` não.
2. `DELETE .../budgets/{id do Lazer}` → 204; some só de 2026-W45. Repetir → 404.

## 6. Copiar (US3)

1. Defina `Transporte` 12345 em `2026-W47`.
2. `POST .../budgets/copy` com `{"fromWeek":"2026-W45","weeks":3}` → `{created: 11, skippedExisting: 1,
   skippedArchivedCategory: 0}` (4 limites × 3 semanas, e o de Transporte em W47 já existia). Em
   `2026-W47` o Transporte continua 12345.
3. Arquive `Transporte` (`POST .../categories/{id}/archive`). Copie `{"fromWeek":"2026-W45","weeks":4}` →
   `{created: 3, skippedExisting: 9, skippedArchivedCategory: 4}`.
4. Em `2026-W45`, o limite de Transporte (categoria arquivada) continua aparecendo e sendo acompanhado;
   `PUT .../budgets/{id}` nele → 409; `DELETE` → 204.

## 7. Recusas (US1, US3)

Todas 400, com o campo em `errors[].field`: `week` mal escrita (`2026-W5`, `2025-W53`, `1999-W52`,
`2101-W01`), `amountCents` negativo, acima de `1000000000000`, `10.5`, ausente; `categoryId` de receita
(`Salário`); campos extras (`workspaceId`, `createdBy`, `createdAt`); `weeks` 0, 53 e `2.5`; cópia que
passaria de 2100 (`fromWeek` `2100-W50`, `weeks` 5). `categoryId` de outro workspace ou inexistente → 404.
Categoria arquivada em `PUT /budgets` → 409. Nada é gravado em nenhuma delas.

## 8. Papéis e isolamento (US5)

1. Semeie (via banco do dono) uma segunda pessoa como `VIEWER`: `GET .../budgets` → 200; `PUT`, `PUT /{id}`,
   `DELETE` e `POST /copy` → 403 e nada muda. Como `EDITOR`: tudo funciona, e o `createdBy` de um limite
   alheio não muda ao ajustá-lo.
2. Pessoa de outro workspace tenta consultar, definir, ajustar, remover e copiar no seu `$WID` → 404 igual ao
   de um workspace inexistente; um `id` de limite seu usado na rota do workspace dela → 404.
3. Sem cookie → 401.
4. Compare uma impressão digital de `transactions`, `accounts` e `categories` antes e depois de todas as
   operações: igual (o orçamento não toca neles).

## 9. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão (`weekly_budgets` primeiro) e confira
`users = workspaces = accounts = categories = transactions = weekly_budgets = 0`.
