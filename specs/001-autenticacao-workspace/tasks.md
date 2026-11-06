---

description: "Tarefas da feature 001-autenticacao-workspace"
---

# Tasks: Autenticação e Workspace

**Input**: documentos em `/specs/001-autenticacao-workspace/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS. A constituição (princípios II e VIII) e o FR-024 exigem testes de isolamento e de integração com Postgres real.

**Organization**: tarefas agrupadas por user story. Só o backend entra nesta feature (as telas ficam no roadmap de front-end).

## Format: `[ID] [P?] [Story] Descrição com caminho`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência de tarefa incompleta)
- **[Story]**: US1 a US5, conforme a spec
- Abreviação de caminhos usada abaixo (nunca em código):
  `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `RES` = `backend/src/main/resources`
- **Commits**: um commit por tarefa ou por pequeno grupo coeso (poucos arquivos), em Conventional Commits, sem co-author. Antes de cada commit rodar os testes que a tarefa tocou.

---

## Phase 1: Setup

**Purpose**: dependências e configuração compartilhadas

- [ ] T001 Adicionar `springdoc-openapi-starter-webmvc-ui` (versão compatível com Spring Boot 4.1.2, conferida no Maven Central) em `backend/pom.xml`
- [ ] T002 [P] Acrescentar as variáveis `DB_APP_PASSWORD`, `DB_MIGRATION_URL`, `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD`, `JWT_SECRET`, `COOKIE_SECURE` e `CORS_ORIGIN` ao serviço `api` de `compose.yaml` (com valores de desenvolvimento) e documentá-las em `.env.example`; `COOKIE_SECURE=false` só no ambiente local
- [ ] T003 [P] Em `RES/application.properties`: Flyway com conexão própria (`spring.flyway.url/user/password` de `DB_MIGRATION_*`, placeholder `app_password` de `DB_APP_PASSWORD`); `spring.datasource.*` passa a usar o usuário `tostao_app`; propriedades `tostao.security.jwt-secret`, `tostao.security.cookie-secure`, `tostao.security.cors-origin`, `tostao.security.session-days=365`; `spring.jackson.deserialization.fail-on-unknown-properties=true`; springdoc desligado por padrão e ligado só no ambiente local

---

## Phase 2: Foundational (bloqueia todas as user stories)

**Purpose**: migration V2, infraestrutura de teste, segurança base, erros e contexto de tenant

**⚠️ CRITICAL**: nenhuma user story começa antes deste checkpoint

### Banco de dados

- [ ] T004 Criar `RES/db/migration/V2__auth_rls.sql` com a parte 1: tabela `login_attempts (email TEXT PRIMARY KEY, failed_count INTEGER NOT NULL, locked_until TIMESTAMPTZ, last_failed_at TIMESTAMPTZ NOT NULL)` com índice parcial em `last_failed_at`; criação idempotente da role `tostao_app` (`LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE`, senha do placeholder `${app_password}`); `GRANT USAGE ON SCHEMA public`; `GRANT SELECT` em `users`, `workspaces`, `workspace_members`; `GRANT SELECT, INSERT, UPDATE, DELETE` em `accounts`, `categories`, `transactions`; nenhum privilégio em `login_attempts` nem em `flyway_schema_history`; `ALTER TABLE login_attempts ENABLE ROW LEVEL SECURITY`
- [ ] T005 Na mesma `V2__auth_rls.sql`, parte 2: políticas de RLS lendo `nullif(current_setting('app.user_id', true), '')::uuid` e `nullif(current_setting('app.workspace_id', true), '')::uuid` — `users_self` (SELECT, `id = app.user_id`), `members_visible` (SELECT, `user_id = app.user_id` OU `workspace_id = app.workspace_id`), `workspaces_visible` (SELECT, `id = app.workspace_id` OU existe associação do `app.user_id`), e `accounts_tenant`, `categories_tenant`, `transactions_tenant` (ALL, `workspace_id = app.workspace_id`, com `WITH CHECK` igual ao `USING`)
- [ ] T006 Na mesma `V2__auth_rls.sql`, parte 3: funções `SECURITY DEFINER` com `SET search_path = public, pg_temp`, `REVOKE EXECUTE ... FROM PUBLIC` e `GRANT EXECUTE` só para `tostao_app` — `register_user(email, password_hash, name)` (insere usuário, workspace `Pessoal` com `base_currency` `BRL` e associação `OWNER` em uma chamada, com `INSERT ... ON CONFLICT DO NOTHING` no e-mail e retorno vazio em conflito, sem exceção), `find_user_for_login(email)` (id, name, email, password_hash), `login_lockout_status(email)` (nulo se o bloqueio venceu), `record_login_failure(email)` (upsert atômico: recomeça em 1 se o bloqueio venceu, `locked_until = now() + 15 min` ao chegar em 5, apaga linhas com mais de 24 h sem bloqueio ativo), `reset_login_attempts(email)`; todas normalizam o e-mail com `lower(trim(...))`

