---

description: "Tarefas da feature 002-contas"
---

# Tasks: Contas

**Input**: documentos em `/specs/002-contas/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS. A constituição (princípios II, V e VIII) e o FR-018 exigem teste de acesso cruzado por operação, teste de saldo e integração com Postgres real.

**Organization**: tarefas agrupadas por user story. Só o backend entra nesta feature (as telas ficam no roadmap de front-end).

## Format: `[ID] [P?] [Story] Descrição com caminho`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência de tarefa incompleta)
- **[Story]**: US1 a US5, conforme a spec
- Abreviação de caminhos usada abaixo (nunca em código):
  `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `RES` = `backend/src/main/resources`
- **Commits**: um commit por tarefa ou por pequeno grupo coeso (poucos arquivos), em Conventional Commits, sem co-author. Antes de cada commit rodar os testes que a tarefa tocou; ao fim de cada fase rodar `./mvnw verify`.
- **Convenção do projeto**: todo `JdbcClient` da aplicação conecta como `tostao_app`. Os testes usam o bean `OwnerJdbc` (conexão do dono, ignora RLS) só para semear e inspecionar dados.

---

## Phase 1: Setup

**Purpose**: configuração compartilhada

- [X] T001 [P] Em `RES/application.properties`, acrescentar `spring.jackson.deserialization.accept-float-as-int=false`, para que um valor como `10.5` ou `1e3` em campo inteiro resulte em 400 em vez de ser truncado em silêncio (o teste que prova isso fica no T007)

---

## Phase 2: Foundational (bloqueia todas as user stories)

**Purpose**: migration V3, papel no aspecto, erros e tipos compartilhados

**⚠️ CRITICAL**: nenhuma user story começa antes deste checkpoint

- [X] T002 Criar `RES/db/migration/V3__accounts.sql` (a V1 e a V2 não são editadas) com: `ALTER TABLE accounts ADD CONSTRAINT accounts_card_fields_only_on_cards CHECK (type = 'CREDIT_CARD' OR (credit_limit_cents IS NULL AND closing_day IS NULL AND due_day IS NULL))`; `ADD CONSTRAINT accounts_amount_limits CHECK (abs(initial_balance_cents) <= 1000000000000 AND (credit_limit_cents IS NULL OR credit_limit_cents <= 1000000000000))`; `ADD CONSTRAINT accounts_name_trimmed CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 100)`; `CREATE UNIQUE INDEX accounts_active_name_key ON accounts (workspace_id, lower(name)) WHERE NOT archived`; função SQL estável `app_can_write(workspace UUID)` que devolve verdadeiro quando `app_user_id()` é `OWNER` ou `EDITOR` em `workspace_members` daquele workspace; `DROP POLICY accounts_tenant ON accounts` e novas políticas para `tostao_app`: `accounts_read` (SELECT, `workspace_id = app_workspace_id()`), `accounts_insert` (INSERT, `WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id))`) e `accounts_update` (UPDATE, a mesma condição em `USING` e em `WITH CHECK`), sem política de `DELETE`; `REVOKE DELETE ON accounts FROM tostao_app`. Rodar `./mvnw verify` e confirmar que os testes da 001 continuam verdes antes de commitar
- [X] T003 [P] Criar `TEST/support/WorkspaceFixtures.java` (componente de teste que usa `OwnerJdbc` e `SessionTokenService`): `newUser()`, `workspaceWithOwner()` devolvendo `Fixture(workspaceId, userId, token)`, `addMember(workspaceId, role)` devolvendo `Fixture` do novo membro com o papel `OWNER`, `EDITOR` ou `VIEWER`, `insertAccount(workspaceId, name, type, initialCents, archived)` devolvendo o id, e `insertTransaction(workspaceId, accountId, userId, amountCents, deleted)`; o token usa `SessionTokenService.issue(userId, 0)` e o `type` do lançamento é derivado do sinal (`INCOME` positivo, `EXPENSE` negativo)
- [X] T004 Depois do T005, em `MAIN/workspace/WorkspaceScoped.java` acrescentar `boolean write() default false`; em `MAIN/workspace/WorkspaceScopeAspect.java` trocar a checagem de existência por `select role from workspace_members where workspace_id = :workspaceId and user_id = :userId` e, depois de confirmada a participação (404 se não houver linha), lançar a `InsufficientRoleException` (criada no T005) quando `write = true` e o papel não for `OWNER` nem `EDITOR`; a anotação passa a ser lida no pontcut por `@annotation(scoped)`. Teste em `TEST/workspace/WorkspaceRoleAspectTest.java` com um serviço de teste anotado: `VIEWER` com `write = true` recebe `InsufficientRoleException`, `EDITOR` e `OWNER` passam, `VIEWER` com `write = false` passa, não membro recebe `WorkspaceNotFoundException` mesmo com `write = true`
- [X] T005 Criar em `MAIN/common/error/` as exceções `InsufficientRoleException` (403, "Seu papel neste workspace não permite esta operação"), `AccountNotFoundException` (404, "Conta não encontrada"), `AccountNameAlreadyExistsException` (409, "Já existe uma conta ativa com este nome"), `AccountArchivedException` (409, "A conta está arquivada; restaure-a antes de editar") e `FieldValidationException(String field, String message)` (400); em `MAIN/common/error/ApiExceptionHandler.java` mapeá-las, mapear `InsufficientRoleException` para 403 ("Sem permissão"), mapear o erro de tipo de um UUID malformado em variável de rota (`MethodArgumentTypeMismatchException`) para 404 "não encontrado", igual a um identificador inexistente (spec, casos de borda), e mapear `DuplicateKeyException` cuja mensagem cite `accounts_active_name_key` para 409 de nome repetido, e `FieldValidationException` para 400 com `errors[]` de um item. Este último mapeamento só roda depois do rollback, na borda web, e nada é capturado dentro de transação
- [X] T006 [P] Criar `MAIN/account/AccountType.java` (enum `CHECKING`, `SAVINGS`, `CASH`, `CREDIT_CARD`, `INVESTMENT`), `MAIN/account/MoneyLimits.java` (constante `MAX_CENTS = 1_000_000_000_000L`) e `MAIN/account/dto/AccountResponse.java` (record com `id`, `name`, `type`, `currency`, `initialBalanceCents`, `balanceCents`, `creditLimitCents`, `closingDay`, `dueDay`, `archived`, `createdAt`, `updatedAt`, em que `createdAt` e `updatedAt` são `Instant`)

