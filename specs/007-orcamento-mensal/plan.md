# Implementation Plan: Orçamento mensal

**Branch**: `007-orcamento-mensal` | **Date**: 2026-11-08 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-orcamento-mensal/spec.md`

## Summary

Limites de gasto por mês (`AAAA-MM`, do dia 1 ao último, o mesmo da `005`), por categoria de despesa ou
gerais, e o acompanhamento do mês: gasto, restante, percentual e estado de cada limite, mais o total gasto e o
gasto fora dos limites de categoria. Também ajusta, remove e copia limites para os meses seguintes. É a
`006-orcamento-semanal` com o mês no lugar da semana, com as mesmas regras de gasto, percentual e estado.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Uma tabela nova, `monthly_budgets`** (migration `V7`), irmã de `weekly_budgets`: `workspace_id`,
  `month_start` (o dia 1 do mês), `category_id` (nulo = limite geral), `amount_cents`, `created_by`, datas.
  Mesma unicidade `NULLS NOT DISTINCT`, mesma chave estrangeira composta para categoria de despesa do mesmo
  workspace, mesmo RLS por papel e mesmo `DELETE` físico restrito a OWNER/EDITOR. A tabela da `006` **não é
  tocada** (migration aplicada nunca é editada), e a independência entre os dois orçamentos (FR-023) vale por
  construção: tabelas, ids e rotas separados.
- **Rotas** sob `/api/v1/workspaces/{wid}/monthly-budgets`: `GET ?month=` (acompanhamento), `PUT` (define ou
  substitui), `PUT /{id}` (ajusta o valor), `DELETE /{id}` (remove), `POST /copy` (copia para os próximos N
  meses, de 1 a 24).
- **Regras reaproveitadas, não copiadas**: `BudgetRules` (percentual, estado, cobertura, ordenação) já é
  independente do período e serve sem mudança. A consulta de gasto por categoria (`WEEK_SPENT_SQL`) só recebe
  um intervalo de datas e vira `PERIOD_SPENT_SQL`, usada pelos dois. O trecho de `BudgetService.week` que
  monta gasto, restante, percentual, estado e ordem por limite é extraído para uma função única que o
  acompanhamento semanal e o mensal chamam (decisão 9 do research): é lógica financeira idêntica nos dois, e
  o SC-003 exige o mesmo resultado.
- **Mês como regra já pronta**: `Period.ofMonth` e `Period.resolve` da `005`. Acrescenta-se só uma
  sobrecarga `ofMonth(text, field)` (o erro da cópia aponta `fromMonth`) e a constante `LAST_MONTH_START`.
- **Acompanhamento numa leitura consistente**: `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`,
  duas consultas (limites com a categoria; gasto do mês por categoria) e o resto em Java com `addExact`.
- **Escrita**: a categoria é conferida e travada (`FOR SHARE`) antes de gravar, para nunca nascer limite em
  categoria arquivada, nem sob corrida com o arquivamento. A cópia é um único
  `INSERT ... SELECT ... ON CONFLICT DO NOTHING` (atômico), com as contagens saindo do que foi realmente
  gravado.
- **Papéis**: leitura para os três papéis; escrita (`@WorkspaceScoped(write = true)`) só OWNER e EDITOR; não
  membro recebe 404.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security (contexto
e aspecto da `001`), springdoc-openapi, Flyway. Nenhuma dependência nova.

**Storage**: PostgreSQL 17. Tabela nova `monthly_budgets` (V7); leitura de `transactions` e `categories`.

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear e
inspecionar; dados aleatórios com semente fixa e cálculo independente; gravações simultâneas em threads.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: consulta de um mês com 20.000 lançamentos e dezenas de limites em menos de 1 s
(SC-011).

**Constraints**: dinheiro em `BIGINT` de centavos (0 a 10^12 no limite); somas em Java com `addExact`;
percentual em `BigDecimal`; toda consulta filtra por `workspace_id`; nenhuma exceção capturada dentro de
transação; entrada validada antes de qualquer gravação.

**Scale/Scope**: um usuário hoje; dezenas de limites por mês; no máximo 24 meses por cópia.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; `created_by` vem da sessão, nunca do corpo; sem segredo novo | OK |
| II. Isolamento em duas camadas | `monthly_budgets` com `workspace_id`, RLS (leitura por workspace; escrita por papel) + `@WorkspaceScoped` + filtro explícito em toda consulta; teste de acesso cruzado por operação e testes com cada camada removida; `RlsCoverageTest` já falha se a tabela nascer sem RLS | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rotas sob o workspace e com sessão | OK |
| IV. Entrada validada, saída controlada | DTOs com campos fechados (extras recusados), Bean Validation, mês pela regra da `005`, erro por campo em Problem Details, consultas parametrizadas, DTO de saída próprio | OK |
| V. Dados financeiros confiáveis | Centavos em `BIGINT`/`long`, `CHECK` de 0 a 10^12, sem float; percentual em `BigDecimal`; gasto sempre calculado, nunca gravado; o orçamento não altera saldo nem lançamento; schema só por migration nova (`V7`), sem editar a `V6` | OK |
| VI. Sem tentativa e erro em transação | Validação e travas (`FOR SHARE`) antes de gravar; corrida resolvida por `ON CONFLICT`, sem `catch` dentro de transação | OK |
| VII. Simplicidade | Uma tabela, cinco rotas, sem cache nem visão materializada; cópia em um único `INSERT ... SELECT`; a única extração é a lógica de acompanhamento que já existe em `week()` (justificativa abaixo) | OK |
| VIII. Prova, não promessa | Testes unitários das regras; integração com Postgres real; cálculo independente em dados aleatórios; corridas com threads; acesso cruzado; paridade semanal × mensal; desempenho com 20.000 lançamentos; [quickstart.md](quickstart.md) com chamadas reais | OK |

**Ponto de atenção (exclusão lógica)**: igual à `006`. O limite é um plano editável, sem efeito em saldo, e a
spec manda apagar de verdade. A role da aplicação ganha `DELETE` **só** em `monthly_budgets` (segunda tabela
apagável pela aplicação, com a mesma política restrita a OWNER/EDITOR). Nenhuma tabela financeira muda. Não é
violação.

**Ponto de atenção (abstração)**: o princípio VII rejeita abstração precoce. A extração do cálculo de
acompanhamento não é precoce: já existem dois consumidores concretos (semanal e mensal), o código é o mesmo, e
duplicá-lo deixaria a regra financeira (estado, restante, ordem) livre para divergir sem aviso, o que o SC-003
proíbe. O SQL de cada tabela **não** é abstraído (cada serviço tem o seu, sem concatenar texto).

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/007-orcamento-mensal/
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
│   └── V7__monthly_budgets.sql                # tabela, restrições, índice único, RLS, privilégios
├── src/main/java/com/tostao/tostao_api/
│   ├── budget/                                # módulo existente; ganha o mensal ao lado do semanal
│   │   ├── MonthlyBudgetController.java       # /monthly-budgets (5 rotas)
│   │   ├── MonthlyBudgetService.java          # define, update, delete, month, copy
│   │   ├── BudgetTracking.java                # NOVO: gasto, restante, percentual, estado e ordem por
│   │   │                                      # limite + total e fora dos limites; usado pelos dois serviços
│   │   ├── BudgetService.java                 # AJUSTE: week() passa a chamar BudgetTracking; WEEK_SPENT_SQL
│   │   │                                      # vira PERIOD_SPENT_SQL (compartilhada; BudgetRlsTest da 006
│   │   │                                      # a referencia na linha 71 e acompanha o renome)
│   │   ├── BudgetController.java              # AJUSTE: categoryOf passa a package-private (reuso)
│   │   └── dto/                               # DefineMonthlyBudgetRequest, CopyMonthlyBudgetsRequest,
│   │                                          # MonthlyBudgetResponse, MonthBudgetResponse
│   │                                          # (reusa UpdateBudgetRequest, CopyBudgetsResponse e
│   │                                          # WeekBudgetResponse.BudgetItem)
│   └── dashboard/Period.java                  # + ofMonth(text, field), LAST_MONTH_START
└── src/test/java/com/tostao/tostao_api/
    ├── budget/                                # MonthlyBudgetSchemaTest, DefineMonthlyBudgetTest,
    │                                          # MonthlyBudgetTrackingTest, MonthlyBudgetStatusTest,
    │                                          # BudgetParityTest, CopyMonthlyBudgetsTest,
    │                                          # UpdateDeleteMonthlyBudgetTest, MonthlyBudgetValidationTest,
    │                                          # MonthlyBudgetAccessTest, MonthlyBudgetRlsTest,
    │                                          # MonthlyBudgetServiceFilterTest, MonthlyBudgetConcurrencyTest,
    │                                          # MonthlyBudgetRandomDataTest, MonthlyBudgetUntouchedDataTest,
    │                                          # MonthlyBudgetPerformanceTest, MonthlyBudgetConsistencyTest
    ├── dashboard/                             # PeriodTest (+ sobrecarga e constante)
    ├── support/WorkspaceFixtures.java         # + insertMonthlyBudget, countMonthlyBudgets
    └── OpenApiContractTest.java               # + contrato da 007
```

**Structure Decision**: continua o monólito em camadas por módulo. O mensal entra no módulo `budget` que já
existe, ao lado do semanal, porque os dois compartilham regras e DTOs de item. `MonthlyBudgetService` só
**lê** `transactions` e `categories` e só **grava** `monthly_budgets`.

## Complexity Tracking

Sem violações da constituição. Duas escolhas que merecem registro:

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| Tabela `monthly_budgets` separada, em vez de generalizar `weekly_budgets` | A `V6` é imutável; generalizar exigiria `ALTER` na tabela da `006`, mudaria unicidade, `CHECK` de data, índices, testes e contrato dela, e acoplaria dois planos que a spec manda manter independentes (FR-023) | Uma tabela `budgets` com `period_type` e `period_start`: reescreve a `006` já entregue e testada |
| Extrair o cálculo de acompanhamento (`BudgetTracking`) | Dois consumidores reais, lógica financeira idêntica, e SC-003 exige resultado igual nos dois | Copiar `week()` para o serviço mensal: ~30 linhas duplicadas de regra financeira que podem divergir em silêncio |
