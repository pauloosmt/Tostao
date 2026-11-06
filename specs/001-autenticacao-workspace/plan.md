# Implementation Plan: Autenticação e Workspace

**Branch**: `001-autenticacao-workspace` | **Date**: 2026-11-05 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-autenticacao-workspace/spec.md`

## Summary

Cadastro e login por e-mail e senha, sessão em cookie `HttpOnly` com JWT assinado, workspace pessoal
criado de forma atômica no cadastro e isolamento por workspace em duas camadas independentes.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Camada de dados**: nova migration `V2` cria a role de aplicação `tostao_app` (sem `BYPASSRLS`,
  sem ser dona das tabelas), políticas RLS por workspace e por usuário, e funções `SECURITY DEFINER`
  estreitas para o que precisa ver dados antes de o workspace ser conhecido (cadastro, busca por e-mail
  no login, contador de tentativas). O workspace e o usuário da requisição são definidos com
  `set_config(..., true)` dentro da transação (equivalente a `SET LOCAL`).
- **Camada de serviço**: um aspecto `@WorkspaceScoped`, executado dentro da transação, confere a
  participação do usuário no workspace da rota (404 se não for membro) e fixa o contexto antes de qualquer
  consulta. Toda consulta de serviço filtra também por `workspace_id`.
- **Sessão**: JWT HS256 em cookie `HttpOnly`/`Secure`/`SameSite=Lax`, lido pelo Spring Security
  (resource server com resolvedor de cookie), renovado a cada requisição autenticada por um filtro.
  Uma versão de sessão em `users` (claim `sv`) permite "sair de todos os aparelhos".
- **Limite de tentativas**: tabela `login_attempts` persistida no Postgres, atualizada por upsert
  atômico, com bloqueio de 15 minutos após 5 falhas consecutivas.
- **Escopo**: somente backend. As telas de cadastro e login fazem parte do roadmap de front-end, que é
  montado à parte.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring Security (com `oauth2-resource-server` e Nimbus JOSE
para o JWT), Spring JDBC (`JdbcClient`), Bean Validation, Flyway, springdoc-openapi (a adicionar). Sem
dependência nova de rate limit: o contador fica no Postgres.

**Storage**: PostgreSQL 17 (Compose e Testcontainers); Supabase em produção.

**Testing**: JUnit 5, Spring Boot Test + MockMvc, Testcontainers (Postgres real), Spring Security Test.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) dentro de monorepo com `backend/` e `frontend/`.

**Performance Goals**: entrar e consultar `/me` em menos de 2 s (SC-008); a verificação de senha com
BCrypt custo 10 leva em torno de 60 a 100 ms, dentro do orçamento.

**Constraints**: nunca expor a credencial da sessão fora do cookie; a diferença de tempo entre e-mail
inexistente e senha errada abaixo de 100 ms em média (SC-006); toda tabela nova deve nascer com RLS.

**Scale/Scope**: um usuário hoje, preparado para poucos; sem cache distribuído nem fila.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | `JWT_SECRET`, senhas de banco e `DB_APP_PASSWORD` só por variável de ambiente; verificação de partida recusa subir com `COOKIE_SECURE=true` e segredo de desenvolvimento ou curto demais; papel e workspace nunca vêm do cliente | OK |
| II. Isolamento em duas camadas | RLS com role sem `BYPASSRLS` e `set_config` por transação, mais aspecto `@WorkspaceScoped` e filtro explícito nas consultas; funções `SECURITY DEFINER` estreitas para as exceções; teste de acesso cruzado | OK |
| III. Autenticação e rede | Cookie `HttpOnly`/`Secure`/`SameSite=Lax`, stateless, BCrypt, renovação por requisição, sem `Authorization`; CORS de uma origem com credenciais; CSP, `Referrer-Policy`, `X-Frame-Options`; `Cache-Control: no-store` | OK |
| IV. Entrada validada, saída controlada | DTOs de entrada com Bean Validation e limites; DTOs de saída; consultas parametrizadas; erros em Problem Details sem detalhes internos | OK |
| V. Dados financeiros confiáveis | Nenhum valor monetário nesta feature; schema só por `V2` nova (V1 intocada); UUID | OK |
| VI. Sem tentativa e erro em transação | a existência do e-mail é decidida pela própria inserção condicional (`INSERT ... ON CONFLICT DO NOTHING` dentro da função), sem exceção e sem tentar-e-capturar; falhas de login contadas fora de qualquer transação que depois aborte | OK |
| VII. Simplicidade | Sem Redis nem biblioteca de rate limit; sem tabela de refresh token; sem entidades JPA (apenas `JdbcClient`) | OK |
| VIII. Prova, não promessa | Testes unitários, de integração com Postgres real e de acesso cruzado; [quickstart.md](quickstart.md) com roteiro manual | OK |

Sem violações. A seção Complexity Tracking não se aplica.

## Project Structure

### Documentation (this feature)

```text
specs/001-autenticacao-workspace/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Entidades, migration V2, políticas e funções
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Gerado por /speckit-tasks
```

### Source Code (repository root)

```text
backend/
├── pom.xml                                   # + springdoc-openapi
├── src/main/resources/
│   ├── application.properties                # + cookie, JWT, CORS, Flyway separado
│   └── db/migration/V2__auth_rls.sql         # role, políticas, funções, login_attempts
├── src/main/java/com/tostao/tostao_api/
│   ├── auth/                                 # AuthController, AuthService, LoginThrottle,
│   │   └── dto/                              # SessionTokenService, SessionCookieFactory,
│   │                                         # SessionRenewalFilter, PasswordPolicy, requests
│   ├── user/                                 # MeController, MeService, MeResponse
│   ├── workspace/                            # WorkspaceController, WorkspaceService,
│   │                                         # WorkspaceScoped, WorkspaceScopeAspect, TenantSession
│   └── common/
│       ├── config/                           # SecurityConfig, SecurityProperties,
│       │                                     # SecurityStartupCheck, CorsConfig
│       └── error/                            # ApiExceptionHandler, ProblemDetail helpers
└── src/test/java/com/tostao/tostao_api/
    ├── auth/                                 # PasswordPolicyTest, LoginThrottleTest,
    │                                         # AuthFlowTest, SessionCookieTest
    ├── workspace/                            # TenantIsolationTest, RlsPolicyTest
    └── support/                              # config de Testcontainers com role de aplicação

compose.yaml                                   # + DB_APP_PASSWORD, JWT_SECRET, COOKIE_SECURE
.env.example                                   # + novas variáveis
```

**Structure Decision**: monólito em camadas por módulo de negócio, como na constituição e no escopo
(`auth`, `user`, `workspace`, `common`). Cada módulo traz seus controladores, serviços e DTOs; não há
entidades JPA porque esta feature só usa funções SQL e consultas simples via `JdbcClient`.

## Complexity Tracking

Sem violações da constituição a justificar.
