---

description: "Tarefas da feature 003-categorias"
---

# Tasks: Categorias

**Input**: documentos em `/specs/003-categorias/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS. A constituição (princípios II, V e VIII) e o FR-019 exigem teste de acesso cruzado por operação e integração com Postgres real.

**Organization**: tarefas agrupadas por user story. Só o backend entra nesta feature (as telas ficam no roadmap de front-end). Não há fase de Setup: nenhuma dependência nem configuração nova.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência de tarefa incompleta)
- **[Story]**: US1 a US6, conforme a spec
- Abreviação de caminhos usada abaixo (nunca em código):
  `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `RES` = `backend/src/main/resources`
- **Commits**: um commit por tarefa ou por pequeno grupo coeso (poucos arquivos), em Conventional Commits, sem co-author. Antes de cada commit rodar os testes que a tarefa tocou; ao fim de cada fase rodar `./mvnw verify`.
- **Convenção do projeto**: todo `JdbcClient` da aplicação conecta como `tostao_app`. Os testes usam o bean `OwnerJdbc` (conexão do dono, ignora RLS) só para semear e inspecionar dados. `WorkspaceFixtures.workspaceWithOwner()` cria o workspace direto no banco, **sem** o conjunto padrão; só o cadastro pela API (`register_user`) semeia as categorias padrão.

---

## Phase 1: Foundational (bloqueia todas as user stories)

**Purpose**: migration V4, erros, tipos compartilhados e fixtures

**⚠️ CRITICAL**: nenhuma user story começa antes deste checkpoint

