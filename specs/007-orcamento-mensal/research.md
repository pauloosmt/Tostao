# Research: Orçamento mensal

Cada decisão segue o formato: decisão, motivo e alternativas descartadas. Onde a decisão é a mesma da
[`006`](../006-orcamento-semanal/research.md), só aponto a diferença.

## 1. Tabela própria, `monthly_budgets`, em vez de generalizar `weekly_budgets`

- **Decisão**: migration `V7__monthly_budgets.sql` cria `monthly_budgets` ao lado de `weekly_budgets`. A tabela
  da `006` não muda.
- **Motivo**: migration já aplicada nunca é editada (princípio V), e generalizar exigiria `ALTER` na tabela da
  `006` (nova coluna de tipo de período, `CHECK` de data diferente para semana e mês, unicidade, testes e
  contrato dela). O FR-023 pede orçamentos independentes: com tabelas, ids e rotas separados, um id semanal
  numa rota mensal dá 404 sem nenhum código extra (cenário 6 da história 5).
- **Alternativas descartadas**: uma tabela `budgets` com `period_type` e `period_start` (reescreve a `006`
  entregue e testada); reaproveitar `weekly_budgets` guardando o mês na coluna `week_start` (mistura dois
  significados na mesma coluna e quebra o `CHECK` de segunda-feira).

## 2. Guardar o primeiro dia do mês

- **Decisão**: `month_start DATE NOT NULL` com `CHECK (extract(day from month_start) = 1 AND month_start
  BETWEEN DATE '2000-01-01' AND DATE '2100-12-01')`.
- **Motivo**: igual à `006` (`week_start`): valor de calendário simples, comparável com `occurred_on` sem
  converter. `AAAA-MM` é só o formato de entrada e saída; `Period.ofMonth` da `005` valida. Como a data
  guardada é sempre o dia 1, somar meses nunca "escorrega" para o último dia (não há 31 de janeiro + 1 mês).
- **Alternativas descartadas**: texto `AAAA-MM` (reconversão em toda consulta); duas colunas ano e mês.

## 3. Unicidade, categoria de despesa, RLS e `DELETE`

- **Decisão**: idênticos à `006` (decisões 2, 3 e 4 dela): `UNIQUE NULLS NOT DISTINCT (workspace_id,
  month_start, category_id)`; coluna `kind` com `CHECK (kind = 'EXPENSE')` e chave estrangeira composta
  `(category_id, workspace_id, kind)`; `GRANT SELECT, INSERT, DELETE` e `GRANT UPDATE (amount_cents,
  updated_at)`; políticas de `SELECT` por workspace e de `INSERT`/`UPDATE`/`DELETE` por workspace e
  `app_can_write`, com `created_by = app_user_id()` no `INSERT`.
- **Motivo**: mesma proteção nas duas camadas e a mesma regra de negócio. A segunda tabela apagável pela
  aplicação segue o mesmo argumento (plano, não registro financeiro).
- **Alternativas descartadas**: as mesmas da `006` (índices parciais, categoria fictícia, `deleted_at`).

## 4. Rotas

- **Decisão**: sob `/api/v1/workspaces/{wid}/monthly-budgets`:
  - `GET ?month=AAAA-MM`: acompanhamento. Sem `month`, o mês corrente (fuso de Brasília). Texto vazio é
    inválido.
  - `PUT` com `{month, categoryId?, amountCents}`: define ou substitui. `201` se criou, `200` se substituiu.
  - `PUT /{id}` com `{amountCents}`: ajusta o valor.
  - `DELETE /{id}`: remove (`204`).
  - `POST /copy` com `{fromMonth, months}`: copia para os próximos `months` meses.
- **Motivo**: mesma forma da `006`, para o front tratar os dois do mesmo jeito. O prefixo `monthly-budgets`
  deixa `/budgets` livre para o semanal sem mudar o contrato dele.
- **Alternativas descartadas**: `/budgets?month=` no mesmo controller (misturaria os dois planos numa rota
  e mudaria o contrato da `006`); renomear `/budgets` para `/weekly-budgets` (quebra o que já foi entregue).

## 5. Definir, ajustar e remover

- **Decisão**: iguais à `006` (decisões 6 e 7), trocando a tabela e a data: upsert numa instrução
  (`INSERT ... ON CONFLICT (workspace_id, month_start, category_id) DO UPDATE`), categoria lida com
  `FOR SHARE` antes (404 inexistente, 400 campo `categoryId` se receita, 409 se arquivada), ajuste com
  `FOR UPDATE` no limite e `FOR SHARE` na categoria, remoção sem olhar a categoria.
- **Motivo**: SC-005 e SC-007 (um limite por escopo e mês sob corrida; nunca limite novo em categoria
  arquivada) já têm solução provada na `006`; a diferença é só a tabela.

## 6. Cópia: um `INSERT ... SELECT` somando meses, de 1 a 24

- **Decisão**: como a `006` (decisão 10), com `(b.month_start + make_interval(months => i.n))::date` no lugar
  de `+ 7 * n`. Valida antes de gravar: `fromMonth` (`Period.ofMonth(text, "fromMonth")`), `months` de 1 a
  24 e que `fromMonth + months` não passe de `2100-12`. Contagens: criados = linhas devolvidas;
  `skippedExisting` = copiáveis × N − criados; `skippedArchivedCategory` = arquivados × N.