### Infraestrutura de teste

- [ ] T007 Reescrever `TEST/TestcontainersConfiguration.java` (Postgres `postgres:17`): Flyway conecta com o usuário dono do container, `spring.datasource` conecta como `tostao_app` com senha de teste (via `DynamicPropertyRegistrar`, placeholder `app_password` incluído), e um bean `@Qualifier("ownerJdbcClient")` expõe uma conexão do dono (ignora RLS) para preparar e inspecionar dados; manter `HealthEndpointTests` e `TostaoApiApplicationTests` passando
- [ ] T008 [P] Criar `TEST/workspace/RlsCoverageTest.java`: consulta `pg_class`/`pg_namespace` e falha se qualquer tabela de `public` fora de `flyway_schema_history` estiver sem `relrowsecurity`; também confere que `tostao_app` tem `rolbypassrls = false` e `rolsuper = false`

### Segurança base e erros

- [ ] T009 [P] Criar `MAIN/common/config/SecurityProperties.java` (`@ConfigurationProperties("tostao.security")`: `jwtSecret`, `cookieSecure`, `corsOrigin`, `sessionDays`) e `MAIN/common/config/SecurityStartupCheck.java` que lança `IllegalStateException` se `jwtSecret` tiver menos de 32 bytes ou se `cookieSecure=true` e o segredo for o valor de desenvolvimento; teste em `TEST/common/SecurityStartupCheckTest.java`
- [ ] T010 [P] Criar `MAIN/common/error/ApiExceptionHandler.java` (`@RestControllerAdvice`, Problem Details RFC 7807, sem detalhes internos) e as exceções `EmailAlreadyExistsException` (409), `InvalidCredentialsException` (401, mensagem genérica única), `TooManyAttemptsException` (429, carrega os segundos para `Retry-After`) e `WorkspaceNotFoundException` (404) em `MAIN/common/error/`; erros de validação saem com 400 e `errors[]` de `field` e `message`; corpo JSON com campo desconhecido também resulta em 400
- [ ] T011 [P] Criar `MAIN/auth/SessionTokenService.java` (beans `NimbusJwtEncoder`/`NimbusJwtDecoder` HS256 com o segredo; `issue(UUID userId)` com `sub`, `iat` e `exp` = agora + `sessionDays`) e `MAIN/auth/SessionCookieFactory.java` (`ResponseCookie` `tostao_session`: `HttpOnly`, `Secure` conforme `cookieSecure`, `SameSite=Lax`, `Path=/api`, `Max-Age` de 365 dias; `clear()` com `Max-Age=0`); teste em `TEST/auth/SessionCookieTest.java` conferindo os atributos
- [ ] T012 Reescrever `MAIN/common/config/SecurityConfig.java`: resource server com `CookieBearerTokenResolver` (novo, `MAIN/auth/CookieBearerTokenResolver.java`, lê só o cookie `tostao_session`, ignora `Authorization` e query string), entry point e handler de acesso negado em Problem Details sem `WWW-Authenticate`, CORS de uma origem com `allowCredentials` (`MAIN/common/config/CorsConfig.java`), `permitAll` para `/api/v1/health`, `/api/v1/auth/register`, `/api/v1/auth/login` e `/api/v1/auth/logout`, o restante autenticado, e o filtro `MAIN/auth/SessionRenewalFilter.java` (após a autenticação, reemite o cookie a cada requisição autenticada, exceto no logout); manter CSP, `X-Frame-Options`, `Referrer-Policy` e CSRF desabilitado; `HealthEndpointTests` deve continuar passando
- [ ] T013 [P] Criar `MAIN/workspace/TenantSession.java` com `enterUser(UUID userId)` e `enterWorkspace(UUID userId, UUID workspaceId)` executando `select set_config('app.user_id', ?, true)` e `set_config('app.workspace_id', ?, true)` com parâmetros ligados; lança erro se não houver transação ativa (`TransactionSynchronizationManager.isActualTransactionActive()`)
- [ ] T014 [P] Criar `MAIN/user/MeService.java` e os DTOs `MAIN/user/MeResponse.java` e `MAIN/workspace/WorkspaceMembershipResponse.java` (`id`, `name`, `baseCurrency`, `role`); `MeService.load(UUID userId)` roda em `@Transactional(readOnly = true)`, chama `TenantSession.enterUser` e lê usuário e workspaces por `JdbcClient` com consultas parametrizadas, sem nunca selecionar `password_hash`

