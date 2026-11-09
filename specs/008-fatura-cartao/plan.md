# Implementation Plan: Fatura do cartão de crédito

**Branch**: `008-fatura-cartao` | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-fatura-cartao/spec.md`

## Summary

Fatura de cartão de crédito **calculada**, só de leitura: datas de fechamento e vencimento a partir dos dias
cadastrados na conta (spec 002), total e lançamentos do período (spec 004), pagamentos, situação e resumo do
cartão (limite, devido, disponível, fatura aberta), mais a lista de faturas de um intervalo de meses.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Nenhuma tabela nem migration.** A fatura nunca é guardada (princípio V da constituição); tudo sai de
  `accounts` e `transactions`. Por isso não há RLS novo: as consultas rodam como `tostao_app` e herdam o RLS
  existente das duas tabelas, mais o filtro explícito por `workspace_id`.
- **Calendário como regra pura**: `InvoiceCalendar` (sem Spring nem banco) calcula fechamento, vencimento,
  início do período, fechamento da fatura seguinte, situação e a fatura aberta de uma data. É onde moram os
  casos difíceis (dia 31 em mês curto, vencimento antes ou depois do fechamento) e é testada em volta, sem
  Docker (princípio VIII).
- **Rotas** sob `/api/v1/workspaces/{wid}/accounts/{accountId}`: `GET /invoices/{month}` (fatura com
  lançamentos), `GET /invoices?from=&to=` (lista sem lançamentos) e `GET /card-summary` (resumo).
- **Uma leitura consistente**: `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` em todas; a
  lista agrega os lançamentos do cartão por dia e tipo numa única consulta e distribui nas faturas em Java.
- **Devido igual ao saldo**: o resumo chama `AccountService.get` (o mesmo cálculo da spec 002), então o devido
  nunca diverge do saldo (FR-016).
- **Papéis**: leitura para os três papéis (`@WorkspaceScoped` sem `write`); não membro recebe 404.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Spring Security (contexto e aspecto da
`001`), springdoc-openapi. Nenhuma dependência nova.

**Storage**: PostgreSQL 17. Sem tabela nova; leitura de `accounts`, `transactions` e `categories`.

**Testing**: JUnit 5 (regras puras sem Docker), MockMvc, Testcontainers (Postgres real, role `tostao_app`),
`OwnerJdbc` para semear e inspecionar; dados aleatórios com semente fixa e cálculo independente.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: fatura, resumo e lista de 36 meses em menos de 1 s cada, com 20.000 lançamentos (SC-009).

**Constraints**: dinheiro em `long` de centavos, somas com `addExact`; toda consulta filtra por `workspace_id`;
nenhuma exceção capturada dentro de transação; mês e intervalo validados antes de qualquer consulta.

**Scale/Scope**: um usuário hoje; centenas de lançamentos por fatura; no máximo 36 faturas por lista.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; nenhum dado de papel vem do cliente; sem segredo novo | OK |
| II. Isolamento em duas camadas | Consultas filtram `workspace_id` (serviço) e rodam sob o RLS de `accounts` e `transactions` (dados); teste de acesso cruzado por consulta e testes com cada camada removida; conta de outro workspace é "não encontrada" | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rotas sob o workspace e com sessão | OK |
| IV. Entrada validada, saída controlada | Mês e intervalo validados com `Period.ofMonth` (campo no erro), DTOs de saída próprios, consultas parametrizadas, erro em Problem Details | OK |
| V. Dados financeiros confiáveis | Centavos inteiros, soma exata, fatura calculada e nunca gravada; só lê lançamentos não excluídos; calendário a partir dos dias de fechamento e vencimento, como a constituição manda | OK |
| VI. Sem tentativa e erro em transação | Só leituras; as recusas (não é cartão, sem dias) são checadas antes de qualquer cálculo, sem `catch` | OK |
| VII. Simplicidade | Sem tabela, sem cache, sem visão materializada; uma classe pura de calendário, um serviço, um controller | OK |
| VIII. Prova, não promessa | Calendário e situação como testes unitários; integração com Postgres real; soma independente em dados aleatórios; partição de dias provada; isolamento por camada; desempenho com 20.000 lançamentos; [quickstart.md](quickstart.md) | OK |

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/008-fatura-cartao/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Regras de cálculo e respostas (sem tabela)
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/tostao/tostao_api/
│   ├── invoice/                               # módulo novo, só leitura
│   │   ├── InvoiceCalendar.java               # regras puras: datas, partição, situação, fatura aberta
│   │   ├── InvoiceStatus.java                 # OPEN, CLOSED, PAID, OVERDUE
│   │   ├── InvoiceService.java                # invoice, invoices, summary
│   │   ├── InvoiceController.java             # 3 rotas GET
│   │   └── dto/                               # InvoiceResponse, InvoiceLine, InvoiceListItem,
│   │                                          # InvoiceListResponse, CardSummaryResponse
│   ├── common/error/                          # InvoiceNotAvailableException (+ handler 409)
│   └── dashboard/Period.java                  # + today(Instant) público
└── src/test/java/com/tostao/tostao_api/
    ├── invoice/                               # InvoiceCalendarTest (unitário), InvoiceScenario,
    │                                          # InvoiceTest, CardSummaryTest, InvoiceListTest,
    │                                          # InvoiceAccessTest, InvoiceLayersTest, InvoiceRandomDataTest,
    │                                          # InvoicePerformanceTest
    ├── dashboard/PeriodTest.java              # + today
    └── OpenApiContractTest.java               # + contrato da 008
```

**Structure Decision**: continua o monólito em camadas por módulo. `invoice` é um módulo novo que só **lê**
`accounts`, `transactions` e `categories`; não grava nada.

## Complexity Tracking

Sem violações da constituição e sem escolhas que fujam do padrão (não há tabela, nem `DELETE`, nem extração de
código da spec anterior).
