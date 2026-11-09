# Implementation Plan: Lançamento pendente

**Branch**: `009-lancamento-pendente` | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/009-lancamento-pendente/spec.md`

## Summary

Um lançamento passa a ter estado, **efetivado** ou **pendente**, e o pendente nunca conta no realizado: saldo das
contas, dashboard, orçamentos e fatura. A pessoa cria o lançamento já pendente, efetiva, ou muda o estado pela
edição; o previsto aparece no saldo previsto das contas, num bloco do dashboard e no filtro da listagem.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **O estado já existe no banco** (`transactions.status`, `PENDING` ou `CLEARED`, padrão `CLEARED`, com `CHECK`
  desde a `V1`). Nenhum dado a migrar. A única mudança de schema é a **migration `V8`**, que libera
  `UPDATE (status)` para a role da aplicação (a `V5` revogou o `UPDATE` geral e listou só as colunas editáveis).
  O RLS de `transactions` já cobre a escrita por papel.
- **Uma regra, aplicada em cada consulta que lê lançamentos para o realizado**: `status = 'CLEARED'`. São 10
  consultas em 4 módulos (conta/saldo, dashboard, gasto de orçamento, fatura). O filtro é uma linha em cada uma e
  fica visível no SQL; não há abstração nova (princípio VII). Os testes de "o pendente não conta" cobrem cada
  consulta, e a suíte inteira das specs 001 a 008 prova que nada muda para quem não tem pendentes (FR-017).
- **Escrita em `TransactionService`**: o estado entra na criação (opcional, padrão efetivado) e na edição
  (opcional; ausente mantém o atual). Uma rota nova, `POST /transactions/{id}/clear`, efetiva. Numa transferência
  as duas pontas mudam juntas, na mesma transação, pelo mesmo caminho que a edição e a exclusão já usam (trava
  das duas pontas em ordem de id).
- **Efetivar é idempotente**: o `UPDATE` só toca linhas que ainda estão pendentes, então efetivar de novo não
  altera nem `updated_at` (SC-006). Não confere conta nem categoria ativas, porque não é passar a usá-las.
- **Previsto**: a conta ganha `pendingCents` e `projectedBalanceCents`; a consulta de saldos ganha o previsto de
  cada conta e o total previsto; o dashboard ganha o bloco `pending`. O `balanceCents` e os totais que já existiam
  mantêm o significado (realizado).
- **Papéis**: leitura para os três; escrita (`@WorkspaceScoped(write = true)`) só OWNER e EDITOR; não membro
  recebe 404.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security (contexto
e aspecto da `001`), springdoc-openapi, Flyway. Nenhuma dependência nova.

**Storage**: PostgreSQL 17. Sem tabela nova; coluna `transactions.status` já existente; migration `V8` só de
privilégio.

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear e
inspecionar; dados aleatórios com semente fixa e cálculo independente; operações simultâneas em threads.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: listagem, saldos, dashboard, orçamentos e fatura em menos de 1 s com 20.000 lançamentos,
parte deles pendentes (SC-012).

**Constraints**: dinheiro em `BIGINT`/`long` de centavos, soma com `addExact`; toda consulta filtra por
`workspace_id`; nenhuma exceção capturada dentro de transação; entrada validada antes de qualquer gravação;
resposta de saída só com campos novos aditivos (nenhum campo existente muda de nome nem de significado).

**Scale/Scope**: um usuário hoje; dezenas de pendentes por mês.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; o estado nunca vem de flag do cliente além do campo validado; sem segredo novo | OK |
| II. Isolamento em duas camadas | Sem tabela nova; `UPDATE (status)` continua sob o RLS de `transactions` (workspace + `app_can_write`) e sob o filtro explícito por `workspace_id`; teste de acesso cruzado para cada operação nova e testes com cada camada removida | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rota sob o workspace e com sessão | OK |
| IV. Entrada validada, saída controlada | `status` validado como enum fechado (valor desconhecido recusado com o campo), campos extras recusados, DTOs de saída próprios, consultas parametrizadas | OK |
| V. Dados financeiros confiáveis | Centavos inteiros; o estado só afasta lançamentos do realizado, sem tocar em valor; exclusão lógica preservada; saldo continua calculado, nunca gravado; schema só por migration (`V8`), sem editar migration aplicada | OK |
| VI. Sem tentativa e erro em transação | Estado conferido e gravado em `UPDATE` condicional (`where status <> 'CLEARED'`); sem `catch` dentro de transação | OK |
| VII. Simplicidade | Uma coluna que já existia, uma rota, um filtro de uma linha em cada consulta; sem agendador, sem fila, sem abstração | OK |
| VIII. Prova, não promessa | Testes de cada consulta com pendente; ida e volta sem resíduo em dados aleatórios; transferência simultânea; acesso cruzado por camada; suíte completa das specs 001 a 008 como prova de não regressão; [quickstart.md](quickstart.md) real | OK |

**Ponto de atenção (alcance)**: é a primeira funcionalidade que muda o resultado de consultas de outras specs
(002, 005, 006, 007, 008). Mitigação: a mudança é só um filtro, os dados existentes são todos efetivados (então
nenhum resultado muda), e a suíte das specs anteriores roda inteira sem alteração (FR-017, SC-011).

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/009-lancamento-pendente/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Estado, regras e respostas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/db/migration/
│   └── V8__transaction_status.sql             # GRANT UPDATE (status) para tostao_app
├── src/main/java/com/tostao/tostao_api/
│   ├── transaction/
│   │   ├── TransactionStatus.java             # NOVO: CLEARED, PENDING
│   │   ├── TransactionService.java            # AJUSTE: status na criação e edição, clear(), filtro status
│   │   ├── TransactionController.java         # AJUSTE: POST /{id}/clear, parâmetro status na lista
│   │   ├── TransactionFilter.java             # AJUSTE: status
│   │   └── dto/                               # AJUSTE: status em Create/Update/Response
│   ├── account/
│   │   ├── AccountService.java                # AJUSTE: saldo só efetivado; pendente e previsto
│   │   └── dto/AccountResponse.java           # AJUSTE: pendingCents, projectedBalanceCents
│   ├── report/                                # AJUSTE: previsto por conta e total previsto
│   ├── dashboard/                             # AJUSTE: filtro de estado e bloco pending
│   │   └── dto/DashboardResponse.java
│   ├── budget/BudgetService.java              # AJUSTE: PERIOD_SPENT_SQL só efetivado (semanal e mensal)
│   └── invoice/InvoiceService.java            # AJUSTE: 4 consultas só efetivado
└── src/test/java/com/tostao/tostao_api/
    └── transaction/                           # PendingTransactionTest, ClearTransactionTest,
                                               # PendingNotCountedTest, ForecastTest, UpdateStatusTest,
                                               # PendingAccessTest, PendingLayersTest, PendingRandomDataTest,
                                               # PendingConcurrencyTest, PendingPerformanceTest
    (ajustes)                                  # OpenApiContractTest (contrato da 009)
```

**Structure Decision**: continua o monólito em camadas por módulo. Não há módulo novo: o estado é um atributo do
lançamento, então a escrita fica em `transaction` e a leitura é um filtro nas consultas dos módulos que já leem
lançamentos.

## Complexity Tracking

Sem violações da constituição. Uma escolha que merece registro:

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| Repetir `status = 'CLEARED'` em cada uma das 10 consultas, sem constante compartilhada | O filtro é uma linha que se lê no próprio SQL; as consultas já são constantes separadas por módulo e uma constante de texto compartilhada esconderia o filtro e acoplaria os módulos | Uma view `cleared_transactions`: exigiria migration com RLS e privilégios próprios, e os módulos deixariam de mostrar de onde vem o dado |