**Checkpoint**: migration V2 aplicada nos testes, segurança base e erros prontos; `./mvnw verify` verde

---

## Phase 3: User Story 1 - Criar conta e começar com um workspace pessoal (Priority: P1) 🎯 MVP

**Goal**: cadastro cria, atomicamente, conta, workspace pessoal e associação OWNER, e deixa a pessoa autenticada

**Independent Test**: `POST /api/v1/auth/register` devolve 201 com cookie `HttpOnly` e um workspace `OWNER`; repetir o e-mail devolve 409

### Tests for User Story 1

- [ ] T015 [P] [US1] `TEST/auth/PasswordPolicyTest.java`: aceita 8 a 72 bytes UTF-8 com ao menos uma letra e um número; recusa 7 caracteres, só letras, só números e 73 bytes (inclusive acentos que passam de 72 bytes com menos de 72 caracteres)
- [ ] T016 [P] [US1] `TEST/auth/EmailNormalizerTest.java`: `trim` e `toLowerCase(Locale.ROOT)`
- [ ] T017 [P] [US1] `TEST/auth/RegisterTest.java` (Testcontainers + MockMvc): 201 com `Set-Cookie` `HttpOnly; SameSite=Lax; Path=/api`, corpo sem senha nem hash e com um workspace `OWNER`; 409 para e-mail repetido com maiúsculas diferentes; 400 com `errors[].field` para senha curta, e-mail inválido, nome vazio, nome com 101 caracteres, e-mail com 255 caracteres e campo extra `role`; duas chamadas simultâneas com o mesmo e-mail resultam em uma conta só e nenhum 500; conferência pelo `ownerJdbcClient` de que uma linha existe em `users`, `workspaces` e `workspace_members`

### Implementation for User Story 1

- [ ] T018 [P] [US1] Criar `MAIN/auth/PasswordPolicy.java` (8 a 72 bytes UTF-8, ao menos uma letra e um número) e `MAIN/auth/EmailNormalizer.java`
- [ ] T019 [P] [US1] Criar `MAIN/auth/dto/RegisterRequest.java` como `record` com Bean Validation: `name` obrigatório, 1 a 100 caracteres após `trim`; `email` obrigatório, formato válido, até 254 caracteres; `password` obrigatório, 8 a 72 bytes, validada por `PasswordPolicy`
- [ ] T020 [US1] Criar `MAIN/auth/AuthService.java` com `register(RegisterRequest)`: normaliza o e-mail, gera o hash BCrypt custo 10, chama a função `register_user` por `JdbcClient` (retorno vazio vira `EmailAlreadyExistsException`), emite o token com `SessionTokenService` e devolve `MeResponse` via `MeService`; sem `@Transactional` abrangente e sem tentar-e-capturar exceção de banco
- [ ] T021 [US1] Criar `MAIN/auth/AuthController.java` com `POST /api/v1/auth/register` (201, `Set-Cookie` pelo `SessionCookieFactory`, corpo `MeResponse`)

