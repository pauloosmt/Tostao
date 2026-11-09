# Data Model: Fatura do cartão de crédito

Esta feature **não adiciona tabela nem migration**. Tudo é calculado a cada consulta sobre `accounts` (spec 002),
`transactions` (spec 004) e `categories` (spec 003). Nada da fatura é guardado.

## Fontes de leitura

| Tabela | Colunas lidas | Filtros fixos |
|---|---|---|
| `accounts` | `id`, `type`, `closing_day`, `due_day`, `credit_limit_cents`, `archived`, `name` | `id = :a`, `workspace_id = :w` |
| `transactions` | `id`, `type`, `amount_cents`, `occurred_on`, `description`, `category_id` | `workspace_id = :w`, `account_id = :a`, `deleted_at is null`, `occurred_on` no intervalo |
| `categories` | `id`, `name` | junção por `id` e `workspace_id` |

## Regras de calendário (`InvoiceCalendar`)

Dados `closingDay` (C) e `dueDay` (D), de 1 a 31, e o mês de vencimento `M`:

| Regra | Definição |
|---|---|
| Vencimento | dia `min(D, tamanho de M)` de `M` |
| Mês do fechamento | `M` se `D > C`; `M - 1` se `D <= C` (comparação com os dias cadastrados) |
| Fechamento | dia `min(C, tamanho do mês do fechamento)` do mês do fechamento |
| Início do período | fechamento da fatura de `M - 1`, mais 1 dia |
| Fim do período | o fechamento desta fatura (incluído) |
| Fechamento da seguinte | fechamento da fatura de `M + 1` |
| Fatura aberta de uma data | a fatura de vencimento em `[mês de hoje - 1, mês de hoje + 2]` cujo período contém hoje |

Cada dia pertence a exatamente uma fatura (os fechamentos crescem estritamente de uma fatura para a seguinte).

## Regras de cálculo

| Regra | Definição |
|---|---|
| Valor de uma linha | `-amount_cents`: despesa e saída por transferência são positivas; receita (estorno) é negativa |
| Linhas da fatura | lançamentos não excluídos do cartão com `occurred_on` no período, **menos** as transferências que entram no cartão |
| Total | soma exata (`addExact`) das linhas |
| Pago | soma das transferências que entram no cartão (`type = 'TRANSFER'`, `amount_cents > 0`, não excluídas) com data depois do fechamento e até o fechamento da seguinte; em fatura aberta, zero |
| Restante | `max(total - pago, 0)` |
| Situação | `hoje <= fechamento` → `OPEN`; senão `restante = 0` → `PAID`; senão `hoje > vencimento` → `OVERDUE`; senão `CLOSED` |
| Devido do cartão | `max(-saldo, 0)`, com o saldo de `AccountService.get` |
| Limite disponível | `limite + saldo` (a dívida reduz, um crédito a favor aumenta); nulo quando o cartão não tem limite |

O saldo inicial do cartão nunca entra em nenhuma fatura. "Hoje" é a data no fuso de Brasília.

## Respostas

### `InvoiceResponse` (`GET /invoices/{month}`)

`month` (`AAAA-MM`), `periodStart`, `closingDate`, `dueDate`, `totalCents`, `paidCents`, `remainingCents`,
`status`, `lines[]`: `id`, `date`, `description`, `categoryId` (nulo), `categoryName` (nulo), `type`
(`EXPENSE`, `INCOME` ou `TRANSFER`), `chargeCents` (positivo = compra ou saque, negativo = estorno). Ordem das
linhas: data, depois `id`.

### `InvoiceListResponse` (`GET /invoices?from=&to=`)

`invoices[]` do vencimento mais recente para o mais antigo, cada uma com os mesmos campos acima, sem `lines`.

### `CardSummaryResponse` (`GET /card-summary`)

`accountId`, `name`, `archived`, `limitCents` (nulo), `owedCents`, `availableCents` (nulo), `openInvoice`
(nulo se o cartão não tem os dois dias): `month`, `periodStart`, `closingDate`, `dueDate`, `totalCents`.

Valores em centavos inteiros (`long`).

## Recusas

| Situação | Resposta |
|---|---|
| Conta inexistente, de outro workspace ou id malformado | 404 "Conta não encontrada" |
| Conta que não é cartão de crédito (qualquer rota) | 409, mensagem diz que a conta não é um cartão |
| Cartão sem `closingDay` e/ou `dueDay` (fatura e lista) | 409, mensagem diz quais campos faltam |
| `month`, `from` ou `to` mal escritos ou fora de 2000-01 a 2100-12 | 400 com o campo |
| `from` depois de `to`, ou mais de 36 meses | 400 no campo `from` / `to` |