- [ ] T001 Criar `RES/db/migration/V4__categories.sql` (a V1, a V2 e a V3 não são editadas), nesta ordem: (1) `ALTER TABLE categories ADD CONSTRAINT categories_name_trimmed CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 60)`, `categories_color_format CHECK (color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$')` e `categories_icon_length CHECK (icon IS NULL OR char_length(icon) BETWEEN 1 AND 40)`; (2) `ALTER TABLE categories ADD UNIQUE (id, workspace_id, kind)` e `ADD FOREIGN KEY (parent_id, workspace_id, kind) REFERENCES categories (id, workspace_id, kind)`; (3) função plpgsql `categories_two_levels()` e gatilho `categories_two_levels BEFORE INSERT OR UPDATE OF parent_id ON categories FOR EACH ROW` que levanta exceção (`check_violation`) quando o `NEW.parent_id` aponta para uma categoria que já tem `parent_id`, ou quando `NEW.parent_id` não é nulo e já existe categoria com `parent_id = NEW.id`; (4) `CREATE UNIQUE INDEX categories_active_root_name_key ON categories (workspace_id, kind, lower(name)) WHERE NOT archived AND parent_id IS NULL` e `categories_active_child_name_key ON categories (workspace_id, parent_id, lower(name)) WHERE NOT archived AND parent_id IS NOT NULL`; (5) `DROP POLICY categories_tenant ON categories` e políticas para `tostao_app`: `categories_read` (SELECT, `workspace_id = app_workspace_id()`), `categories_insert` (INSERT, `WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id))`) e `categories_update` (UPDATE, a mesma condição em `USING` e em `WITH CHECK`), sem política de `DELETE`, e `REVOKE DELETE ON categories FROM tostao_app` (a função `app_can_write` vem da V3); (6) função `seed_default_categories(p_workspace UUID)` com `INSERT ... ON CONFLICT DO NOTHING` das 10 principais e 10 subcategorias do [data-model.md](data-model.md) (despesas Alimentação, Moradia, Transporte, Saúde, Lazer, Educação e Outros; receitas Salário, Rendimentos e Outros; subcategorias Mercado e Restaurantes, Aluguel e Contas de casa, Combustível e Transporte público, Farmácia e Consultas, Viagens e Assinaturas), seguida de `REVOKE ALL ON FUNCTION seed_default_categories(UUID) FROM PUBLIC` e nenhum `GRANT` à `tostao_app`; (7) `CREATE OR REPLACE FUNCTION register_user(p_email TEXT, p_password_hash TEXT, p_name TEXT)` com a **mesma assinatura, retorno `TABLE (new_user_id UUID, new_workspace_id UUID)`, `LANGUAGE plpgsql SECURITY DEFINER` e `SET search_path = public, pg_temp`** do corpo da `V2` (e-mail repetido continua devolvendo zero linhas, sem exceção) mais `PERFORM seed_default_categories(v_workspace)` logo depois de criar a associação `OWNER`; (8) `SELECT seed_default_categories(w.id) FROM workspaces w WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.workspace_id = w.id)`. Rodar `./mvnw verify` e confirmar que os testes da 001 e da 002 continuam verdes (o `RlsPolicyTest` semeia `categories` pelo dono e segue válido) antes de commitar
- [ ] T002 [P] Criar em `MAIN/common/error/` as exceções `CategoryNotFoundException` (404, "Categoria não encontrada"), `CategoryNameAlreadyExistsException` (409, "Já existe uma categoria ativa com este nome neste nível") e `CategoryConflictException(String message)` (409, usada para categoria arquivada, pai arquivado e restauração bloqueada); em `MAIN/common/error/ApiExceptionHandler.java` mapeá-las (404 "Não encontrado"; 409 "Nome de categoria já em uso"; 409 "Conflito de estado") e estender o `@ExceptionHandler(DuplicateKeyException.class)` para também traduzir em 409 de nome repetido a violação de `categories_active_root_name_key` ou `categories_active_child_name_key`, mantendo o tratamento de `accounts_active_name_key`. Este mapeamento só roda depois do rollback, na borda web, e nada é capturado dentro de transação
- [ ] T003 [P] Criar `MAIN/category/CategoryKind.java` (enum `INCOME`, `EXPENSE`) e `MAIN/category/dto/CategoryResponse.java` (record com `id`, `name`, `kind`, `parentId`, `color`, `icon`, `archived`, `createdAt` como `Instant`) e `MAIN/category/dto/CategoryTreeResponse.java` (record com os mesmos campos de `CategoryResponse` mais `List<CategoryResponse> subcategories`)
- [ ] T004 [P] Em `TEST/support/WorkspaceFixtures.java`, acrescentar `insertCategory(UUID workspaceId, UUID parentId, String name, String kind, boolean archived)` devolvendo o id (insere pelo `OwnerJdbc`; `parentId` nulo cria principal) e `seedDefaults(UUID workspaceId)`, que chama `select seed_default_categories(:w)` pelo dono

**Checkpoint**: V4 aplicada, erros e tipos prontos; `./mvnw verify` verde

---

## Phase 2: User Story 1 - Já começar com categorias prontas (Priority: P1) 🎯 MVP

**Goal**: o cadastro entrega o workspace com o conjunto padrão, de forma atômica, e os workspaces antigos recebem o conjunto uma vez

**Independent Test**: cadastrar pessoas pela API e contar as categorias pelo `OwnerJdbc`; rodar a V4 sobre um banco já na V3

### Tests for User Story 1

