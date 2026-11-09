-- Tostão · V7: orçamento mensal (spec 007)
-- Limites de gasto por mês: uma categoria de despesa ou o geral (category_id nulo). Irmã de weekly_budgets
-- (V6), que não muda: os dois orçamentos são independentes. Aqui entram a tabela, as restrições, a
-- unicidade por escopo e mês, o RLS por papel e os privilégios. Depois de aplicada, esta migration nunca é
-- editada: mudanças vão em V8, V9...

CREATE TABLE monthly_budgets (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    -- primeiro dia do mês; 2000-01-01 abre 2000-01 e 2100-12-01 abre 2100-12
    month_start  DATE        NOT NULL,
    -- nulo = limite geral do mês
    category_id  UUID,
    -- sempre EXPENSE: existe só para a chave estrangeira composta abaixo
    kind         TEXT        NOT NULL DEFAULT 'EXPENSE',
    -- centavos; zero significa "não quero gastar nada"; módulo máximo de R$ 10 bilhões (como a V5 e a V6)
    amount_cents BIGINT      NOT NULL,
    created_by   UUID        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT monthly_budgets_kind_expense CHECK (kind = 'EXPENSE'),
    CONSTRAINT monthly_budgets_amount_range CHECK (amount_cents BETWEEN 0 AND 1000000000000),
    CONSTRAINT monthly_budgets_month_start_first_day
        CHECK (extract(day FROM month_start) = 1
               AND month_start BETWEEN DATE '2000-01-01' AND DATE '2100-12-01'),
    -- a categoria é do mesmo workspace e de despesa (usa a chave única da V4); com category_id nulo
    -- (limite geral) a chave não se aplica
    CONSTRAINT monthly_budgets_category_fkey
        FOREIGN KEY (category_id, workspace_id, kind) REFERENCES categories (id, workspace_id, kind),
    -- um limite por escopo e mês; NULLS NOT DISTINCT faz o limite geral (category_id nulo) também ser
    -- único. É o alvo do ON CONFLICT e serve a busca por (workspace_id, month_start)
    CONSTRAINT monthly_budgets_scope_key UNIQUE NULLS NOT DISTINCT (workspace_id, month_start, category_id)
);

ALTER TABLE monthly_budgets ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- Privilégios e escrita por papel: só OWNER e EDITOR gravam, também no banco (app_can_write vem da V3).
-- created_by é sempre o usuário da sessão. Um limite é um plano editável, não um lançamento: pode ser
-- apagado de verdade (a exclusão lógica é regra dos lançamentos). O UPDATE fica restrito ao valor:
-- workspace, categoria, mês, autor e data de criação nunca mudam depois da inserção.
-- ============================================================================

GRANT SELECT, INSERT, DELETE ON monthly_budgets TO tostao_app;
GRANT UPDATE (amount_cents, updated_at) ON monthly_budgets TO tostao_app;

CREATE POLICY monthly_budgets_read ON monthly_budgets
    FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id());

CREATE POLICY monthly_budgets_insert ON monthly_budgets
    FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id)
                AND created_by = app_user_id());

CREATE POLICY monthly_budgets_update ON monthly_budgets
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

CREATE POLICY monthly_budgets_delete ON monthly_budgets
    FOR DELETE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id));
