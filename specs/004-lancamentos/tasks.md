---

description: "Tarefas da feature 004-lancamentos"
---

# Tasks: Lançamentos

**Input**: documentos em `/specs/004-lancamentos/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS. A constituição (princípios II, V e VIII) e o FR-019 exigem teste de acesso cruzado por operação, teste de saldo e de transferência e integração com Postgres real.

**Organization**: tarefas agrupadas por user story. Só o backend entra nesta feature (as telas ficam no roadmap de front-end). Não há fase de Setup: nenhuma dependência nem configuração nova.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência de tarefa incompleta)
- **[Story]**: US1 a US6, conforme a spec
- Abreviação de caminhos usada abaixo (nunca em código):
  `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `RES` = `backend/src/main/resources`
- **Commits**: um commit por tarefa ou por pequeno grupo coeso (poucos arquivos), em Conventional Commits, sem co-author. Antes de cada commit rodar os testes que a tarefa tocou; ao fim de cada fase rodar `./mvnw verify`.
- **Convenção do projeto**: todo `JdbcClient` da aplicação conecta como `tostao_app`. Os testes usam o bean `OwnerJdbc` (conexão do dono, ignora RLS) só para semear e inspecionar dados. `WorkspaceFixtures.workspaceWithOwner()` cria o workspace sem categorias; use `seedDefaults` ou `insertCategory` quando o teste precisar delas.
- **Valores**: o cliente envia `amountCents` sempre positivo; o serviço grava `+` para receita e entrada de transferência e `-` para despesa e saída. As respostas trazem o valor com sinal.

---

## Phase 1: Foundational (bloqueia todas as user stories)

**Purpose**: migration V5, usuário atual, erros, tipos e fixtures compartilhados

**⚠️ CRITICAL**: nenhuma user story começa antes deste checkpoint

- [ ] T001 Criar `RES/db/migration/V5__transactions.sql` (a V1 a V4 não são editadas), nesta ordem: (1) `ALTER TABLE transactions ADD CONSTRAINT transactions_description_trimmed CHECK (description = btrim(description) AND char_length(description) BETWEEN 1 AND 200)`, `transactions_notes_length CHECK (notes IS NULL OR char_length(notes) BETWEEN 1 AND 1000)`, `transactions_amount_limit CHECK (abs(amount_cents) <= 1000000000000)` e `transactions_date_range CHECK (occurred_on BETWEEN DATE '2000-01-01' AND DATE '2100-12-31')`; (2) `ALTER TABLE transactions ADD CONSTRAINT transactions_category_kind_fkey FOREIGN KEY (category_id, workspace_id, type) REFERENCES categories (id, workspace_id, kind)` (usa a chave única `(id, workspace_id, kind)` da V4; a chave composta existente `(category_id, workspace_id)` continua); (3) `CREATE UNIQUE INDEX transactions_transfer_leg_key ON transactions (transfer_group_id, (amount_cents > 0)) WHERE transfer_group_id IS NOT NULL`; (4) `DROP POLICY transactions_tenant ON transactions` e políticas para `tostao_app`: `transactions_read` (SELECT, `workspace_id = app_workspace_id()`), `transactions_insert` (INSERT, `WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id) AND created_by = app_user_id())`) e `transactions_update` (UPDATE, `workspace_id = app_workspace_id() AND app_can_write(workspace_id)` em `USING` e em `WITH CHECK`), sem política de `DELETE` (a função `app_can_write` vem da V3); (5) `REVOKE DELETE ON transactions FROM tostao_app`, `REVOKE UPDATE ON transactions FROM tostao_app` e `GRANT UPDATE (account_id, category_id, amount_cents, occurred_on, description, notes, updated_at, deleted_at) ON transactions TO tostao_app`. Rodar `./mvnw verify` e anotar quais testes antigos quebram por causa do `DELETE` e do `UPDATE` revogados (esperado: só o `RlsPolicyTest`, tratado no T002) antes de commitar
- [ ] T002 [P] Em `TEST/workspace/RlsPolicyTest.java`, no teste `updatingAndDeletingAnotherWorkspacesRowsAffectsNothing`, trocar o `delete from transactions where workspace_id = ...` por `update transactions set description = 'hackeada' where workspace_id = ...` (coluna que a role ainda pode atualizar) e manter a asserção de zero linhas; a proibição de `DELETE` passa a ser provada no `TransactionRlsTest` (T019)
- [ ] T003 [P] Criar `MAIN/workspace/CurrentUser.java` com o método estático `UUID id()`, que lê o `sub` do `JwtAuthenticationToken` do `SecurityContext` e lança `UnauthenticatedException` se não houver sessão; trocar a leitura equivalente em `MAIN/workspace/WorkspaceScopeAspect.java` por `CurrentUser.id()` (mesmo comportamento; o usuário continua vindo só do `SecurityContext`, nunca de argumento). Rodar `WorkspaceRoleAspectTest` e `TenantIsolationTest`
- [ ] T004 [P] Criar em `MAIN/common/error/` as exceções `TransactionNotFoundException` (404, "Lançamento não encontrado") e `TransactionConflictException(String message)` (409, usada para conta ou categoria arquivada); em `MAIN/common/error/ApiExceptionHandler.java` mapeá-las (404 "Não encontrado"; 409 "Conflito de estado") e fazer o `handleTypeMismatch` tratar também o parâmetro de consulta (`@RequestParam`) com tipo errado: 400 no formato de validação com `errors[0].field` igual ao nome do parâmetro (`MethodArgumentTypeMismatchException.getName()`) e mensagem "valor inválido"; variável de rota continua 404 e o resto segue o padrão
- [ ] T005 [P] Criar `MAIN/transaction/TransactionType.java` (enum `INCOME`, `EXPENSE`, `TRANSFER`), `MAIN/transaction/dto/TransactionResponse.java` (record com `id`, `accountId`, `type`, `amountCents` com sinal, `occurredOn` como `LocalDate`, `description`, `notes`, `categoryId`, `transferGroupId`, `counterpartAccountId`, `createdBy`, `createdAt` e `updatedAt` como `Instant`) e `MAIN/transaction/dto/TransactionPageResponse.java` (record com `List<TransactionResponse> items`, `int page`, `int size`, `long totalItems`, `int totalPages`)
- [ ] T006 [P] Em `TEST/support/WorkspaceFixtures.java`, acrescentar `insertLedgerTransaction(UUID workspaceId, UUID accountId, UUID userId, UUID categoryId, String type, long absAmountCents, LocalDate date, String description, boolean deleted)` devolvendo o id (grava o sinal certo para `INCOME`/`EXPENSE` pelo `OwnerJdbc`) e `insertTransferPair(UUID workspaceId, UUID fromAccount, UUID toAccount, UUID userId, long cents, LocalDate date, String description)` devolvendo `UUID[]{idDaSaida, idDaEntrada}` com o mesmo `transfer_group_id`

