# Implementation Plan: Recorrências e efetivação automática

**Branch**: `011-recorrencias-agendamentos` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/011-recorrencias-agendamentos/spec.md`

## Summary

Duas capacidades que dependem de uma execução automática do sistema: (1) **efetivar sozinho** no dia da data um
lançamento pendente que a pessoa marcou para isso; (2) **recorrências** que o sistema repete toda semana, mês ou
ano, mantendo as ocorrências geradas como lançamentos pendentes até 12 meses à frente. A compra parcelada (010)
ganha a opção de efetivar as parcelas automaticamente.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Migration `V10` (schema)**: três colunas novas em `transactions` (`auto_clear`, `recurrence_id`,
  `occurrence_number`), a tabela `recurrences` (registro próprio, com RLS por papel como as demais), a restrição
  "marcador só em pendente" (`NOT auto_clear OR status = 'PENDING'`) e o índice único `(recurrence_id,
  occurrence_number)`. **Migration `V11` (funções)**: duas funções `SECURITY DEFINER` estreitas,
  `generate_recurrence_occurrences` e `auto_clear_due`, no mesmo padrão das funções da `V2`.
- **A execução sem pessoa logada não precisa de bypass do RLS na aplicação**: ela chama as duas funções, que fazem
  tudo em lote e só tocam linhas pela regra (cada ocorrência herda `workspace_id`, conta e categoria da sua
  recorrência, e as chaves compostas impedem misturar workspaces). A criação da recorrência usa a **mesma** função
  de geração (para uma só recorrência), então há uma única definição do calendário.
- **Agendador**: `AutomationScheduler` (`@Scheduled`, ao iniciar e a cada hora) chama `AutomationService.runOnce(hoje)`,
  que numa transação toma uma trava consultiva do Postgres (execuções simultâneas não duplicam trabalho), gera as
  ocorrências que faltam e depois efetiva o que venceu. "Hoje" vem do relógio no fuso `America/Sao_Paulo`
  (`Period.today`). Ligado por padrão; os testes o desligam por propriedade de sistema no `pom.xml` e chamam
  `runOnce` com uma data escolhida.
- **Efetivação automática de uso único**: ao efetivar, por qualquer caminho, o marcador é desligado na mesma
  instrução (`clear`, edição e execução automática), e o banco garante a coerência pela restrição.
- **Recorrência**: módulo novo `recurrence` (`RecurrenceService`, `RecurrenceController`, regras puras de
  validação). Rotas sob `/api/v1/workspaces/{wid}/recurrences`: `POST` (cria e gera), `GET` (lista), `GET /{id}`
  (detalhe com as ocorrências) e `POST /{id}/end` (encerra). A regra da recorrência não é editável.
- **Encerrar** trava a recorrência (`FOR UPDATE`), marca `ended_at` e exclui as ocorrências pendentes futuras; a
  geração toma `FOR SHARE` na recorrência, então uma geração em curso e um encerramento nunca deixam uma ocorrência
  futura perdida.
- **Papéis**: leitura para os três; escrita (`@WorkspaceScoped(write = true)`) só OWNER e EDITOR; não membro recebe
  404.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Scheduling (já no
Spring), Spring Security (contexto e aspecto da `001`), springdoc-openapi, Flyway. Nenhuma dependência nova.

**Storage**: PostgreSQL 17. `V10` (colunas em `transactions` e tabela `recurrences`) e `V11` (duas funções).

**Testing**: JUnit 5 (regras puras sem Docker), MockMvc, Testcontainers (Postgres real, role `tostao_app`),
`OwnerJdbc` para semear e inspecionar; o calendário do banco é conferido contra um cálculo independente em
`java.time`, com dados aleatórios e semente fixa.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: uma execução automática sobre 20.000 lançamentos, 200 recorrências e 2.000 vencidos em menos
de 5 s; criar e consultar uma recorrência e listar lançamentos em menos de 1 s cada (SC-013).

**Constraints**: dinheiro em `long` de centavos; toda consulta de requisição filtra por `workspace_id`; nenhuma
exceção capturada dentro de transação; entrada validada antes de qualquer gravação; campos novos nas respostas só
aditivos; o agendador nunca roda concorrente consigo mesmo.

**Scale/Scope**: um usuário hoje; até ~106 ocorrências por recorrência semanal criada.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; `created_by` vem da sessão, nunca do corpo; as funções só rodam para a role da aplicação; sem segredo novo | OK |
| II. Isolamento em duas camadas | `recurrences` com RLS de leitura por workspace e escrita por papel, e filtro explícito em toda consulta das requisições; a execução automática (sem requisição) isola por construção (cada linha gerada herda o workspace da recorrência, chaves compostas conferem conta e categoria) e tem teste com dados em dois workspaces; teste de acesso cruzado por operação e de cada camada removida | OK, com a ressalva registrada em Complexity Tracking |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rotas sob o workspace e com sessão; a execução automática não abre porta nenhuma | OK |
| IV. Entrada validada, saída controlada | DTOs com campos fechados e Bean Validation, validação de calendário e valor antes de gravar, erro por campo em Problem Details, consultas parametrizadas, DTO de saída próprio | OK |
| V. Dados financeiros confiáveis | Centavos inteiros, `BIGINT`, sem float; schema só por migration (`V10`, `V11`); exclusão lógica ao encerrar; saldo continua calculado e só efetivado conta; conta e categoria do mesmo workspace pelas chaves compostas | OK |
| VI. Sem tentativa e erro em transação | Conta e categoria conferidas e travadas antes de gravar; unicidade da ocorrência garantida por índice e `ON CONFLICT DO NOTHING`, sem `catch`; as funções são em lote, sem bloco de exceção; o único `try/catch` fica no agendador, fora de qualquer transação | OK |
| VII. Simplicidade | Um módulo novo pequeno e uma classe de automação; sem biblioteca de agendamento, sem fila, sem tabela de execuções; regra da recorrência não editável (encerra e recria) | OK |
| VIII. Prova, não promessa | Regras puras como teste unitário; funções provadas direto no banco; calendário do banco contra cálculo independente; idempotência e corrida provadas; isolamento por camada e da execução automática; desempenho; [quickstart.md](quickstart.md) real | OK |

**Ponto de atenção (alcance)**: toca a efetivação manual (`clear`), a edição de lançamentos, o registro de compra
parcelada e `TransactionResponse` (campos novos). Mitigação: a suíte inteira das specs 001 a 010 roda sem alteração
depois de cada passo (FR-026, SC-012).

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/011-recorrencias-agendamentos/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Colunas, tabela, funções, regras e respostas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                          # AJUSTE: surefire desliga o agendador nos testes
├── src/main/resources/db/migration/
│   ├── V10__recurrences.sql                         # colunas em transactions, tabela recurrences, RLS, privilégios
│   └── V11__automation_functions.sql                # generate_recurrence_occurrences, auto_clear_due
├── src/main/java/com/tostao/tostao_api/
│   ├── recurrence/
│   │   ├── RecurrenceFrequency.java                 # NOVO: WEEKLY, MONTHLY, YEARLY
│   │   ├── RecurrenceRules.java                     # NOVO: validação de datas e tipo (puras)
│   │   ├── RecurrenceService.java                   # NOVO: criar, listar, consultar, encerrar
│   │   ├── RecurrenceController.java                # NOVO: 4 rotas
│   │   └── dto/                                     # NOVO: CreateRecurrenceRequest, RecurrenceResponse,
│   │                                                #       RecurrenceDetailResponse, EndRecurrenceResponse
│   ├── automation/
│   │   ├── AutomationService.java                   # NOVO: runOnce(hoje) com trava consultiva
│   │   └── AutomationScheduler.java                 # NOVO: @Scheduled, ligado por propriedade
│   ├── transaction/
│   │   ├── TransactionService.java                  # AJUSTE: autoClear na criação, edição e efetivação;
│   │   │                                            # campos novos no SELECT
│   │   ├── InstallmentService.java                  # AJUSTE: autoClear das parcelas pendentes
│   │   └── dto/                                     # AJUSTE: autoClear nos pedidos; TransactionResponse
│   │                                                # ganha autoClear, recurrenceId, occurrenceNumber
│   └── common/error/                                # RecurrenceNotFoundException (+ handler 404)
└── src/test/java/com/tostao/tostao_api/
    ├── recurrence/
    │   ├── RecurrenceSchemaTest.java                # V10: colunas, restrições, RLS, privilégios
    │   ├── RecurrenceRulesTest.java                 # unitário
    │   ├── AutomationFunctionsTest.java             # V11 direto no banco, calendário contra java.time
    │   ├── CreateRecurrenceTest.java                # criar e gerar
    │   ├── RecurrenceReadTest.java                  # listar e consultar
    │   ├── EndRecurrenceTest.java                   # encerrar
    │   ├── AutomationRunTest.java                   # runOnce: gerar, efetivar, idempotência
    │   ├── RecurrenceCountsTest.java                # pendente não conta (integração)
    │   ├── RecurrenceAccessTest.java                # papéis e isolamento
    │   ├── RecurrenceLayersTest.java                # cada camada e a execução automática
    │   ├── RecurrenceRandomDataTest.java            # banco contra cálculo independente
    │   ├── RecurrenceConcurrencyTest.java           # execuções simultâneas, encerrar x gerar
    │   └── RecurrencePerformanceTest.java           # SC-013
    └── transaction/
        ├── AutoClearTest.java                       # marcador: criar, editar, efetivar, uso único
        └── InstallmentAutoClearTest.java            # parcelas com o marcador
```