**Checkpoint**: US1 funciona e é testável sozinha

---

## Phase 4: User Story 2 - Entrar, permanecer autenticada e sair (Priority: P1)

**Goal**: login com e-mail e senha, sessão que dura até a pessoa sair, logout

**Independent Test**: login 200 com cookie; rota protegida acessível com o cookie; logout 204 com `Max-Age=0`

### Tests for User Story 2

- [ ] T022 [P] [US2] `TEST/auth/LoginLogoutTest.java`: login correto devolve 200 e cookie; e-mail inexistente e senha errada devolvem 401 com corpo idêntico; e-mail com maiúsculas e espaços entra normalmente; requisição autenticada devolve novo `Set-Cookie` (renovação); logout devolve 204 com `Max-Age=0`; logout sem sessão devolve 204; `Authorization: Bearer <jwt válido>` sem cookie devolve 401; cookie adulterado devolve 401, nunca 500; o `exp` do token é de cerca de 365 dias
- [ ] T023 [P] [US2] `TEST/auth/LoginTimingTest.java`: a diferença média de tempo entre e-mail inexistente e senha errada, em ao menos 20 tentativas de cada, fica abaixo de 100 ms

### Implementation for User Story 2

- [ ] T024 [P] [US2] Criar `MAIN/auth/dto/LoginRequest.java` (`email` obrigatório até 254 caracteres, `password` obrigatória até 72 bytes)
- [ ] T025 [US2] Em `MAIN/auth/AuthService.java`, adicionar `login(LoginRequest)`: normaliza o e-mail, chama `find_user_for_login`, compara a senha com BCrypt (se o e-mail não existe, compara com um hash fictício calculado na partida, para o custo ser igual), lança `InvalidCredentialsException` na falha, emite o token e devolve `MeResponse`; falha de senha é retorno normal, nunca exceção dentro de transação
- [ ] T026 [US2] Em `MAIN/auth/AuthController.java`, adicionar `POST /api/v1/auth/login` (200 com cookie e `MeResponse`) e `POST /api/v1/auth/logout` (204 com o cookie vazio de `Max-Age=0`, também sem sessão)

**Checkpoint**: US1 e US2 funcionam; a pessoa cadastra, sai e volta a entrar

---

## Phase 5: User Story 3 - Cada pessoa vê somente os dados dos seus workspaces (Priority: P1)

**Goal**: isolamento em duas camadas, com 404 indistinguível para workspace alheio e inexistente

**Independent Test**: com duas contas, a primeira recebe 404 ao consultar o workspace da segunda, e o RLS sozinho já esconde as linhas

### Tests for User Story 3

- [ ] T027 [P] [US3] `TEST/workspace/RlsPolicyTest.java` (conectado como `tostao_app`, linhas preparadas pelo `ownerJdbcClient`): sem contexto, `select` em `accounts`, `categories`, `transactions`, `workspaces` e `workspace_members` devolve 0 linhas; com `app.workspace_id` do workspace A só enxerga as linhas de A; inserir em `accounts` com `workspace_id` de B sob o contexto A é rejeitado pelo `WITH CHECK`; `app.workspace_id` definido numa transação não aparece na seguinte da mesma conexão; `insert` direto em `users`, `workspaces` e `workspace_members` é negado
- [ ] T028 [P] [US3] `TEST/workspace/TenantIsolationTest.java` (HTTP): `GET /api/v1/workspaces/{wid}` devolve 200 com `role: OWNER` para o próprio workspace; 404 para o workspace de outra pessoa e 404 para um UUID aleatório, com corpos indistinguíveis; 401 sem sessão
- [ ] T029 [P] [US3] `TEST/workspace/ServiceLayerFilterTest.java`: chama `WorkspaceService` com a conexão do dono (que ignora o RLS) e prova que a consulta do serviço já filtra por `workspace_id` e que a checagem de participação nega quem não é membro (SC-003: cada camada isola sozinha)