**Checkpoint**: V5 aplicada, `RlsPolicyTest` ajustado, erros e tipos prontos; `./mvnw verify` verde

---

## Phase 2: User Story 1 - Registrar uma receita ou uma despesa (Priority: P1) 🎯 MVP

**Goal**: criar receitas e despesas, com o saldo da conta refletindo na hora

**Independent Test**: `POST /api/v1/workspaces/{wid}/transactions` devolve 201 e `GET /accounts/{id}` mostra o saldo novo

### Tests for User Story 1

- [ ] T007 [P] [US1] `TEST/transaction/CreateTransactionTest.java` (Testcontainers + MockMvc, `WorkspaceFixtures`; categorias semeadas com `insertCategory`): despesa de R$ 100,00 devolve 201 com `amountCents` `-10000`, `type` `EXPENSE`, `createdBy` igual ao usuário da sessão, `createdAt` e `updatedAt` presentes, cabeçalho `Location` e `categoryId`; receita de R$ 250,00 devolve `25000`; o `GET /accounts/{id}` mostra o saldo inicial de 100000 virando 115000 (−10000 + 25000); soma exata de 10 e 20 centavos (30); categoria principal e subcategoria são aceitas; recusas com 400 e `errors[].field` apontando o campo (e nada criado, conferido pelo `OwnerJdbc`): `amountCents` `0`, `-5`, `10.5` e `1000000000001`, `description` vazia, só espaços e com 201 caracteres, `occurredOn` `1999-12-31` e `2101-01-01` e `2026-02-30`, `type` desconhecido, receita sem `categoryId`, despesa com categoria de receita e receita com categoria de despesa (campo `categoryId`), `destinationAccountId` informado em receita (campo `destinationAccountId`), `notes` com 1.001 caracteres; campos extras `workspaceId`, `createdBy`, `transferGroupId`, `deletedAt`, `createdAt` e `status` devolvem 400; descrição aparada nas pontas; data futura aceita; dois lançamentos idênticos são aceitos; conta arquivada e categoria arquivada devolvem 409 e nada é criado; conta e categoria de outro workspace e inexistentes devolvem 404 e nada é criado; nenhuma resposta contém `workspace_id`

