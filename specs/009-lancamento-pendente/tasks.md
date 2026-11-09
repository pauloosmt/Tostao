---

description: "Tarefas da feature 009-lancamento-pendente"
---

# Tasks: Lançamento pendente

**Input**: documentos em `/specs/009-lancamento-pendente/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição II, V e VIII; FR-013 pede acesso cruzado por operação; FR-017 pede prova de não regressão).

**Organization**: só backend; uma migration (`V8`, só privilégio). A mudança atravessa quatro módulos, então a ordem é: base, escrita do estado, depois **a regra única em cada consulta**, e só então os testes de cada história. Depois de **cada** grupo de consultas alteradas roda-se a suíte do módulo para provar que nada mudou (FR-017).

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação de caminhos: `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `MIG` = `backend/src/main/resources/db/migration`
- **Commits**: pequenos, Conventional Commits, **sem co-author**; testes rodados antes de cada commit; nunca push sem pedido
- **Rodar Maven neste computador**: `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`, Docker Desktop aberto e no PATH. Mandar o log para um arquivo (nunca `Select-Object -First N` em pipe com o Maven: fecha o pipe e mata o build)
- **Valores**: centavos inteiros (`long`), soma com `Math.addExact`

---

## Phase 1: Base

- [ ] T001 Criar `MIG/V8__transaction_status.sql` com o cabeçalho no estilo da `V7` e `GRANT UPDATE (status) ON transactions TO tostao_app;` (comentando que a `V5` listou só as colunas editáveis e que o RLS de `UPDATE` já exige `app_can_write`). Subir o Postgres e conferir que a `V8` aplica
- [ ] T002 [P] Criar `TEST/transaction/TransactionStatusSchemaTest.java` (Testcontainers; conexão do dono para semear, `tostao_app` com contexto de RLS): `tostao_app` como OWNER e EDITOR consegue `UPDATE transactions SET status = 'PENDING'` e `'CLEARED'` no próprio workspace; como VIEWER o `UPDATE` não alcança linha alguma; sem contexto e com o contexto de outro workspace, não alcança linha alguma; o banco recusa `status = 'OUTRO'` (`CHECK`); `UPDATE` de `type`, `transfer_group_id`, `created_by`, `workspace_id` e `created_at` continua recusado por privilégio; uma linha criada sem `status` nasce `CLEARED`
- [ ] T003 [P] Criar `MAIN/transaction/TransactionStatus.java` (`CLEARED`, `PENDING`) e acrescentar o campo `status` (`TransactionStatus`, **opcional**, sem `@NotNull`) a `CreateTransactionRequest` e `UpdateTransactionRequest`, e `status` (`TransactionStatus`) a `TransactionResponse` (e ao `ROW_MAPPER` e ao `SELECT` de `TransactionService`, lendo `t.status`); acrescentar `status` a `TransactionFilter`. Valor desconhecido já vira 400 no campo `status` pelo tratador de corpo ilegível; no parâmetro `?status=` pelo conversor de enum, conferir que o erro aponta o campo `status` (como o `type` faz)

**Checkpoint**: `V8` aplicada e provada; o projeto compila

---

## Phase 2: US1 + US2 + US5 - Escrever o estado (P1/P2) 🎯 MVP

- [ ] T004 [US1] Em `TransactionService.create`, gravar o estado: ausente = `CLEARED`; `insertRow` recebe o estado e o `insert` passa `status` (as duas pontas da transferência recebem o mesmo). A resposta já traz `status` (T003)
- [ ] T005 [US5] Em `TransactionService.update`, `updateRow` passa a gravar `status = coalesce(cast(:status as text), status)` nas duas pontas de uma transferência e na linha única: estado ausente mantém o atual; presente muda junto com os demais campos, na mesma transação
- [ ] T006 [US2] Em `TransactionService`, acrescentar `@WorkspaceScoped(write = true) @Transactional public TransactionResponse clear(UUID workspaceId, UUID transactionId)`: `peek` + `lockUnit` (a linha, ou as duas pontas em ordem de id), `UPDATE transactions SET status = 'CLEARED', updated_at = now() WHERE workspace_id = :workspaceId AND id IN (:ids) AND deleted_at IS NULL AND status <> 'CLEARED'`, devolve `load`. **Não** chama `requireUsableAccount` nem `requireUsableCategory`. Em `TransactionController`: `POST /{id}/clear` (200 com o lançamento) e o parâmetro `status` da listagem (`@RequestParam(name = "status", required = false) TransactionStatus status`) passado ao `TransactionFilter`
- [ ] T007 [US4] Em `TransactionService.list`, acrescentar a condição `and t.status = :status` (parametrizada) quando o filtro vem, tanto na contagem quanto na página

