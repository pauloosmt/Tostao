-- Tostão · V5: lançamentos (spec 004)
-- A tabela transactions já existe na V1. Aqui entram restrições, o tipo da categoria garantido pelo
-- banco, o índice das pontas de transferência, a escrita por papel no RLS, os privilégios por coluna e
-- a revogação do DELETE. Depois de aplicada, esta migration nunca é editada: mudanças vão em V6, V7...

-- Descrição já sem espaços nas pontas (1 a 200 caracteres); observação nula ou de 1 a 1.000;
-- módulo máximo de 10^12 centavos (R$ 10 bilhões), para as somas de saldo nunca estourarem BIGINT;
-- datas de 2000 a 2100, para barrar erro de digitação.
ALTER TABLE transactions ADD CONSTRAINT transactions_description_trimmed
    CHECK (description = btrim(description) AND char_length(description) BETWEEN 1 AND 200);
ALTER TABLE transactions ADD CONSTRAINT transactions_notes_length
    CHECK (notes IS NULL OR char_length(notes) BETWEEN 1 AND 1000);
ALTER TABLE transactions ADD CONSTRAINT transactions_amount_limit
    CHECK (abs(amount_cents) <= 1000000000000);
ALTER TABLE transactions ADD CONSTRAINT transactions_date_range
    CHECK (occurred_on BETWEEN DATE '2000-01-01' AND DATE '2100-12-31');

-- A categoria de uma receita é de receita e a de uma despesa é de despesa (usa a chave única
-- (id, workspace_id, kind) da V4). Transferência não tem categoria, então a chave não se aplica a ela.
-- A regra "toda receita e despesa tem categoria" é do serviço: o banco segue aceitando category_id nulo
-- nesses tipos, para as fixtures das specs anteriores e a importação da fase 3.
ALTER TABLE transactions ADD CONSTRAINT transactions_category_kind_fkey
    FOREIGN KEY (category_id, workspace_id, type) REFERENCES categories (id, workspace_id, kind);

-- Uma transferência tem no máximo uma saída (valor negativo) e uma entrada (valor positivo).
CREATE UNIQUE INDEX transactions_transfer_leg_key
    ON transactions (transfer_group_id, (amount_cents > 0)) WHERE transfer_group_id IS NOT NULL;

-- ============================================================================
-- Escrita por papel: só OWNER e EDITOR gravam lançamentos, também no banco (app_can_write vem da V3).
-- created_by é sempre o usuário da sessão.
-- ============================================================================

DROP POLICY transactions_tenant ON transactions;

CREATE POLICY transactions_read ON transactions
    FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id());

CREATE POLICY transactions_insert ON transactions
    FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id)
                AND created_by = app_user_id());

CREATE POLICY transactions_update ON transactions
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

-- Lançamento não se apaga: só se exclui de forma lógica (deleted_at). Sem política de DELETE e sem o
-- privilégio. O UPDATE fica restrito às colunas editáveis: workspace, tipo, grupo da transferência,
-- quem criou e a data de criação nunca mudam depois da inserção.
REVOKE DELETE ON transactions FROM tostao_app;
REVOKE UPDATE ON transactions FROM tostao_app;
GRANT UPDATE (account_id, category_id, amount_cents, occurred_on, description, notes, updated_at, deleted_at)
    ON transactions TO tostao_app;
