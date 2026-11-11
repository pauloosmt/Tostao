-- Tostão · V15: transferência recorrente (spec 014)
-- A recorrência da V10 só repetia receita e despesa. Agora também repete uma transferência entre duas contas do
-- workspace: ganha a conta de destino, a categoria passa a ser opcional (a transferência não tem categoria, como na
-- spec 004) e cada ocorrência é uma transferência comum de duas pontas. A geração das ocorrências (a função da V11) é
-- substituída aqui, na mesma assinatura: uma única definição do calendário serve à criação e à execução automática.
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V16, V17...

-- ============================================================================
-- recurrences
-- ============================================================================

ALTER TABLE recurrences ADD COLUMN destination_account_id UUID;
ALTER TABLE recurrences ALTER COLUMN category_id DROP NOT NULL;

ALTER TABLE recurrences DROP CONSTRAINT recurrences_type_check;
ALTER TABLE recurrences ADD CONSTRAINT recurrences_type_check CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER'));

-- O destino é do mesmo workspace, como a origem (a chave composta impede misturar workspaces).
ALTER TABLE recurrences ADD CONSTRAINT recurrences_destination_fkey
    FOREIGN KEY (destination_account_id, workspace_id) REFERENCES accounts (id, workspace_id);

-- A forma: transferência tem destino diferente da origem e nenhuma categoria; receita e despesa têm categoria e nenhum
-- destino. Com a categoria nula, a chave estrangeira (category_id, workspace_id, type) da V10 não se aplica.
ALTER TABLE recurrences ADD CONSTRAINT recurrences_shape CHECK (
    (type = 'TRANSFER' AND destination_account_id IS NOT NULL AND destination_account_id <> account_id
        AND category_id IS NULL)
    OR (type <> 'TRANSFER' AND destination_account_id IS NULL AND category_id IS NOT NULL));

-- Privilégios e políticas da V10 já cobrem a coluna nova (GRANT SELECT, INSERT na tabela; UPDATE só do encerramento): o
-- destino, como o resto da regra, nunca muda depois de criado.

-- ============================================================================
-- transactions
-- ============================================================================

-- A ocorrência pode ser de qualquer tipo. Continuam valendo: recorrência e número juntos, número >= 0 e nunca parcela.
ALTER TABLE transactions DROP CONSTRAINT transactions_occurrence_coherent;
ALTER TABLE transactions ADD CONSTRAINT transactions_occurrence_coherent CHECK (
    (recurrence_id IS NULL AND occurrence_number IS NULL)
    OR (recurrence_id IS NOT NULL AND occurrence_number IS NOT NULL AND occurrence_number >= 0
        AND installment_group_id IS NULL));

-- Uma ocorrência de transferência tem duas linhas com o mesmo número: uma saída (valor negativo) e uma entrada (valor
-- positivo), no desenho do índice das pontas da V5. Para receita e despesa o sinal é constante dentro da recorrência, então
-- continua valendo "uma linha por número". Inclui as excluídas: uma ocorrência (as duas pontas) que a pessoa excluiu continua
-- ocupando o número e não é recriada. É também o alvo do ON CONFLICT da geração.
DROP INDEX transactions_recurrence_occurrence_key;
CREATE UNIQUE INDEX transactions_recurrence_occurrence_key
    ON transactions (recurrence_id, occurrence_number, (amount_cents > 0)) WHERE recurrence_id IS NOT NULL;