**Structure Decision**: continua o monólito em camadas por módulo. Recorrência é um módulo novo (tem registro
próprio, rotas e regras); a execução automática fica em `automation`, separada porque não pertence a nenhum
módulo de dados.

## Complexity Tracking

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| A execução automática chama funções `SECURITY DEFINER` em vez de entrar no contexto de cada workspace | Sem pessoa logada não há usuário para o RLS (`app_user_id`) nem papel de escrita (`app_can_write`). As funções são em lote, fixam o `search_path`, só a role da aplicação as executa e cada linha que produzem herda o workspace da sua recorrência, então não há como misturar workspaces. É o padrão da `V2` | Dar `BYPASSRLS` à role da aplicação (afrouxa o isolamento de **todas** as requisições); um usuário "de sistema" membro de todos os workspaces (cria uma identidade privilegiada que a constituição não prevê) |
| O calendário das ocorrências vive só no banco (função SQL), e o Java não o repete | A geração precisa ser em lote, e a criação de uma recorrência chama a mesma função para gerar sob a mesma regra: há uma única definição. Um teste confere o SQL contra um cálculo independente em `java.time`, escrito só no teste | Gerar em Java com uma consulta por recorrência (milhares de idas ao banco por hora e dois caminhos de geração, o da criação e o do agendador, que podem divergir) |