- [ ] T005 [P] [US1] `TEST/category/DefaultCategoriesTest.java` (Testcontainers + MockMvc, conta pelo `OwnerJdbc`): `POST /api/v1/auth/register` cria um workspace com exatamente 10 principais (7 `EXPENSE` e 3 `INCOME`) e 10 subcategorias, com os nomes do [data-model.md](data-model.md), todas ativas, e cada subcategoria com o `kind` do pai; dois cadastros têm conjuntos independentes (arquivar "Lazer" de um pelo banco não muda o outro); cadastrar de novo o mesmo e-mail devolve 409 e não cria usuário, workspace nem categorias (contagens iguais antes e depois); o conjunto não é recriado depois de a pessoa arquivar uma categoria (nova consulta, mesma contagem de ativas menos a arquivada); a role `tostao_app` não consegue executar `select seed_default_categories(...)` ("permission denied"); 4 cadastros simultâneos (threads) resultam em 4 workspaces com 20 categorias cada, sem mistura entre eles
- [ ] T006 [P] [US1] `TEST/category/DefaultCategoriesAtomicTest.java`: numa conexão do dono com `autoCommit` desligado, cria um gatilho temporário `BEFORE INSERT ON categories` que levanta exceção, abre um `SAVEPOINT`, chama `select * from register_user('atomico@example.com', 'x', 'Atomico')`, espera a exceção, faz `ROLLBACK TO SAVEPOINT` e confere que não existe usuário com esse e-mail nem workspace novo; no fim `rollback` da conexão inteira (o DDL do gatilho também é desfeito)
- [ ] T007 [P] [US1] `TEST/category/CategoriesMigrationTest.java` (contêiner Postgres próprio, não o do contexto Spring; Flyway com `placeholders.app_password` como em `TestcontainersConfiguration`): migra até `target("3")`, insere dois workspaces (um sem categorias e outro já com a categoria "Minha" inserida direto), migra para a V4 e confere que o primeiro recebeu as 20 categorias do conjunto, o segundo continua só com "Minha" (sem duplicar e sem receber o conjunto), e que migrar de novo não muda as contagens (a V4 só roda uma vez)

**Checkpoint**: US1 funciona e é testável sozinha

---

## Phase 3: User Story 2 - Ver as categorias em árvore (Priority: P1)

**Goal**: listar as categorias em dois níveis, filtrar por tipo, incluir arquivadas e consultar uma categoria

**Independent Test**: com o conjunto padrão semeado, listar tudo, só despesas e só receitas, e conferir ordem e agrupamento

### Tests for User Story 2

- [ ] T008 [P] [US2] `TEST/category/ListCategoriesTest.java`: com `seedDefaults`, `GET /api/v1/workspaces/{wid}/categories` devolve 10 principais, cada uma com `subcategories` (Alimentação com 2, Educação com `[]`), principais ordenadas por nome sem diferenciar maiúsculas e subcategorias na mesma ordem (semear "banana" e "Zebra" prova a ordem sem depender da collation do banco para letras acentuadas); `kind=INCOME` devolve só as 3 de receita; `kind=EXPENSE` só as 7 de despesa; `kind=XYZ` devolve 400 com `errors[].field` `kind`; arquivadas (semeadas pelo banco) ficam de fora por padrão e aparecem com `includeArchived=true` com `archived` `true`; `GET /categories/{id}` de uma subcategoria traz `parentId` e o `kind` do pai, e de uma principal traz `parentId` nulo; id de categoria de outro workspace devolve 404; identificador que não é UUID (`/categories/abc`) devolve 404, nunca 400 nem 500; workspace sem categorias devolve `[]`; a resposta não contém `workspace_id`
- [ ] T009 [P] [US2] `TEST/category/CategoryPerformanceTest.java`: semeia 200 categorias (20 principais e 180 subcategorias, com `generate_series` pelo `OwnerJdbc`) e confere que `GET /categories` responde em menos de 1 segundo (mediana de 5 chamadas depois de uma de aquecimento) e traz as 200

### Implementation for User Story 2

- [ ] T010 [US2] Criar `MAIN/category/CategoryService.java`, cujo único argumento de construtor é o `JdbcClient` (o T015 monta o serviço assim com a conexão do dono), com `list(UUID workspaceId, String kind, boolean includeArchived)` anotado `@WorkspaceScoped` e `@Transactional(readOnly = true)`: `kind` nulo ou `INCOME`/`EXPENSE`, qualquer outro valor lança `FieldValidationException("kind", "deve ser INCOME ou EXPENSE")`; uma consulta `select id, parent_id, name, kind, color, icon, archived, created_at from categories where workspace_id = :workspaceId` (mais `and kind = :kind` e `and not archived` quando couber) ordenada por `lower(name), id`; a árvore é montada em memória, só as principais na raiz, cada uma com suas subcategorias na ordem da consulta. E `get(UUID workspaceId, UUID categoryId)` com `@WorkspaceScoped` e `@Transactional(readOnly = true)`, que lança `CategoryNotFoundException` se não houver linha com `id` e `workspace_id` casando. Toda consulta com `workspace_id` explícito e parâmetros ligados
- [ ] T011 [US2] Criar `MAIN/category/CategoryController.java` com `GET /api/v1/workspaces/{wid}/categories?kind=&includeArchived=false` (o `kind` entra como `String` opcional, validado no serviço) e `GET /api/v1/workspaces/{wid}/categories/{id}`; o `wid` entra como `UUID` primeiro parâmetro do serviço