**Checkpoint**: criar, listar com filtro, editar e efetivar funcionam; `./mvnw verify` verde

---

## Phase 3: A regra única nas consultas do realizado (P1)

- [ ] T008 [US1] **Saldo e previsto (spec 002).** Em `AccountService.SELECT_WITH_BALANCE`: o `left join transactions t` ganha `and t.status = 'CLEARED'` na condição do join para o saldo (`balanceCents`); o pendente vem de `coalesce(sum(case when t.status = 'PENDING' ...))` — como o join filtra só efetivados, usar duas junções com alias (`t` efetivados e `p` pendentes) ou uma única junção sem filtro com `sum(t.amount_cents) filter (where t.status = 'CLEARED')` e `filter (where t.status = 'PENDING')` (escolher a mais simples e provar com teste). `AccountResponse` ganha `pendingCents` e `projectedBalanceCents` (saldo + pendente, `addExact`) e o `ROW_MAPPER` os preenche. `BalanceReportController`/`BalanceReportResponse`: cada item ganha `pendingCents` e `projectedBalanceCents`, e a resposta ganha `totalProjectedCents` (soma dos previstos das contas ativas). **Rodar a suíte `account.*` e `report.*` sem alterar teste**
- [ ] T009 [US1] **Dashboard (spec 005).** Em `DashboardService`: `DAILY_SQL` e `CATEGORY_SQL` ganham `and status = 'CLEARED'` (alias `t.` no segundo); `ACCOUNT_BALANCES_SQL` ganha `and t.status = 'CLEARED'` na condição do `left join`; `MOVEMENT_SQL` ganha `and t.status = 'CLEARED'` no `where`. Acrescentar `PENDING_SQL` (receitas pendentes, despesas pendentes positivas e quantidade, no período, sem transferências e sem excluídos) e o bloco `Pending(long incomeCents, long expenseCents, long count)` em `DashboardResponse` (campo `pending`), lido na mesma transação `REPEATABLE READ`. **Rodar a suíte `dashboard.*` sem alterar teste**
- [ ] T010 [US1] **Orçamentos (specs 006 e 007) e fatura (008).** `BudgetService.PERIOD_SPENT_SQL` ganha `and t.status = 'CLEARED'` (vale para os dois orçamentos). `InvoiceService`: `LINES_SQL`, `TOTAL_SQL`, `PAID_SQL` e `DAILY_SQL` ganham `and t.status = 'CLEARED'`. **Rodar as suítes `budget.*` e `invoice.*` sem alterar teste**

**Checkpoint**: nenhum teste das specs anteriores mudou de resultado (FR-017); `./mvnw verify` verde

---

## Phase 4: Testes das histórias

