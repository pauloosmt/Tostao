# Data Model: Membros e convites

Esta feature **adiciona uma tabela** (`invitations`), políticas e privilégios em `workspace_members` e `users`, um índice
único de dono (migration `V12`) e uma função de banco (migration `V13`). Nenhum dado existente muda.

## Tabela `invitations` (V12)

| Coluna | Tipo | Regra |
|---|---|---|
| `id` | `UUID` PK | gerado no banco |
| `workspace_id` | `UUID NOT NULL` | `REFERENCES workspaces ON DELETE CASCADE` |
| `email` | `TEXT NOT NULL` | normalizado (minúsculas, sem espaços nas pontas), 3 a 254 caracteres |
| `role` | `TEXT NOT NULL` | `CHECK (role IN ('EDITOR', 'VIEWER'))`: nunca OWNER |
| `code_hash` | `CHAR(64) NOT NULL UNIQUE` | SHA-256 do código em hexadecimal minúsculo; o código nunca é guardado |
| `created_by` | `UUID NOT NULL` | `REFERENCES users`; sempre a pessoa da sessão |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | `created_at + 7 dias`; `CHECK (expires_at > created_at)` |
| `accepted_at` | `TIMESTAMPTZ` | preenchido só pela função de aceite |
| `accepted_by` | `UUID` | `REFERENCES users`; preenchido só pela função de aceite |
| `revoked_at` | `TIMESTAMPTZ` | preenchido ao revogar |

Restrições: `email = lower(btrim(email))`; `code_hash ~ '^[0-9a-f]{64}$'`; `(accepted_at IS NULL) = (accepted_by IS NULL)`;
`NOT (accepted_at IS NOT NULL AND revoked_at IS NOT NULL)`.

Índices: único parcial `(workspace_id, email) WHERE accepted_at IS NULL AND revoked_at IS NULL` (um pendente por e-mail e
workspace) e `(workspace_id, created_at)`.

### Segurança (V12)

```sql
ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT ON invitations TO tostao_app;
GRANT UPDATE (revoked_at) ON invitations TO tostao_app;

CREATE POLICY invitations_read ON invitations FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_is_owner(workspace_id));
CREATE POLICY invitations_insert ON invitations FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_is_owner(workspace_id) AND created_by = app_user_id());
CREATE POLICY invitations_update ON invitations FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_is_owner(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_is_owner(workspace_id));
```

Sem `DELETE`. `accepted_at` e `accepted_by` sem privilégio de `UPDATE` para a aplicação.

## Mudanças em `workspace_members` (V12)

```sql
-- Um só OWNER por workspace (todo workspace existente já tem exatamente um).
CREATE UNIQUE INDEX workspace_members_one_owner ON workspace_members (workspace_id) WHERE role = 'OWNER';

GRANT UPDATE (role), DELETE ON workspace_members TO tostao_app;      -- sem INSERT: o vínculo nasce por register_user ou accept_invitation

CREATE POLICY members_update ON workspace_members FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_is_owner(workspace_id) AND role <> 'OWNER' AND user_id <> app_user_id())
    WITH CHECK (workspace_id = app_workspace_id() AND role IN ('EDITOR', 'VIEWER'));

CREATE POLICY members_delete ON workspace_members FOR DELETE TO tostao_app
    USING (workspace_id = app_workspace_id() AND role <> 'OWNER' AND (app_is_owner(workspace_id) OR user_id = app_user_id()));
```

## Mudanças em `users` (V12)

```sql
-- Os membros de um workspace veem nome e e-mail uns dos outros (o GRANT por coluna já esconde hash e versão de sessão).
CREATE POLICY users_comembers ON users FOR SELECT TO tostao_app
    USING (EXISTS (SELECT 1 FROM workspace_members m WHERE m.user_id = users.id AND m.workspace_id = app_workspace_id()));
```

## Função `app_is_owner` (V12)

`app_is_owner(p_workspace UUID) RETURNS BOOLEAN`: verdadeiro quando o usuário da transação é OWNER do workspace (como
`app_can_write`, só com `role = 'OWNER'`).

## Função `accept_invitation` (V13)

