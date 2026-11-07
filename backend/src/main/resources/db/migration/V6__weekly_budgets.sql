-- Tostão · V6: orçamento semanal (spec 006)
-- Limites de gasto por semana: uma categoria de despesa ou o geral (category_id nulo). Aqui entram a
-- tabela, as restrições, a unicidade por escopo e semana, o RLS por papel e os privilégios. Depois de
-- aplicada, esta migration nunca é editada: mudanças vão em V7, V8...

CREATE TABLE weekly_budgets (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    -- segunda-feira da semana ISO; 2000-01-03 abre a semana 2000-W01 e 2100-12-27 abre a 2100-W52
    week_start   DATE        NOT NULL,
    -- nulo = limite geral da semana
    category_id  UUID,
    -- sempre EXPENSE: existe só para a chave estrangeira composta abaixo
    kind         TEXT        NOT NULL DEFAULT 'EXPENSE',
    -- centavos; zero significa "não quero gastar nada"; módulo máximo de R$ 10 bilhões (como a V5)
    amount_cents BIGINT      NOT NULL,
    created_by   UUID        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT weekly_budgets_kind_expense CHECK (kind = 'EXPENSE'),
    CONSTRAINT weekly_budgets_amount_range CHECK (amount_cents BETWEEN 0 AND 1000000000000),
    CONSTRAINT weekly_budgets_week_start_monday
        CHECK (extract(isodow FROM week_start) = 1
               AND week_start BETWEEN DATE '2000-01-03' AND DATE '2100-12-27'),
    -- a categoria é do mesmo workspace e de despesa (usa a chave única da V4); com category_id nulo
    -- (limite geral) a chave não se aplica
    CONSTRAINT weekly_budgets_category_fkey
        FOREIGN KEY (category_id, workspace_id, kind) REFERENCES categories (id, workspace_id, kind),
    -- um limite por escopo e semana; NULLS NOT DISTINCT faz o limite geral (category_id nulo) também ser
    -- único. É o alvo do ON CONFLICT e serve a busca por (workspace_id, week_start)
    CONSTRAINT weekly_budgets_scope_key UNIQUE NULLS NOT DISTINCT (workspace_id, week_start, category_id)
);

ALTER TABLE weekly_budgets ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- Privilégios e escrita por papel: só OWNER e EDITOR gravam, também no banco (app_can_write vem da V3).
-- created_by é sempre o usuário da sessão. Um limite é um plano editável, não um lançamento: pode ser
-- apagado de verdade (a exclusão lógica é regra dos lançamentos). O UPDATE fica restrito ao valor:
-- workspace, categoria, semana, autor e data de criação nunca mudam depois da inserção.
-- ============================================================================

GRANT SELECT, INSERT, DELETE ON weekly_budgets TO tostao_app;
GRANT UPDATE (amount_cents, updated_at) ON weekly_budgets TO tostao_app;

CREATE POLICY weekly_budgets_read ON weekly_budgets
    FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id());

CREATE POLICY weekly_budgets_insert ON weekly_budgets
    FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id)
                AND created_by = app_user_id());

CREATE POLICY weekly_budgets_update ON weekly_budgets
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

CREATE POLICY weekly_budgets_delete ON weekly_budgets
    FOR DELETE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id));