**Checkpoint**: US1 e US2 funcionam; `./mvnw verify` verde

---

## Phase 4: User Story 3 - Só quem pode altera, e só no seu workspace (Priority: P1)

**Goal**: papel e isolamento provados nas duas camadas para as operações de leitura já existentes. Os casos de criar, editar, arquivar e restaurar entram nos testes das fases 5 a 7, junto com cada operação.

**Independent Test**: `VIEWER` consulta, não membro recebe 404, e o banco sozinho recusa escrita sem papel e sem DELETE

### Tests for User Story 3

- [ ] T012 [P] [US3] `TEST/category/CategoryAccessTest.java`: não membro recebe 404 em `GET /categories` e `GET /categories/{id}`, com corpo igual ao de um UUID de workspace inexistente (ignorado o campo `instance`); `VIEWER` e `EDITOR` recebem 200 nas duas leituras; categoria de B pedida pela rota de A devolve 404; sem sessão recebe 401; resposta autenticada leva `Cache-Control: no-store`. (Criar, editar, arquivar e restaurar por papel e por não membro são cobertos em T020, T021 e T025)
- [ ] T013 [P] [US3] `TEST/category/CategoryRlsTest.java` (conectado como `tostao_app`, linhas semeadas pelo `OwnerJdbc`, como `AccountRlsTest`): com contexto `VIEWER`, `insert` e `update` em `categories` são recusados por "row-level security", e com `EDITOR` e `OWNER` funcionam; sem contexto, `select` devolve 0 linhas; contexto do workspace A não enxerga nem altera categorias de B; `delete from categories` é recusado por "permission denied" mesmo com `OWNER`; inserir subcategoria apontando para pai de outro workspace falha pela chave estrangeira composta
- [ ] T014 [P] [US3] `TEST/category/CategoryHierarchyDbTest.java` (invariantes do banco, como dono): inserir subcategoria com `kind` diferente do pai falha; inserir categoria sob uma subcategoria (terceiro nível) falha pelo gatilho `categories_two_levels`; atualizar uma principal que tem filhas para ganhar um pai falha; atualizar o `kind` de uma principal que tem filhas falha pela chave composta; nome com espaços nas pontas, nome com 61 caracteres, cor `vermelho` e ícone com 41 caracteres falham pelas restrições `CHECK`; dois nomes iguais entre irmãs ativas ("Mercado" e "mercado") falham pelos índices únicos e o mesmo nome sob outro pai ou em outro tipo é aceito; o mesmo nome com uma das duas arquivada é aceito
- [ ] T015 [P] [US3] `TEST/category/CategoryServiceFilterTest.java`: monta `new CategoryService(ownerJdbc.client())` (conexão do dono, RLS contornado) e prova que `get` e `list` filtram por `workspace_id` por conta própria: a categoria de B pedida com o workspace A lança `CategoryNotFoundException`, e `list(A, ...)` nunca devolve categorias de B (SC-006)

**Checkpoint**: papel e isolamento provados; `./mvnw verify` verde

---

## Phase 5: User Story 4 - Criar minhas categorias e subcategorias (Priority: P2)

**Goal**: criar principais (com tipo) e subcategorias (com pai) respeitando a hierarquia

