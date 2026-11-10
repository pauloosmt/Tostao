---

description: "Tarefas da feature 010-compra-parcelada"
---

# Tasks: Compra parcelada

**Input**: documentos em `/specs/010-compra-parcelada/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição II, V e VIII; FR-017 pede acesso cruzado por operação; FR-020 pede prova de não regressão).

**Organization**: só backend; uma migration (`V9`, três colunas, uma restrição e um índice). Ordem: base (schema, regras puras, extração), depois ler o plano, registrar e cancelar, e por fim as provas transversais (acesso, camadas, dados aleatórios, concorrência, desempenho, contrato). Depois de **cada** passo que toca código existente roda-se a suíte do módulo para provar que nada mudou (FR-020).

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação de caminhos: `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `MIG` = `backend/src/main/resources/db/migration`
- **Commits**: pequenos, Conventional Commits, mensagem curta em inglês, **sem co-author**; **código junto com os testes que o provam**; sem commit só para marcar tarefa (a marca `[X]` vai no commit que fecha a tarefa); testes rodados antes de cada commit; nunca push sem pedido
- **Rodar Maven neste computador**: `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`, Docker Desktop aberto e no PATH. Mandar o log para um arquivo (nunca `Select-Object -First N` em pipe com o Maven: fecha o pipe e mata o build)
- **Valores**: centavos inteiros (`long`); a parcela é gravada **negativa** (despesa)

---

## Phase 1: Base

- [X] T001 Criar `MIG/V9__installments.sql` (cabeçalho no estilo da `V8`): as três colunas nulas, `CHECK installments_coherent` e o índice único parcial `transactions_installment_key (workspace_id, installment_group_id, installment_number)`, exatamente como no [data-model.md](data-model.md); **sem `GRANT`**. Criar `TEST/transaction/InstallmentSchemaTest.java` (Testcontainers; conexão do dono para semear, `tostao_app` com contexto de RLS): o banco recusa só parte das colunas, `installment_count` 1 e 49, número 0 e acima do total, e parcela que não é `EXPENSE` (receita e transferência); aceita 2 e 48; recusa a segunda linha com o mesmo número no mesmo grupo do mesmo workspace e aceita o mesmo número em grupos diferentes; `tostao_app` insere uma parcela como OWNER e EDITOR e não como VIEWER; **`UPDATE` das três colunas é recusado por privilégio** (`permission denied`), enquanto `status` e `deleted_at` continuam atualizáveis; linhas antigas continuam com as três nulas
- [X] T002 [P] Criar `MAIN/transaction/InstallmentRules.java` (classe final, sem Spring nem banco, constantes `MIN_COUNT = 2`, `MAX_COUNT = 48`, `MAX_DESCRIPTION = 190`) com `split`, `dates`, `requireDatesInRange`, `requireTotal` e `describe`, como em [research.md](research.md) decisão 3 (erros por `FieldValidationException` nos campos `totalCents` e `firstOccurredOn`), e `baseDescription(description, number, count)` (decisão 7). Criar `TEST/transaction/InstallmentRulesTest.java` (**sem Docker**): R$ 100,00 em 3 = 3334/3333/3333; 5 centavos em 5, em 3 e em 2; soma exata e diferença máxima de 1 centavo para 5.000 pares aleatórios (semente fixa 20261110) com total até 10¹² e 2 a 48 parcelas; `total < count` recusado; calendário: 31/01 → 31/01, 28/02, 31/03, 30/04; ano bissexto (2028-01-31 → 29/02); 2026-11-10 com 3 → 10/11, 10/12, 10/01; 30/01 → 28/02 e 30/03; dia 29, 30 e 31 em 48 parcelas sem acumular; a última data passando de 2100-12-31 e a primeira antes de 2000 recusadas, 2100-12-31 em 2 parcelas recusada e 2100-01-31 em 12 aceita; `describe` e `baseDescription` (com sufixo certo, sem sufixo, sufixo de outro número, descrição de 190 caracteres)
- [X] T003 Extrair de `MAIN/transaction/TransactionService.java` `requireUsableAccount` e `requireUsableCategory` para a classe `MAIN/transaction/UsableRefs.java` (final, **não é bean**: recebe o `JdbcClient` e cada serviço faz `new UsableRefs(jdbc)`, para o construtor de `TransactionService` continuar com um argumento como os testes antigos o usam; métodos package-private com **o mesmo corpo, as mesmas mensagens e as mesmas travas `FOR SHARE`**); `TransactionService` passa a recebê-lo no construtor e a chamá-lo. `requireUsableAccounts` (transferência) fica onde está. **Nenhum teste muda**: rodar a suíte `transaction.*`, `account.*` e `category.*` e confirmar o mesmo resultado antes e depois
- [X] T004 Em `MAIN/transaction/dto/TransactionResponse.java` acrescentar, **ao fim**, `UUID installmentGroupId`, `Integer installmentNumber` e `Integer installmentCount`; em `TransactionService` incluir `t.installment_group_id, t.installment_number, t.installment_count` no `SELECT` e ler os três no `ROW_MAPPER` (`rs.getObject(..., Integer.class)` para os dois inteiros: nulo continua nulo). Em `TEST/transaction/CreateTransactionTest.java` **não mexer**; acrescentar `TEST/transaction/InstallmentFieldsTest.java`: um lançamento comum (receita, despesa e as duas pontas de transferência) devolve os três campos nulos na criação, na consulta por id e na listagem; uma parcela semeada pela conexão do dono devolve os três preenchidos na listagem e na consulta por id; **toda a suíte `transaction.*` e `OpenApiContractTest` sem alteração**