### Implementation for User Story 1

- [ ] T008 [P] [US1] Criar `MAIN/transaction/dto/CreateTransactionRequest.java` como `record` com Bean Validation: `type` `TransactionType` obrigatório; `accountId` `UUID` obrigatório; `destinationAccountId` `UUID` opcional; `amountCents` `Long` obrigatório de 1 a 1.000.000.000.000 (`@Min(1)` e `@Max` com `MoneyLimits.MAX_CENTS`); `occurredOn` `LocalDate` obrigatório; `description` obrigatória, 1 a 200 caracteres após `strip`; `notes` opcional, até 1.000 caracteres, com `strip` e texto vazio virando nulo; `categoryId` `UUID` opcional; mensagens em português; construtor compacto que aplica `strip`
- [ ] T009 [US1] Criar `MAIN/transaction/TransactionService.java`, cujo único argumento de construtor é o `JdbcClient` (o T021 monta o serviço assim com a conexão do dono), com `create(UUID workspaceId, CreateTransactionRequest)` anotado `@WorkspaceScoped(write = true)` e `@Transactional`, ramo `INCOME`/`EXPENSE`: `FieldValidationException("destinationAccountId", "só vale para transferência")` se vier destino; `FieldValidationException("categoryId", "obrigatório para receita e despesa")` se faltar; data fora de 2000-01-01 a 2100-12-31 vira `FieldValidationException("occurredOn", ...)`; carregar a conta com `select archived from accounts where id = :id and workspace_id = :workspaceId for share` (`AccountNotFoundException` se faltar, `TransactionConflictException("A conta está arquivada")` se arquivada) e a categoria com `select kind, archived from categories where id = :id and workspace_id = :workspaceId for share` (`CategoryNotFoundException` se faltar, `FieldValidationException("categoryId", "deve ser uma categoria de receita")` ou "de despesa" se o tipo não bater, `TransactionConflictException("A categoria está arquivada")` se arquivada); inserir com `created_by = CurrentUser.id()` e o sinal do valor pelo tipo; devolver o lançamento carregado por um método privado `load(workspaceId, id)` que lê só linhas com `deleted_at is null` e `workspace_id = :workspaceId` e junta a outra ponta (`left join transactions o on o.workspace_id = t.workspace_id and o.transfer_group_id = t.transfer_group_id and o.id <> t.id`) para `counterpartAccountId`. Toda consulta com `workspace_id` explícito e parâmetros ligados
- [ ] T010 [US1] Criar `MAIN/transaction/TransactionController.java` com `POST /api/v1/workspaces/{wid}/transactions` (201, cabeçalho `Location`, corpo `TransactionResponse`); o `wid` entra como `UUID` primeiro parâmetro do serviço

**Checkpoint**: US1 funciona e é testável sozinha

---

## Phase 3: User Story 2 - Transferir dinheiro entre contas (Priority: P1)

**Goal**: transferências atômicas de duas pontas, sem mudar o saldo total

**Independent Test**: transferir R$ 300,00 de A para B e conferir A −300, B +300 e total igual

### Tests for User Story 2

- [ ] T011 [P] [US2] `TEST/transaction/TransferTest.java`: transferência de 30000 de Corrente (100000) para Poupança (0) devolve 201 com a ponta de saída (`amountCents` `-30000`, `type` `TRANSFER`, `categoryId` nulo, `transferGroupId` e `counterpartAccountId` igual à Poupança); no banco há exatamente duas linhas com o mesmo `transfer_group_id`, `-30000` na origem e `30000` no destino, mesma data, descrição e observação, ambas com `created_by` do usuário; saldos 70000 e 30000 e `reports/balance` com o mesmo total de antes; transferência de e para cartão de crédito é aceita; recusas com 400 e o campo, sem nenhuma ponta criada (contagem de linhas igual antes e depois): `destinationAccountId` ausente, igual à origem (campo `destinationAccountId`), `categoryId` informado (campo `categoryId`), `amountCents` `0`, `-1`, `10.5` e acima do limite; origem ou destino arquivado devolve 409; origem ou destino de outro workspace ou inexistente devolve 404; falha forçada na segunda ponta (gatilho temporário `BEFORE INSERT` que levanta exceção quando `amount_cents > 0`, criado e removido pelo dono dentro do teste) deixa zero linhas gravadas e a API responde sem expor detalhes

