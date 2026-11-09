# Quickstart: validar a Fatura do cartão

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md). Não há migration nesta feature.

## Pré-requisitos

- Docker Desktop ligado e stack local no ar: `docker compose up -d --build`.
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 no Windows.
- As datas abaixo são de 2024 de propósito: com "hoje" depois delas, a situação das faturas é sempre a mesma.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace.
2. Crie a conta `Corrente` (`CHECKING`, saldo inicial 0) e o cartão `Visa` (`CREDIT_CARD`, saldo inicial 0,
   `creditLimitCents` 500000, `closingDay` 20, `dueDay` 28). Crie também `Poupança` (`SAVINGS`) só para ter
   uma conta que não é cartão.
3. Lance no cartão (`POST .../transactions`), com uma categoria de despesa para as compras:
   - `EXPENSE` 12345 em `2024-10-20` (dia do fechamento de outubro);
   - `EXPENSE` 30000 em `2024-10-21` (primeiro dia da fatura de novembro);
   - `EXPENSE` 20000 em `2024-11-05`;
   - `INCOME` 4000 em `2024-11-12` (estorno);
   - `TRANSFER` do `Visa` para a `Corrente` de 6000 em `2024-11-15` (saque);
   - `EXPENSE` 10000 em `2024-11-20` (dia do fechamento de novembro);
   - `EXPENSE` 5000 em `2024-11-21` (cai na fatura de dezembro);
   - `EXPENSE` 7777 em `2024-11-10`, que você **exclui** em seguida;
   - `TRANSFER` da `Corrente` para o `Visa` de 25000 em `2024-11-25` (pagamento).

## 3. Fatura de novembro (US1, US3)

`GET $BASE/workspaces/$WID/accounts/$CARD/invoices/2024-11` →

1. `month` `2024-11`, `periodStart` `2024-10-21`, `closingDate` `2024-11-20`, `dueDate` `2024-11-28`.
2. `totalCents` **62000** (30000 + 20000 − 4000 + 6000 + 10000), com 5 linhas em ordem de data: 21/10 30000,
   05/11 20000, 12/11 -4000 (estorno), 15/11 6000 (saque, `TRANSFER`), 20/11 10000. A compra de 20/10 (outra
   fatura), a de 21/11, a excluída e o pagamento **não** aparecem.
3. `paidCents` **25000** (a transferência de 25/11), `remainingCents` **37000**, `status` **OVERDUE** (hoje é
   depois do vencimento de 28/11/2024 e ainda resta valor).

`GET .../invoices/2024-10` → `periodStart` `2024-09-21`, `closingDate` `2024-10-20`, `dueDate` `2024-10-28`,
`totalCents` **12345**, `paidCents` 0, `OVERDUE`. `GET .../invoices/2024-12` → `periodStart` `2024-11-21`,
`closingDate` `2024-12-20`, `dueDate` `2024-12-28`, `totalCents` **5000**.

Pagamento que cobre: lance outra `TRANSFER` de 37000 da `Corrente` para o `Visa` em `2024-12-01` → a fatura de
novembro fica com `paidCents` 62000, `remainingCents` 0 e `status` **PAID**. Um mês sem lançamentos
(`2024-01`) → lista vazia, total 0 e, como já fechou, `PAID`.

## 4. Calendário (US1)

Edite o cartão para `closingDay` 25 e `dueDay` 5: `GET .../invoices/2024-12` → `closingDate` `2024-11-25`,
`dueDate` `2024-12-05`, `periodStart` `2024-10-26`. Edite para 31 e 31: `GET .../invoices/2027-02` →
`closingDate` e `dueDate` `2027-02-28`, `periodStart` `2027-01-01`; `2028-02` → 29/02. Volte para 20 e 28.

## 5. Lista (US4)

`GET .../invoices?from=2024-09&to=2024-12` → quatro faturas, de `2024-12` a `2024-09`, cada uma igual à
consulta individual do mês, sem `lines`. Sem parâmetros → seis faturas até a aberta. Recusas 400 com o campo:
`from=2024-1`, `to=2101-01`, `from=2024-12&to=2024-01`, intervalo de 37 meses.

## 6. Resumo (US2)

`GET .../card-summary` → `limitCents` 500000; `owedCents` **54345**, porque o saldo do cartão é
−(12345 + 30000 + 20000 + 10000 + 5000) + 4000 − 6000 + 25000 = −54345 (se você já lançou o pagamento extra de
37000 do passo 3, o devido é **17345**); `availableCents` = 500000 − devido (**445655** no primeiro caso);
`openInvoice` com o mês de hoje e `closingDate` no dia 20. O `owedCents` é igual ao saldo negativo de
`GET .../accounts/$CARD`.

## 7. Recusas e papéis (US5)

1. `GET .../accounts/$POUPANCA/invoices/2024-11` e `.../card-summary` → 409 dizendo que não é cartão.
2. Crie um cartão sem `closingDay`/`dueDay`: `GET .../invoices/2024-11` → 409 listando os dias que faltam;
   `GET .../card-summary` → 200 com `openInvoice` nulo.
3. Segunda pessoa como `VIEWER` (via banco do dono): as três consultas → 200.
4. Pessoa de outro workspace nas três rotas → 404 igual ao de um workspace inexistente; o `id` de um cartão seu
   usado no workspace dela → 404. Sem cookie → 401. `month` `2024-13` → 400 campo `month`.
5. Compare uma impressão digital de `accounts`, `transactions` e `categories` antes e depois de todas as
   consultas: igual (a fatura só lê).

## 8. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão e confira `users = workspaces = accounts =
categories = transactions = 0`.