`accept_invitation(p_code_hash TEXT, p_user_id UUID) RETURNS TABLE (outcome TEXT, workspace_id UUID, workspace_name TEXT,
role TEXT)`, `SECURITY DEFINER`, `SET search_path = public, pg_temp`, `REVOKE ALL FROM PUBLIC`, `GRANT EXECUTE TO tostao_app`.

| `outcome` | Quando | Efeito |
|---|---|---|
| `INVALID` | pessoa sem conta; código inexistente; convite usado, revogado ou expirado; e-mail da conta diferente do convite | nenhum |
| `ALREADY_MEMBER` | a pessoa já é membro do workspace do convite | nenhum (o convite não é consumido) |
| `FULL` | o workspace já tem 20 membros | nenhum |
| `ACCEPTED` | todas as condições acima atendidas | vínculo criado com o papel do convite; `accepted_at` e `accepted_by` preenchidos; devolve o workspace e o papel |

O convite e a linha do workspace ficam travados (`FOR UPDATE`) durante a função: um segundo aceite do mesmo código recebe
`INVALID`, e os aceites do mesmo workspace são serializados (o limite de 20 nunca é ultrapassado).

## Regras que o serviço aplica

| Regra | Definição |
|---|---|
| Papel do convite | `EDITOR` ou `VIEWER`; `OWNER` e qualquer outro valor: 400, campo `role` |
| E-mail do convite | obrigatório, formato de e-mail, até 254 caracteres, normalizado; senão 400, campo `email` |
| Já é membro | 409 |
| Reconvidar | revoga o pendente do mesmo e-mail (inclusive expirado) e cria um novo, com novo código e nova validade |
| Limites | 20 membros ou 20 convites pendentes: 409, ao convidar; 20 membros: 409, ao aceitar |
| Validade | 7 dias (`now() + interval '7 days'`, no banco) |
| Pendentes na lista | `accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()` |
| Mudar papel | só o dono; alvo OWNER, o próprio dono ou papel OWNER: 409; papel igual: 200 sem efeito |
| Remover | o dono remove qualquer não-dono (204); o alvo OWNER: 409 |
| Sair | qualquer não-dono remove a si mesmo (204); não-dono removendo outro: 403 |
| Serialização do convite | `pg_advisory_xact_lock` por workspace |

## Respostas

### `MemberResponse`

`userId`, `name`, `email`, `role`, `joinedAt`. A lista vem com o OWNER primeiro e depois por data de entrada. Nunca traz senha,
hash nem versão de sessão.

### `InvitationCreatedResponse` (201)

`id`, `email`, `role`, `createdBy`, `createdAt`, `expiresAt` e `code`. **É a única resposta com o código.**

### `InvitationResponse` (lista de pendentes)

`id`, `email`, `role`, `createdBy`, `createdAt`, `expiresAt`. Sem o código.

### `AcceptInvitationResponse` (200)

`workspaceId`, `name`, `role`.

### `ChangeRoleRequest` e `CreateInvitationRequest` e `AcceptInvitationRequest`

| Pedido | Campo | Regra |
|---|---|---|
| `ChangeRoleRequest` | `role` | `EDITOR` ou `VIEWER`; obrigatório |
| `CreateInvitationRequest` | `email` | obrigatório; e-mail válido; até 254 |
| `CreateInvitationRequest` | `role` | `EDITOR` ou `VIEWER`; obrigatório |
| `AcceptInvitationRequest` | `code` | obrigatório e de até 200 caracteres; **sem validação de formato**: um código de tamanho errado responde 404 igual a um inexistente |

## Erros

| Situação | Resposta | Campo |
|---|---|---|
| Campo obrigatório ausente, tipo errado ou extra | 400 | o campo |
| E-mail inválido ou acima de 254 | 400 | `email` |
| Papel OWNER ou inválido | 400 | `role` |
| E-mail já é membro; limite de membros ou de convites; mexer no dono; mudar o próprio papel | 409 | |
| Aceitar sendo já membro; aceitar com o workspace cheio | 409 | |
| Código inexistente, expirado, usado, revogado ou de outro e-mail | 404 "Convite inválido ou expirado" | |
| Workspace, membro ou convite inexistente, de outro workspace ou malformado; não membro | 404 | |
| EDITOR ou VIEWER gerindo; não-dono removendo outro | 403 | |
| Sem sessão | 401 | |
