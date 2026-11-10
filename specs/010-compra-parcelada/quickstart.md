# Quickstart: validar a Compra parcelada

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e stack local reconstruída: `docker compose up -d --build` (a migration `V9` roda na
  subida; confira `select version from flyway_schema_history order by installed_rank desc limit 1` → `9`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 no Windows.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos passam, inclusive os das specs 001 a 009 **sem alteração de resultado** (FR-020).

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace. Pegue `Alimentação`
   (despesa) e `Salário` (receita).
2. Crie `Corrente` (`CHECKING`, saldo inicial 500000) e o cartão `Visa` (`CREDIT_CARD`, fecha dia 20, vence dia 28).
3. Defina o limite mensal de `Alimentação` em 500000 para `2026-11` (`PUT .../monthly-budgets`).

## 3. Registrar uma compra parcelada (US1)

1. **Notebook** (`A`): `POST .../installment-purchases` com `Corrente`, `Alimentação`, `totalCents` **300000**,
   `installmentCount` **3**, `firstOccurredOn` **2026-11-10**, `description` "Notebook" → 201, `Location` para o
   plano. `installments`: 3 itens de `amountCents` **-100000**, datas **2026-11-10**, **2026-12-10**, **2027-01-10**,
   descrições "Notebook (1/3)", "Notebook (2/3)", "Notebook (3/3)", estados **CLEARED, PENDING, PENDING**, mesmo
   `installmentGroupId`, `installmentNumber` 1..3 e `installmentCount` 3. Plano: `totalCents` 300000,
   `clearedCents` **100000**, `pendingCents` **200000**.
2. Só a primeira conta: `GET .../accounts` → `Corrente`: `balanceCents` **400000**, `pendingCents` **-200000**,
   `projectedBalanceCents` **200000**.
3. **Café** (`B`): `totalCents` **10000**, `installmentCount` 3, `firstOccurredOn` 2026-11-12 → valores
   **3334, 3333, 3333** (soma 10000). `Corrente`: `balanceCents` **396666**, `pendingCents` **-206666**.
4. **Fim de mês** (`C`): `totalCents` **4000**, `installmentCount` 4, `firstOccurredOn` **2027-01-31**,
   `"firstInstallmentStatus":"PENDING"` → 4 parcelas de **-1000** em **2027-01-31, 2027-02-28, 2027-03-31,
   2027-04-30**, todas **PENDING**. `Corrente`: `balanceCents` **396666** (não mudou), `pendingCents` **-210666**.
5. `GET .../transactions?size=100` → cada parcela traz `installmentGroupId`, `installmentNumber` e
   `installmentCount`; um lançamento comum (crie um qualquer) traz os três **nulos**.

## 4. Só o efetivado conta (US1, cenário 3)

1. `GET .../dashboard?month=2026-11` → `totals.expenseCents` **103334** (100000 + 3334; as parcelas pendentes e as
   de outros meses não entram).
2. `GET .../monthly-budgets?month=2026-11` → `Alimentação`: gasto **103334**.
3. Fatura: `POST .../installment-purchases` na `Visa`, `Alimentação`, `totalCents` **9000**, 3 parcelas, primeira em
   **2026-11-05**, descrição "Fone" → `GET .../accounts/{visa}/invoices/2026-11` → `totalCents` **3000** (1 linha);
   `.../invoices/2026-12` → `totalCents` **0** (a parcela 2 está pendente). Efetive a parcela 2
   (`POST .../transactions/{id}/clear`) → a fatura `2026-12` passa a `totalCents` **3000**.

## 5. Validação (US1, cenários 7 a 10)

Anote `totalItems` da listagem antes e confira que **não muda** depois de todos os casos abaixo (nada é criado):

| Entrada | Esperado |
|---|---|
| `installmentCount` 1, 49, `3.5`, ausente, `"abc"` | 400, campo `installmentCount` |
| `totalCents` 2 com 3 parcelas | 400, campo `totalCents` |
| `totalCents` 0, -1, 1000000000001, `10.5`, ausente | 400, campo `totalCents` |
| `firstOccurredOn` 1999-12-31; 2099-12-31 com 24 parcelas; texto inválido | 400, campo `firstOccurredOn` |
| `description` vazia, só espaços, 191 caracteres | 400, campo `description` |
| `description` com 190 caracteres | 201 |
| `firstInstallmentStatus` "OUTRO", "" | 400, campo `firstInstallmentStatus` |
| campo extra (`createdBy`, `workspaceId`, `installmentGroupId`, `status`) | 400 |
| categoria de receita (`Salário`) | 400, campo `categoryId` |
| conta ou categoria arquivada | 409 |
| conta ou categoria inexistente (UUID aleatório) | 404 |

## 6. Ver o plano e o efeito de editar e excluir (US2)

1. `GET .../installment-purchases/{grupo de A}` → 200: `description` **"Notebook"**, `installmentCount` 3,
   `totalCents` 300000, `clearedCents` 100000, `pendingCents` 200000, 3 itens em ordem de número.
2. Efetive a parcela 2 de `A` → o plano passa a `clearedCents` **200000**, `pendingCents` **100000**; `Corrente`
   `balanceCents` **296666**.
3. Exclua a parcela 3 (`DELETE .../transactions/{id}`) → o plano tem **2** itens, `totalCents` **200000**,
   `pendingCents` **0** e `installmentCount` ainda **3**. `Corrente` `pendingCents` **-10666** (6666 do café e 4000
   do fim de mês) e `projectedBalanceCents` **286000**.
4. `GET` com um UUID aleatório, com o grupo de outro workspace e com `abc` → 404.

## 7. Cancelar o que falta (US3)

1. `POST .../installment-purchases/{grupo de B}/cancel` → 200 `{"cancelled":2}`. Repita → `{"cancelled":0}`. A
   parcela 1 (efetivada) continua; `Corrente` `balanceCents` **296666** (igual), `pendingCents` **-4000** e
   `projectedBalanceCents` **292666**.
2. Cancele `C` → `{"cancelled":4}`; `GET` do plano de `C` → 200 com **lista vazia**, totais **0** e
   `installmentCount` 4. `Corrente` `pendingCents` **0**.
3. Cancele `A` (2 efetivadas e 1 excluída) → `{"cancelled":0}`; nada muda.
4. UUID aleatório, grupo de outro workspace e `abc` → 404, nada muda.

## 8. Papéis e isolamento (US4)

1. Semeie (via banco do dono) uma segunda pessoa como `VIEWER`: `GET` do plano → 200 igual ao do dono;
   `POST .../installment-purchases` e `POST .../cancel` → 403 e nada muda. Como `EDITOR`: registra e cancela,
   inclusive plano de outra pessoa, e o `createdBy` das parcelas não muda.
2. Pessoa de outro workspace tenta registrar, consultar e cancelar no seu `$WID` → 404 igual ao de um workspace
   inexistente (mesmo `title` e `detail`); um grupo seu usado no workspace dela → 404. Sem cookie → 401.

## 9. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão e confira `users = workspaces = accounts =
categories = transactions = weekly_budgets = monthly_budgets = 0`.
