-- Tostão · V12: membros e convites (spec 012)
-- O dono de um workspace convida pessoas por e-mail, com o papel EDITOR ou VIEWER; quem é convidado aceita com um
-- código secreto de uso único (a função de aceite está na V13). Aqui entram a tabela de convites, as políticas que
-- deixam só o dono mexer neles e nos vínculos dos outros membros, o índice que garante um só OWNER por workspace e
-- a leitura de nome e e-mail entre os membros de um mesmo workspace.
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V13, V14...

-- Verdadeiro quando o usuário da transação é OWNER do workspace. Como app_can_write (V3), roda com os direitos de
-- quem chama; a política members_visible deixa o usuário ver a própria associação.
CREATE FUNCTION app_is_owner(p_workspace UUID) RETURNS BOOLEAN
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (
        SELECT 1 FROM workspace_members m
        WHERE m.workspace_id = p_workspace
          AND m.user_id = app_user_id()
          AND m.role = 'OWNER'
    )
$$;

-- ============================================================================
-- Convites
-- ============================================================================

CREATE TABLE invitations (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    -- normalizado como no cadastro: minúsculas e sem espaços nas pontas
    email        TEXT        NOT NULL,
    -- nunca OWNER: um workspace tem um só dono, o que o criou
    role         TEXT        NOT NULL,
    -- SHA-256 do código em hexadecimal; o código em si nunca é guardado
    code_hash    CHAR(64)    NOT NULL,
    created_by   UUID        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    -- preenchidos só pela função de aceite
    accepted_at  TIMESTAMPTZ,
    accepted_by  UUID REFERENCES users (id),
    revoked_at   TIMESTAMPTZ,
    CONSTRAINT invitations_role_check CHECK (role IN ('EDITOR', 'VIEWER')),
    CONSTRAINT invitations_email_normalized
        CHECK (email = lower(btrim(email)) AND char_length(email) BETWEEN 3 AND 254),
    CONSTRAINT invitations_code_hash_format CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT invitations_expiry_after_creation CHECK (expires_at > created_at),
    CONSTRAINT invitations_accepted_pair CHECK ((accepted_at IS NULL) = (accepted_by IS NULL)),
    CONSTRAINT invitations_not_accepted_and_revoked CHECK (accepted_at IS NULL OR revoked_at IS NULL),
    CONSTRAINT invitations_code_hash_key UNIQUE (code_hash)
);

-- Um convite pendente por e-mail e workspace: convidar de novo revoga o anterior antes de inserir o novo.
CREATE UNIQUE INDEX invitations_one_pending_key
    ON invitations (workspace_id, email) WHERE accepted_at IS NULL AND revoked_at IS NULL;
CREATE INDEX invitations_workspace_idx ON invitations (workspace_id, created_at);

ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;

-- Só o OWNER do workspace do contexto enxerga e mexe nos convites; EDITOR e VIEWER não veem nem os e-mails pendentes.
-- O convite é um registro: não se apaga (usado ou revogado fica no histórico) e a aplicação só revoga (revoked_at).
GRANT SELECT, INSERT ON invitations TO tostao_app;
GRANT UPDATE (revoked_at) ON invitations TO tostao_app;

CREATE POLICY invitations_read ON invitations
    FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_is_owner(workspace_id));

CREATE POLICY invitations_insert ON invitations
    FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_is_owner(workspace_id)
                AND created_by = app_user_id());

CREATE POLICY invitations_update ON invitations
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_is_owner(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_is_owner(workspace_id));

-- ============================================================================
-- Vínculos (workspace_members)
-- ============================================================================

-- Um só OWNER por workspace (todo workspace existente já tem exatamente um: o register_user o cria assim). Garante,
-- no banco, que ninguém vire OWNER nem que um segundo dono apareça, qualquer que seja o defeito do serviço.
CREATE UNIQUE INDEX workspace_members_one_owner ON workspace_members (workspace_id) WHERE role = 'OWNER';

-- O vínculo nasce só por register_user (V2) e accept_invitation (V13): sem INSERT para a aplicação. O papel muda e o
-- vínculo termina por estes dois privilégios, sob as políticas abaixo.
GRANT UPDATE (role), DELETE ON workspace_members TO tostao_app;

-- Só o dono muda o papel de um membro que não é dono e não é ele mesmo, e só entre EDITOR e VIEWER.
CREATE POLICY members_update ON workspace_members
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_is_owner(workspace_id)
           AND role <> 'OWNER' AND user_id <> app_user_id())
    WITH CHECK (workspace_id = app_workspace_id() AND role IN ('EDITOR', 'VIEWER'));

-- O dono remove qualquer membro que não é dono; um membro que não é dono sai (remove o próprio vínculo). O dono
-- nunca é removido nem sai.
CREATE POLICY members_delete ON workspace_members
    FOR DELETE TO tostao_app
    USING (workspace_id = app_workspace_id() AND role <> 'OWNER'
           AND (app_is_owner(workspace_id) OR user_id = app_user_id()));

-- ============================================================================
-- Usuários
-- ============================================================================

-- Os membros de um workspace veem nome e e-mail uns dos outros. O GRANT por coluna da V2 (id, name, email,
-- created_at) continua escondendo o hash da senha e a versão da sessão.
CREATE POLICY users_comembers ON users
    FOR SELECT TO tostao_app
    USING (EXISTS (
        SELECT 1 FROM workspace_members m
        WHERE m.user_id = users.id AND m.workspace_id = app_workspace_id()
    ));