### Implementation for User Story 2

- [ ] T012 [US2] Em `MAIN/transaction/TransactionService.java`, acrescentar ao `create` o ramo `TRANSFER`: `FieldValidationException("destinationAccountId", "obrigatório para transferência")` se faltar, `"deve ser diferente da conta de origem"` se for igual, `FieldValidationException("categoryId", "transferência não tem categoria")` se vier categoria; travar as duas contas com `select id, archived from accounts where workspace_id = :workspaceId and id in (:origin, :destination) order by id for share` (ordem de id evita deadlock entre transferências opostas), `AccountNotFoundException` se não vierem as duas, `TransactionConflictException` se alguma estiver arquivada; gerar um `transfer_group_id` novo (`UUID.randomUUID()`) e inserir as duas pontas (saída `-valor` na origem, entrada `+valor` no destino) com a mesma data, descrição e observação e `created_by = CurrentUser.id()`; devolver a ponta de saída

**Checkpoint**: US1 e US2 funcionam; `./mvnw verify` verde

---

## Phase 4: User Story 3 - Consultar e filtrar o extrato (Priority: P1)

**Goal**: listar paginado, filtrar e consultar um lançamento

**Independent Test**: com lançamentos variados, cada filtro devolve o esperado e paginar cobre tudo exatamente uma vez

### Tests for User Story 3

- [ ] T013 [P] [US3] `TEST/transaction/ListTransactionsTest.java`: sem filtros, a primeira página vem do mais recente para o mais antigo, com empate de data decidido pelo `created_at` mais novo primeiro e depois pelo `id` (semear com `created_at` explícito), `page` `1`, `size` `20`, `totalItems` e `totalPages` corretos; percorrer todas as páginas com `size=7` devolve cada lançamento não excluído exatamente uma vez; `from`/`to` inclusivos (lançamento em cada borda aparece, um dia fora não); `from` maior que `to` devolve 400 com o campo `from`; filtro `accountId` traz inclusive a saída e a entrada de transferências que tocam a conta; `categoryId` de uma principal traz os lançamentos dela e os de suas subcategorias (inclusive subcategoria arquivada), e de uma subcategoria só os dela; `type` `INCOME`, `EXPENSE` e `TRANSFER`; `q` sem diferenciar maiúsculas, `q=%` e `q=_` e `q=\` casam só descrições com esses caracteres literais, `q` com 101 caracteres devolve 400; filtros combinados (conta, categoria, período, tipo e texto) devolvem a interseção; lançamento excluído (semeado com `deleted_at`) não aparece na lista, no total, nem na consulta direta (404); `page=0`, `size=0`, `size=101`, `page=abc`, `type=XYZ`, `accountId=abc`, `categoryId=abc`, `from=ontem` devolvem 400 com `errors[].field` igual ao parâmetro; workspace sem lançamentos devolve `items` vazio e `totalItems` `0`; `GET /transactions/{id}` devolve o lançamento (com `counterpartAccountId` na transferência), de outro workspace, inexistente ou malformado (`abc`) devolve 404; pontas de transferência aparecem uma por conta; nenhuma resposta contém `workspace_id`
- [ ] T014 [P] [US3] `TEST/transaction/TransactionPerformanceTest.java`: semeia 20.000 lançamentos (várias contas e categorias, datas espalhadas por 3 anos, algumas transferências e excluídos) com `generate_series` pelo `OwnerJdbc` e confere que uma página sem filtros, uma com filtro de período + conta, uma com `categoryId` principal e uma com `q` respondem em menos de 1 segundo cada (mediana de 5 chamadas depois de uma de aquecimento); registra o plano real (`explain`) de pelo menos a consulta de período no log do teste. Se falhar, a correção é um índice novo numa `V6`, nunca relaxar a meta

### Implementation for User Story 3

- [ ] T015 [US3] Em `MAIN/transaction/TransactionService.java`, acrescentar `list(UUID workspaceId, TransactionFilter filter)` com `@WorkspaceScoped` e `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)` e `get(UUID workspaceId, UUID transactionId)` com `@WorkspaceScoped` e `@Transactional(readOnly = true)` (`TransactionNotFoundException` se não houver linha vigente do workspace). Criar o record `MAIN/transaction/TransactionFilter.java` (`page`, `size`, `from`, `to`, `accountId`, `categoryId`, `type`, `q`) com a validação no serviço: `page >= 1`, `size` de 1 a 100, `from <= to` (senão `FieldValidationException("from", "não pode ser depois da data final")`), `q` com no máximo 100 caracteres. A consulta lê `t.workspace_id = :workspaceId and t.deleted_at is null` mais as condições parametrizadas dos filtros presentes: período em `occurred_on`, `account_id`, `type`, `categoryId` como `t.category_id = :categoryId or t.category_id in (select id from categories where parent_id = :categoryId and workspace_id = :workspaceId)`, e texto como `lower(t.description) like lower(:pattern) escape '\'` com `%`, `_` e `\` escapados no Java e o padrão `%texto%`; ordenação `t.occurred_on desc, t.created_at desc, t.id desc`, `limit :size offset :offset`; uma segunda consulta `count(*)` com as mesmas condições; `totalPages` é o teto de `totalItems / size`
- [ ] T016 [US3] Em `MAIN/transaction/TransactionController.java`, acrescentar `GET /api/v1/workspaces/{wid}/transactions` (parâmetros `page`, `size`, `from`, `to`, `accountId`, `categoryId`, `type`, `q`, com `page` padrão 1 e `size` padrão 20; `type` lido como `TransactionType`) devolvendo `TransactionPageResponse`, e `GET .../transactions/{id}`