**Checkpoint**: `V9` aplicada e provada; as regras puras provadas; nada das specs anteriores mudou

---

## Phase 2: US2 + US1 + US3 - Plano, registro e cancelamento (P1/P2) 🎯 MVP

- [X] T005 [US2] Ler o plano. Criar `MAIN/common/error/InstallmentPlanNotFoundException.java` (mensagem "Plano de parcelas não encontrado", tratador 404 `Não encontrado` em `ApiExceptionHandler`), `MAIN/transaction/dto/InstallmentPlanResponse.java` e `MAIN/transaction/InstallmentService.java` com `@WorkspaceScoped @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ) public InstallmentPlanResponse plan(UUID workspaceId, UUID groupId)`: cabeçalho (descrição base, conta, categoria, `installment_count`) da **parcela de menor número, excluída ou não**; sem nenhuma linha do grupo no workspace = `InstallmentPlanNotFoundException`; totais somados em Java (`Math.addExact`) **a partir da própria lista** de não excluídas (corpo num método privado `loadPlan`, que o `create` da T006 também usa); lista de parcelas reaproveitando o `SELECT` e o `ROW_MAPPER` de lançamentos (tornados package-private em `TransactionService`) com `and t.installment_group_id = :groupId order by t.installment_number`. Criar `MAIN/transaction/InstallmentController.java` com `GET /api/v1/workspaces/{wid}/installment-purchases/{groupId}`. Criar `TEST/transaction/InstallmentPlanTest.java` (cenário de teste novo `InstallmentScenario`: `Corrente`, `Visa`, `Alimentação` e `Salário`; parcelas semeadas pela conexão do dono com `WorkspaceFixtures`): plano com 3 parcelas (1 efetivada, 2 pendentes) → totais 300000/100000/200000 e lista em ordem de número; efetivar uma muda os totais; parcela excluída sai da lista e dos totais e `installmentCount` fica o original; **todas excluídas** → 200, lista vazia, totais 0; descrição base com e sem sufixo (editada à mão); parcela 1 excluída e cabeçalho estável; grupo inexistente, de outro workspace e `abc` → 404; sem cookie → 401; `Cache-Control: no-store`
- [X] T006 [US1] Registrar. Criar `MAIN/transaction/dto/CreateInstallmentPurchaseRequest.java` (record como em [data-model.md](data-model.md); compact constructor com `strip` da descrição e `notes` vazio = nulo, no estilo de `CreateTransactionRequest`) e, em `InstallmentService`, `@WorkspaceScoped(write = true) @Transactional public InstallmentPlanResponse create(UUID workspaceId, CreateInstallmentPurchaseRequest request)`: `TransactionRules.parseStatus(firstInstallmentStatus)`, `InstallmentRules.requireTotal` e `requireDatesInRange` **antes de qualquer gravação**, `UsableRefs` (conta, depois categoria com tipo `EXPENSE`), `UUID.randomUUID()` para o grupo, `split` e `dates`, um `insert` por parcela (valor **negativo**, `created_by = CurrentUser.id()`, estado: primeira `CLEARED` ou o pedido, demais `PENDING`, descrição por `describe`, `notes` igual), e devolver `plan(...)`. **Nenhum `try/catch`**. Em `InstallmentController`: `POST` → 201 com `Location` do plano. Criar `TEST/transaction/InstallmentPurchaseTest.java`: R$ 3.000,00 em 3 (datas, valores, estados, descrições, grupo, número e total; resposta = plano); R$ 100,00 em 3 = 3334/3333/3333; 31/01 em 4; dia 29/02 em ano bissexto; primeira `PENDING` pedida → todas pendentes; `notes` copiada; criado por = a pessoa da sessão; 2 e 48 parcelas aceitas, 1, 49, 0, -1, `3.5`, `"abc"` e ausente recusadas em `installmentCount`; `totalCents` 0, -1, `10.5`, ausente, acima do teto e menor que o número de parcelas recusados em `totalCents`; datas fora da faixa (primeira e última); descrição vazia, só espaços, 190 (aceita) e 191 (recusada); estado `OUTRO` e vazio; campos extras (`createdBy`, `workspaceId`, `installmentGroupId`, `status`, `installmentNumber`); categoria de receita → 400 `categoryId`; conta e categoria arquivadas → 409; inexistentes e de outro workspace → 404; **em toda recusa `transactions` não muda** (impressão digital pela conexão do dono); cada parcela é um lançamento comum: aparece na listagem, edita, efetiva e exclui pelas rotas da 004/009; o grupo, o número e o total **não mudam** numa edição (corpo com esses campos é recusado)
- [ ] T007 [US3] Cancelar. Em `InstallmentService`: `@WorkspaceScoped(write = true) @Transactional public CancelInstallmentsResponse cancel(UUID workspaceId, UUID groupId)`: conferir que o plano existe (qualquer linha do grupo no workspace, excluída ou não; senão `InstallmentPlanNotFoundException`), travar `FOR UPDATE` as parcelas **pendentes e vivas** em ordem de `installment_number`, `UPDATE ... SET deleted_at = now(), updated_at = now() WHERE workspace_id = :w AND id IN (:ids) AND status = 'PENDING' AND deleted_at IS NULL` e devolver `.update()`. `MAIN/transaction/dto/CancelInstallmentsResponse.java` (`int cancelled`) e `POST /{groupId}/cancel` → 200. Criar `TEST/transaction/CancelInstallmentsTest.java`: 3 parcelas (1 efetivada) → `cancelled` 2, a efetivada continua, o plano mostra 1; repetir → 0; plano sem pendentes → 0; plano de parcelas excluídas à mão → conta só as pendentes que existem; **nenhum número realizado muda** (saldo, dashboard, orçamento mensal e fatura iguais antes e depois) e o previsto deixa de incluir as canceladas; `updated_at` das efetivadas não muda (pela conexão do dono); parcela cancelada fica com `deleted_at` (a linha continua no banco); cancelar plano de outro workspace, inexistente e `abc` → 404 e nada muda; `Cache-Control: no-store`

