# Data Model: Autenticação e Workspace

As tabelas `users`, `workspaces` e `workspace_members` já existem na `V1`. Esta feature acrescenta a
migration **`V2__auth_rls.sql`** (a `V1` não é editada) com a tabela `login_attempts`, a coluna
`users.session_version`, a role de aplicação, as políticas de RLS e as funções estreitas.

## Entidades

### Usuário (`users`, existente)

| Campo | Regra |
|---|---|
| `id` | UUID gerado pelo banco |
| `email` | texto, único por `lower(email)`; gravado já normalizado (trim e minúsculas), até 254 caracteres |
| `password_hash` | hash BCrypt custo 10; nunca sai do banco para a API |
| `name` | 1 a 100 caracteres, sem espaços só nas pontas |
| `created_at` | preenchido pelo banco |
| `session_version` | **novo na V2**: `INTEGER NOT NULL DEFAULT 0`; sobe 1 ao "sair de todos os aparelhos" e invalida os tokens anteriores |

### Workspace (`workspaces`, existente)

`id`, `name`, `base_currency` (`BRL` por padrão), `created_at`. O workspace pessoal recebe o nome
`Pessoal` (ajustável depois). A criação só acontece dentro de `register_user`.

### Associação (`workspace_members`, existente)

Chave `(workspace_id, user_id)`; `role` em `OWNER`, `EDITOR` ou `VIEWER`. Nesta feature só `OWNER` é
gravado, e nenhuma rota altera papéis (FR-021).

### Registro de tentativas (`login_attempts`, nova)

| Campo | Tipo | Regra |
|---|---|---|
| `email` | `TEXT` PK | e-mail normalizado; existe também para e-mails sem conta |
| `failed_count` | `INTEGER` | falhas consecutivas desde o último reinício da contagem |
| `locked_until` | `TIMESTAMPTZ` nulo | instante até o qual novas tentativas são recusadas |
| `last_failed_at` | `TIMESTAMPTZ` | usado para limpar linhas antigas |

Não é dado de workspace e não tem `workspace_id`. Fica fora do alcance direto da role de aplicação:
somente as funções abaixo a leem e escrevem.

### Sessão

Não é entidade de banco. É o JWT no cookie: `sub` = id do usuário, `sv` = `users.session_version` no
momento da emissão, `iat`, `exp` (365 dias, renovado a cada requisição autenticada). O token só vale
enquanto `sv` for igual à versão atual do usuário.

## Transições de estado

```text
login_attempts: (sem linha) --falha--> count=1 ... --5ª falha--> locked_until = agora + 15 min
                locked_until vencido --falha--> count=1 (recomeça)
                qualquer estado --login ok--> (linha removida)
```

## Role e privilégios

- `tostao_app`: `LOGIN`, `NOSUPERUSER`, `NOBYPASSRLS`, `NOCREATEDB`, `NOCREATEROLE`; criada pela `V2`
  se ainda não existir, com senha do placeholder `${app_password}`.
- `GRANT USAGE ON SCHEMA public`.
- `SELECT` em `users`, `workspaces`, `workspace_members`.
- `SELECT, INSERT, UPDATE, DELETE` em `accounts`, `categories`, `transactions`.
- Nada em `login_attempts` nem em `flyway_schema_history`. Nenhum `INSERT` direto em `users`, `workspaces`
  ou `workspace_members`: o cadastro passa só por `register_user`.
- Tabelas novas de módulos futuros recebem `GRANT` explícito na própria migration; não há
  `ALTER DEFAULT PRIVILEGES`.

## Políticas de RLS

Variáveis de transação: `app.user_id` e `app.workspace_id`, lidas com
`nullif(current_setting('<nome>', true), '')::uuid` (ausente resulta em nulo, e nulo nunca é igual a nada).
Todas as tabelas já têm RLS ligado pela `V1`.

| Tabela | Política | Condição (USING e WITH CHECK) |
|---|---|---|
| `users` | `users_self` (SELECT) | `id = app.user_id` |
| `workspace_members` | `members_visible` (SELECT) | `user_id = app.user_id` OU `workspace_id = app.workspace_id` |
| `workspaces` | `workspaces_visible` (SELECT) | `id = app.workspace_id` OU existe associação do `app.user_id` com ele |
| `accounts` | `accounts_tenant` (ALL) | `workspace_id = app.workspace_id` |
| `categories` | `categories_tenant` (ALL) | `workspace_id = app.workspace_id` |
| `transactions` | `transactions_tenant` (ALL) | `workspace_id = app.workspace_id` |

Sem contexto definido (`app.workspace_id` ausente), as políticas de `accounts`, `categories` e
`transactions` não liberam nenhuma linha.

## Funções `SECURITY DEFINER`

Todas pertencem ao dono do schema (que ignora RLS por ser dono), usam
`SET search_path = public, pg_temp`, recebem `REVOKE EXECUTE ... FROM PUBLIC` e `GRANT EXECUTE` só para
`tostao_app`. Recebem o e-mail já normalizado pela aplicação e o normalizam de novo por segurança.

| Função | Entrada | Retorno | Uso |
|---|---|---|---|
| `register_user(email, password_hash, name)` | e-mail, hash, nome | `(user_id, workspace_id)` ou nenhuma linha se o e-mail já existe | Cria usuário, workspace `Pessoal` e associação `OWNER` numa chamada atômica, com `INSERT ... ON CONFLICT DO NOTHING` |
| `find_user_for_login(email)` | e-mail | `(id, name, email, password_hash)` ou nenhuma linha | Único caminho que lê o hash |
| `login_lockout_status(email)` | e-mail | `locked_until` ou nulo | Retorna nulo se o bloqueio já venceu |
| `record_login_failure(email)` | e-mail | `locked_until` ou nulo | Upsert atômico: soma 1, recomeça se o bloqueio venceu, bloqueia por 15 min ao chegar em 5; apaga linhas com mais de 24 h sem bloqueio |
| `reset_login_attempts(email)` | e-mail | nada | Remove a linha após login bem-sucedido |
| `session_version_of(user_id)` | UUID do usuário | inteiro, ou nulo se o usuário não existe | Consultada a cada requisição autenticada pelo validador do token; também devolve a versão atual para emitir tokens |
| `revoke_sessions(user_id)` | UUID do usuário | a nova versão | Soma 1 a `session_version` do próprio usuário; é a ação "sair de todos os aparelhos" |

## Validações de entrada (aplicadas na API, antes do banco)

| Campo | Regra |
|---|---|
| `name` | obrigatório, 1 a 100 caracteres após `trim` |
| `email` | obrigatório, formato válido, até 254 caracteres, normalizado |
| `password` | obrigatório, 8 a 72 bytes em UTF-8, ao menos uma letra e um número |
| campos extras | rejeitados (a desserialização falha em propriedade desconhecida) |

## Índices e restrições relevantes

- Já existentes: `users_email_key` em `lower(email)` e `workspace_members_user_idx` em `user_id`.
- `login_attempts`: chave primária em `email`; índice parcial em `last_failed_at` para a limpeza.