**Checkpoint**: US1 a US3 funcionam; `./mvnw verify` verde

---

## Phase 5: User Story 4 - Só quem pode altera, e só no seu workspace (Priority: P1)

**Goal**: papel e isolamento provados nas duas camadas e as garantias do banco. Editar e excluir por papel e por não membro entram nos testes das fases 6 e 7, junto com cada operação.

**Independent Test**: `VIEWER` consulta, não membro recebe 404, o banco sozinho recusa escrita sem papel, apagar e forjar `created_by`

### Tests for User Story 4

- [ ] T017 [P] [US4] `TEST/transaction/TransactionAccessTest.java`: não membro recebe 404 em `GET /transactions`, `GET /transactions/{id}`, `POST /transactions` (receita e transferência), com corpo igual ao de um UUID de workspace inexistente (ignorado o campo `instance`) e sem criar nada; `VIEWER` recebe 200 nas leituras e 403 em `POST` (receita e transferência) sem criar nada; `EDITOR` e `OWNER` criam (201); sem sessão recebe 401; lançamento de outro workspace pela rota do meu devolve 404; conta e categoria de outro workspace no corpo devolvem 404 e nada é criado; resposta autenticada leva `Cache-Control: no-store`; `createdBy` da resposta é o usuário da sessão (um `EDITOR` cria e a resposta traz o id dele, não o do dono)
- [ ] T018 [P] [US4] `TEST/transaction/TransactionRlsTest.java` (conectado como `tostao_app`, linhas semeadas pelo `OwnerJdbc`, como `CategoryRlsTest`): com contexto `VIEWER`, `insert` é recusado por "row-level security" e `update` afeta zero linhas; com `EDITOR` e `OWNER` ambos funcionam; sem contexto, `select` devolve zero linhas; contexto do workspace A não enxerga nem altera lançamentos de B; `insert` com `created_by` diferente do usuário do contexto é recusado por "row-level security"; `delete from transactions` é recusado por "permission denied" mesmo com `OWNER`; `update` de `created_by`, `type`, `workspace_id`, `transfer_group_id` e `created_at` é recusado por "permission denied"; `update` de `description`, `amount_cents`, `deleted_at` funciona para quem escreve; inserir lançamento com conta ou categoria de outro workspace falha pela chave estrangeira composta
- [ ] T019 [P] [US4] `TEST/transaction/TransactionSchemaTest.java` (invariantes do banco como dono): receita com categoria de despesa e despesa com categoria de receita falham pela `transactions_category_kind_fkey`; transferência com categoria falha pela restrição da V1; valor zero, receita negativa, despesa positiva e módulo acima de 1.000.000.000.000 falham; descrição com espaços nas pontas, vazia e com 201 caracteres, observação vazia e com 1.001 caracteres falham; data em `1999-12-31` e `2101-01-01` falham e `2000-01-01` e `2100-12-31` passam; um terceiro lançamento, ou uma segunda saída, ou uma segunda entrada no mesmo `transfer_group_id` falha pelo índice `transactions_transfer_leg_key`, enquanto uma saída e uma entrada passam
- [ ] T020 [P] [US4] `TEST/transaction/TransactionServiceFilterTest.java`: monta `new TransactionService(ownerJdbc.client())` (conexão do dono, RLS contornado) e prova que `get` e `list` filtram por `workspace_id` por conta própria: o lançamento de B pedido com o workspace A lança `TransactionNotFoundException`, e `list(A, ...)` nunca devolve lançamentos de B, nem com `accountId` ou `categoryId` de B no filtro (SC-007)