**Checkpoint**: registrar, ler e cancelar funcionam; `./mvnw verify` verde

---

## Phase 3: Regras que atravessam outras specs (P1)

- [ ] T008 [US1] `TEST/transaction/InstallmentCountsTest.java` (**o coração da integração**): num workspace gêmeo comparado campo a campo, conferir que as parcelas **pendentes não entram** em `GET /accounts` e `/reports/balance` (saldo; o previsto sim), `GET /dashboard` (totais, categorias, dias, comparação e saldos; o bloco `pending` sim), `GET /monthly-budgets` e `GET /budgets` (gasto) e na fatura de um cartão (`GET .../invoices/{mês}`, a lista e o `card-summary`); que efetivar uma parcela a inclui em exatos centavos em cada um desses lugares; que as parcelas de meses diferentes caem nas faturas do mês de cada data; que cancelar não muda nenhum desses números realizados. Não altera nenhuma classe de produção

---

## Phase 4: Acesso, camadas, dados aleatórios, concorrência e desempenho

- [ ] T009 [P] [US4] `TEST/transaction/InstallmentAccessTest.java`: não-membro em registrar, consultar e cancelar → 404 igual ao de workspace inexistente (mesmo `title` e `detail`) e nada muda (impressão digital de `transactions`); **VIEWER** consulta o plano normalmente, mas registrar e cancelar → 403 e nada muda; EDITOR e OWNER registram e cancelam, inclusive plano criado por outra pessoa, e `createdBy` não muda; sem cookie → 401 nas três rotas; grupo de outro workspace usado no workspace do chamador → 404 e o plano de lá não muda; conta e categoria de outro workspace no registro → 404 e nada gravado
- [ ] T010 [P] [US4] `TEST/transaction/InstallmentLayersTest.java` (SC-009): camada de dados sozinha: como `tostao_app` no contexto de A, `INSERT` de parcela com `workspace_id` de B recusado pelo RLS, `UPDATE ... SET deleted_at` sem filtro de workspace só alcança linhas de A, e como VIEWER não alcança nenhuma nem insere; camada de serviço sozinha, com RLS neutralizado (conexão do dono, `new InstallmentService(owner.client())`): `plan` e `cancel` de um grupo de B pelo workspace de A → `InstallmentPlanNotFoundException` e B intacto; `create` com conta e categoria de B pelo workspace de A → `AccountNotFoundException` e nada gravado
- [ ] T011 [P] `TEST/transaction/InstallmentRandomDataTest.java` (semente fixa 20261110 e +1): 300 compras aleatórias (total até 10¹², 2 a 48 parcelas, primeiras datas de 2000 a 2090, estados da primeira) pela API, e conferir contra um **cálculo independente no teste** (sem usar `InstallmentRules`): soma das parcelas = total, diferença máxima de 1 centavo, ordem de datas crescente, cada data = primeira + (n-1) meses limitada ao fim do mês, número e total corretos e estados; depois efetivar, excluir e editar um sorteio de parcelas e conferir os totais do plano (`totalCents`, `clearedCents`, `pendingCents`) contra uma soma independente sobre as parcelas lidas (SC-001, SC-002, SC-006)
- [ ] T012 [P] `TEST/transaction/InstallmentConcurrencyTest.java` (threads reais, `CountDownLatch`): **atomicidade** (SC-004): pela conexão do dono criar um gatilho temporário em `transactions` que levanta exceção ao inserir uma descrição terminada em "(3/5)" (removido ao fim, em `finally`), registrar uma compra de 5 parcelas e conferir resposta 500 padronizada sem vazar detalhe e **zero** parcelas gravadas (impressão digital de `transactions` igual à de antes); sem o gatilho a mesma compra funciona; 20 cancelamentos simultâneos do mesmo plano → sempre 200, a soma dos `cancelled` é igual ao número de pendentes, nunca 500 nem impasse; cancelar e efetivar a mesma parcela ao mesmo tempo, repetido 30 vezes → ou a parcela fica efetivada e **não** é cancelada, ou é cancelada e a efetivação responde 404, nunca as duas; 10 registros simultâneos na mesma conta → 10 grupos distintos, cada um completo
- [ ] T013 [P] `TEST/transaction/InstallmentPerformanceTest.java` (SC-012): 20.000 lançamentos, 3 anos; mediana de 5 chamadas de registrar uma compra de 48 parcelas, consultar o plano e listar lançamentos, cada uma abaixo de 1000 ms; imprimir as medianas
- [ ] T014 Em `TEST/OpenApiContractTest.java` acrescentar `INSTALLMENT_CONTRACT` (`specs/010-compra-parcelada/contracts/openapi.yaml`): declara as três rotas; `/v3/api-docs` expõe `post` em `/installment-purchases`, `get` em `/{groupId}` e `post` em `/{groupId}/cancel`; os schemas de saída trazem `TransactionResponse.installmentGroupId`, `installmentNumber` e `installmentCount`, `InstallmentPlanResponse` com `groupId`, `description`, `installmentCount`, `accountId`, `categoryId`, `totalCents`, `clearedCents`, `pendingCents` e `installments`, e `CancelInstallmentsResponse.cancelled`; incluir no laço "todos os contratos"

