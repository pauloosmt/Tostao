# Quickstart: validar o Dashboard mensal e semanal

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado.
- Stack local de pé: `docker compose up -d --build` (esta feature não tem migration: o banco continua na
  `V5`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 (`--data-binary @arquivo`) no
  Windows.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos os testes passam, inclusive os novos de `dashboard/` e o `OpenApiContractTest` com o
contrato da `005`.

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace. Pegue as categorias
   padrão (`GET .../categories`): guarde o `id` de "Alimentação" (despesa), de uma subcategoria dela e de
   "Salário" (receita).
2. Crie duas contas: `Corrente` (`CHECKING`, saldo inicial 100000) e `Poupança` (`SAVINGS`, saldo 0).
3. Lance (`POST .../transactions`):
   - `EXPENSE` 20000 na Corrente em `2026-10-30`, "Alimentação" (mês e semana anteriores);
   - `EXPENSE` 10000 na Corrente em `2026-11-02`, "Alimentação" direto;
   - `EXPENSE` 5000 na Corrente em `2026-11-03`, na subcategoria de Alimentação;
   - `INCOME` 250000 na Corrente em `2026-11-05`, "Salário";
   - `TRANSFER` 30000 de Corrente para Poupança em `2026-11-06`.
   - Um lançamento qualquer em `2026-11-04` que você **exclui** (`DELETE`) em seguida.

## 3. Semana (US4 e US1)

`GET $BASE/workspaces/$WID/dashboard?week=2026-W45` (segunda 02/11 a domingo 08/11) →

1. `period`: `WEEK`, `2026-W45`, `2026-11-02` a `2026-11-08`.
2. `totals`: receitas `250000`, despesas `15000`, resultado `235000` (a transferência e o excluído não
   contam).
3. `expenseByCategory`: um grupo "Alimentação" com `totalCents` 15000, `directCents` 10000 e a
   subcategoria com 5000 (percentuais 100,00 e 33,33); `incomeByCategory`: "Salário" 250000, 100,00.
4. `daily`: 7 itens (02/11 a 08/11), com despesa 10000 no dia 02, 5000 no 03, receita 250000 no 05 e zeros
   nos outros.
5. `comparison`: `previousPeriod` `2026-W44`; despesas `previousCents` 20000, `changeCents` `-5000`,
   `changePercent` `-25.00`; receitas `previousCents` 0 e `changePercent` nulo.
6. `balances`: Corrente `openingBalanceCents` 80000, `closingBalanceCents` 285000; Poupança 0 e 30000;
   `openingTotalCents` 80000, `closingTotalCents` 315000; `daily[].balanceCents`: 70000, 65000, 65000,
   315000, 315000, 315000, 315000 (o do dia 06 não muda por causa da transferência).

## 4. Mês (US1 a US3, US5)

`GET .../dashboard?month=2026-11` → `totals` iguais aos da semana (todos os lançamentos estão na primeira
semana de novembro); `daily` com 30 itens; `comparison.previousPeriod` `2026-10`, despesas anteriores 20000.
Sem `month` nem `week` → o mês corrente (confira `period.id`).

Saldo de hoje: `balances.accounts[].balanceCents` e `totalBalanceCents` (315000) idênticos aos de
`GET .../reports/balance`.

## 5. Mês sem lançamentos e zeros (US3, US1)

`GET .../dashboard?month=2027-03` → `totals` zerados, listas de grupos vazias, `daily` com 31 itens de zeros
e `balance` 315000 em todos os dias, `comparison` com `changePercent` nulo.

## 6. Recusas (US7)

Todas 400, com o campo em `errors[].field`: `month=2026-13`, `month=2026-1`, `month=novembro`,
`month=2026-11-05`, `month=1999-12`, `month=2101-01`, `month=` (vazio); `week=2025-W53` (2025 só tem 52),
`week=2026-W54`, `week=2026W45`, `week=1999-W52`; `month=2026-11&week=2026-W45` (os dois juntos).
`week=2026-W53` é válida (2026 tem 53 semanas).

## 7. Papéis e isolamento (US6)

1. Convide ou semeie (via banco do dono) uma segunda pessoa como `VIEWER`: `GET .../dashboard` → 200.
2. Outra pessoa de outro workspace tenta o seu `$WID` → 404, igual a um workspace inexistente.
3. Sem cookie → 401.
4. `POST`, `PUT` e `DELETE` em `.../dashboard` → 405; nenhuma linha mudou.

## 8. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão no banco local (a rotina das specs anteriores) e
confira `users = workspaces = accounts = categories = transactions = 0`.
