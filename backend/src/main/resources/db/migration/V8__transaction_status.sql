-- Tostão · V8: estado do lançamento (spec 009)
-- A coluna transactions.status já existe desde a V1 (PENDING ou CLEARED, padrão CLEARED, com CHECK). A V5
-- revogou o UPDATE geral e concedeu só as colunas editáveis, que não incluem o estado. Aqui entra apenas o
-- privilégio de atualizar o estado: o RLS de UPDATE (workspace e app_can_write, da V5) continua valendo, e
-- workspace, tipo, grupo da transferência, autor e data de criação continuam sem privilégio de UPDATE.
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V9, V10...

GRANT UPDATE (status) ON transactions TO tostao_app;