### Implementation for User Story 3

- [ ] T030 [US3] Criar `MAIN/workspace/WorkspaceScoped.java` (anotação em método de serviço cujo primeiro parâmetro é o `UUID workspaceId`) e `MAIN/workspace/WorkspaceScopeAspect.java` (`@Around`, `@Order(Ordered.LOWEST_PRECEDENCE)` para rodar dentro da transação): lê o usuário do `SecurityContext`, chama `TenantSession.enterUser`, confere em `workspace_members` com `user_id` e `workspace_id` parametrizados, lança `WorkspaceNotFoundException` se não houver linha e então chama `TenantSession.enterWorkspace`
- [ ] T031 [US3] Criar `MAIN/workspace/WorkspaceService.java` com `get(@WorkspaceScoped UUID workspaceId)` em `@Transactional(readOnly = true)`, consulta com `where id = :workspaceId` e junção explícita com `workspace_members` do usuário, devolvendo `WorkspaceMembershipResponse`
- [ ] T032 [US3] Criar `MAIN/workspace/WorkspaceController.java` com `GET /api/v1/workspaces/{wid}` (200, ou 404 por `WorkspaceNotFoundException`)

**Checkpoint**: o isolamento está provado nas duas camadas; os módulos 002 a 005 herdam `@WorkspaceScoped`

---

## Phase 6: User Story 4 - Consultar quem sou e quais workspaces tenho (Priority: P2)

**Goal**: `GET /me` com usuário e workspaces

**Independent Test**: autenticada, `GET /api/v1/me` devolve nome, e-mail e workspaces com papel, sem campos sensíveis

### Tests for User Story 4

- [ ] T033 [P] [US4] `TEST/user/MeEndpointTest.java`: 200 com `name`, `email` e `workspaces[].role`; o JSON não contém `password`, `passwordHash` nem `password_hash`; 401 sem sessão; a resposta tem `Cache-Control` com `no-store`

### Implementation for User Story 4

- [ ] T034 [US4] Criar `MAIN/user/MeController.java` com `GET /api/v1/me` usando o usuário autenticado e `MeService.load`

**Checkpoint**: o front-end já tem o que precisa para abrir o app

---

## Phase 7: User Story 5 - Resistir a tentativas repetidas de adivinhar senha (Priority: P2)

**Goal**: 5 falhas consecutivas bloqueiam por 15 minutos, inclusive para e-mails sem conta, e o bloqueio sobrevive a reinício

**Independent Test**: errar a senha 5 vezes e receber 429 com `Retry-After`, mesmo com a senha correta

### Tests for User Story 5

- [ ] T035 [P] [US5] `TEST/auth/LoginThrottleTest.java`: cinco falhas seguidas e a sexta tentativa, com a senha certa, devolve 429 com `Retry-After`; e-mail inexistente se comporta igual (mesmo 401 nas falhas e mesmo 429 no limite); login bem-sucedido abaixo do limite zera o contador (confere pelo `ownerJdbcClient` que a linha sumiu); com `locked_until` movido para o passado pelo `ownerJdbcClient`, o login correto volta a funcionar e uma nova falha recomeça a contagem em 1; o estado está no banco, então persiste sem depender da memória do processo; 20 tentativas concorrentes com falha não contam menos que 5

### Implementation for User Story 5

- [ ] T036 [P] [US5] Criar `MAIN/auth/LoginThrottle.java` com `remainingLockSeconds(String email)`, `recordFailure(String email)` e `reset(String email)`, cada um uma chamada curta e independente às funções `login_lockout_status`, `record_login_failure` e `reset_login_attempts` por `JdbcClient`
- [ ] T037 [US5] Em `MAIN/auth/AuthService.java`, integrar o `LoginThrottle` ao `login`: checar o bloqueio antes de verificar a senha (lança `TooManyAttemptsException` com os segundos restantes), registrar falha em credencial inválida (inclusive e-mail inexistente) e zerar o contador em sucesso
- [ ] T038 [US5] Em `MAIN/common/error/ApiExceptionHandler.java`, mapear `TooManyAttemptsException` para 429 com o header `Retry-After` em segundos

