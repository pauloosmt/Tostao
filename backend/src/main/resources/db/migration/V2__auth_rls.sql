-- Tostão · V2: autenticação, role de aplicação e isolamento por workspace (RLS)
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V3, V4...
-- Placeholder do Flyway: ${app_password} (vem de DB_APP_PASSWORD, nunca do repositório).

-- ============================================================================
-- Parte 1: estruturas e role de aplicação
-- ============================================================================

-- Versão da sessão: "sair de todos os aparelhos" soma 1 e invalida os tokens anteriores.
ALTER TABLE users ADD COLUMN session_version INTEGER NOT NULL DEFAULT 0;

-- Contador de falhas de entrada por e-mail normalizado (existe também para e-mails sem conta).
-- Não é dado de workspace; só as funções abaixo leem e escrevem nela.
CREATE TABLE login_attempts (
    email          TEXT PRIMARY KEY,
    failed_count   INTEGER     NOT NULL,
    locked_until   TIMESTAMPTZ,
    last_failed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX login_attempts_last_failed_idx ON login_attempts (last_failed_at);
ALTER TABLE login_attempts ENABLE ROW LEVEL SECURITY;

-- Role de aplicação: não é dona das tabelas e não ignora RLS.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tostao_app') THEN
        CREATE ROLE tostao_app LOGIN NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE
            PASSWORD '${app_password}';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO tostao_app;
-- Nunca password_hash nem session_version: só as funções SECURITY DEFINER acessam essas colunas.
GRANT SELECT (id, name, email, created_at) ON users TO tostao_app;
GRANT SELECT ON workspaces, workspace_members TO tostao_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounts, categories, transactions TO tostao_app;
-- Nenhum privilégio em login_attempts nem em flyway_schema_history.
-- Nenhum INSERT direto em users, workspaces e workspace_members: o cadastro passa por register_user.

-- ============================================================================
-- Parte 2: políticas de RLS
-- ============================================================================

-- Contexto da transação, definido por set_config(..., true) (equivale a SET LOCAL).
-- Ausente ou vazio vira NULL, e NULL nunca é igual a nada: sem contexto, nenhuma linha é visível.
CREATE FUNCTION app_user_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

CREATE FUNCTION app_workspace_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.workspace_id', true), '')::uuid $$;

CREATE POLICY users_self ON users
    FOR SELECT TO tostao_app
    USING (id = app_user_id());

CREATE POLICY members_visible ON workspace_members
    FOR SELECT TO tostao_app
    USING (user_id = app_user_id() OR workspace_id = app_workspace_id());

CREATE POLICY workspaces_visible ON workspaces
    FOR SELECT TO tostao_app
    USING (
        id = app_workspace_id()
        OR EXISTS (
            SELECT 1 FROM workspace_members m
            WHERE m.workspace_id = workspaces.id AND m.user_id = app_user_id()
        )
    );

CREATE POLICY accounts_tenant ON accounts
    FOR ALL TO tostao_app
    USING (workspace_id = app_workspace_id())
    WITH CHECK (workspace_id = app_workspace_id());

CREATE POLICY categories_tenant ON categories
    FOR ALL TO tostao_app
    USING (workspace_id = app_workspace_id())
    WITH CHECK (workspace_id = app_workspace_id());

CREATE POLICY transactions_tenant ON transactions
    FOR ALL TO tostao_app
    USING (workspace_id = app_workspace_id())
    WITH CHECK (workspace_id = app_workspace_id());

-- ============================================================================
-- Parte 3: funções SECURITY DEFINER estreitas
-- Rodam como o dono das tabelas (que ignora RLS). Únicos caminhos que enxergam dados antes de o
-- workspace ser conhecido. Cada uma fixa o search_path e só a role de aplicação pode executá-la.
-- ============================================================================

-- Cadastro atômico: usuário, workspace pessoal e associação OWNER numa chamada.
-- E-mail já existente devolve zero linhas (ON CONFLICT DO NOTHING), sem levantar exceção.
CREATE FUNCTION register_user(p_email TEXT, p_password_hash TEXT, p_name TEXT)
    RETURNS TABLE (new_user_id UUID, new_workspace_id UUID)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_user      UUID;
    v_workspace UUID;
BEGIN
    INSERT INTO users (email, password_hash, name)
    VALUES (lower(trim(p_email)), p_password_hash, p_name)
    ON CONFLICT ((lower(email))) DO NOTHING
    RETURNING id INTO v_user;

    IF v_user IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO workspaces (name) VALUES ('Pessoal') RETURNING id INTO v_workspace;
    INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (v_workspace, v_user, 'OWNER');

    RETURN QUERY SELECT v_user, v_workspace;
END
$$;

-- Único caminho que lê o hash da senha.
CREATE FUNCTION find_user_for_login(p_email TEXT)
    RETURNS TABLE (id UUID, name TEXT, email TEXT, password_hash TEXT, session_version INTEGER)
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT u.id, u.name, u.email, u.password_hash, u.session_version
    FROM users u
    WHERE lower(u.email) = lower(trim(p_email))
$$;

-- Instante até o qual o e-mail está bloqueado, ou NULL se não há bloqueio ativo.
CREATE FUNCTION login_lockout_status(p_email TEXT)
    RETURNS TIMESTAMPTZ
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT la.locked_until
    FROM login_attempts la
    WHERE la.email = lower(trim(p_email)) AND la.locked_until > now()
$$;

-- Soma uma falha de forma atômica. Bloqueio vencido recomeça a contagem em 1; ao chegar em 5
-- falhas, bloqueia por 15 minutos. Também apaga linhas antigas sem bloqueio ativo.
CREATE FUNCTION record_login_failure(p_email TEXT)
    RETURNS TIMESTAMPTZ
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_email  TEXT := lower(trim(p_email));
    v_locked TIMESTAMPTZ;
BEGIN
    DELETE FROM login_attempts
    WHERE last_failed_at < now() - interval '24 hours'
      AND (locked_until IS NULL OR locked_until <= now());

    INSERT INTO login_attempts AS la (email, failed_count, locked_until, last_failed_at)
    VALUES (v_email, 1, NULL, now())
    ON CONFLICT (email) DO UPDATE SET
        failed_count = CASE
            WHEN la.locked_until IS NOT NULL AND la.locked_until <= now() THEN 1
            ELSE la.failed_count + 1
        END,
        locked_until = CASE
            WHEN la.locked_until IS NOT NULL AND la.locked_until <= now() THEN NULL
            WHEN la.locked_until > now() THEN la.locked_until
            WHEN la.failed_count + 1 >= 5 THEN now() + interval '15 minutes'
            ELSE NULL
        END,
        last_failed_at = now()
    RETURNING la.locked_until INTO v_locked;

    RETURN v_locked;
END
$$;

CREATE FUNCTION reset_login_attempts(p_email TEXT)
    RETURNS VOID
    LANGUAGE sql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    DELETE FROM login_attempts WHERE email = lower(trim(p_email))
$$;

-- Versão atual da sessão do usuário, ou NULL se ele não existe.
CREATE FUNCTION session_version_of(p_user_id UUID)
    RETURNS INTEGER
    LANGUAGE sql STABLE SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    SELECT u.session_version FROM users u WHERE u.id = p_user_id
$$;

-- "Sair de todos os aparelhos": soma 1 e devolve a nova versão.
CREATE FUNCTION revoke_sessions(p_user_id UUID)
    RETURNS INTEGER
    LANGUAGE sql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
    UPDATE users SET session_version = session_version + 1
    WHERE id = p_user_id
    RETURNING session_version
$$;

REVOKE ALL ON FUNCTION register_user(TEXT, TEXT, TEXT)   FROM PUBLIC;
REVOKE ALL ON FUNCTION find_user_for_login(TEXT)         FROM PUBLIC;
REVOKE ALL ON FUNCTION login_lockout_status(TEXT)        FROM PUBLIC;
REVOKE ALL ON FUNCTION record_login_failure(TEXT)        FROM PUBLIC;
REVOKE ALL ON FUNCTION reset_login_attempts(TEXT)        FROM PUBLIC;
REVOKE ALL ON FUNCTION session_version_of(UUID)          FROM PUBLIC;
REVOKE ALL ON FUNCTION revoke_sessions(UUID)             FROM PUBLIC;

GRANT EXECUTE ON FUNCTION register_user(TEXT, TEXT, TEXT)   TO tostao_app;
GRANT EXECUTE ON FUNCTION find_user_for_login(TEXT)         TO tostao_app;
GRANT EXECUTE ON FUNCTION login_lockout_status(TEXT)        TO tostao_app;
GRANT EXECUTE ON FUNCTION record_login_failure(TEXT)        TO tostao_app;
GRANT EXECUTE ON FUNCTION reset_login_attempts(TEXT)        TO tostao_app;
GRANT EXECUTE ON FUNCTION session_version_of(UUID)          TO tostao_app;
GRANT EXECUTE ON FUNCTION revoke_sessions(UUID)             TO tostao_app;