**Checkpoint**: papel, isolamento e garantias do banco provados; `./mvnw verify` verde

---

## Phase 6: User Story 5 - Corrigir um lançamento (Priority: P2)

**Goal**: editar lançamentos (e transferências como unidade); o tipo não muda

**Independent Test**: editar valor e categoria de uma despesa e ver saldo e consulta refletirem; editar uma ponta de transferência e ver as duas mudarem

### Tests for User Story 5

- [ ] T021 [P] [US5] `TEST/transaction/UpdateTransactionTest.java`: `PUT` com novo valor devolve 200 e o saldo da conta passa a refletir o novo no lugar do antigo; mudar a conta atualiza o saldo das duas contas; mudar data, descrição, observação e categoria (da mesma natureza) aparece na consulta e `updatedAt` muda e `createdAt` e `createdBy` não; `type` igual ao atual é aceito e diferente devolve 400 no campo `type`; `destinationAccountId` numa receita devolve 400; as mesmas validações do cadastro (valor, descrição, data, categoria obrigatória e do tipo certo) devolvem 400 com o campo e o lançamento não muda; `PUT` numa ponta de transferência (saída ou entrada) mudando valor, data, descrição, observação, origem e destino altera as duas pontas juntas e coerentes (conferido pelo `OwnerJdbc`: `-v`/`+v`, mesma data, descrição e observação, contas diferentes), e origem igual ao destino devolve 400; passar a usar conta ou categoria arquivada devolve 409 e nada muda; manter a conta ou a categoria arquivada que o lançamento já tinha é aceito (200); conta ou categoria de outro workspace devolve 404; lançamento excluído, de outro workspace, inexistente ou malformado devolve 404; campos extras (`createdBy`, `workspaceId`, `transferGroupId`, `deletedAt`) devolvem 400; `VIEWER` recebe 403, não membro 404 (corpo igual ao de workspace inexistente), `EDITOR` e `OWNER` editam, também o que outra pessoa criou, sem mudar `createdBy`, e sem sessão 401

### Implementation for User Story 5

- [ ] T022 [P] [US5] Criar `MAIN/transaction/dto/UpdateTransactionRequest.java` como `record` com as mesmas validações de `accountId`, `destinationAccountId`, `amountCents`, `occurredOn`, `description`, `notes` e `categoryId` do `CreateTransactionRequest` (T008), e `type` opcional (`TransactionType`); o construtor compacto aplica `strip`
- [ ] T023 [US5] Em `MAIN/transaction/TransactionService.java`, acrescentar `update(UUID workspaceId, UUID transactionId, UpdateTransactionRequest)` com `@WorkspaceScoped(write = true)` e `@Transactional`: ler a linha endereçada com `select ... from transactions where id = :id and workspace_id = :workspaceId and deleted_at is null for update` (`TransactionNotFoundException` se faltar); se for transferência, travar as duas pontas com `where transfer_group_id = :group and workspace_id = :workspaceId and deleted_at is null order by id for update` (se alguma sumiu, `TransactionNotFoundException`); `FieldValidationException("type", "não pode ser mudado")` se `type` vier diferente; as mesmas validações do cadastro por tipo; carregar com `for share` só as contas e a categoria que **mudaram** em relação ao que o lançamento já tinha (as iguais não são conferidas, então conta ou categoria arquivada mantida passa) e aplicar as mesmas regras de existência, arquivamento e tipo; atualizar com `set account_id, category_id, amount_cents, occurred_on, description, notes, updated_at = now()` (na transferência, as duas pontas na mesma transação: saída `-valor` na origem e entrada `+valor` no destino); devolver a ponta endereçada
- [ ] T024 [US5] Em `MAIN/transaction/TransactionController.java`, acrescentar `PUT /api/v1/workspaces/{wid}/transactions/{id}`. Acrescentar a `TEST/transaction/TransactionAccessTest.java` (T017) os casos de editar por papel e por não membro, se ainda não cobertos pelo T021