**Checkpoint**: as cinco user stories funcionam; o limite de tentativas protege o login

---

## Phase 8: Polish & Cross-Cutting

- [ ] T039 [P] Ligar o springdoc no ambiente local e conferir, em `TEST/OpenApiContractTest.java`, que as rotas e os esquemas gerados batem com `specs/001-autenticacao-workspace/contracts/openapi.yaml`; a UI do Swagger continua fechada (`denyAll`) fora do ambiente local
- [ ] T040 [P] Atualizar `README.md` com as novas variáveis de ambiente e o roteiro de login local
- [ ] T041 Rodar `cd backend && ./mvnw verify` e garantir todos os testes verdes
- [ ] T042 Rodar o roteiro de `specs/001-autenticacao-workspace/quickstart.md` contra `docker compose up -d --build` e registrar a saída real de cada passo, incluindo recusa de partida com `COOKIE_SECURE=true` e segredo de desenvolvimento
- [ ] T043 [P] Marcar a `001` como concluída no checklist da nota `D:\Paulo\Projetos\Tostão\Tostão.md`

---

## Dependencies & Execution Order

- **Setup (Phase 1)**: sem dependências. T002 e T003 em paralelo.
- **Foundational (Phase 2)**: depende do Setup e bloqueia tudo. Ordem: T004 → T005 → T006 (mesmo arquivo), depois T007; T008 depende de T007. T009, T010, T011, T013 e T014 são independentes entre si; T012 depende de T009, T010 e T011; T014 depende de T013.
- **US1 (Phase 3)**: depende da Phase 2. T018 e T019 antes de T020; T020 antes de T021.
- **US2 (Phase 4)**: depende de US1 (os testes usam o cadastro para criar contas) e de T020 (compartilha `AuthService`).
- **US3 (Phase 5)**: depende da Phase 2 e de US1 (os testes HTTP criam duas contas). Pode andar em paralelo com US2.
- **US4 (Phase 6)**: depende de US1.
- **US5 (Phase 7)**: depende de US2 (altera o `login`).
- **Polish (Phase 8)**: depende de todas as stories.

```text
Setup → Foundational → US1 ─┬→ US2 → US5
                            ├→ US3
                            └→ US4
                                   └→ Polish
```

## Parallel Opportunities

- Setup: T002 e T003.
- Foundational: T008, T009, T010, T011 e T013 juntos depois de T007; T014 depois de T013.
- US1: T015, T016 e T017 (testes) juntos; T018 e T019 juntos.
- US2 e US3 em paralelo depois de US1, por pessoas ou sessões diferentes (arquivos distintos).
- US3: T027, T028 e T029 juntos.

## Implementation Strategy

- **MVP**: Phases 1 e 2 mais US1 (cadastro com workspace pessoal). Já dá uma conta funcional, mas ainda sem entrada de volta, então o MVP utilizável é **US1 + US2 + US3**, que entregam cadastro, login e o isolamento que os módulos 002 a 005 herdam.
- **Entrega incremental**: Foundational verde → US1 → US2 → US3 (validar o isolamento antes de qualquer dado financeiro existir) → US4 → US5 → Polish.
- **Antes do `/speckit-implement`**: rodar `/speckit-analyze` para cruzar spec, plano e tarefas contra a constituição.

## Notes

- Cada tarefa que altera a migration `V2` mexe no mesmo arquivo: T004, T005 e T006 são sequenciais, e depois de a `V2` ser aplicada em qualquer banco ela não é mais editada (novas mudanças vão em `V3`).
- A senha de teste da role `tostao_app` vive só nos testes e no `.env.example` de desenvolvimento, nunca em valor de produção.
