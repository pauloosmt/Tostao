# Research: Lançamento pendente

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Reaproveitar a coluna `status` que já existe

- **Decisão**: usar `transactions.status` (`TEXT NOT NULL DEFAULT 'CLEARED' CHECK (status IN ('PENDING',
  'CLEARED'))`, criada na `V1`). Nenhuma migration de dados e nenhum `ALTER` de coluna.
- **Motivo**: a coluna já existe, é `NOT NULL` com padrão `CLEARED` (todo lançamento atual é efetivado) e já
  tem o `CHECK` dos dois valores. A spec 004 a deixou reservada ("pendente é da fase 2").
- **Alternativas descartadas**: coluna booleana nova `is_pending` (duplicaria uma coluna que já existe); tipo
  enumerado do Postgres (o `CHECK` atual já garante o conjunto, e trocar exigiria `ALTER` desnecessário).

## 2. Migration `V8`: só o privilégio

- **Decisão**: `V8__transaction_status.sql` com `GRANT UPDATE (status) ON transactions TO tostao_app`.
- **Motivo**: a `V5` fez `REVOKE UPDATE ON transactions` e concedeu `UPDATE` só nas colunas editáveis (`account_id,
  category_id, amount_cents, occurred_on, description, notes, updated_at, deleted_at`), que não incluem `status`.
  Sem o `GRANT`, qualquer `UPDATE` do estado falharia com `permission denied`. A política de `UPDATE` do RLS
  (`workspace_id = app_workspace_id() AND app_can_write(workspace_id)`) já exige o papel de escrita e o workspace.
  O `INSERT` já aceita `status` (privilégio de tabela inteira).
- **Alternativas descartadas**: devolver o `UPDATE` geral à role (afrouxaria o que a `V5` fechou de propósito:
  workspace, tipo, grupo da transferência, autor e data de criação não mudam).

## 3. A regra única: pendente fora do realizado, por filtro de uma linha

- **Decisão**: `status = 'CLEARED'` (alias `t.` quando houver) em cada consulta que lê lançamentos para o
  realizado: `AccountService.SELECT_WITH_BALANCE` (saldo), as quatro do `DashboardService` (`DAILY_SQL`,
  `CATEGORY_SQL`, `ACCOUNT_BALANCES_SQL`, `MOVEMENT_SQL`), `BudgetService.PERIOD_SPENT_SQL` (compartilhada pelo
  semanal e pelo mensal) e as quatro do `InvoiceService` (`LINES_SQL`, `TOTAL_SQL`, `PAID_SQL`, `DAILY_SQL`).
- **Motivo**: simples, local e legível. Como `PERIOD_SPENT_SQL` e as consultas de fatura são compartilhadas dentro
  do módulo, um filtro basta para os dois orçamentos. Nos `left join transactions t on ...` do saldo e do
  dashboard o filtro vai **na condição do `join`** (não no `where`), para a conta sem lançamentos efetivados
  continuar aparecendo com saldo zero.
- **Alternativas descartadas**: view `cleared_transactions` (migration com RLS e privilégios próprios, e esconde a
  origem do dado); constante de texto compartilhada com o filtro (acopla módulos e esconde a regra).

## 4. Previsto: pendente e saldo previsto na conta, bloco no dashboard

- **Decisão**: `AccountResponse` ganha `pendingCents` (soma dos pendentes não excluídos, com sinal) e
  `projectedBalanceCents` (`balanceCents + pendingCents`), calculados na mesma consulta do saldo com `sum(...)
  filter (where status = ...)`. `BalanceReportResponse.Item` ganha os mesmos dois campos e a resposta ganha
  `totalProjectedCents`. `DashboardResponse` ganha o bloco `pending` (`incomeCents`, `expenseCents`, `count`),
  de uma consulta nova sobre o período.
- **Motivo**: aditivo (nenhum campo existente muda), uma só leitura para o realizado e o pendente, e o previsto
  total é somado em Java com `addExact`, como o saldo total já é.
- **Alternativas descartadas**: devolver só o previsto e deixar o cliente subtrair (perde o realizado); trocar o
  significado de `balanceCents` para o previsto (quebraria as specs 002 e 005).

## 5. Rota de efetivar