**Independent Test**: criar uma principal e uma subcategoria sob ela, conferir tipo herdado e as recusas

### Tests for User Story 4

- [ ] T016 [P] [US4] `TEST/category/CreateCategoryTest.java` (`WorkspaceFixtures`; papéis semeados pelo banco): principal `{"name":"Pets","kind":"EXPENSE"}` devolve 201 com `Location`, `parentId` nulo, `archived` `false`, `createdAt` presente e aparece em `GET /categories`; subcategoria `{"name":"Ração","parentId":"<Pets>"}` devolve 201 com `kind` `EXPENSE` herdado e aparece em `subcategories`; subcategoria com `kind` igual ao do pai é aceita e com `kind` diferente devolve 400 com `errors[].field` `kind`; principal sem `kind` devolve 400 no campo `kind`; `parentId` de uma subcategoria devolve 400 no campo `parentId`; `parentId` de outro workspace ou inexistente devolve 404 e nada é criado; `parentId` de principal arquivada devolve 409 e nada é criado; nome vazio, só espaços e com 61 caracteres, `kind` desconhecido, cor `vermelho` e `#12345`, ícone vazio e com 41 caracteres devolvem 400 com o campo; campos extras `workspaceId`, `archived` e `createdAt` devolvem 400; nome com espaços nas pontas é gravado sem eles; "Mercado" e depois "mercado " sob a mesma principal devolvem 409; o mesmo nome sob outra principal devolve 201; "Outros" de despesa e "Outros" de receita coexistem (201) e uma segunda principal "outros" de despesa devolve 409; arquivar a de despesa e criar de novo devolve 201; nenhuma resposta contém `workspace_id`
- [ ] T017 [P] [US4] `TEST/category/CategoryConcurrencyTest.java`: 8 threads criam a mesma principal ao mesmo tempo e o resultado é exatamente um 201 e sete 409, nunca 500 (SC-009); o mesmo para subcategoria de mesmo nome sob o mesmo pai; criar subcategorias sob uma principal enquanto outra thread a arquiva, repetido 20 vezes, nunca deixa subcategoria ativa sob principal arquivada (conferido por consulta ao banco depois de cada rodada)

### Implementation for User Story 4

- [ ] T018 [P] [US4] Criar `MAIN/category/dto/CreateCategoryRequest.java` como `record` com Bean Validation: `name` obrigatório, 1 a 60 caracteres após `strip`; `kind` `CategoryKind` opcional no DTO (a obrigatoriedade sem `parentId` é do serviço); `parentId` `UUID` opcional; `color` opcional com `@Pattern("^#[0-9A-Fa-f]{6}$")`; `icon` opcional de 1 a 40 caracteres; mensagens em português; construtor compacto que aplica `strip` no nome
- [ ] T019 [US4] Em `MAIN/category/CategoryService.java`, acrescentar `create(UUID workspaceId, CreateCategoryRequest)` com `@WorkspaceScoped(write = true)` e `@Transactional`: sem `parentId`, `kind` ausente lança `FieldValidationException("kind", "obrigatório para categoria principal")`; com `parentId`, carregar o pai com `select kind, parent_id, archived from categories where id = :parentId and workspace_id = :workspaceId for share` (`CategoryNotFoundException` se faltar), lançar `FieldValidationException("parentId", "uma subcategoria não pode ter filhas")` se o pai tiver `parent_id`, `CategoryConflictException("A categoria principal está arquivada")` se estiver arquivado, e `FieldValidationException("kind", "deve ser igual ao da categoria principal")` se `kind` vier diferente; inserir com `INSERT ... ON CONFLICT DO NOTHING RETURNING id` (sem alvo de conflito, cobre os dois índices únicos), resultado vazio vira `CategoryNameAlreadyExistsException`; o `kind` gravado é o do pai; devolver a categoria carregada. Observação: `FOR SHARE` exige o privilégio UPDATE e passa pelas políticas de SELECT e de UPDATE, o que vale para `OWNER` e `EDITOR` (quem escreve) e para a `tostao_app`, que tem UPDATE em `categories`
- [ ] T020 [US4] Em `MAIN/category/CategoryController.java`, acrescentar `POST /api/v1/workspaces/{wid}/categories` (201, cabeçalho `Location`, corpo `CategoryResponse`). Acrescentar a `TEST/category/CategoryAccessTest.java` os casos de criar: não membro recebe 404 com corpo igual ao de workspace inexistente e nada é criado; `VIEWER` recebe 403 e nada é criado; `EDITOR` e `OWNER` recebem 201; `parentId` de categoria de B usado na rota de A devolve 404

