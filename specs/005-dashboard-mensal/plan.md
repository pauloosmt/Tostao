# Implementation Plan: Dashboard mensal e semanal

**Branch**: `005-dashboard-mensal` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-dashboard-mensal/spec.md`

## Summary

Uma consulta de leitura que resume um período (um mês ou uma semana ISO) de um workspace: totais de
receitas, despesas e resultado, grupos por categoria, evolução diária, comparação com o período anterior e
saldos (o de hoje, o de abertura, o de fechamento e o de cada dia). Fecha a fase 1 e não grava nada.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Sem schema novo**: nenhuma migration, tabela, coluna ou índice. O painel só lê `transactions`,
  `accounts` e `categories`, que já têm RLS de leitura por workspace (`V2` a `V5`) e os índices parciais da
  `V1` por data, conta e categoria. Um índice só entra se a medição com 20.000 lançamentos pedir.
- **Uma rota**: `GET /api/v1/workspaces/{wid}/dashboard` com `month=AAAA-MM` **ou** `week=AAAA-Www`. Sem
  nenhum dos dois, vale o mês corrente no fuso de Brasília. Os dois juntos são 400.
- **Períodos como regra pura**: o tipo `Period` (mês ou semana ISO, de segunda a domingo) calcula início,
  fim, período anterior e os dias do período, e a validação do identificador (formato, existência da semana,
  faixa 2000 a 2100). É testado sem Spring nem banco (princípio VIII).
- **Uma leitura consistente**: o serviço roda numa só transação `READ ONLY` com isolamento
  `REPEATABLE READ` (como a listagem da `004`), então totais, grupos, dias, comparação e saldos vêm do mesmo
  instante, mesmo com gravações simultâneas.
- **Três consultas agregadoras**, todas parametrizadas e filtradas por `workspace_id`:
  1. receita e despesa **por dia** no intervalo `[início do período anterior, fim do período]` (sem
     `TRANSFER`, sem excluídos). Os totais, a evolução e a comparação saem **somando essas linhas em Java**,
     então a soma dos dias é igual ao total por construção;
  2. receita e despesa **por categoria** no período, agrupadas por categoria principal e subcategoria (com
     lançamentos sem categoria à parte);
  3. **saldos** das contas ativas: saldo inicial mais lançamentos não excluídos até cada data de corte
     (abertura, fechamento e hoje), e o **movimento por dia** das contas ativas (com transferências), de onde
     sai o saldo total ao fim de cada dia.
- **Regras puras testadas à parte**: percentuais (duas casas, metade para cima, sem divisão por zero),
  variação, ordenação e montagem dos grupos e saldo acumulado ficam em `DashboardRules`, com teste unitário.
- **Papéis**: leitura para os três papéis (`@WorkspaceScoped` sem `write`); não-membro recebe 404.
- **Escopo**: somente backend. A tela do dashboard fica no roadmap de front-end.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Spring Security (contexto e aspecto da
`001`), springdoc-openapi. Nenhuma dependência nova. `Clock` já existe como bean (usado pelo JWT).

**Storage**: PostgreSQL 17, tabelas `transactions`, `accounts` e `categories` existentes, só leitura. Sem
migration.

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear
dados e outros workspaces; teste de consistência com gravações em threads; dados aleatórios com semente fixa
e um cálculo independente no teste.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: painel de um workspace com 20.000 lançamentos em menos de 1 s, para mês e semana
(SC-008), com os índices parciais da `V1`.

**Constraints**: dinheiro em `BIGINT` de centavos; somas em Java com `addExact`; percentuais em `BigDecimal`;
toda consulta filtra por `workspace_id`; nenhuma gravação; nenhuma exceção capturada dentro de transação.

**Scale/Scope**: um usuário hoje, milhares de lançamentos por workspace, no máximo ~62 dias por consulta.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; nada vindo do cliente decide acesso; sem segredo novo | OK |
| II. Isolamento em duas camadas | `@WorkspaceScoped` + filtro explícito por `workspace_id` em todas as consultas (inclusive nos `join` de contas e categorias) e RLS de leitura já existente; teste de acesso cruzado e testes com cada camada removida | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rota sob `/api/v1/workspaces/{wid}` e com sessão | OK |
| IV. Entrada validada, saída controlada | `month` e `week` validados por formato estrito e por regras de calendário, erro por campo em Problem Details; consultas parametrizadas; DTO de saída próprio | OK |
| V. Dados financeiros confiáveis | Centavos em `BIGINT`/`long`, sem float; percentuais em `BigDecimal`; saldo sempre calculado, nunca gravado; sem mudança de schema; lançamentos excluídos nunca contam | OK |
| VI. Sem tentativa e erro em transação | Só leitura, sem `catch` dentro de transação; validação de entrada antes de abrir qualquer consulta | OK |
| VII. Simplicidade | Sem tabela de resumo, sem cache, sem visão materializada, sem índice sem medição; uma rota, três consultas | OK |
| VIII. Prova, não promessa | Teste unitário das regras de período, percentual e saldo; integração com Postgres real; cálculo independente em dados aleatórios; acesso cruzado; desempenho com 20.000 lançamentos; [quickstart.md](quickstart.md) com chamadas reais | OK |

Sem violações. A seção Complexity Tracking não se aplica.

Reavaliação depois do desenho (Phase 1): nenhuma mudança. Dois pontos pedem atenção na implementação: o
saldo diário e os saldos de abertura e de fechamento valem só para contas **ativas** (como o saldo total da
`002`), e o saldo atual do painel é calculado na mesma consulta dos de corte, então um teste prova que ele é
idêntico ao de `GET /reports/balance`.

## Project Structure

### Documentation (this feature)

```text
specs/005-dashboard-mensal/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Modelo de leitura: consultas, resposta e regras
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Gerado por /speckit-tasks
```

### Source Code (repository root)

```text
backend/
├── src/main/java/com/tostao/tostao_api/
│   └── dashboard/                           # DashboardController, DashboardService, Period (mês ou semana
│       │                                    # ISO), DashboardRules (regras puras: percentual, variação,
│       │                                    # grupos, saldo acumulado)
│       └── dto/                             # DashboardResponse (com PeriodInfo, Totals, CategoryGroup,
│                                            # CategoryItem, DayPoint, Comparison, Change, AccountBalances)
└── src/test/java/com/tostao/tostao_api/
    └── dashboard/                           # PeriodTest e DashboardRulesTest (unitários),
                                             # MonthlyDashboardTest, WeeklyDashboardTest,
                                             # CategoryGroupsTest, ComparisonTest, DashboardBalancesTest,
                                             # DashboardValidationTest, DashboardAccessTest,
                                             # DashboardRlsTest, DashboardServiceFilterTest,
                                             # DashboardReadOnlyTest, DashboardRandomDataTest,
                                             # DashboardConsistencyTest, DashboardPerformanceTest
    (ajustes)                                # OpenApiContractTest (contrato da 005);
                                             # WorkspaceFixtures (helpers de lançamento por data, se faltar)
```

**Structure Decision**: continua o monólito em camadas por módulo. `dashboard` é um módulo só de leitura,
como `report`; não toca em `account`, `category` nem `transaction`. O relatório de saldo da `002` não
muda.

## Complexity Tracking

Sem violações da constituição a justificar.