- **Decisão**: `POST /api/v1/workspaces/{wid}/transactions/{id}/clear`, sem corpo, devolve o lançamento (200).
  Voltar a pendente é pela edição (`PUT /{id}` com `status`), que também aceita efetivar com data e valor novos.
- **Motivo**: efetivar é a ação mais comum e não tem dados; uma rota própria evita reenviar o lançamento inteiro
  (a edição é completa: campos opcionais ausentes limpam o valor). `POST` de ação segue o padrão de
  `archive`/`restore` das contas e categorias. A reversão é rara, então a edição basta.
- **Alternativas descartadas**: `PATCH` (o projeto não usa); duas rotas (`clear` e `pend`) (a segunda quase não
  seria usada e a edição já cobre).

## 6. Efetivar: idempotente, atômico e sem conferir conta ou categoria

- **Decisão**: `@WorkspaceScoped(write = true) @Transactional public TransactionResponse clear(...)`: `peek` para
  achar o grupo, `lockUnit` (a linha, ou as duas pontas em ordem de id, `FOR UPDATE`, relendo se foi excluída),
  `UPDATE transactions SET status = 'CLEARED', updated_at = now() WHERE workspace_id = :w AND id IN (:ids) AND
  deleted_at IS NULL AND status <> 'CLEARED'`, e devolve `load`. Não chama `requireUsableAccount` nem
  `requireUsableCategory`.
- **Motivo**: a trava em ordem de id é a mesma da edição e da exclusão (sem impasse entre operações opostas na
  mesma transferência). O `AND status <> 'CLEARED'` torna a repetição sem efeito até em `updated_at` (SC-006) e
  faz dois pedidos simultâneos terminarem com as duas pontas efetivadas uma só vez. Efetivar não é passar a usar
  conta ou categoria (cenário 4 da história 2), então não as confere, como a edição já faz ao manter a mesma.
- **Alternativas descartadas**: ler, decidir em Java e gravar (corrida entre dois efetivar); recusar o segundo
  efetivar com 409 (a spec manda sucesso sem efeito).

## 7. Edição com estado opcional

- **Decisão**: `UpdateTransactionRequest` ganha `status` (opcional, enum). O `UPDATE` usa `status =
  coalesce(cast(:status as text), status)` nas duas pontas da transferência. Ausente mantém o atual (cenário 2
  da história 5), e valor desconhecido é recusado na leitura do corpo com o campo `status`.
- **Motivo**: a edição já é completa e atômica sobre as duas pontas; o estado entra no mesmo `UPDATE`.
- **Alternativas descartadas**: estado obrigatório na edição (um cliente antigo, que não conhece o estado,
  efetivaria sem querer os pendentes); rota separada só para mudar estado com data (a edição já cobre).

## 8. Criação com estado opcional

- **Decisão**: `CreateTransactionRequest` ganha `status` (opcional). Ausente grava `CLEARED`, igual a hoje. Na
  transferência, as duas pontas recebem o mesmo estado.
- **Motivo**: FR-001 e FR-017: o comportamento de quem não informa o estado não muda.

## 9. Listagem com filtro de estado e estado na resposta

- **Decisão**: `TransactionResponse` ganha `status`; `TransactionFilter` ganha `status`; a listagem aceita
  `?status=PENDING|CLEARED` combinado por "e". Valor inválido vira 400 no campo `status`.
- **Motivo**: FR-007. O filtro entra na lista de condições que já existe, parametrizado.

## 10. Operações simultâneas

- **Decisão**: sem mecanismo novo. Efetivar e editar travam a unidade (`FOR UPDATE`, ordem de id) antes de
  gravar; a exclusão já faz o mesmo. Dois efetivar na mesma transferência esperam um ao outro e o segundo não
  altera nada; efetivar e excluir: o segundo a travar relê a linha e recebe "não encontrado" se foi excluída.
- **Motivo**: FR-015. A trava existente já garante as duas pontas no mesmo estado, porque as duas são gravadas
  na mesma transação.

## 11. Desempenho

- **Decisão**: sem índice novo. A medição com 20.000 lançamentos (parte pendente) decide; se algo passar de 1 s,
  um índice parcial entra numa migration seguinte com a medição registrada.
- **Motivo**: princípio VII. O filtro `status = 'CLEARED'` seleciona quase todas as linhas, então não ajuda a
  restringir; as consultas já usam os índices por workspace, conta, data e categoria da `V1`.
