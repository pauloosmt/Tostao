-- Tostão · V9: compra parcelada (spec 010)
-- Uma compra parcelada não tem tabela própria: cada parcela é uma despesa comum de transactions, e o plano é o
-- conjunto das despesas com o mesmo installment_group_id. Aqui entram as três colunas que ligam as parcelas, a
-- restrição que as mantém coerentes e o índice único do número da parcela no grupo.
-- Sem GRANT: o INSERT vale para a tabela inteira (V2) e o UPDATE continua restrito às colunas da V5 e à status
-- da V8, então grupo, número e total de parcelas nascem na criação e nunca mais mudam.
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V10, V11...

ALTER TABLE transactions
    ADD COLUMN installment_group_id UUID,
    ADD COLUMN installment_number   SMALLINT,
    ADD COLUMN installment_count    SMALLINT;

-- As três colunas juntas ou nenhuma. Só despesa é parcela (isso também impede transferência parcelada), o total
-- vai de 2 a 48 e o número fica entre 1 e o total.
ALTER TABLE transactions ADD CONSTRAINT installments_coherent CHECK (
    (installment_group_id IS NULL AND installment_number IS NULL AND installment_count IS NULL)
    OR (installment_group_id IS NOT NULL AND installment_number IS NOT NULL AND installment_count IS NOT NULL
        AND type = 'EXPENSE'
        AND installment_count BETWEEN 2 AND 48
        AND installment_number BETWEEN 1 AND installment_count));

-- Um número por grupo. Com workspace_id na frente, o mesmo índice serve a busca de todas as parcelas do grupo.
-- É parcial: lançamentos comuns (a esmagadora maioria) não entram nele.
CREATE UNIQUE INDEX transactions_installment_key
    ON transactions (workspace_id, installment_group_id, installment_number)
    WHERE installment_group_id IS NOT NULL;