- [ ] T011 [US1] `TEST/transaction/PendingTransactionTest.java` (Testcontainers + MockMvc, com um cenário de teste novo `PendingScenario`: `Corrente` com saldo inicial 100000, `Poupança` com 0, `Alimentação` e `Salário`): criar sem `status` → `CLEARED`; criar `PENDING` (despesa, receita e transferência: as duas pontas `PENDING`); a listagem mostra o estado; `status` inválido (`OUTRO`, vazio, número) → 400 no campo `status` e nada criado; data no passado e no futuro aceitas; campos extras continuam recusados; um pendente excluído não conta em lugar nenhum
- [ ] T012 [US1] `TEST/transaction/PendingNotCountedTest.java` (**o coração da funcionalidade**): num workspace com efetivados e pendentes (despesa, receita, transferência, e um pendente excluído), conferir que **nada realizado muda**: `GET /accounts` e `/reports/balance` (saldo e total só efetivados); `GET /dashboard` do mês (totais, `expenseByCategory`, `incomeByCategory`, `daily`, `comparison` e `balances` iguais aos de um workspace gêmeo sem os pendentes); `GET /budgets?week=` e `GET /monthly-budgets?month=` (gasto, total gasto e fora dos limites iguais); uma compra pendente num cartão não entra em `GET .../invoices/{mês}`, na lista nem no `card-summary`, e uma transferência pendente para o cartão não conta como paga. Montar o "gêmeo" criando o mesmo cenário sem os pendentes e comparando as respostas campo a campo
- [ ] T013 [US2] `TEST/transaction/ClearTransactionTest.java`: efetivar uma despesa pendente → `CLEARED`, `updatedAt` maior, e o saldo, o dashboard, o orçamento e a fatura passam a incluí-la em exatos centavos; efetivar uma ponta de transferência pendente → as duas `CLEARED` e os dois saldos mudam; efetivar de novo → 200 e **nenhuma coluna muda**, nem `updated_at` (conferir pela conexão do dono); só o estado e `updated_at` mudam (valor, data, conta, categoria e descrição iguais); efetivar em conta e em categoria arquivadas depois da criação funciona; excluído, de outro workspace, inexistente (`UUID` aleatório) e malformado (`abc`) → 404; `createdBy` não muda quando outra pessoa efetiva
- [ ] T014 [US4] `TEST/transaction/ForecastTest.java`: conta com saldo 100000, despesa pendente 80000 e receita pendente 30000 e transferência pendente 10000 → `balanceCents`, `pendingCents` (com sinal) e `projectedBalanceCents` certos em cada conta; conta sem pendentes → pendente 0 e previsto = saldo; `GET /reports/balance`: cada item e `totalProjectedCents` (só contas ativas: arquivar uma tira do total); o filtro `?status=PENDING`, `?status=CLEARED` e combinado com `type`, `accountId` e datas; `?status=OUTRO` → 400 campo `status`; dashboard: bloco `pending` com receita, despesa positiva e `count` (sem transferências, sem excluídos, só do período); período sem pendentes → zeros
- [ ] T015 [US5] `TEST/transaction/UpdateStatusTest.java`: editar informando `status` efetiva ou volta a pendente junto com os demais campos; **sem `status` mantém o atual** (pendente continua pendente); transferência: as duas pontas mudam juntas pela edição; efetivado → pendente tira o lançamento do realizado e põe no previsto (ida e volta sem resíduo); estado inválido → 400 campo `status` e nada muda; edição que muda a data e o estado juntos
- [ ] T016 [P] [US3] `TEST/transaction/PendingAccessTest.java`: não-membro em `POST .../clear`, criar pendente e editar estado → 404 igual ao de workspace inexistente (mesmo `title` e `detail`) e nada muda (impressão digital de `transactions`); **VIEWER** lê a listagem com o estado, mas `clear`, criar com `status` e editar com `status` → 403 e nada muda; EDITOR e OWNER mudam o estado, inclusive de lançamento de outra pessoa; sem cookie → 401; `id` de lançamento de outro workspace → 404 e o lançamento não muda
- [ ] T017 [P] [US3] `TEST/transaction/PendingLayersTest.java` (SC-009): camada de dados sozinha: como `tostao_app` no contexto de A, o `UPDATE ... SET status` sem filtro de workspace só alcança linhas de A; como VIEWER, não alcança nenhuma; camada de serviço sozinha, com RLS neutralizado (conexão do dono, `new TransactionService(owner.client())`): `clear` com `id` de lançamento de B pelo workspace de A → `TransactionNotFoundException` e B intacto; `update` com `status` idem; `list` com `status` não mostra lançamentos de B

---

## Phase 5: Polish

