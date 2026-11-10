-- Tostão · V10: recorrências e efetivação automática (spec 011)
-- Uma recorrência é um registro próprio (ao contrário do plano de parcelas da V9): tem frequência, início, fim e
-- estado, que nenhuma ocorrência carrega. As ocorrências são despesas ou receitas comuns de transactions, ligadas à
-- recorrência por recurrence_id e occurrence_number. O marcador auto_clear faz um lançamento pendente ser efetivado
-- sozinho quando a data chega; é de uso único (efetivado nunca o tem ligado, garantido por CHECK).
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V11, V12...

CREATE TABLE recurrences (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    account_id   UUID        NOT NULL,
    category_id  UUID        NOT NULL,
    -- só receita ou despesa; transferência recorrente fica para depois
    type         TEXT        NOT NULL,
    -- sempre positivo: o sinal vem do tipo; módulo máximo de R$ 10 bilhões (como a V5)
    amount_cents BIGINT      NOT NULL,
    description  TEXT        NOT NULL,
    notes        TEXT,
    frequency    TEXT        NOT NULL,
    start_on     DATE        NOT NULL,
    end_on       DATE,
    -- as ocorrências nascem pendentes com este marcador
    auto_clear   BOOLEAN     NOT NULL DEFAULT false,
    -- nulo enquanto a recorrência está ativa
    ended_at     TIMESTAMPTZ,
    created_by   UUID        NOT NULL REFERENCES users (id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT recurrences_type_check CHECK (type IN ('INCOME', 'EXPENSE')),
    CONSTRAINT recurrences_frequency_check CHECK (frequency IN ('WEEKLY', 'MONTHLY', 'YEARLY')),
    CONSTRAINT recurrences_amount_range CHECK (amount_cents BETWEEN 1 AND 1000000000000),
    CONSTRAINT recurrences_description_trimmed
        CHECK (description = btrim(description) AND char_length(description) BETWEEN 1 AND 200),
    CONSTRAINT recurrences_notes_length CHECK (notes IS NULL OR char_length(notes) BETWEEN 1 AND 1000),
    -- datas de 2000 a 2100, como os lançamentos (V5)
    CONSTRAINT recurrences_start_range CHECK (start_on BETWEEN DATE '2000-01-01' AND DATE '2100-12-31'),
    CONSTRAINT recurrences_end_range
        CHECK (end_on IS NULL OR (end_on >= start_on AND end_on <= DATE '2100-12-31')),
    -- alvo da chave composta de transactions: a ocorrência é do mesmo workspace da recorrência
    CONSTRAINT recurrences_id_workspace_key UNIQUE (id, workspace_id),
    -- conta e categoria do mesmo workspace; a categoria tem o tipo da recorrência (chave única da V4)
    CONSTRAINT recurrences_account_fkey FOREIGN KEY (account_id, workspace_id) REFERENCES accounts (id, workspace_id),
    CONSTRAINT recurrences_category_fkey
        FOREIGN KEY (category_id, workspace_id, type) REFERENCES categories (id, workspace_id, kind)
);
CREATE INDEX recurrences_workspace_idx ON recurrences (workspace_id, created_at);

ALTER TABLE recurrences ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- Privilégios e escrita por papel: só OWNER e EDITOR gravam, também no banco (app_can_write vem da V3).
-- created_by é sempre o usuário da sessão. A regra da recorrência não é editável: o UPDATE fica restrito ao
-- encerramento (ended_at e updated_at). Não se apaga: encerra-se.
-- ============================================================================

GRANT SELECT, INSERT ON recurrences TO tostao_app;
GRANT UPDATE (ended_at, updated_at) ON recurrences TO tostao_app;

CREATE POLICY recurrences_read ON recurrences
    FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id());

CREATE POLICY recurrences_insert ON recurrences
    FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id)
                AND created_by = app_user_id());

CREATE POLICY recurrences_update ON recurrences
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

-- ============================================================================
-- Colunas novas em transactions
-- ============================================================================

ALTER TABLE transactions
    ADD COLUMN auto_clear        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN recurrence_id     UUID,
    ADD COLUMN occurrence_number INTEGER;

-- O marcador só vale para pendente: efetivado nunca o tem ligado. Toda instrução que efetiva um lançamento (a
-- efetivação manual, a edição e a rotina automática) desliga o marcador junto.
ALTER TABLE transactions ADD CONSTRAINT transactions_auto_clear_pending
    CHECK (NOT auto_clear OR status = 'PENDING');

-- Recorrência de origem e número juntos ou nenhum; só receita ou despesa é ocorrência; uma linha nunca é parcela e
-- ocorrência ao mesmo tempo.
ALTER TABLE transactions ADD CONSTRAINT transactions_occurrence_coherent CHECK (
    (recurrence_id IS NULL AND occurrence_number IS NULL)
    OR (recurrence_id IS NOT NULL AND occurrence_number IS NOT NULL AND occurrence_number >= 0
        AND type IN ('INCOME', 'EXPENSE') AND installment_group_id IS NULL));

-- A ocorrência é do mesmo workspace da recorrência.
ALTER TABLE transactions ADD CONSTRAINT transactions_recurrence_fkey
    FOREIGN KEY (recurrence_id, workspace_id) REFERENCES recurrences (id, workspace_id);

-- Uma ocorrência por número. Inclui as linhas excluídas: uma ocorrência que a pessoa excluiu continua ocupando o
-- seu número e não é recriada. É também o alvo do ON CONFLICT da geração.
CREATE UNIQUE INDEX transactions_recurrence_occurrence_key
    ON transactions (recurrence_id, occurrence_number) WHERE recurrence_id IS NOT NULL;

-- Só os lançamentos marcados: a rotina de efetivação não varre a tabela inteira.
CREATE INDEX transactions_auto_clear_idx
    ON transactions (occurred_on) WHERE auto_clear AND deleted_at IS NULL;

-- O marcador pode ser ligado e desligado pela edição. recurrence_id e occurrence_number nascem na inserção e nunca
-- mudam: sem privilégio de UPDATE, como as colunas de parcela da V9.
GRANT UPDATE (auto_clear) ON transactions TO tostao_app;
