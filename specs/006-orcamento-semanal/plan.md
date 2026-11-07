# Implementation Plan: Orçamento semanal

**Branch**: `006-orcamento-semanal` | **Date**: 2026-11-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-orcamento-semanal/spec.md`

## Summary

Limites de gasto por semana (de segunda a domingo, a mesma da `005`), por categoria de despesa ou gerais, e o
acompanhamento da semana: gasto, restante, percentual e estado de cada limite, mais o total gasto e o gasto
fora dos limites de categoria. Também ajusta, remove e copia limites para as semanas seguintes.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Uma tabela nova, `weekly_budgets`** (migration `V6`): `workspace_id`, `week_start` (a segunda-feira),
  `category_id` (nulo = limite geral), `amount_cents`, `created_by`, datas. Unicidade por
  `(workspace_id, week_start, category_id)` com `NULLS NOT DISTINCT` (PostgreSQL 15+), então o geral também é
  único por semana e o `INSERT ... ON CONFLICT DO UPDATE` resolve a corrida de duas pessoas sem erro. A
  categoria ser de despesa e do mesmo workspace é garantido pelo banco (chave estrangeira composta
  `(category_id, workspace_id, kind)`, como na `V5`).
- **RLS por papel**: leitura por workspace; inserir, alterar e **apagar** só OWNER e EDITOR (`app_can_write`).
  Um limite é um plano editável, não um registro financeiro, então esta é a primeira tabela com `DELETE`
  liberado para a aplicação (decisão 4 do research). `created_by` sempre o usuário da sessão; só
  `amount_cents` e `updated_at` são atualizáveis.
- **Rotas** sob `/api/v1/workspaces/{wid}/budgets`: `GET ?week=` (acompanhamento), `PUT` (define ou
  substitui), `PUT /{id}` (ajusta o valor), `DELETE /{id}` (remove), `POST /copy` (copia para as próximas N
  semanas).
- **Semana como regra já pronta**: reaproveita `Period.ofWeek` da `005` (acrescenta só `Period.resolveWeek`,
  que devolve a semana corrente quando `week` não vem). A data guardada é a segunda-feira da semana.
- **Regras puras**: `BudgetRules` (percentual, estado, ordenação, cobertura de categoria) testadas sem Spring
  nem banco (princípio VIII).
- **Acompanhamento numa leitura consistente**: `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`;
  duas consultas (limites com a categoria; gasto da semana por categoria) e o resto em Java com `addExact`.
- **Escrita**: a categoria é conferida e travada (`FOR SHARE`) antes de gravar, como na `004`, para nunca nascer
  limite em categoria arquivada, nem sob corrida com o arquivamento. A cópia é um único
  `INSERT ... SELECT ... ON CONFLICT DO NOTHING` (atômico por construção), com as contagens saindo do que
  foi realmente gravado.
- **Papéis**: leitura para os três papéis; escrita (`@WorkspaceScoped(write = true)`) só OWNER e EDITOR; não
  membro recebe 404.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security (contexto
e aspecto da `001`), springdoc-openapi, Flyway. Nenhuma dependência nova.

**Storage**: PostgreSQL 17. Tabela nova `weekly_budgets` (V6); leitura de `transactions` e `categories`.

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear e
inspecionar; dados aleatórios com semente fixa e cálculo independente; gravações simultâneas em threads.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: consulta de uma semana com 20.000 lançamentos e dezenas de limites em menos de 1 s
(SC-011).

**Constraints**: dinheiro em `BIGINT` de centavos (0 a 10^12 no limite); somas em Java com `addExact`;
percentual em `BigDecimal`; toda consulta filtra por `workspace_id`; nenhuma exceção capturada dentro de
transação; entrada validada antes de qualquer gravação.

**Scale/Scope**: um usuário hoje; dezenas de limites por semana; no máximo 52 semanas por cópia.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; `created_by` vem da sessão, nunca do corpo; sem segredo novo | OK |
| II. Isolamento em duas camadas | `weekly_budgets` com `workspace_id`, RLS (leitura por workspace; escrita por papel) + `@WorkspaceScoped` + filtro explícito em toda consulta; teste de acesso cruzado por operação e testes com cada camada removida | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rotas sob o workspace e com sessão | OK |
| IV. Entrada validada, saída controlada | DTOs com campos fechados (extras recusados), Bean Validation, semana pela regra da `005`, erro por campo em Problem Details, consultas parametrizadas, DTO de saída próprio | OK |
| V. Dados financeiros confiáveis | Centavos em `BIGINT`/`long`, `CHECK` de 0 a 10^12, sem float; percentual em `BigDecimal`; gasto sempre calculado, nunca gravado; o orçamento não altera saldo nem lançamento | OK |
| VI. Sem tentativa e erro em transação | Validação e travas (`FOR SHARE`) antes de gravar; corrida resolvida por `ON CONFLICT`, sem `catch` dentro de transação | OK |
| VII. Simplicidade | Uma tabela, cinco rotas, sem cache nem visão materializada; cópia em um único `INSERT ... SELECT` | OK |
| VIII. Prova, não promessa | Testes unitários das regras; integração com Postgres real; cálculo independente em dados aleatórios; corridas com threads; acesso cruzado; desempenho com 20.000 lançamentos; [quickstart.md](quickstart.md) com chamadas reais | OK |

**Ponto de atenção (exclusão lógica)**: a constituição (Dados financeiros) exige exclusão lógica só para
**lançamentos**. O limite semanal é um plano editável, sem efeito em saldo, e a spec (Assumptions) manda
apagar de verdade. A role da aplicação ganha `DELETE` **só** em `weekly_budgets`, com política de RLS restrita
a OWNER/EDITOR do workspace. Nenhuma tabela financeira muda. Não é violação; fica registrado em Complexity
Tracking por ser a primeira tabela apagável pela aplicação.

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/006-orcamento-semanal/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Tabela, regras e respostas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Gerado por /speckit-tasks
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/db/migration/
│   └── V6__weekly_budgets.sql                 # tabela, restrições, índice único, RLS, privilégios
├── src/main/java/com/tostao/tostao_api/
│   ├── budget/                                # BudgetController, BudgetService, BudgetRules (regras puras:
│   │   │                                      # percentual, estado, ordenação, cobertura)
│   │   └── dto/                               # DefineBudgetRequest, UpdateBudgetRequest, CopyBudgetsRequest,
│   │                                          # BudgetResponse, WeekBudgetResponse, CopyBudgetsResponse
│   ├── common/error/                          # BudgetNotFoundException, BudgetConflictException (+ handler)
│   └── dashboard/Period.java                  # + resolveWeek (semana corrente quando week não vem)
└── src/test/java/com/tostao/tostao_api/
    └── budget/                                # BudgetRulesTest (unitário), DefineBudgetTest,
                                               # BudgetTrackingTest, BudgetStatusTest, BudgetCopyTest,
                                               # BudgetUpdateDeleteTest, BudgetValidationTest,
                                               # BudgetAccessTest, BudgetRlsTest, BudgetServiceFilterTest,
                                               # BudgetConcurrencyTest, BudgetRandomDataTest,
                                               # BudgetUntouchedDataTest, BudgetPerformanceTest
    (ajustes)                                  # OpenApiContractTest (contrato da 006);
                                               # WorkspaceFixtures (helper de limite, se faltar)
```

**Structure Decision**: continua o monólito em camadas por módulo. `budget` é um módulo novo que só **lê**
`transactions` e `categories` e só **grava** `weekly_budgets`. A base reaproveitável para o orçamento mensal
(regra de estado e de gasto) fica em `BudgetRules`.

## Complexity Tracking

Sem violações da constituição. Uma escolha que merece registro:

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| `DELETE` físico em `weekly_budgets` (primeira tabela apagável pela aplicação) | O limite é um plano, não um lançamento; a regra de exclusão lógica vale para lançamentos. Uma remoção lógica criaria um estado "removido" que o acompanhamento, a unicidade e a cópia teriam de tratar sem ganho | Coluna `deleted_at`: exigiria índice único parcial, filtros em toda consulta e uma regra de "ressuscitar" na redefinição, só para guardar um plano descartado |