**Checkpoint**: US1 a US4 funcionam

---

## Phase 6: User Story 5 - Corrigir uma categoria (Priority: P2)

**Goal**: editar nome, cor e ícone; tipo e pai não mudam

**Independent Test**: renomear uma categoria padrão e ver a lista refletir; tentar mudar tipo ou pai é recusado

### Tests for User Story 5

- [ ] T021 [P] [US5] `TEST/category/UpdateCategoryTest.java`: `PUT` com novo nome, cor `#1A9E5C` e ícone devolve 200 e a lista mostra os valores novos; `color` e `icon` ausentes limpam os valores; `kind` e `parentId` iguais aos atuais são aceitos, `kind` diferente devolve 400 com `errors[].field` `kind` e `parentId` diferente devolve 400 com o campo `parentId`; as mesmas validações da criação (nome vazio e com 61 caracteres, cor inválida, ícone fora do tamanho, campos extras `archived` e `workspaceId`) devolvem 400; nome já usado por irmã ativa devolve 409 e a categoria não muda; manter o próprio nome (sem mudança) devolve 200; renomear uma subcategoria para o nome de uma subcategoria de outro pai devolve 200; categoria arquivada devolve 409 e não muda; categoria de outro workspace devolve 404; `VIEWER` recebe 403 e não membro 404 (corpo igual ao de workspace inexistente), sem alterar nada; `EDITOR` e `OWNER` editam; `PUT` concorrente de dois nomes iguais em categorias irmãs diferentes resulta em um 200 e um 409, nunca 500

### Implementation for User Story 5

- [ ] T022 [P] [US5] Criar `MAIN/category/dto/UpdateCategoryRequest.java` como `record` com as mesmas validações de `name`, `color` e `icon` do `CreateCategoryRequest` (T018), mais `kind` (`CategoryKind`) e `parentId` (`UUID`) opcionais; o construtor compacto aplica `strip` no nome
- [ ] T023 [US5] Em `MAIN/category/CategoryService.java`, acrescentar `update(UUID workspaceId, UUID categoryId, UpdateCategoryRequest)` com `@WorkspaceScoped(write = true)` e `@Transactional`: carrega a categoria com `where id = :id and workspace_id = :workspaceId` (`CategoryNotFoundException` se faltar), `CategoryConflictException("A categoria está arquivada; restaure-a antes de editar")` se estiver arquivada, `FieldValidationException("kind", "não pode ser mudado")` se `kind` vier diferente do atual, `FieldValidationException("parentId", "não pode ser mudado")` se `parentId` vier diferente do atual, checagem prévia de nome livre entre irmãs ativas (mesmo pai; ou mesmas principais do mesmo tipo) excluindo a própria categoria, e `update categories set name = :name, color = :color, icon = :icon where id = :id and workspace_id = :workspaceId`; a corrida residual é barrada pelos índices únicos e traduzida em 409 na borda web (T002); devolve a categoria carregada
- [ ] T024 [US5] Em `MAIN/category/CategoryController.java`, acrescentar `PUT /api/v1/workspaces/{wid}/categories/{id}`

**Checkpoint**: US1 a US5 funcionam

---

## Phase 7: User Story 6 - Arquivar e restaurar categorias (Priority: P2)

**Goal**: arquivar em vez de apagar (com cascata nas subcategorias), ver as arquivadas e restaurar

