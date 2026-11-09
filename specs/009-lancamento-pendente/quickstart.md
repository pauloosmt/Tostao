# Quickstart: validar o Lançamento pendente

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e stack local reconstruída: `docker compose up -d --build` (a migration `V8` roda na
  subida; confira `select version from flyway_schema_history order by installed_rank desc limit 1` → `8`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 no Windows.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos passam, inclusive os das specs 001 a 008 **sem alteração de resultado** (FR-017).

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace. Pegue `Alimentação`
   (despesa), `Salário` (receita) e `Mercado` (subcategoria).
2. Crie `Corrente` (`CHECKING`, saldo inicial 100000) e `Poupança` (`SAVINGS`, 0).
3. Lance (`POST .../transactions`):
   - `EXPENSE` 5000 em `2026-11-02`, `Alimentação`, **sem** `status` → efetivado (`"status":"CLEARED"` na resposta);
   - `EXPENSE` 80000 em `2026-11-20`, `Alimentação`, `"status":"PENDING"`;
   - `INCOME` 30000 em `2026-11-25`, `Salário`, `"status":"PENDING"`;
   - `TRANSFER` 10000 de `Corrente` para `Poupança` em `2026-11-28`, `"status":"PENDING"`.
4. Defina o limite mensal de `Alimentação` em 100000 para `2026-11` (`PUT .../monthly-budgets`).

## 3. O pendente não mexe no realizado (US1)

1. `GET .../accounts` → `Corrente`: `balanceCents` **95000** (100000 − 5000), `pendingCents` **-60000**
   (−80000 + 30000 − 10000), `projectedBalanceCents` **35000**; `Poupança`: `balanceCents` 0, `pendingCents`
   **10000**, `projectedBalanceCents` **10000**.
2. `GET .../reports/balance` → `totalBalanceCents` **95000** e `totalProjectedCents` **45000**.
3. `GET .../dashboard?month=2026-11` → `totals`: receita 0, despesa **5000**, resultado −5000 (os pendentes não
   entram nos totais, nos grupos nem nos saldos), e `pending`: `incomeCents` **30000**, `expenseCents` **80000**,
   `count` **2** (a transferência não entra).
4. `GET .../monthly-budgets?month=2026-11` → `Alimentação`: gasto **5000** (5,00%, `WITHIN`); o pendente de 80000
   não conta.
5. Estado inválido: `POST .../transactions` com `"status":"OUTRO"` → 400, campo `status`, nada criado.

## 4. Listagem e filtro (US4)

`GET .../transactions?status=PENDING` → **4** itens (a despesa, a receita e as duas pontas da transferência), todos
`PENDING`; `?status=CLEARED` → 1 item (a despesa de 5000); sem filtro → 5. `?status=OUTRO` → 400, campo `status`.

## 5. Efetivar (US2)

1. `POST .../transactions/{id da despesa de 80000}/clear` → 200, `status` **CLEARED**, `updatedAt` mudou.
2. Confira: `Corrente` `balanceCents` **15000** (100000 − 5000 − 80000), `pendingCents` **20000** (+30000
   − 10000), `projectedBalanceCents` **35000** (efetivar não muda o previsto); orçamento mensal de `Alimentação`:
   gasto **85000**, 85,00%, `WARNING`; dashboard: despesa **85000**, resultado −85000 e `pending` com receita
   30000, despesa 0 e `count` **1** (a transferência não conta).
3. Repita o `clear` da mesma despesa → 200 e `updatedAt` **igual** ao de antes (nenhum efeito).
4. `POST .../transactions/{id de uma ponta da transferência}/clear` → as **duas** pontas `CLEARED`:
   `Corrente` `balanceCents` **5000**, `Poupança` `balanceCents` **10000**.

## 6. Mudar o estado pela edição (US5)

1. `PUT .../transactions/{id da despesa de 80000}` com os mesmos campos e `"status":"PENDING"` → 200, `PENDING`;
   `Corrente` volta a `balanceCents` **85000** (100000 − 5000 − 10000 da transferência já efetivada) e
   `pendingCents` **-50000** (−80000 + 30000); o gasto de `Alimentação` volta a **5000**.
2. `PUT` sem `status` nesse mesmo lançamento → mantém `PENDING`.
3. `PUT` com `"status":"CLEARED"` e `occurredOn` de hoje → efetiva e muda a data na mesma operação.

## 7. Fatura do cartão (US1, cenário 5)

1. Crie o cartão `Visa` (`CREDIT_CARD`, fecha dia 20, vence dia 28) e lance `EXPENSE` 7000 em `2024-11-05`,
   `"status":"PENDING"`: `GET .../accounts/{visa}/invoices/2024-11` → `totalCents` **0**, sem linhas;
   `GET .../card-summary` → `owedCents` **0**.
2. `POST .../transactions/{id}/clear` → a fatura passa a `totalCents` **7000** (1 linha) e o devido a **7000**.

## 8. Papéis e isolamento (US3)

1. Semeie (via banco do dono) uma segunda pessoa como `VIEWER`: `GET .../transactions` → 200 com o estado de
   cada item; `POST .../clear`, `POST .../transactions` com `status` e `PUT` com `status` → 403 e nada muda. Como
   `EDITOR`: funciona, e o `createdBy` não muda.
2. Pessoa de outro workspace tenta efetivar, criar pendente e editar o estado no seu `$WID` → 404 igual ao de um
   workspace inexistente; um `id` de lançamento seu usado no workspace dela → 404. Sem cookie → 401.

## 9. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão e confira `users = workspaces = accounts =
categories = transactions = weekly_budgets = monthly_budgets = 0`.
