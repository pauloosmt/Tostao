-- Tostão · V14: importação de extratos (spec 013)
-- A pessoa envia o extrato do banco para uma conta, vê uma prévia e confirma; cada movimento vira um lançamento
-- efetivado. Para importar o mesmo extrato (ou um que se sobrepõe) sem duplicar, cada lançamento importado guarda a
-- chave do movimento do banco. Não há tabela nova: o extrato em si não é guardado.
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V15, V16...

ALTER TABLE transactions ADD COLUMN import_key TEXT;

-- Chave: 1 a 200 caracteres, sem espaços nas pontas. Nula significa "não veio de importação".
ALTER TABLE transactions ADD CONSTRAINT transactions_import_key_format
    CHECK (import_key IS NULL OR (import_key = btrim(import_key) AND char_length(import_key) BETWEEN 1 AND 200));

-- Só receita e despesa avulsas são importadas: nunca transferência, parcela nem ocorrência de recorrência.
ALTER TABLE transactions ADD CONSTRAINT transactions_import_key_coherent
    CHECK (import_key IS NULL
           OR (type IN ('INCOME', 'EXPENSE') AND installment_group_id IS NULL AND recurrence_id IS NULL));

-- Uma chave por conta, inclusive nas linhas excluídas: importar de novo nunca recria o que a pessoa excluiu (o mesmo
-- critério das ocorrências de recorrência, V10). É também o alvo do ON CONFLICT da confirmação. Parcial: as linhas
-- que não vêm de importação (chave nula) ficam fora do índice.
CREATE UNIQUE INDEX transactions_import_key_key
    ON transactions (account_id, import_key) WHERE import_key IS NOT NULL;

-- A aplicação já pode inserir em transactions (V2) e o UPDATE é restrito às colunas editáveis (V5): import_key não está
-- entre elas, então a chave é gravada na criação e nunca muda. Nenhum GRANT novo e nenhuma política nova: o RLS por
-- workspace da V5 cobre a coluna.