**Independent Test**: arquivar uma principal com subcategorias, ver tudo sair da lista padrão e voltar aos poucos ao restaurar

### Tests for User Story 6

- [ ] T025 [P] [US6] `TEST/category/ArchiveRestoreCategoryTest.java`: arquivar uma principal com duas subcategorias ativas (e uma já arquivada antes) devolve 200 com `archived` `true`, as três somem de `GET /categories` e aparecem com `includeArchived=true` marcadas como arquivadas; arquivar uma subcategoria arquiva só ela e a principal e as irmãs seguem ativas; os lançamentos que usam a categoria (semeados pelo banco) continuam ligados a ela depois de arquivada (conferido pelo `OwnerJdbc`); arquivar de novo devolve 200 e não mexe em nenhuma outra linha; restaurar uma principal devolve 200 e **não** restaura as subcategorias; restaurar uma subcategoria com a principal arquivada devolve 409 e a categoria segue arquivada; depois de restaurar a principal, restaurar a subcategoria devolve 200; restaurar uma categoria ativa devolve 200 sem efeito; restaurar com nome em uso por irmã ativa devolve 409; `DELETE /categories/{id}` devolve 404 ou 405 e a categoria continua no banco; `VIEWER` recebe 403 em arquivar e restaurar, não membro 404 (corpo igual ao de workspace inexistente), categoria de outro workspace pela rota do meu 404, `EDITOR` arquiva, `OWNER` restaura, sem sessão 401; invariante final: nenhuma subcategoria ativa sob principal arquivada em nenhum dos cenários

### Implementation for User Story 6

- [ ] T026 [US6] Em `MAIN/category/CategoryService.java`, acrescentar `archive(UUID workspaceId, UUID categoryId)` com `@WorkspaceScoped(write = true)` e `@Transactional`: `update categories set archived = true where id = :id and workspace_id = :workspaceId and not archived`; se alguma linha mudou e a categoria é principal (`parent_id is null`), segunda instrução `update categories set archived = true where parent_id = :id and workspace_id = :workspaceId and not archived` na mesma transação; idempotente (nada muda se já estava arquivada); `CategoryNotFoundException` se a categoria não existir no workspace; devolve a categoria carregada. E `restore(UUID workspaceId, UUID categoryId)` com `@WorkspaceScoped(write = true)` e `@Transactional`: carrega a categoria (`CategoryNotFoundException` se faltar); se já está ativa, devolve sem escrever; se é subcategoria, `select archived from categories where id = :parentId and workspace_id = :workspaceId for share` e `CategoryConflictException("Restaure antes a categoria principal")` se o pai estiver arquivado; checagem prévia de nome livre entre irmãs ativas (`CategoryNameAlreadyExistsException`); `update categories set archived = false where id = :id and workspace_id = :workspaceId and archived`; nunca restaura subcategorias; a corrida residual é traduzida em 409 pela borda web
- [ ] T027 [US6] Em `MAIN/category/CategoryController.java`, acrescentar `POST /api/v1/workspaces/{wid}/categories/{id}/archive` e `POST .../restore`, sem nenhum mapeamento `DELETE`

**Checkpoint**: as seis user stories funcionam

---

## Phase 8: Polish & Cross-Cutting

