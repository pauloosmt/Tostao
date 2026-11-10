# Implementation Plan: Compra parcelada

**Branch**: `010-compra-parcelada` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/010-compra-parcelada/spec.md`

## Summary

Registrar uma despesa dividida em 2 a 48 parcelas mensais de uma vez, ver o plano (todas as parcelas, o efetivado e
o pendente) e cancelar o que ainda está pendente. Cada parcela é um lançamento comum da spec 004; a primeira nasce
efetivada (ou pendente, se pedido) e as seguintes pendentes, pela regra da spec 009.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **O plano não é uma tabela**: é o conjunto de despesas com o mesmo `installment_group_id`. A migration `V9`
  acrescenta três colunas nulas a `transactions` (`installment_group_id`, `installment_number`,
  `installment_count`), uma restrição que as mantém coerentes e um índice único (que também serve à busca do
  plano pelo grupo). Lançamentos existentes ficam com
  as três nulas, então nada muda para eles. O `INSERT` já vale para a tabela inteira (`V2`) e o `UPDATE` só nas
  colunas da `V5`, então as colunas novas nascem na criação e **nunca mais mudam**, sem nenhum `GRANT` novo.
- **Regras puras em `InstallmentRules`** (sem Spring nem banco): divisão em centavos exatos, datas mês a mês sem
  acumular ajuste, faixa de datas e descrição com o sufixo. Provadas por teste unitário sem Docker (princípio
  VIII).
- **Serviço próprio, `InstallmentService`**, com três operações: registrar, consultar o plano e cancelar. Reaproveita
  a conferência de conta e categoria (`FOR SHARE`) que a criação de lançamentos já usa, extraída para uma classe
  de apoio pequena (`UsableRefs`, criada por cada serviço a partir do seu `JdbcClient`) para os dois serviços
  compartilharem sem duplicar.
- **Rotas** sob `/api/v1/workspaces/{wid}/installment-purchases`: `POST` (registra), `GET /{groupId}` (plano) e
  `POST /{groupId}/cancel` (cancela as pendentes).
- **Atômico**: o registro é uma transação só (todas as parcelas ou nenhuma). O cancelamento é um `UPDATE`
  condicional (`status = 'PENDING'`), então uma efetivação simultânea de uma parcela nunca é desfeita por ele.
- **Papéis**: leitura para os três; escrita (`@WorkspaceScoped(write = true)`) só OWNER e EDITOR; não membro
  recebe 404.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security (contexto
e aspecto da `001`), springdoc-openapi, Flyway. Nenhuma dependência nova.

**Storage**: PostgreSQL 17. Três colunas novas em `transactions` (migration `V9`); sem tabela nova.

**Testing**: JUnit 5 (regras puras sem Docker), MockMvc, Testcontainers (Postgres real, role `tostao_app`),
`OwnerJdbc` para semear e inspecionar; dados aleatórios com semente fixa e cálculo independente.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: registrar 48 parcelas, consultar o plano e listar lançamentos em menos de 1 s cada, com
20.000 lançamentos (SC-012).

**Constraints**: dinheiro em `long` de centavos (soma exata); toda consulta filtra por `workspace_id`; nenhuma
exceção capturada dentro de transação; entrada validada antes de qualquer gravação; campos novos nas respostas
só aditivos.

**Scale/Scope**: um usuário hoje; no máximo 48 parcelas por compra.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; `created_by` vem da sessão, nunca do corpo; sem segredo novo | OK |
| II. Isolamento em duas camadas | Sem tabela nova: as parcelas são linhas de `transactions` sob o RLS existente (leitura por workspace, escrita por papel) e o filtro explícito por `workspace_id` em toda consulta; teste de acesso cruzado por operação e testes com cada camada removida | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rotas sob o workspace e com sessão | OK |
| IV. Entrada validada, saída controlada | DTOs com campos fechados e Bean Validation, validação de calendário e valor antes de gravar, erro por campo em Problem Details, consultas parametrizadas, DTO de saída próprio | OK |
| V. Dados financeiros confiáveis | Centavos inteiros, divisão exata (soma = total), `BIGINT`, sem float; schema só por migration (`V9`); exclusão lógica ao cancelar; saldo continua calculado; conta e categoria do mesmo workspace pelas chaves compostas existentes | OK |
| VI. Sem tentativa e erro em transação | Conta e categoria conferidas e travadas antes de gravar; unicidade do número por grupo garantida por índice, sem `catch`; cancelamento por `UPDATE` condicional | OK |
| VII. Simplicidade | Sem tabela nova, sem agendador; uma classe de regras puras, um serviço, um controller; a única extração é a conferência de conta e categoria, que ganha um segundo consumidor real | OK |
| VIII. Prova, não promessa | Regras como teste unitário; integração com Postgres real; soma e datas conferidas em dados aleatórios; atomicidade e corrida provadas; isolamento por camada; desempenho com 20.000 lançamentos; [quickstart.md](quickstart.md) real | OK |

**Ponto de atenção (alcance)**: toca `TransactionResponse` e o `SELECT` de lançamentos (campos novos nulos) e extrai
duas conferências de `TransactionService`. Mitigação: a suíte inteira de lançamentos e das specs anteriores roda sem
alteração depois de cada passo (FR-020, SC-011).

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/010-compra-parcelada/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Colunas, regras e respostas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/db/migration/
│   └── V9__installments.sql                   # 3 colunas, restrição de coerência e índice único
├── src/main/java/com/tostao/tostao_api/
│   ├── transaction/
│   │   ├── InstallmentRules.java              # NOVO: divisão, datas, faixa e descrição (puras)
│   │   ├── InstallmentService.java            # NOVO: registrar, consultar o plano, cancelar
│   │   ├── InstallmentController.java         # NOVO: 3 rotas
│   │   ├── UsableRefs.java                    # NOVO: confere e trava conta e categoria ativas
│   │   ├── TransactionService.java            # AJUSTE: usa UsableRefs; campos de parcela no SELECT;
│   │   │                                      # installmentsOf(workspace, grupo)
│   │   └── dto/                               # AJUSTE: TransactionResponse ganha grupo, número e total;
│   │                                          # NOVO: CreateInstallmentPurchaseRequest,
│   │                                          # InstallmentPlanResponse, CancelInstallmentsResponse
│   └── common/error/                          # InstallmentPlanNotFoundException (+ handler 404)
└── src/test/java/com/tostao/tostao_api/transaction/
    ├── InstallmentRulesTest.java              # unitário
    ├── InstallmentSchemaTest.java             # restrições e privilégios da V9
    ├── InstallmentPurchaseTest.java           # registrar, plano, cancelar
    ├── InstallmentAccessTest.java             # papéis e isolamento
    ├── InstallmentLayersTest.java             # cada camada de isolamento
    ├── InstallmentRandomDataTest.java         # soma e datas em dados aleatórios
    └── InstallmentConcurrencyTest.java        # cancelar x efetivar, e desempenho
```

**Structure Decision**: continua o monólito em camadas por módulo. As parcelas são lançamentos, então o código fica
no módulo `transaction`; não há módulo novo.

## Complexity Tracking

Sem violações da constituição. Uma escolha que merece registro:

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| Plano sem tabela própria (conjunto de linhas com o mesmo grupo) | O plano não tem dados que não sejam das parcelas (descrição base, total e número vêm delas); uma tabela exigiria RLS, privilégios, chave estrangeira e sincronização com as parcelas editadas ou excluídas | Tabela `installment_purchases` com o total original: criaria um segundo valor que diverge quando uma parcela é editada |
