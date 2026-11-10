-- Tostão · V11: execução automática (spec 011)
-- A rotina que gera as ocorrências das recorrências e efetiva o que venceu roda sem pessoa logada, então não há
-- app.user_id nem app.workspace_id para o RLS. Em vez de dar BYPASSRLS à role da aplicação (o que afrouxaria o
-- isolamento de todas as requisições), a rotina chama duas funções SECURITY DEFINER estreitas, no mesmo padrão
-- das funções da V2: fixam o search_path, só a role da aplicação as executa, fazem tudo em lote (uma instrução
-- cada, sem bloco de exceção) e só produzem o que a regra descreve. Cada ocorrência gerada copia workspace, conta,
-- categoria e autor da própria recorrência, e as chaves compostas impedem misturar workspaces.
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V12, V13...

-- ============================================================================
-- Gera as ocorrências que faltam e devolve quantas inseriu.
-- p_recurrence_id nulo = todas as recorrências ativas; informado = só a dela (a criação da recorrência usa a mesma
-- função, então há uma única definição do calendário).
--   * ativa: ended_at nulo, conta e categoria não arquivadas;
--   * as linhas das recorrências ficam travadas em modo compartilhado: o encerramento (FOR UPDATE) espera uma geração
--     em curso, e uma geração que chega depois do encerramento relê a linha e a deixa de fora;
--   * a data da ocorrência n é sempre calculada a partir do início, sem acumular o ajuste de fim de mês;
--   * vai até 12 meses depois de p_today, até o fim da recorrência e até 2100-12-31;
--   * idempotente: o índice único (recurrence_id, occurrence_number) inclui as excluídas, então uma ocorrência que a
--     pessoa excluiu não volta, e duas execuções simultâneas nunca duplicam.
-- ============================================================================
CREATE FUNCTION generate_recurrence_occurrences(p_today DATE, p_recurrence_id UUID DEFAULT NULL)
    RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_horizon DATE := (p_today + INTERVAL '12 months')::date;
    v_count   INTEGER;
BEGIN
    WITH r AS (
        SELECT rc.id, rc.workspace_id, rc.account_id, rc.category_id, rc.type, rc.amount_cents, rc.description,
               rc.notes, rc.frequency, rc.start_on, rc.end_on, rc.auto_clear, rc.created_by
        FROM recurrences rc
        JOIN accounts a   ON a.id = rc.account_id   AND a.workspace_id = rc.workspace_id
        JOIN categories c ON c.id = rc.category_id  AND c.workspace_id = rc.workspace_id
        WHERE rc.ended_at IS NULL AND NOT a.archived AND NOT c.archived
          AND (p_recurrence_id IS NULL OR rc.id = p_recurrence_id)
        FOR SHARE OF rc
    ),
    n AS (
        SELECT r.*, g AS num,
               CASE r.frequency
                   WHEN 'WEEKLY'  THEN r.start_on + 7 * g
                   WHEN 'MONTHLY' THEN (r.start_on + g * INTERVAL '1 month')::date
                   ELSE                (r.start_on + g * INTERVAL '1 year')::date
               END AS on_date
        FROM r
        CROSS JOIN LATERAL generate_series(0,
                 CASE r.frequency
                     WHEN 'WEEKLY'  THEN (v_horizon - r.start_on) / 7
                     WHEN 'MONTHLY' THEN ((EXTRACT(YEAR FROM v_horizon) - EXTRACT(YEAR FROM r.start_on)) * 12
                                          + EXTRACT(MONTH FROM v_horizon) - EXTRACT(MONTH FROM r.start_on))::int
                     ELSE                (EXTRACT(YEAR FROM v_horizon) - EXTRACT(YEAR FROM r.start_on))::int
                 END) AS g
    )
    INSERT INTO transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on, description,
                              notes, status, auto_clear, created_by, recurrence_id, occurrence_number)
    SELECT n.workspace_id, n.account_id, n.category_id, n.type,
           CASE n.type WHEN 'EXPENSE' THEN -n.amount_cents ELSE n.amount_cents END,
           n.on_date, n.description, n.notes, 'PENDING', n.auto_clear, n.created_by, n.id, n.num
    FROM n
    WHERE n.on_date <= v_horizon
      AND (n.end_on IS NULL OR n.on_date <= n.end_on)
      AND n.on_date <= DATE '2100-12-31'
    ON CONFLICT (recurrence_id, occurrence_number) WHERE recurrence_id IS NOT NULL DO NOTHING;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

-- ============================================================================
-- Efetiva o que venceu e devolve quantas linhas mudou: pendente, não excluído, com o marcador de efetivação
-- automática e a data até p_today. Mesmo efeito da efetivação manual (spec 009): só status, auto_clear (desligado:
-- o marcador é de uso único) e updated_at mudam. As duas pontas de uma transferência têm sempre a mesma data e o
-- mesmo marcador, então são efetivadas juntas. As linhas são travadas em ordem de id, como as operações do serviço
-- (edição, exclusão, efetivação), então nunca formam um ciclo de espera com elas; uma linha que a pessoa efetivou
-- ou excluiu enquanto a rotina esperava a trava sai do conjunto.
-- ============================================================================
CREATE FUNCTION auto_clear_due(p_today DATE)
    RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH due AS (
        SELECT id FROM transactions
        WHERE auto_clear AND status = 'PENDING' AND deleted_at IS NULL AND occurred_on <= p_today
        ORDER BY id
        FOR UPDATE
    )
    UPDATE transactions t
    SET status = 'CLEARED', auto_clear = false, updated_at = now()
    FROM due
    WHERE t.id = due.id;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;

REVOKE ALL ON FUNCTION generate_recurrence_occurrences(DATE, UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION auto_clear_due(DATE)                        FROM PUBLIC;

GRANT EXECUTE ON FUNCTION generate_recurrence_occurrences(DATE, UUID) TO tostao_app;
GRANT EXECUTE ON FUNCTION auto_clear_due(DATE)                        TO tostao_app;