- [ ] T028 [P] Em `TEST/OpenApiContractTest.java`, passar a conferir também `specs/003-categorias/contracts/openapi.yaml` contra o `/v3/api-docs` (todas as operações do contrato existem; novo teste `categoriesContractFileDeclaresTheExpectedRoutes` com os quatro caminhos do contrato) e acrescentar a asserção de que os caminhos `/api/v1/workspaces/{wid}/categories` e `/api/v1/workspaces/{wid}/categories/{id}` não declaram o método `delete`
- [ ] T029 [P] Em `TEST/auth/RegisterTest.java`, acrescentar a conferência de que o cadastro bem-sucedido deixa o workspace com categorias (contagem maior que zero pelo `OwnerJdbc`) e que o cadastro com e-mail repetido continua devolvendo 409 sem exceção do banco
- [ ] T030 [P] Acrescentar ao `README.md` um exemplo de `curl` para listar categorias e criar uma subcategoria, e uma linha dizendo que o cadastro já traz o conjunto padrão
- [ ] T031 Rodar `cd backend && ./mvnw verify` e garantir todos os testes verdes, os da 001 e da 002 inclusive
- [ ] T032 Rodar o roteiro de `specs/003-categorias/quickstart.md` contra `docker compose up -d --build` (a V4 roda sobre o banco que já tem V1, V2 e V3) e registrar a saída real de cada passo; conferir o conjunto padrão dos workspaces que já existiam; limpar os dados de teste do banco local depois
- [ ] T033 [P] Marcar a `003-categorias` como concluída no checklist da nota `D:\Paulo\Projetos\Tostão\Tostão.md`

---

## Dependencies & Execution Order

- **Foundational (Phase 1)**: bloqueia tudo. T001 vem primeiro e só é commitada com `./mvnw verify` verde. T002, T003 e T004 são independentes entre si e podem ir juntos depois do T001.
- **US1 (Phase 2)**: depende da Phase 1. T005, T006 e T007 são testes independentes entre si; não há implementação além da V4 (T001), então a US1 fecha quando os três passam.
- **US2 (Phase 3)**: depende da Phase 1. T010 antes do T011; T008 e T009 (testes) podem ser escritos antes e só passam depois do T011.
- **US3 (Phase 4)**: T013 e T014 dependem só da Phase 1; T012 e T015 dependem da US2 (testam as leituras). Pode andar em paralelo com a US4 a US6.
- **US4 (Phase 5)**: depende da US2 (usa o serviço e o controlador). T018 antes do T019; T019 antes do T020.
- **US5 (Phase 6)** e **US6 (Phase 7)**: dependem da US4 (precisam criar categorias pela API nos testes) e editam o mesmo `CategoryService` e `CategoryController`, então são sequenciais entre si (US5, depois US6).
- **Polish (Phase 8)**: depende de todas as stories.

```text
Foundational ─┬→ US1
              └→ US2 ─┬→ US3
                      └→ US4 → US5 → US6 → Polish
```

## Parallel Opportunities

- Foundational: T002, T003 e T004 juntos depois do T001.
- US1: T005, T006 e T007 juntos.
- US2: T008 e T009 (testes) juntos.
- US3: T013 e T014 já depois da Phase 1; T012 e T015 depois da US2.
- US4: T016, T017 e T018 juntos.
- Polish: T028, T029, T030 e T033 juntos.

## Implementation Strategy

- **MVP**: Phase 1 mais US1 e US2: o cadastro já entrega o conjunto padrão e dá para ler a árvore, que é o que a spec 004 (lançamentos) precisa para classificar. O MVP seguro inclui a US3, porque o isolamento e o papel são o que torna o módulo seguro para os módulos 004 e 005.
- **Entrega incremental**: Foundational verde → US1 → US2 → US3 (validar papel e isolamento antes de existir escrita) → US4 → US5 → US6 → Polish.
- **Antes do `/speckit-implement`**: rodar `/speckit-analyze` para cruzar spec, plano e tarefas contra a constituição.

## Notes

- A V4 só pode ser editada enquanto não foi aplicada em nenhum banco compartilhado; depois disso, mudanças vão em `V5`. Hoje ela só existiria no banco local.
- A `register_user` é a única função da `V2` recriada; a `V2` em si não é editada.
- O `RlsCoverageTest` da 001 continua valendo: `categories` segue com RLS ligado.
- Os testes de papel semeiam as associações `VIEWER` e `EDITOR` pelo `OwnerJdbc`, porque nenhuma rota atribui papéis antes da fase 3 do roadmap.
- Os testes que contam as categorias padrão cadastram pela API; os que precisam de um workspace limpo usam `workspaceWithOwner()`.