**Checkpoint**: V3 aplicada, papel checado no aspecto, erros prontos; `./mvnw verify` verde

---

## Phase 3: User Story 1 - Cadastrar e listar as minhas contas (Priority: P1) 🎯 MVP

**Goal**: criar contas dos cinco tipos e listar as ativas, com o saldo (inicial) de cada uma

**Independent Test**: `POST /api/v1/workspaces/{wid}/accounts` devolve 201 com a conta, e `GET` a lista

### Tests for User Story 1

- [ ] T007 [P] [US1] `TEST/account/CreateAccountTest.java` (Testcontainers + MockMvc, `WorkspaceFixtures`): cria uma conta de cada tipo com 201 e `currency` `BRL`, `archived` `false`, `balanceCents` igual ao `initialBalanceCents`, `createdAt` e `updatedAt` presentes; cartão com `creditLimitCents`, `closingDay` e `dueDay` guarda os três; cartão sem os três campos é aceito; limite, `closingDay` ou `dueDay` em conta corrente devolvem 400 com `errors[].field` apontando o campo; nome vazio, só espaços, com 101 caracteres, tipo desconhecido, `initialBalanceCents` acima de 1.000.000.000.000 e abaixo de -1.000.000.000.000, `creditLimitCents` negativo, dia 0 e dia 32 devolvem 400; `initialBalanceCents` negativo é aceito; `initialBalanceCents` `10.5` devolve 400 (prova o T001); campos extras `workspaceId`, `archived`, `balanceCents`, `createdAt` e `currency` devolvem 400; nome com espaços nas pontas é gravado sem eles; o nome `Nubank` e depois `nubank ` devolvem 409; a lista vazia devolve `[]`; a lista traz as contas ordenadas por nome sem diferenciar maiúsculas; duas criações simultâneas do mesmo nome resultam em um 201 e um 409, nunca 500; nenhuma resposta contém o `workspace_id`

### Implementation for User Story 1

