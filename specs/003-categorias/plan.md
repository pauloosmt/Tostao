# Implementation Plan: Categorias

**Branch**: `003-categorias` | **Date**: 2026-11-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-categorias/spec.md`

## Summary

Categorias de receita e despesa por workspace, em dois níveis, com um conjunto padrão criado junto com o
workspace pessoal e arquivamento no lugar de exclusão. Segue o desenho da `002-contas`.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Tabela existente**: `categories` já existe na `V1` (com `parent_id`, `kind`, `color`, `icon`,
  `archived`). A migration `V4` só acrescenta restrições (nome, cor, ícone), a chave composta que faz o
  banco garantir que a subcategoria tem o mesmo tipo e o mesmo workspace do pai, um gatilho que barra o
  terceiro nível, dois índices únicos parciais para o nome entre irmãs ativas, as políticas de RLS de
  escrita por papel (como em `accounts`) e a revogação do `DELETE`.
- **Conjunto padrão no cadastro**: a função `register_user` (da `V2`) é recriada na `V4` para chamar
  `seed_default_categories(workspace)` na mesma chamada que cria usuário, workspace e associação. Como é
  uma única função, o cadastro e as categorias são atômicos por construção (FR-002). A mesma função de
  semente preenche, uma única vez e dentro da própria `V4`, os workspaces que já existiam (FR-003).
- **Permissão por papel**: `@WorkspaceScoped(write = true)` da `002`; mesmas respostas (404 para quem não
  é membro, 403 para `VIEWER`).
- **Nome único sem exceção em transação**: criação com `INSERT ... ON CONFLICT DO NOTHING`; edição e
  restauração com checagem prévia; a corrida residual vira 409 na borda web, depois do rollback.
- **Arquivar a principal**: uma só transação arquiva a principal e as subcategorias ativas. O bloqueio da
  linha do pai (`FOR SHARE` na criação e na restauração de filha) impede nascer ou voltar uma filha ativa
  sob um pai que está sendo arquivado.
- **Escopo**: somente backend. As telas ficam no roadmap de front-end.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security
(contexto e aspecto da `001`/`002`), springdoc-openapi. Nenhuma dependência nova.

**Storage**: PostgreSQL 17, tabela `categories` (V1) alterada pela migration `V4__categories.sql`; função
`register_user` (V2) recriada na `V4`.

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear
papéis, categorias e lançamentos de outros workspaces; teste de concorrência para o mesmo nome.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: listar um workspace com 200 categorias em menos de 1 s (SC-007), com uma única
consulta e montagem da árvore em memória.

**Constraints**: nenhuma exceção capturada dentro de transação; toda consulta filtra por `workspace_id`;
`register_user` continua sendo o único caminho de cadastro e continua sem levantar exceção para e-mail
repetido.

**Scale/Scope**: um usuário hoje, poucas dezenas de categorias por workspace, sem paginação.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | O papel vem do banco (`workspace_members`); a escrita é negada no aspecto e de novo pela política de RLS; `seed_default_categories` não é executável pela role da aplicação | OK |
| II. Isolamento em duas camadas | `@WorkspaceScoped` + filtro explícito por `workspace_id` em toda consulta e RLS de leitura e escrita por workspace; chave composta impede pai de outro workspace; teste de acesso cruzado por operação; `DELETE` revogado | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (cookie, CSP, `no-store`); rotas novas sob `/api/v1/workspaces/{wid}` e com sessão | OK |
| IV. Entrada validada, saída controlada | DTOs de entrada com Bean Validation e campos não listados recusados; DTOs de saída próprios; consultas parametrizadas; erros em Problem Details | OK |
| V. Dados financeiros confiáveis | Mudança de schema só pela `V4`, com `V1`, `V2` e `V3` intocadas (a `register_user` é recriada, não editada); UUID; FK composta `(id, workspace_id)` mantida e ampliada com o tipo | OK |
| VI. Sem tentativa e erro em transação | Criação com `ON CONFLICT DO NOTHING`; edição, restauração e criação de filha com checagem prévia; gatilho de profundidade e índices só como rede de segurança; corrida vira 409 depois do rollback | OK |
| VII. Simplicidade | Sem entidade JPA, sem coluna de ordem, sem paginação, sem tabela de modelos de categoria: o conjunto padrão é uma função SQL com os nomes escritos nela | OK |
| VIII. Prova, não promessa | Testes de integração com Postgres real (inclusive a `V4` sobre banco já na `V3`, hierarquia, RLS, papel, concorrência e semente atômica); [quickstart.md](quickstart.md) com chamadas reais | OK |

Sem violações. A seção Complexity Tracking não se aplica.

Reavaliação depois do desenho (Phase 1): nenhuma mudança. O único ponto que merece atenção é recriar a
`register_user` (função de segurança): a `V4` repete o corpo da `V2` e acrescenta uma linha, e há teste
que prova que o cadastro continua devolvendo zero linhas para e-mail repetido.

## Project Structure

### Documentation (this feature)

```text
specs/003-categorias/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Categoria, conjunto padrão, migration V4 e políticas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Gerado por /speckit-tasks
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/
│   └── db/migration/V4__categories.sql      # restrições, FK de tipo, gatilho, índices, RLS, semente, register_user
├── src/main/java/com/tostao/tostao_api/
│   ├── category/                            # CategoryController, CategoryService, CategoryKind
│   │   └── dto/                             # CreateCategoryRequest, UpdateCategoryRequest,
│   │                                        # CategoryResponse, CategoryTreeResponse
│   └── common/error/                        # CategoryNotFoundException, CategoryNameAlreadyExistsException,
│                                            # CategoryConflictException, e mapeamentos em ApiExceptionHandler
└── src/test/java/com/tostao/tostao_api/
    ├── category/                            # DefaultCategoriesTest, ListCategoriesTest, CreateCategoryTest,
    │                                        # UpdateCategoryTest, ArchiveRestoreCategoryTest, CategoryAccessTest,
    │                                        # CategoryRlsTest, CategoryServiceFilterTest, CategoryConcurrencyTest,
    │                                        # CategoryHierarchyDbTest, CategoryPerformanceTest,
    │                                        # DefaultCategoriesAtomicTest, CategoriesMigrationTest
    ├── auth/                                # + cadastro continua atômico e sem exceção para e-mail repetido
    └── support/                             # WorkspaceFixtures (+ insertCategory)
```

**Structure Decision**: continua o monólito em camadas por módulo. `category` guarda a regra e o acesso a
dados, como `account`. O `AuthService` não muda: a semente vive dentro de `register_user`, no banco.

## Complexity Tracking

Sem violações da constituição a justificar.
