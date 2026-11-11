# Implementation Plan: Transferência recorrente

**Branch**: `014-transferencia-recorrente` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/014-transferencia-recorrente/spec.md`

## Summary

A pessoa cria uma recorrência do tipo transferência (conta de origem, conta de destino, valor, frequência, início, fim, efetivação
automática). O sistema mantém, 12 meses à frente, uma transferência pendente de duas pontas por ocorrência, que a execução automática
pode efetivar sozinha no dia, as duas pontas juntas. É uma **extensão** da recorrência da spec 011: mesma rota, mesmo calendário, mesma
função de geração, mesmo encerramento.

Abordagem técnica (decisões em [research.md](research.md)):

- **Migration `V15`**: `recurrences` ganha `destination_account_id`, `category_id` passa a ser opcional, o tipo aceita `TRANSFER` e um
  `CHECK` garante a forma (transferência: destino diferente da origem e sem categoria; receita e despesa: categoria e sem destino).
  Em `transactions`, o `CHECK` das ocorrências deixa de exigir receita ou despesa e o índice único `(recurrence_id,
  occurrence_number)` passa a contar a ponta `(amount_cents > 0)`, no desenho do índice das pontas de transferência (V5).
- **Geração idempotente por par**: a função `generate_recurrence_occurrences` é substituída (`CREATE OR REPLACE`, mesma assinatura) para
  inserir as duas pontas na mesma instrução, com `transfer_group_id` determinístico (`md5` da recorrência e do número) e `ON CONFLICT`
  no novo índice. É a mesma função da criação e da execução automática.
- **Serviço**: `RecurrenceRules` aceita `TRANSFER` e confere a forma por campo; `RecurrenceService.create` ramifica por tipo e confere
  e trava as duas contas (`requireUsableAccounts`, movido para `UsableRefs`); o detalhe soma a saída de cada transferência uma vez; o
  encerramento exclui as duas pontas, conta ocorrências e trava por id.
- **Respostas**: `destinationAccountId` nos pedidos e respostas de recorrência; `categoryId` pode ser nulo. Nada muda para receita e despesa.
- **Sem mudança**: agendador, efetivação automática (`auto_clear_due`), saldo, dashboard, orçamentos e faturas.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security (contexto e aspecto da `001`),
springdoc-openapi, Flyway. Nenhuma dependência nova.

**Storage**: PostgreSQL 17. `V15` (colunas, restrições, índice, função de geração).

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear e inspecionar, threads reais para
a corrida da geração, o `RecurrenceScenario` e a `RecurrenceCalendar` da 011.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: execução automática sobre 20.000 lançamentos e 200 recorrências (um terço de transferência) em menos de 5 s; criar,
consultar e listar em menos de 1 s (SC-011).

**Constraints**: dinheiro em centavos inteiros; toda consulta filtra por `workspace_id`; nenhuma exceção capturada dentro de transação;
entrada validada antes de qualquer gravação; nunca uma ponta só.

**Scale/Scope**: no máximo ~106 ocorrências por recorrência semanal (duas linhas cada).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; `created_by` vem da sessão e as ocorrências copiam a autoria da recorrência; nenhum segredo novo | OK |
| II. Isolamento em duas camadas | Origem e destino do mesmo workspace por chave estrangeira composta e conferência explícita; RLS de `recurrences` e `transactions` cobre as colunas novas; a função de geração continua `SECURITY DEFINER` estreita e só produz o que a regra descreve (copia workspace, contas e autoria da própria recorrência); testes de acesso cruzado e com cada camada removida | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` e as rotas da 011; nenhuma rota pública nova | OK |
| IV. Entrada validada, saída controlada | DTO fechado com Bean Validation; regras por tipo no serviço, apontando o campo; `CHECK` no banco como segunda barreira; DTOs de saída próprios | OK |
| V. Dados financeiros confiáveis | Centavos inteiros; nunca uma ponta só (mesma instrução, grupo determinístico, índices); pendente não conta; exclusão respeitada; schema só por migration | OK |
| VI. Sem tentativa e erro em transação | Contas conferidas e travadas antes de gravar; unicidade por índice e `ON CONFLICT DO NOTHING`; nenhum `catch` dentro de transação | OK |
| VII. Simplicidade | Uma migration, uma função substituída, sem tabela nova, sem rota nova, sem dependência nova | OK |
| VIII. Prova, não promessa | Calendário igual ao da despesa; idempotência do par em série e em paralelo; efetivação das duas pontas; atomicidade; isolamento por camada; não regressão da 011; [quickstart.md](quickstart.md) real | OK |

**Ponto de atenção (alcance)**: troca uma função de banco e um índice único usados pela 011 e relaxa restrições de `recurrences` e
`transactions`. Mitigação: a suíte da 011 (e todas as demais) roda inteira depois de cada passo; a forma (`recurrences_shape`) mantém o
rigor que a anotação `NOT NULL` dava à categoria; o índice novo mantém a mesma garantia para receita e despesa. Só os três testes que
registravam a recusa do tipo transferência mudam (FR-015).

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/014-transferencia-recorrente/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Colunas, restrições, índice, função e respostas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API (a extensão)
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/db/migration/V15__recurring_transfer.sql   # colunas, restrições, índice e função de geração
├── src/main/java/com/tostao/tostao_api/
│   ├── transaction/
│   │   ├── UsableRefs.java                                       # AJUSTE: requireUsableAccounts (origem e destino, em ordem de id)
│   │   └── TransactionService.java                               # AJUSTE: usa o método movido
│   └── recurrence/
│       ├── RecurrenceRules.java                                  # AJUSTE: aceita TRANSFER; confere a forma por campo
│       ├── RecurrenceService.java                                # AJUSTE: criar, detalhe e encerrar por tipo
│       └── dto/                                                  # AJUSTE: destinationAccountId; categoryId opcional
└── src/test/java/com/tostao/tostao_api/recurrence/
    ├── TransferRecurrenceSchemaTest.java                         # V15: restrições, índice e função direto no banco
    ├── TransferRecurrenceRulesTest.java                          # unitário: forma por campo
    ├── CreateTransferRecurrenceTest.java                         # criar, validar, atomicidade
    ├── TransferOccurrencesTest.java                              # calendário, par, idempotência, efetivação
    ├── EndTransferRecurrenceTest.java                            # encerrar, detalhe, totais
    ├── TransferRecurrenceEffectsTest.java                        # saldo, dashboard, orçamentos, fatura
    ├── TransferRecurrenceAccessTest.java                         # papéis e isolamento
    └── TransferRecurrenceLayersTest.java                         # cada camada, concorrência e desempenho
```

**Structure Decision**: continua o monólito em camadas por módulo; nada de módulo novo. A transferência recorrente vive em `recurrence`,
junto da recorrência que ela estende; só `UsableRefs` (que já guarda as conferências de conta e categoria) ganha o método de duas contas.

## Complexity Tracking

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| Substituir a função de geração da 011 em vez de criar outra | Uma única definição do calendário e da geração é o que garante que criação e execução automática nunca discordem | Uma segunda função só para transferência (dois calendários e duas chamadas na rotina) |
| `transfer_group_id` determinístico (`md5` do par recorrência e número) | Duas gerações simultâneas produzem as mesmas linhas, então o resultado é sempre um par e o índice de pontas é uma segunda barreira | Grupo aleatório (execuções simultâneas poderiam misturar as pontas de grupos diferentes) |