- [ ] T008 [P] [US1] Criar `MAIN/account/dto/CreateAccountRequest.java` como `record` com Bean Validation: `name` obrigatório, 1 a 100 caracteres após `strip`; `type` obrigatório (`AccountType`); `initialBalanceCents` `Long` obrigatório de -1.000.000.000.000 a 1.000.000.000.000; `creditLimitCents` `Long` opcional de 0 a 1.000.000.000.000; `closingDay` e `dueDay` `Integer` opcionais de 1 a 31; mensagens em português; construtor compacto que aplica `strip` no nome
- [ ] T009 [US1] Criar `MAIN/account/AccountService.java`, cujo único argumento de construtor é o `JdbcClient` (o T018 monta o serviço assim com a conexão do dono), com `create(UUID workspaceId, CreateAccountRequest)` anotado `@WorkspaceScoped(write = true)` e `@Transactional`: se o tipo não for `CREDIT_CARD` e houver limite, `closingDay` ou `dueDay`, lançar `FieldValidationException` com o nome do campo e a mensagem "só vale para cartão de crédito"; inserir com `INSERT ... ON CONFLICT (workspace_id, lower(name)) WHERE NOT archived DO NOTHING RETURNING id`, resultado vazio vira `AccountNameAlreadyExistsException`; devolver a conta carregada. E `list(UUID workspaceId, boolean includeArchived)` com `@WorkspaceScoped` e `@Transactional(readOnly = true)`, que usa a consulta única de contas com saldo (`a.initial_balance_cents + coalesce(sum(t.amount_cents), 0)` com `left join transactions t on t.account_id = a.id and t.workspace_id = a.workspace_id and t.deleted_at is null`, filtrada por `a.workspace_id`, agrupada por conta, ordenada por `lower(a.name), a.id`, convertendo a soma para `bigint`); toda consulta com `workspace_id` explícito e parâmetros ligados
- [ ] T010 [US1] Criar `MAIN/account/AccountController.java` com `POST /api/v1/workspaces/{wid}/accounts` (201, cabeçalho `Location`, corpo `AccountResponse`) e `GET /api/v1/workspaces/{wid}/accounts?includeArchived=false`; o `wid` entra como `UUID` primeiro parâmetro do serviço

**Checkpoint**: US1 funciona e é testável sozinha

---

## Phase 4: User Story 2 - Ver o saldo de cada conta e o saldo total (Priority: P1)

**Goal**: saldo calculado com lançamentos, consulta de uma conta e o relatório consolidado com total

**Independent Test**: com lançamentos semeados no banco, o saldo da conta e o total mudam exatamente pela soma deles

### Tests for User Story 2

- [ ] T011 [P] [US2] `TEST/account/ListAndBalanceTest.java`: conta de R$ 1.000,00 sem lançamentos tem `balanceCents` 100000; com um lançamento de +25000 e um de -10000 passa a 115000; lançamento com `deleted_at` preenchido não entra; lançamento de outra conta do mesmo workspace não entra; transferência afeta cada conta pelo valor da própria linha; 10 centavos mais 20 centavos somam exatamente 30; `GET /accounts/{id}` devolve o mesmo saldo da lista; conta de outro workspace pelo id na rota do meu devolve 404; identificador de conta que não é UUID (`/accounts/abc`) devolve 404, nunca 400 nem 500
- [ ] T012 [P] [US2] `TEST/report/BalanceReportTest.java`: `GET /api/v1/workspaces/{wid}/reports/balance` devolve só as contas ativas, cada uma com `id`, `name`, `type` e `balanceCents`, e `totalBalanceCents` igual à soma dos saldos listados; cartão com saldo negativo reduz o total; conta arquivada (semeada pelo banco) não entra nem altera o total; workspace sem contas devolve lista vazia e total 0; o total é igual à soma de `balanceCents` do `GET /accounts` (consistência); não membro recebe 404; sem sessão recebe 401
- [ ] T013 [P] [US2] `TEST/account/BalancePerformanceTest.java`: semeia 50 contas e 20.000 lançamentos com `generate_series` pelo `OwnerJdbc`, confere que `GET /accounts` e `GET /reports/balance` respondem em menos de 1 segundo (mediana de 5 chamadas depois de uma de aquecimento; a margem de 1 s é a da spec, com folga grande sobre o tempo real esperado) e que o total bate com a soma esperada calculada pelo próprio teste

