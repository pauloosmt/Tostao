# Implementation Plan: Contas

**Branch**: `002-contas` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-contas/spec.md`

## Summary

CRUD de contas financeiras dentro de um workspace, com arquivamento no lugar de exclusão e saldo
calculado (nunca gravado). Primeiro módulo de dados que usa o isolamento da `001`.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Tabela existente**: `accounts` já existe na `V1`. A migration `V3` só acrescenta restrições
  (campos de cartão só em `CREDIT_CARD`, limites de valor, nome sem espaços nas pontas), um índice
  único parcial para o nome entre contas ativas, e troca a política de RLS genérica por políticas
  separadas de leitura e escrita. A escrita exige papel `OWNER` ou `EDITOR` também no banco, e o
  privilégio `DELETE` sai da role da aplicação.
- **Permissão por papel**: `@WorkspaceScoped(write = true)` faz o aspecto da `001` responder 404 para
  quem não é membro e 403 para `VIEWER` em operação de escrita, antes de qualquer consulta.
- **Saldo**: uma única consulta agrega `saldo inicial + soma dos lançamentos não excluídos` por conta;
  o total é somado em memória a partir das mesmas linhas, o que garante consistência entre as contas
  mostradas e o total.
- **Sem exceção em transação**: o nome repetido na criação é decidido por `INSERT ... ON CONFLICT DO
  NOTHING`. Em edição e restauração há checagem prévia, e a corrida residual é traduzida em 409 na
  borda web, depois do rollback.
- **Escopo**: somente backend. As telas ficam no roadmap de front-end.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security
(contexto e aspecto da `001`), springdoc-openapi. Nenhuma dependência nova.

**Storage**: PostgreSQL 17, tabela `accounts` (V1) alterada pela migration `V3__accounts.sql`; leitura de
`transactions` (V1) para o saldo.

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), conexão do dono
(`OwnerJdbc`) para semear lançamentos, papéis e dados de outros workspaces.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: listar 50 contas com saldos sobre 20.000 lançamentos em menos de 1 s (SC-007), com
o índice parcial `(workspace_id, account_id, occurred_on)` da `V1`.

**Constraints**: dinheiro em `BIGINT` de centavos; valor absoluto máximo de 10^12 centavos; nenhuma
exceção capturada dentro de transação; toda consulta filtra por `workspace_id`.

**Scale/Scope**: um usuário hoje, poucas dezenas de contas por workspace, sem paginação.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | O papel vem do banco (`workspace_members`), nunca do cliente; a escrita é negada no aspecto e de novo pela política de RLS | OK |
| II. Isolamento em duas camadas | `@WorkspaceScoped` + filtro explícito por `workspace_id` em toda consulta (serviço) e RLS de leitura e escrita por workspace (dados); teste de acesso cruzado por operação; `DELETE` revogado | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` da `001` (cookie, CSP, `no-store`); rotas novas ficam sob `/api/v1/workspaces/{wid}` e exigem sessão | OK |
| IV. Entrada validada, saída controlada | DTOs de entrada com Bean Validation e campos não listados recusados; DTO de saída próprio; consultas parametrizadas; erros em Problem Details; float em campo inteiro recusado | OK |
| V. Dados financeiros confiáveis | Centavos em `BIGINT`, sem float; saldo calculado a cada consulta e nunca gravado; mudança de schema só pela `V3`, com a `V1` e a `V2` intocadas; UUID | OK |
| VI. Sem tentativa e erro em transação | Criação com `ON CONFLICT DO NOTHING`; edição e restauração com checagem prévia; a corrida residual vira 409 só depois do rollback, na borda web | OK |
| VII. Simplicidade | Sem entidade JPA, sem cache de saldo, sem paginação, sem tabela de saldo materializado | OK |
| VIII. Prova, não promessa | Testes unitários, de integração com Postgres real, de acesso cruzado, de papel e de desempenho; [quickstart.md](quickstart.md) com chamadas reais | OK |

Sem violações. A seção Complexity Tracking não se aplica.

## Project Structure

### Documentation (this feature)

```text
specs/002-contas/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Conta, saldo, migration V3 e políticas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Gerado por /speckit-tasks
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/
│   ├── application.properties               # + recusa de float em campo inteiro
│   └── db/migration/V3__accounts.sql        # restrições, índice único parcial, políticas, DELETE revogado
├── src/main/java/com/tostao/tostao_api/
│   ├── account/                             # AccountController, AccountService, AccountType,
│   │   └── dto/                             # CreateAccountRequest, UpdateAccountRequest, AccountResponse
│   ├── report/                              # BalanceReportController, BalanceReportResponse
│   ├── workspace/                           # WorkspaceScoped (+ write), WorkspaceScopeAspect (+ papel)
│   └── common/error/                        # InsufficientRoleException, AccountNotFoundException,
│                                            # AccountNameAlreadyExistsException, AccountArchivedException, FieldValidationException,
│                                            # e novos mapeamentos em ApiExceptionHandler
└── src/test/java/com/tostao/tostao_api/
    ├── account/                             # CreateAccountTest, ListAndBalanceTest, UpdateAccountTest,
    │                                        # ArchiveRestoreTest, AccountAccessTest, AccountRlsTest,
    │                                        # AccountServiceFilterTest, BalancePerformanceTest
    ├── workspace/                           # WorkspaceRoleAspectTest
    ├── report/                              # BalanceReportTest
    └── support/                             # WorkspaceFixtures (usuários, papéis, tokens, lançamentos)
```

**Structure Decision**: continua o monólito em camadas por módulo da `001`. `account` guarda a regra e o
acesso a dados; `report` só expõe o saldo consolidado, para a spec 005 acrescentar o relatório mensal no
mesmo lugar. Sem entidades JPA, como na `001`.

## Complexity Tracking

Sem violações da constituição a justificar.
