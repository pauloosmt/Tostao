# Implementation Plan: Lançamentos

**Branch**: `004-lancamentos` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-lancamentos/spec.md`

## Summary

Receitas, despesas e transferências dentro de um workspace, com listagem paginada e filtrada, edição,
exclusão lógica e saldo sempre calculado. É o módulo de dados mais sensível do sistema e segue o desenho das
`002-contas` e `003-categorias`.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Tabela existente**: `transactions` já existe na `V1` (com `type`, `amount_cents` com sinal,
  `transfer_group_id`, `created_by`, `deleted_at` e as chaves compostas de conta e categoria). A migration
  `V5` só acrescenta restrições (descrição, observação, limite de valor, faixa de datas), a chave composta que
  faz o banco garantir que a categoria tem o mesmo tipo do lançamento, um índice único que impede uma
  transferência de ter mais de uma saída ou mais de uma entrada, as políticas de RLS de escrita por papel
  (como em `accounts` e `categories`), `created_by` preso ao usuário da sessão, privilégio de `UPDATE` só nas
  colunas editáveis e a revogação do `DELETE`.
- **Um recurso, três tipos**: `POST /transactions` recebe `type` (`INCOME`, `EXPENSE` ou `TRANSFER`).
  A transferência usa `accountId` (origem) e `destinationAccountId` (destino) e vira duas linhas com o mesmo
  `transfer_group_id`, gravadas na mesma transação.
- **Operações sobre a unidade**: editar ou excluir qualquer ponta de uma transferência trava as duas linhas
  (`FOR UPDATE`, em ordem de id) e altera as duas na mesma transação, então elas nunca ficam incoerentes nem
  sob concorrência.
- **Sem uso de conta ou categoria arquivada**: as linhas da conta e da categoria referenciadas são
  travadas com `FOR SHARE` antes de gravar, o que serializa com o arquivamento (que faz `UPDATE` nelas), como
  na `003`. Nunca nasce lançamento em conta ou categoria arquivada, nem sob corrida.
- **Listagem**: uma consulta paginada (`LIMIT`/`OFFSET`, ordem `occurred_on desc, created_at desc, id desc`)
  e uma contagem, na mesma transação de leitura com isolamento `REPEATABLE READ`, para o total e a página
  concordarem. Filtros viram condições parametrizadas; o texto escapa `%`, `_` e `\`.
- **Permissão por papel e sem exceção em transação**: `@WorkspaceScoped(write = true)`; todas as
  pré-condições são checadas antes de gravar, sem `catch` dentro de `@Transactional`.
- **Escopo**: somente backend. As telas ficam no roadmap de front-end.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security
(contexto e aspecto da `001`/`002`), springdoc-openapi. Nenhuma dependência nova.

**Storage**: PostgreSQL 17, tabela `transactions` (V1) alterada pela migration `V5__transactions.sql`.

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear
papéis, contas, categorias e lançamentos de outros workspaces; testes de concorrência com threads.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: uma página (filtrada ou não) de um workspace com 20.000 lançamentos em menos de 1 s
(SC-008), com os índices parciais da `V1`; novo índice só se a medição pedir.

**Constraints**: dinheiro em `BIGINT` de centavos com módulo máximo de 10^12; nenhuma exceção capturada
dentro de transação; toda consulta filtra por `workspace_id`; lançamento nunca é apagado fisicamente.

**Scale/Scope**: um usuário hoje, milhares de lançamentos por workspace, listagem paginada.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel vindo do banco; escrita negada no aspecto e de novo pelo RLS; `created_by` vem da sessão (preso também pela política de RLS), nunca do cliente | OK |
| II. Isolamento em duas camadas | `@WorkspaceScoped` + filtro explícito por `workspace_id` em toda consulta e RLS de leitura e escrita por workspace; chaves compostas impedem conta e categoria de outro workspace; teste de acesso cruzado por operação; `DELETE` revogado | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rotas sob `/api/v1/workspaces/{wid}` e com sessão | OK |
| IV. Entrada validada, saída controlada | DTOs de entrada com Bean Validation e campos não listados recusados; DTO de saída próprio; consultas parametrizadas; texto de busca com `LIKE` escapado; erros em Problem Details; float em campo inteiro recusado | OK |
| V. Dados financeiros confiáveis | Centavos em `BIGINT`, sem float; exclusão lógica (`deleted_at`) sem `DELETE`; saldo calculado, nunca gravado; mudança de schema só pela `V5`, com `V1` a `V4` intocadas; UUID; FK composta `(id, workspace_id)` e tipo da categoria garantidos pelo banco | OK |
| VI. Sem tentativa e erro em transação | Contas e categorias conferidas e travadas antes de gravar; nada de `catch` em `@Transactional`; índices e constraints só como rede de segurança | OK |
| VII. Simplicidade | Sem entidade JPA, sem cache de saldo, sem tabela de transferências separada, sem índice extra sem medição, sem busca full-text | OK |
| VIII. Prova, não promessa | Testes unitários da regra de saldo e de transferência, de integração com Postgres real, de acesso cruzado, de papel, de concorrência e de desempenho; [quickstart.md](quickstart.md) com chamadas reais | OK |

Sem violações. A seção Complexity Tracking não se aplica.

Reavaliação depois do desenho (Phase 1): nenhuma mudança. Dois pontos pedem atenção na implementação: a
`V5` estreita os privilégios da role da aplicação em `transactions` (o teste `RlsPolicyTest` da `001`, que
apagava lançamentos com `tostao_app`, passa a ser ajustado), e a regra "toda receita e despesa tem
categoria" fica no serviço, não no banco (ver [research.md](research.md), item 3).

## Project Structure

### Documentation (this feature)

```text
specs/004-lancamentos/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Lançamento, transferência, migration V5 e políticas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Gerado por /speckit-tasks
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/
│   └── db/migration/V5__transactions.sql    # restrições, FK de tipo, índice de pontas, RLS, grants
├── src/main/java/com/tostao/tostao_api/
│   ├── workspace/                           # CurrentUser (usuário da sessão, usado pelo aspecto e pelo serviço)
│   ├── transaction/                         # TransactionController, TransactionService, TransactionType,
│   │   │                                    # TransactionFilter, TransactionRules (regras puras, testadas
│   │   │                                    # por teste unitário)
│   │   └── dto/                             # CreateTransactionRequest, UpdateTransactionRequest,
│   │                                        # TransactionResponse, TransactionPageResponse
│   └── common/error/                        # TransactionNotFoundException, TransactionConflictException,
│                                            # mapeamentos novos em ApiExceptionHandler (inclui o campo
│                                            # nos erros de tipo de parâmetro de consulta)
└── src/test/java/com/tostao/tostao_api/
    ├── transaction/                         # TransactionRulesTest (unitário), CreateTransactionTest,
    │                                        # TransferTest, ListTransactionsTest,
    │                                        # TransactionPerformanceTest, UpdateTransactionTest,
    │                                        # DeleteTransactionTest, TransactionAccessTest, TransactionRlsTest,
    │                                        # TransactionSchemaTest, TransactionServiceFilterTest,
    │                                        # TransactionConcurrencyTest, BalanceConsistencyTest
    ├── workspace/                           # RlsPolicyTest (ajuste: sem DELETE em transactions)
    └── support/                             # WorkspaceFixtures (+ insertCategory já existe)
```

**Structure Decision**: continua o monólito em camadas por módulo. `transaction` guarda a regra e o acesso a
dados, como `account` e `category`. O saldo (`account`) não muda: a consulta da `002` já soma os lançamentos
não excluídos.

## Complexity Tracking

Sem violações da constituição a justificar.