- [ ] T018 [P] `TEST/transaction/PendingRandomDataTest.java` (semente fixa 20261109 e +1): semear ~800 lançamentos aleatórios (receitas, despesas, transferências, excluídos, efetivados e pendentes, em categorias e contas ativas e arquivadas, num cartão também) e conferir, contra um **cálculo independente no teste**, o saldo, o pendente e o previsto de cada conta, o bloco `pending` do dashboard, o gasto de um limite mensal e o total de uma fatura; depois **efetivar todos os pendentes** e conferir que o realizado passa a incluí-los e o previsto não muda; e **voltar** um sorteio de efetivados a pendente e conferir a ida e a volta sem resíduo (SC-001, SC-002, SC-004, SC-005)
- [ ] T019 [P] `TEST/transaction/PendingConcurrencyTest.java` (threads reais, `CountDownLatch`): 20 efetivações simultâneas da mesma transferência pendente → sempre 200, as duas pontas `CLEARED`, nunca 500, e os dois saldos mudam uma só vez; efetivar e excluir o mesmo lançamento ao mesmo tempo, repetido 30 vezes → ou efetivado e depois excluído, ou 404; efetivar e editar a mesma transferência ao mesmo tempo → as duas pontas sempre no mesmo estado (SC-003)
- [ ] T020 [P] `TEST/transaction/PendingPerformanceTest.java` (SC-012): 20.000 lançamentos, uma parte pendente (~10%), 3 anos; mediana de 5 chamadas de listagem (com e sem `status`), saldos, dashboard do mês, acompanhamento de orçamento e fatura, cada uma abaixo de 1000 ms; imprimir as medianas
- [ ] T021 Em `TEST/OpenApiContractTest.java` acrescentar `PENDING_CONTRACT` (`specs/009-lancamento-pendente/contracts/openapi.yaml`): declara a rota `POST /api/v1/workspaces/{wid}/transactions/{id}/clear`; `/v3/api-docs` expõe a rota (só `post`) e o parâmetro `status` na listagem; os schemas de saída trazem os campos novos (`TransactionResponse.status`, `AccountResponse.pendingCents` e `projectedBalanceCents`, `BalanceReportResponse.totalProjectedCents`, `DashboardResponse.pending`); incluir no laço "todos os contratos"
- [ ] T022 Rodar `./mvnw -B verify` completo; corrigir qualquer falha **nova** sem enfraquecer testes e registrar a contagem. **Nenhum teste das specs 001 a 008 pode ter sido alterado** (conferir com `git diff` dos arquivos de teste antigos)
- [ ] T023 [P] Atualizar `README.md`: seção de lançamentos com o `status`, a rota de efetivar, o filtro `?status=` e a nota "o pendente nunca conta no realizado (saldo, dashboard, orçamentos e fatura)"; e nas notas dos saldos o previsto
- [ ] T024 Executar o [quickstart.md](quickstart.md) contra o Docker Compose reconstruído (`docker compose up -d --build`), com `Flyway` na versão 8 e limpeza ao fim; marcar todas as tarefas como `[X]` e commitar; não fazer push

---

## Dependencies & Execution Order

- **Phase 1** (T001 a T003) bloqueia tudo; T002 e T003 em paralelo depois de T001
- **Phase 2** (T004 a T007) depende da Phase 1; T004 a T006 editam `TransactionService` (sequenciais)
- **Phase 3** (T008 a T010) depende da Phase 1 (só precisa da `V8` aplicada para o schema; as consultas não dependem de T004 a T007) e **cada tarefa fecha com a suíte do seu módulo sem alterar teste**
- **Phase 4** depende das fases 2 e 3
- **Phase 5**: T022 depois de T021; T024 por último

## Implementation Strategy

1. Base e escrita do estado primeiro (T001 a T007): é pequeno e destrava o resto.
2. A regra única em cada consulta (T008 a T010), com a suíte de cada módulo como prova de não regressão.
3. Testes de cada história, o "pendente não conta" (T012) como o principal.
4. Dados aleatórios, concorrência, desempenho e contrato fecham a prova.
5. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