-- ============================================================================
-- Geração das ocorrências (substitui a da V11; mesma assinatura, privilégios preservados)
--   * ativa: ended_at nulo e a conta de origem não arquivada; mais, para transferência, o destino existente e não arquivado e,
--     para receita e despesa, a categoria existente e não arquivada;
--   * as linhas das recorrências ficam travadas em modo compartilhado: o encerramento (FOR UPDATE) espera uma geração em curso;
--   * a data da ocorrência n é calculada a partir do início, sem acumular o ajuste de fim de mês; vai até 12 meses depois de
--     p_today, até o fim da recorrência e até 2100-12-31;
--   * receita e despesa: uma linha por número. Transferência: duas linhas por número, na MESMA instrução (a saída na conta de
--     origem, a entrada na de destino), ligadas por um grupo derivado da recorrência e do número. Com o grupo determinístico,
--     duas gerações simultâneas produzem as mesmas linhas e o resultado é sempre um par completo;
--   * idempotente: o índice único inclui as excluídas, então o que a pessoa excluiu não volta;
--   * devolve quantas LINHAS inseriu.
-- ============================================================================
CREATE OR REPLACE FUNCTION generate_recurrence_occurrences(p_today DATE, p_recurrence_id UUID DEFAULT NULL)
    RETURNS INTEGER
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_horizon DATE := (p_today + INTERVAL '12 months')::date;
    v_count   INTEGER;
BEGIN
    WITH r AS (
        SELECT rc.id, rc.workspace_id, rc.account_id, rc.destination_account_id, rc.category_id, rc.type, rc.amount_cents,
               rc.description, rc.notes, rc.frequency, rc.start_on, rc.end_on, rc.auto_clear, rc.created_by
        FROM recurrences rc
        JOIN accounts a        ON a.id = rc.account_id AND a.workspace_id = rc.workspace_id
        LEFT JOIN accounts d   ON d.id = rc.destination_account_id AND d.workspace_id = rc.workspace_id
        LEFT JOIN categories c ON c.id = rc.category_id AND c.workspace_id = rc.workspace_id
        WHERE rc.ended_at IS NULL AND NOT a.archived
          AND CASE WHEN rc.type = 'TRANSFER' THEN d.id IS NOT NULL AND NOT d.archived
                   ELSE c.id IS NOT NULL AND NOT c.archived END
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
    ),
    m AS (
        SELECT n.* FROM n
        WHERE n.on_date <= v_horizon
          AND (n.end_on IS NULL OR n.on_date <= n.end_on)
          AND n.on_date <= DATE '2100-12-31'
    ),
    legs AS (
        -- receita e despesa: uma linha, com o sinal do tipo
        SELECT m.id, m.num, 0 AS leg, m.workspace_id, m.account_id, m.category_id, m.type,
               CASE m.type WHEN 'EXPENSE' THEN -m.amount_cents ELSE m.amount_cents END AS amount,
               m.on_date, m.description, m.notes, m.auto_clear, m.created_by, NULL::uuid AS group_id
        FROM m WHERE m.type <> 'TRANSFER'
        UNION ALL
        -- transferência: a saída na conta de origem...
        SELECT m.id, m.num, 0, m.workspace_id, m.account_id, NULL::uuid, m.type, -m.amount_cents,
               m.on_date, m.description, m.notes, m.auto_clear, m.created_by, md5(m.id::text || ':' || m.num)::uuid
        FROM m WHERE m.type = 'TRANSFER'
        UNION ALL
        -- ... e a entrada na conta de destino, com o mesmo grupo
        SELECT m.id, m.num, 1, m.workspace_id, m.destination_account_id, NULL::uuid, m.type, m.amount_cents,
               m.on_date, m.description, m.notes, m.auto_clear, m.created_by, md5(m.id::text || ':' || m.num)::uuid
        FROM m WHERE m.type = 'TRANSFER'
    )
    INSERT INTO transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on, description,
                              notes, status, auto_clear, created_by, recurrence_id, occurrence_number, transfer_group_id)
    SELECT l.workspace_id, l.account_id, l.category_id, l.type, l.amount, l.on_date, l.description, l.notes,
           'PENDING', l.auto_clear, l.created_by, l.id, l.num, l.group_id
    FROM legs l
    ORDER BY l.id, l.num, l.leg
    ON CONFLICT (recurrence_id, occurrence_number, (amount_cents > 0)) WHERE recurrence_id IS NOT NULL DO NOTHING;

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RETURN v_count;
END
$$;