### Implementation for User Story 2

- [ ] T014 [US2] Em `MAIN/account/AccountService.java`, acrescentar `get(UUID workspaceId, UUID accountId)` (`@WorkspaceScoped`, `@Transactional(readOnly = true)`, `AccountNotFoundException` se não houver linha com `id` e `workspace_id` casando) e `balances(UUID workspaceId)`, que roda a consulta de saldo só das contas ativas e soma o total em memória a partir das mesmas linhas
- [ ] T015 [US2] Em `MAIN/account/AccountController.java`, acrescentar `GET /api/v1/workspaces/{wid}/accounts/{id}` e criar `MAIN/report/BalanceReportResponse.java` (lista de `{id, name, type, balanceCents}` e `totalBalanceCents`) e `MAIN/report/BalanceReportController.java` com `GET /api/v1/workspaces/{wid}/reports/balance`

**Checkpoint**: saldo e total corretos e consistentes; `./mvnw verify` verde

---

## Phase 5: User Story 3 - Só quem pode altera, e só no seu workspace (Priority: P1)

**Goal**: papel e isolamento provados nas duas camadas para as operações já existentes (criar, listar, consultar, relatório). Os casos de editar, arquivar e restaurar entram nos testes das fases 6 e 7, junto com cada operação.

**Independent Test**: `VIEWER` consulta mas não cria, não membro recebe 404, e o banco sozinho recusa escrita sem papel

### Tests for User Story 3

- [ ] T016 [P] [US3] `TEST/account/AccountAccessTest.java`: não membro recebe 404 em `GET /accounts`, `POST /accounts`, `GET /accounts/{id}` e `GET /reports/balance`, com corpo igual ao de um UUID de workspace inexistente (ignorado o campo `instance`) e sem criar nada; `VIEWER` recebe 200 em `GET /accounts`, `GET /accounts/{id}` e `GET /reports/balance`, e 403 em `POST /accounts` sem criar nada (conferido pelo `OwnerJdbc`); `EDITOR` e `OWNER` recebem 201 em `POST`; sem sessão recebe 401 em todas; `GET /accounts` e `POST /accounts` respondem com `Cache-Control` contendo `no-store`; o 403 do `VIEWER` tem corpo diferente do 404 do não membro
- [ ] T017 [P] [US3] `TEST/account/AccountRlsTest.java` (conectado como `tostao_app`, linhas semeadas pelo `OwnerJdbc`): com contexto de usuário `VIEWER` e workspace, `insert` e `update` em `accounts` são recusados por "row-level security", e com `EDITOR` e `OWNER` funcionam; sem contexto, `select` em `accounts` devolve 0 linhas; contexto do workspace A não enxerga nem altera contas de B; `delete from accounts` é negado por "permission denied" para qualquer papel; as restrições do V3 recusam, mesmo escrevendo como o dono, limite de crédito em conta corrente (`accounts_card_fields_only_on_cards`), saldo inicial acima de 1.000.000.000.000 (`accounts_amount_limits`), nome com espaços nas pontas ou de 101 caracteres (`accounts_name_trimmed`) e dois nomes iguais ativos no mesmo workspace sem diferenciar maiúsculas (`accounts_active_name_key`), mas aceitam o mesmo nome em workspaces diferentes e quando uma das contas está arquivada
- [ ] T018 [P] [US3] `TEST/account/AccountServiceFilterTest.java`: monta `new AccountService(ownerJdbc.client())` (conexão do dono, RLS contornado) e prova que `get` e `list` filtram por `workspace_id` por conta própria: a conta de B pedida com o workspace A lança `AccountNotFoundException`, e `list(A)` nunca devolve contas de B (SC-006)

**Checkpoint**: papel e isolamento provados; `./mvnw verify` verde

---

## Phase 6: User Story 4 - Corrigir uma conta (Priority: P2)

**Goal**: editar nome, saldo inicial e dados do cartão; o tipo não muda

**Independent Test**: editar nome e saldo inicial e ver a lista e o saldo refletirem

### Tests for User Story 4

