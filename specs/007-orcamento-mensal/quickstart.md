# Quickstart: validar o Orçamento mensal

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado.
- Stack local reconstruída: `docker compose up -d --build` (a migration `V7` roda na subida; confira
  `select version from flyway_schema_history order by installed_rank desc limit 1` → `7`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 (`--data-binary @arquivo`) no
  Windows.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos os testes passam, inclusive os novos de `budget/` (mensal), os da `006` (que não podem
mudar de resultado depois da extração do `BudgetTracking`) e o `OpenApiContractTest` com os contratos da `006`
e da `007`.

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace. Pegue as categorias
   padrão (`GET .../categories`): `Alimentação` (despesa), uma subcategoria dela (`Mercado`), `Transporte`,
   `Lazer` e `Salário` (receita).
2. Crie a conta `Corrente` (`CHECKING`, saldo inicial 100000) e a `Poupança` (`SAVINGS`, 0).
3. Lance (`POST .../transactions`):
   - `EXPENSE` 40000 em `2026-11-01` (primeiro dia), `Alimentação` direto;
   - `EXPENSE` 20000 em `2026-11-15`, `Mercado`;
   - `EXPENSE` 36000 em `2026-11-10`, `Transporte`;
   - `EXPENSE` 8000 em `2026-11-30` (último dia), `Lazer`;
   - `INCOME` 500000 em `2026-11-05`, `Salário`; `TRANSFER` 30000 em `2026-11-06`;
   - `EXPENSE` 7777 em `2026-11-04`, que você **exclui** em seguida;
   - `EXPENSE` 99999 em `2026-10-31` e `EXPENSE` 4000 em `2026-12-01`, ambos `Alimentação`: ficam fora de
     `2026-11`.

## 3. Definir limites (US1)

`PUT $BASE/workspaces/$WID/monthly-budgets` com
`{"month":"2026-11","categoryId":"<Alimentação>","amountCents":64000}` → **201** (e `Location`). Repita o mesmo
corpo → **200**, mesmo `id`, `createdAt` igual. Defina também: `Mercado` 20000, `Transporte` 32000 e o geral
(sem `categoryId`) 400000.

## 4. Acompanhar o mês (US2)

`GET .../monthly-budgets?month=2026-11` (de 01/11 a 30/11) →

1. `totalSpentCents` **104000** (40000 + 20000 + 36000 + 8000; receita, transferência, excluído e os
   lançamentos de 31/10 e 01/12 não contam) e `spentOutsideCategoryLimitsCents` **8000** (Lazer).
2. `budgets` nesta ordem:
   - `Transporte`: limite 32000, gasto 36000, restante **-4000**, `percentUsed` 112.50, `EXCEEDED`;
   - `Alimentação`: 64000, gasto 60000 (direto + Mercado), restante 4000, 93.75, `WARNING`;
   - `Mercado`: 20000, gasto 20000, restante 0, 100.00, `WARNING`;
   - geral (`categoryId` nulo): 400000, gasto 104000, restante 296000, 26.00, `WITHIN`.
3. Defina `Lazer` com valor **0**: aparece `EXCEEDED` com `percentUsed` nulo e o fora dos limites cai para 0.
4. Sem `month` → o mês corrente (confira `month.id`). Um mês sem limites → `budgets` vazia, total gasto
   correto, sem erro.
5. Fim de mês: lance `EXPENSE` 100 em `2027-02-28` e `EXPENSE` 100 em `2027-03-01` e defina o limite geral de
   `2027-02` em 1000: o gasto é **100** (o de 01/03 fica fora). Em `2028-02`, um lançamento em `2028-02-29`
   (ano bissexto) conta no mês.

## 5. Ajustar e remover (US4)

1. `PUT .../monthly-budgets/{id do Transporte}` com `{"amountCents":36000}` → 200; no acompanhamento vira
   `WARNING` (100.00%) e `updatedAt` mudou, `createdAt` não.
2. `DELETE .../monthly-budgets/{id do Lazer}` → 204; some só de `2026-11`. Repetir → 404.

## 6. Copiar (US3)

1. Defina `Transporte` 12345 em `2027-01`.
2. `POST .../monthly-budgets/copy` com `{"fromMonth":"2026-11","months":3}` → `{created: 11,
   skippedExisting: 1, skippedArchivedCategory: 0}` (4 limites × 3 meses, e o de Transporte em `2027-01` já
   existia). Os destinos são `2026-12`, `2027-01` e `2027-02` (a virada de ano não pula nem repete mês). Em
   `2027-01` o Transporte continua 12345.
3. Arquive `Transporte` (`POST .../categories/{id}/archive`). Copie `{"fromMonth":"2026-11","months":4}` →
   `{created: 3, skippedExisting: 9, skippedArchivedCategory: 4}` (o mês novo é `2027-03`).
4. Em `2026-11`, o limite de Transporte (categoria arquivada) continua aparecendo e sendo acompanhado;
   `PUT .../monthly-budgets/{id}` nele → 409; `DELETE` → 204.

## 7. Recusas (US1, US3)

Todas 400, com o campo em `errors[].field`: `month` mal escrito (`2026-1`, `202611`, `2026-13`, `2026-00`,
`1999-12`, `2101-01`, `2026-11-01`, vazio), `amountCents` negativo, acima de `1000000000000`, `10.5`, ausente;
`categoryId` de receita (`Salário`); campos extras (`workspaceId`, `createdBy`, `createdAt`); `months` 0, 25 e
`2.5`; cópia que passaria de 2100 (`fromMonth` `2100-10`, `months` 3; com `months` 2 funciona). `categoryId`
de outro workspace ou inexistente → 404. Categoria arquivada em `PUT /monthly-budgets` → 409. Nada é gravado
em nenhuma delas.

## 8. Independência do semanal (FR-023)

1. Defina `PUT .../budgets` (semanal) com `{"week":"2026-W45","categoryId":"<Alimentação>","amountCents":16000}`.
   O acompanhamento de `2026-11` e o de `2026-W45` continuam cada um com os seus números; nenhum muda o outro.
2. Use o `id` de um limite semanal em `PUT`/`DELETE .../monthly-budgets/{id}` → 404, e o de um mensal em
   `.../budgets/{id}` → 404.
3. Paridade das regras: lance `EXPENSE` 8000 em `2027-04-07` (quarta-feira da semana `2027-W14`), `Alimentação`,
   e defina o limite de `Alimentação` em 10000 tanto em `2027-W14` (semanal) quanto em `2027-04` (mensal). Os
   dois acompanhamentos devolvem gasto 8000, restante 2000, `percentUsed` 80.00 e `WARNING` (exatamente 80%).

## 9. Papéis e isolamento (US5)

1. Semeie (via banco do dono) uma segunda pessoa como `VIEWER`: `GET .../monthly-budgets` → 200; `PUT`,
   `PUT /{id}`, `DELETE` e `POST /copy` → 403 e nada muda. Como `EDITOR`: tudo funciona, e o `createdBy` de um
   limite alheio não muda ao ajustá-lo.
2. Pessoa de outro workspace tenta consultar, definir, ajustar, remover e copiar no seu `$WID` → 404 igual ao
   de um workspace inexistente; um `id` de limite seu usado na rota do workspace dela → 404.
3. Sem cookie → 401.
4. Compare uma impressão digital de `transactions`, `accounts`, `categories` e `weekly_budgets` antes e depois
   de todas as operações mensais: igual (o orçamento mensal não toca neles).

## 10. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão (`monthly_budgets` e `weekly_budgets` primeiro) e
confira `users = workspaces = accounts = categories = transactions = weekly_budgets = monthly_budgets = 0`.
