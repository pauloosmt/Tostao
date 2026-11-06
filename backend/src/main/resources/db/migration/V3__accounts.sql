-- Tostão · V3: contas (spec 002)
-- A tabela accounts já existe na V1. Aqui entram restrições, nome único entre contas ativas,
-- escrita por papel no RLS e a revogação do DELETE. Depois de aplicada, esta migration nunca é
-- editada: mudanças vão em V4, V5...

-- Campos de cartão só existem em CREDIT_CARD.
ALTER TABLE accounts ADD CONSTRAINT accounts_card_fields_only_on_cards
    CHECK (type = 'CREDIT_CARD'
           OR (credit_limit_cents IS NULL AND closing_day IS NULL AND due_day IS NULL));

-- Módulo máximo de 10^12 centavos (R$ 10 bilhões): as somas de saldo nunca estouram BIGINT.
ALTER TABLE accounts ADD CONSTRAINT accounts_amount_limits
    CHECK (abs(initial_balance_cents) <= 1000000000000
           AND (credit_limit_cents IS NULL OR credit_limit_cents <= 1000000000000));

-- Nome já sem espaços nas pontas, de 1 a 100 caracteres.
ALTER TABLE accounts ADD CONSTRAINT accounts_name_trimmed
    CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 100);

-- Nome único entre as contas ativas do workspace, sem diferenciar maiúsculas.
CREATE UNIQUE INDEX accounts_active_name_key
    ON accounts (workspace_id, lower(name)) WHERE NOT archived;

-- ============================================================================
-- Escrita por papel: só OWNER e EDITOR gravam contas, também no banco.
-- ============================================================================

-- Verdadeiro quando o usuário da transação é OWNER ou EDITOR do workspace. Roda com os direitos de
-- quem chama; a política members_visible deixa o usuário ver a própria associação.
CREATE FUNCTION app_can_write(p_workspace UUID) RETURNS BOOLEAN
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (
        SELECT 1 FROM workspace_members m
        WHERE m.workspace_id = p_workspace
          AND m.user_id = app_user_id()
          AND m.role IN ('OWNER', 'EDITOR')
    )
$$;

DROP POLICY accounts_tenant ON accounts;

CREATE POLICY accounts_read ON accounts
    FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id());

CREATE POLICY accounts_insert ON accounts
    FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

CREATE POLICY accounts_update ON accounts
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

-- Conta não se apaga: só se arquiva. Sem política de DELETE e sem o privilégio.
REVOKE DELETE ON accounts FROM tostao_app;