- [ ] T019 [P] [US4] `TEST/account/UpdateAccountTest.java`: `PUT` com novo nome devolve 200 e a lista mostra o nome novo; trocar `initialBalanceCents` de uma conta com lançamentos muda o saldo pelo mesmo delta; mudar para cartão limite, `closingDay` e `dueDay` funciona em cartão; `type` igual ao atual é aceito, `type` diferente devolve 400 com `errors[].field` `type`; as mesmas validações do cadastro valem (nome, limites de valor, dias, campos de cartão em conta que não é cartão, float, campos extras `archived` e `balanceCents`); nome já usado por outra conta ativa devolve 409 e a conta não muda; renomear para o próprio nome com outras maiúsculas é aceito; conta arquivada devolve 409 e não muda; `updatedAt` avança e `createdAt` não muda; dois `PUT` simultâneos que renomeiam contas diferentes para o mesmo nome resultam em um 200 e um 409, nunca 500; não membro recebe 404; `VIEWER` recebe 403 sem alterar nada; `EDITOR` e `OWNER` recebem 200; conta de outro workspace pelo id na rota do meu recebe 404 e não muda

### Implementation for User Story 4

- [ ] T020 [P] [US4] Criar `MAIN/account/dto/UpdateAccountRequest.java` como `record` com as mesmas validações do `CreateAccountRequest` (T008) para `name`, `initialBalanceCents`, `creditLimitCents`, `closingDay` e `dueDay`, e `type` opcional (`AccountType`); o construtor compacto aplica `strip` no nome
- [ ] T021 [US4] Em `MAIN/account/AccountService.java`, acrescentar `update(UUID workspaceId, UUID accountId, UpdateAccountRequest)` com `@WorkspaceScoped(write = true)` e `@Transactional`: carrega a conta com `where id = :id and workspace_id = :workspaceId` (`AccountNotFoundException` se faltar), `AccountArchivedException` se estiver arquivada, `FieldValidationException("type", "não pode ser mudado")` se `type` vier diferente, mesma regra de campos de cartão do cadastro, checagem prévia de nome em uso por outra conta ativa (`AccountNameAlreadyExistsException`), e `update accounts set ..., updated_at = now() where id = :id and workspace_id = :workspaceId`
- [ ] T022 [US4] Em `MAIN/account/AccountController.java`, acrescentar `PUT /api/v1/workspaces/{wid}/accounts/{id}`

**Checkpoint**: US1 a US4 funcionam

---

## Phase 7: User Story 5 - Arquivar e restaurar contas (Priority: P2)

**Goal**: arquivar em vez de apagar, ver as arquivadas e restaurar

**Independent Test**: arquivar sai da lista padrão e do total, e restaurar traz de volta

### Tests for User Story 5

- [ ] T023 [P] [US5] `TEST/account/ArchiveRestoreTest.java`: `POST .../archive` devolve 200 com `archived` `true`, a conta some de `GET /accounts` e do `reports/balance`, aparece em `GET /accounts?includeArchived=true` marcada como arquivada e mantém os lançamentos (conferido pelo `OwnerJdbc`); arquivar de novo devolve 200 sem mudar `updatedAt`; `POST .../restore` devolve 200 com `archived` `false` e a conta volta à lista e ao total; restaurar uma conta ativa devolve 200 sem efeito; restaurar com o nome já em uso por outra ativa devolve 409 e a conta continua arquivada; não existe rota `DELETE` (devolve 405 ou 404, e a conta continua no banco); conta com saldo diferente de zero pode ser arquivada e seu saldo sai do total; não membro recebe 404; `VIEWER` recebe 403 em `archive` e em `restore` sem alterar nada; `EDITOR` e `OWNER` recebem 200; conta de outro workspace pelo id na rota do meu recebe 404 e não muda

### Implementation for User Story 5

- [ ] T024 [US5] Em `MAIN/account/AccountService.java`, acrescentar `archive(UUID workspaceId, UUID accountId)` e `restore(UUID workspaceId, UUID accountId)` com `@WorkspaceScoped(write = true)` e `@Transactional`: ambos idempotentes: só escrevem quando o estado muda (`update accounts set archived = :novo, updated_at = now() where id = :id and workspace_id = :workspaceId and archived <> :novo`), então repetir a ação devolve a conta sem tocar em `updated_at`; `restore` faz a checagem prévia de nome em uso por outra conta ativa (`AccountNameAlreadyExistsException`); as escritas filtram por `id` e `workspace_id` e atualizam `updated_at = now()`
- [ ] T025 [US5] Em `MAIN/account/AccountController.java`, acrescentar `POST /api/v1/workspaces/{wid}/accounts/{id}/archive` e `POST .../restore`, sem nenhum mapeamento `DELETE`