**Checkpoint**: US1 a US5 funcionam; `./mvnw verify` verde

---

## Phase 7: User Story 6 - Excluir um lançamento (Priority: P2)

**Goal**: exclusão lógica, com a transferência excluída por inteiro

**Independent Test**: excluir uma despesa e ver o saldo voltar; excluir uma ponta e ver as duas sumirem

### Tests for User Story 6

- [ ] T025 [P] [US6] `TEST/transaction/DeleteTransactionTest.java`: `DELETE` de uma despesa devolve 204, ela some da lista (e do total), a consulta direta, `PUT` e novo `DELETE` devolvem 404, o saldo da conta volta ao de antes, e a linha segue no banco com `deleted_at` e `updated_at` preenchidos e demais campos intactos (`SC-009`); `DELETE` de qualquer uma das pontas de uma transferência exclui as duas (as duas com `deleted_at`, mesmo instante) e os saldos das duas contas voltam ao de antes; excluir lançamento numa conta ou categoria arquivada funciona (204); lançamento de outro workspace, inexistente ou malformado devolve 404 e nada muda; `VIEWER` recebe 403, não membro 404 (corpo igual ao de workspace inexistente) e nada muda; `EDITOR` e `OWNER` excluem, inclusive o que outra pessoa criou; sem sessão 401; não existe rota de restauração nem de exclusão física (nenhum outro método ou caminho apaga a linha: `delete from transactions` pela role da aplicação já é recusado no T018)

### Implementation for User Story 6

- [ ] T026 [US6] Em `MAIN/transaction/TransactionService.java`, acrescentar `delete(UUID workspaceId, UUID transactionId)` com `@WorkspaceScoped(write = true)` e `@Transactional`: travar a linha (e, na transferência, as duas pontas, em ordem de id) com `for update` e `deleted_at is null` (`TransactionNotFoundException` se faltar, inclusive depois de esperar um bloqueio), e `update transactions set deleted_at = now(), updated_at = now() where ... and deleted_at is null` nas linhas travadas; nunca `DELETE`
- [ ] T027 [US6] Em `MAIN/transaction/TransactionController.java`, acrescentar `DELETE /api/v1/workspaces/{wid}/transactions/{id}` respondendo 204 sem corpo. Acrescentar ao `TransactionAccessTest` os casos de excluir por papel e por não membro, se ainda não cobertos pelo T025

**Checkpoint**: as seis user stories funcionam

---

## Phase 8: Concorrência, consistência e acabamento

- [ ] T028 [P] `TEST/transaction/TransactionConcurrencyTest.java` (threads + MockMvc, invariantes conferidas no banco depois de cada rodada): arquivar a conta enquanto se criam lançamentos nela, 20 rodadas, nunca deixa lançamento criado depois do arquivamento nem 500 (respostas 201 ou 409, e nenhum lançamento novo em conta arquivada); o mesmo com arquivar a categoria (e a principal, com lançamento numa subcategoria); `PUT` e `DELETE` simultâneos da mesma transferência, 20 rodadas, terminam com as duas pontas excluídas, ou com as duas vivas e coerentes (mesmo módulo, data, descrição e observação), nunca uma de cada, e nunca 500; dois `PUT` simultâneos da mesma transferência com valores diferentes terminam com as duas pontas iguais entre si; transferências opostas simultâneas (A→B e B→A) terminam sem deadlock
- [ ] T029 [P] `TEST/transaction/BalanceConsistencyTest.java` (SC-001 e SC-002): para 3 contas e uma sequência pseudoaleatória com semente fixa de 200 operações (criar receita, despesa e transferência, editar valor e conta, excluir), depois de cada operação o `balanceCents` de cada conta de `GET /accounts` é igual ao saldo inicial mais a soma de `amountCents` da listagem completa (`size=100`, todas as páginas) filtrada por aquela conta, o saldo total de `reports/balance` é igual à soma das contas, e a soma de todos os lançamentos de tipo `TRANSFER` listados é zero
- [ ] T030 [P] Em `TEST/OpenApiContractTest.java`, passar a conferir também `specs/004-lancamentos/contracts/openapi.yaml` contra o `/v3/api-docs`: novo teste `transactionsContractFileDeclaresTheExpectedRoutes` com os dois caminhos do contrato (`/api/v1/workspaces/{wid}/transactions` com `get` e `post`; `/api/v1/workspaces/{wid}/transactions/{id}` com `get`, `put` e `delete`), todas as operações do contrato existem no `/v3/api-docs`, e não existe rota `/transactions/{id}/restore` nem outro caminho com `delete`
- [ ] T031 [P] Acrescentar ao `README.md` exemplos de `curl` para registrar uma despesa, uma transferência, listar com filtros e excluir
- [ ] T032 Rodar `cd backend && ./mvnw verify` e garantir todos os testes verdes, os das specs 001 a 003 inclusive; anotar o tempo real das consultas do T014
- [ ] T033 Rodar o roteiro de `specs/004-lancamentos/quickstart.md` contra `docker compose up -d --build` (a V5 roda sobre o banco que já tem V1 a V4) e registrar a saída real de cada passo; limpar os dados de teste do banco local depois
- [ ] T034 [P] Marcar a `004-lancamentos` como concluída no checklist da nota `D:\Paulo\Projetos\Tostão\Tostão.md`