- **Motivo**: uma instrução é atômica (FR-014) e `ON CONFLICT DO NOTHING` nunca altera limite existente.
  Somar meses ao dia 1 não tem o problema de fim de mês. O limite de 24 cobre dois anos de planejamento.
- **Alternativas descartadas**: laço de `INSERT` por limite e mês no Java; 52 meses (não há motivo, pois o
  uso real é planejar o ano e o ano seguinte).

## 7. Acompanhamento numa leitura consistente

- **Decisão**: como a `006` (decisão 8): `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`,
  duas consultas. A de gasto é a mesma da `006` com o intervalo do mês (`WEEK_SPENT_SQL` só recebe `:start` e
  `:end`; é renomeada `PERIOD_SPENT_SQL` e compartilhada; o `BudgetRlsTest` da `006`, que lista essa consulta,
  acompanha o renome). A de limites lê `monthly_budgets` com o filtro
  `workspace_id = :w and month_start = :s`.
- **Motivo**: FR-021 e somas exatas com `addExact`. O índice da unicidade atende a busca por
  `(workspace_id, month_start)`.
- **Alternativas descartadas**: uma consulta única com `join` (um lançamento cobre vários limites e
  multiplicaria linhas).

## 8. Mês corrente e fuso

- **Decisão**: `Period.resolve(month, null, now)` da `005`, que já devolve o mês pedido ou o corrente em
  Brasília; texto vazio ou malformado é `400` no campo `month`. Acrescenta-se `Period.ofMonth(text, field)`
  porque a cópia precisa do campo `fromMonth` no erro (a `006` fez o mesmo com `ofWeek(text, field)`), e
  `LAST_MONTH_START = 2100-12-01` para a checagem da cópia.
- **Motivo**: não duplicar regra de calendário.

## 9. Extrair o cálculo de acompanhamento (`BudgetTracking`)

- **Decisão**: mover de `BudgetService.week` para uma classe do pacote, `BudgetTracking`, a montagem do
  acompanhamento a partir de duas listas já lidas (limites e gastos por categoria): gasto de cada limite
  (`BudgetRules.covers`), restante, percentual, estado, ordem, total gasto e gasto fora dos limites. O serviço
  semanal e o mensal só leem as duas listas do banco (cada um com o seu SQL) e chamam essa função. Os testes
  da `006` são a rede de segurança da mudança.
- **Motivo**: duas coisas distintas se parecem aqui. O que é igual é **regra financeira** (cobertura,
  estado, ordem, "fora dos limites"), cuja divergência silenciosa o SC-003 proíbe. O que é diferente é
  **acesso a dados** (tabela e data), que continua separado. Extrai-se só o primeiro.
- **Alternativas descartadas**: copiar `week()` (duplica ~30 linhas de regra financeira); um serviço
  genérico parametrizado por tabela (exigiria montar SQL com nome de tabela, que o princípio IV proíbe, e
  seria abstração para dois casos).

## 10. Estado e percentual

- **Decisão**: `BudgetRules` sem nenhuma mudança. Um teste de paridade cria o mesmo gasto e o mesmo limite no
  orçamento semanal e no mensal e confere gasto, restante, percentual e estado iguais (SC-003).
- **Motivo**: a `006` já documenta `BudgetRules` como a base do mensal; as regras não dependem do período.

## 11. Erros e cabeçalhos

- **Decisão**: reaproveitar `BudgetNotFoundException` (404) e `BudgetConflictException` (409), e
  `CategoryNotFoundException` para categoria inexistente. A mensagem `Limite não encontrado` já serve aos dois
  planos; só o comentário da classe deixa de dizer "semanal". Campos inválidos são `400` com `errors[].field`;
  `VIEWER` em escrita é `403` do aspecto; `Cache-Control: no-store` vem da `SecurityConfig`.
- **Motivo**: nada de novo no tratamento de erro, e nenhum detalhe interno vaza.

## 12. DTOs

- **Decisão**: DTOs novos só onde o formato muda (`month`/`fromMonth`/`months`, e o bloco `month` da
  resposta). Reaproveitam-se `UpdateBudgetRequest` (`{amountCents}`), `CopyBudgetsResponse` (`{created,
  skippedExisting, skippedArchivedCategory}`) e `WeekBudgetResponse.BudgetItem` (o item de limite).
- **Motivo**: o item e as contagens são idênticos; novos tipos iguais seriam duplicação.
- **Alternativas descartadas**: mover `BudgetItem` para o nível de pacote (mudaria o nome interno dos tipos da
  `006` sem benefício; o nome do schema no OpenAPI continua `BudgetItem`).

## 13. Desempenho

- **Decisão**: sem índice novo. A medição com 20.000 lançamentos e 40 limites decide; se passar de 1 s, o
  índice entra numa `V8` com a medição registrada.
- **Motivo**: princípio VII. Um mês agrega mais lançamentos que uma semana, mas é a mesma consulta com os
  mesmos índices; o teste de desempenho mede o mês cheio.