**Checkpoint**: as cinco user stories funcionam

---

## Phase 8: Polish & Cross-Cutting

- [ ] T026 [P] Em `TEST/OpenApiContractTest.java`, passar a conferir também `specs/002-contas/contracts/openapi.yaml` contra o `/v3/api-docs` (todas as operações do contrato existem) e acrescentar a asserção de que o caminho `/api/v1/workspaces/{wid}/accounts/{id}` não declara o método `delete`
- [ ] T027 [P] Acrescentar ao `README.md` um exemplo de `curl` para criar uma conta e consultar `reports/balance`
- [ ] T028 Rodar `cd backend && ./mvnw verify` e garantir todos os testes verdes, os da 001 inclusive
- [ ] T029 Rodar o roteiro de `specs/002-contas/quickstart.md` contra `docker compose up -d --build` (a V3 roda sobre o banco que já tem V1 e V2) e registrar a saída real de cada passo; limpar os dados de teste do banco local depois
- [ ] T030 [P] Marcar a `002-contas` como concluída no checklist da nota `D:\Paulo\Projetos\Tostão\Tostão.md`

---

## Dependencies & Execution Order

- **Setup (Phase 1)**: sem dependências.
- **Foundational (Phase 2)**: depende do Setup e bloqueia tudo. T002 vem primeiro e só é commitada com `./mvnw verify` verde. T003, T005 e T006 são independentes entre si e podem ir juntos depois do T002. T004 depende do T002 (o teste de papel precisa das tabelas), do T003 e do T005 (que cria a `InsufficientRoleException`).
- **US1 (Phase 3)**: depende da Phase 2. T008 antes do T009; T009 antes do T010; o T007 (teste) pode ser escrito antes e só passa depois do T010.
- **US2 (Phase 4)**: depende da US1 (usa `list` e a consulta de saldo do T009). T014 e T015 dependem do T009 e do T010.
- **US3 (Phase 5)**: depende da US1 e da US2 (testa as rotas que já existem). Pode andar em paralelo com a US4 e a US5.
- **US4 (Phase 6)**: depende da US1; T021 e T024 editam o mesmo `AccountService`, então a US4 e a US5 são sequenciais entre si no serviço.
- **US5 (Phase 7)**: depende da US1.
- **Polish (Phase 8)**: depende de todas as stories.

```text
Setup → Foundational → US1 ─┬→ US2 ──┐
                            ├→ US3 ──┤
                            ├→ US4 ──┼→ US5 → Polish
                            └────────┘
```

## Parallel Opportunities

- Foundational: T003, T005 e T006 juntos depois do T002; o T004 espera o T005.
- US1: T007 (teste) e T008 juntos.
- US2: T011, T012 e T013 (testes) juntos.
- US3: T016, T017 e T018 juntos.
- US4: T019 e T020 juntos; a US3 pode correr ao lado da US4 e da US5.

## Implementation Strategy

- **MVP**: Phases 1 e 2 mais US1 e US2: dá para cadastrar contas e ver os saldos. O MVP completo da spec inclui a US3, porque o isolamento e o papel são o que torna o módulo seguro para os módulos 003 a 005.
- **Entrega incremental**: Foundational verde → US1 → US2 → US3 (validar papel e isolamento antes de qualquer lançamento existir) → US4 → US5 → Polish.
- **Antes do `/speckit-implement`**: rodar `/speckit-analyze` para cruzar spec, plano e tarefas contra a constituição.

## Notes

- A V3 só pode ser editada enquanto não foi aplicada em nenhum banco compartilhado; depois disso, mudanças vão em `V4`. Hoje ela só existiria no banco local.
- O `RlsCoverageTest` da 001 continua valendo: `accounts` segue com RLS ligado.
- Os testes de papel semeiam as associações `VIEWER` e `EDITOR` pelo `OwnerJdbc`, porque nenhuma rota atribui papéis antes da fase 3 do roadmap.
- Ordem de execução da Phase 2: T001, T002, depois T003, T005 e T006, e por último o T004.