---

## Dependencies & Execution Order

- **Foundational (Phase 1)**: bloqueia tudo. T001 vem primeiro e só é commitada com `./mvnw verify` verde depois do T002. T002 a T006 são independentes entre si e podem ir juntos depois do T001 (o T002 é necessário para o verify ficar verde).
- **US1 (Phase 2)**: depende da Phase 1. T008 antes do T009; T009 antes do T010; o T007 (teste) pode ser escrito antes e só passa depois do T010.
- **US2 (Phase 3)**: depende da US1 (usa o `create` e o `load`).
- **US3 (Phase 4)**: depende da US1. T015 antes do T016; os testes T013 e T014 podem ser escritos antes.
- **US4 (Phase 5)**: T018 e T019 dependem só da Phase 1; T017 depende da US1 e da US2; T020 depende da US3. Pode andar em paralelo com a US5 e a US6.
- **US5 (Phase 6)** e **US6 (Phase 7)**: dependem da US1 e da US2 (precisam criar lançamentos pela API nos testes) e editam o mesmo `TransactionService` e `TransactionController`, então são sequenciais entre si (US5, depois US6).
- **Phase 8**: depende de todas as stories.

```text
Foundational ─→ US1 ─┬→ US2 ─┬→ US3 ──→ US4 (T017, T020)
                     │       └→ US5 → US6 → Fase 8
Foundational ────────┴→ US4 (T018, T019)
```

## Parallel Opportunities

- Foundational: T002 a T006 juntos depois do T001.
- US1: T007 (teste) e T008 juntos.
- US3: T013 e T014 (testes) juntos.
- US4: T017 a T020 juntos, respeitadas as dependências acima.
- Phase 8: T028 a T031 e T034 juntos.

## Implementation Strategy

- **MVP**: Phase 1 mais US1, US2 e US3: dá para registrar, transferir e conferir o extrato. O MVP seguro inclui a US4, porque os lançamentos são o dado mais sensível do sistema e o isolamento e o papel têm de estar provados antes de editar e excluir.
- **Entrega incremental**: Foundational verde → US1 → US2 → US3 → US4 → US5 → US6 → Phase 8.
- **Antes do `/speckit-implement`**: rodar `/speckit-analyze` para cruzar spec, plano e tarefas contra a constituição.

## Notes

- A V5 só pode ser editada enquanto não foi aplicada em nenhum banco compartilhado; depois disso, mudanças vão em `V6`. Hoje ela só existiria no banco local.
- A V5 estreita os privilégios da `tostao_app` em `transactions` (sem `DELETE`, `UPDATE` só nas colunas editáveis): qualquer código novo da aplicação que precise de outra coluna exige uma migration nova.
- O `RlsCoverageTest` da 001 continua valendo: `transactions` segue com RLS ligado.
- A regra "toda receita e despesa tem categoria" é do serviço; o banco continua aceitando `category_id` nulo nesses tipos para não quebrar as fixtures das specs anteriores e a importação da fase 3 (ver research, item 3).
- O saldo das contas (spec 002) não muda de código: a consulta já soma os lançamentos não excluídos.