---

## Phase 5: Polish

- [ ] T015 Rodar `./mvnw -B verify` completo; corrigir qualquer falha **nova** sem enfraquecer testes e registrar a contagem. **Nenhum teste das specs 001 a 009 pode ter sido alterado** (conferir com `git diff` dos arquivos de teste antigos)
- [ ] T016 [P] Atualizar `README.md`: seção de compra parcelada (as três rotas, divisão em centavos, datas mês a mês, primeira efetivada e demais pendentes, cancelar só as pendentes, parcela = lançamento comum) e a nota de que os campos de parcela aparecem em cada lançamento
- [ ] T017 Executar o [quickstart.md](quickstart.md) contra o Docker Compose reconstruído (`docker compose up -d --build`), com `Flyway` na versão 9 e limpeza ao fim; marcar as tarefas restantes como `[X]` junto do último commit de código ou documento que as fecha; não fazer push

---

## Dependencies & Execution Order

- **Phase 1**: T001, T002 e T003 independentes entre si (T002 sem banco); T004 depois de T001 (precisa das colunas)
- **Phase 2** depende da Phase 1: T005 antes de T006 (o registro devolve o plano) e T007 depende de T005
- **Phase 3** (T008) depende da Phase 2
- **Phase 4**: T009 a T013 em paralelo depois da Phase 2; T014 depois de T007
- **Phase 5**: T015 depois de tudo; T017 por último

## Commits previstos (código sempre com os testes que o provam)

1. `feat(db): add the installment columns to transactions` — T001
2. `feat(transaction): add the installment rules` — T002
3. `refactor(transaction): extract the account and category checks` — T003
4. `feat(transaction): expose the installment fields on every transaction` — T004
5. `feat(transaction): read the installment plan` — T005
6. `feat(transaction): register an installment purchase` — T006
7. `feat(transaction): cancel the pending installments` — T007
8. `test(transaction): prove pending installments never count` — T008
9. `test(transaction): prove installment access and isolation` — T009, T010
10. `test(transaction): prove installment sums, races and speed` — T011, T012, T013
11. `test(api): check the installment contract` — T014
12. `docs: describe installment purchases in the README` — T016, com as marcas de T015 e T017 no último commit

## Implementation Strategy

1. Schema e regras puras primeiro (T001 a T004): é pequeno, provado sem a API e destrava o resto.
2. Ler o plano, registrar e cancelar (T005 a T007), cada um com a suíte do módulo como prova de não regressão.
3. O "pendente não conta" (T008) como a prova principal de integração com as specs 002, 005, 006, 007, 008 e 009.
4. Acesso, camadas, dados aleatórios, concorrência, desempenho e contrato fecham a prova.
5. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
