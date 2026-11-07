# Research: Orçamento semanal

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Uma tabela nova, `weekly_budgets`, guardando a segunda-feira da semana

- **Decisão**: migration `V6__weekly_budgets.sql` cria `weekly_budgets` com `week_start DATE` (sempre a
  segunda-feira, `extract(isodow from week_start) = 1`, de `2000-01-03` a `2100-12-27`), `category_id` nulo
  para o limite geral, `amount_cents BIGINT` de 0 a 10^12, `created_by`, `created_at`, `updated_at`.
- **Motivo**: a data da segunda-feira é um valor de calendário simples, indexável e comparável com
  `occurred_on` sem converter nada. O texto `AAAA-Www` é só o formato de entrada e de saída; `Period.ofWeek`
  da `005` faz a conversão e a validação (ano ISO, semana que existe). O `CHECK` é a rede de segurança do banco.
- **Alternativas descartadas**: guardar o texto `AAAA-Www` (cada consulta teria de reconverter, e `2026-W5`
  e `2026-W05` virariam dois valores); guardar ano e número de semana em duas colunas (mais campos para o
  mesmo dado).

## 2. Unicidade com `NULLS NOT DISTINCT`

- **Decisão**: `CONSTRAINT weekly_budgets_scope_key UNIQUE NULLS NOT DISTINCT (workspace_id, week_start,
  category_id)`.
- **Motivo**: o limite geral tem `category_id` nulo, e num índice único comum dois nulos nunca colidem, então
  poderia haver dois limites gerais na mesma semana. `NULLS NOT DISTINCT` (PostgreSQL 15 ou mais; o projeto usa
  17) trata os nulos como iguais, e uma única restrição serve para o geral e para as categorias. Ela é também o
  alvo do `ON CONFLICT`, que resolve duas gravações simultâneas sem erro (SC-005). O mesmo índice atende a
  busca por `(workspace_id, week_start)` do acompanhamento.
- **Alternativas descartadas**: dois índices parciais (`WHERE category_id IS NULL` e `IS NOT NULL`): mais
  objetos e `ON CONFLICT` com predicado; uma categoria "geral" fictícia: quebraria a chave estrangeira para
  `categories`.

## 3. Categoria de despesa garantida pelo banco

- **Decisão**: coluna `kind TEXT NOT NULL DEFAULT 'EXPENSE' CHECK (kind = 'EXPENSE')` e chave estrangeira
  `(category_id, workspace_id, kind) REFERENCES categories (id, workspace_id, kind)`, usando a chave única que a
  `V4` já criou. Com `category_id` nulo, a chave não se aplica (limite geral).
- **Motivo**: o mesmo desenho da `V5` para lançamentos. O banco recusa categoria de receita ou de outro
  workspace mesmo que o serviço falhe; o serviço valida antes para devolver o erro certo (400 ou 404).
- **Alternativas descartadas**: gatilho (mais código para o mesmo efeito); só validar no serviço (uma camada).

## 4. Apagar de verdade, só nesta tabela

- **Decisão**: `GRANT SELECT, INSERT, DELETE` e `GRANT UPDATE (amount_cents, updated_at)` na tabela para
  `tostao_app`; políticas de RLS: `SELECT` por workspace; `INSERT`, `UPDATE` e `DELETE` por workspace e
  `app_can_write(workspace_id)`; `INSERT` ainda exige `created_by = app_user_id()`.
- **Motivo**: a spec manda remover o limite de verdade, e a constituição exige exclusão lógica só para
  lançamentos. Permitir só a coluna do valor no `UPDATE` impede mudar workspace, categoria, semana, autor e
  data de criação (FR-015).
- **Alternativas descartadas**: `deleted_at` (ver Complexity Tracking do plano).

## 5. Rotas

- **Decisão**:
  - `GET /budgets?week=AAAA-Www`: acompanhamento. Sem `week`, vale a semana corrente (fuso de Brasília), como o
    painel usa o mês corrente. Texto vazio é inválido.
  - `PUT /budgets` com `{week, categoryId?, amountCents}`: define ou substitui. `201` se criou, `200` se
    substituiu. Idempotente.
  - `PUT /budgets/{id}` com `{amountCents}`: ajusta o valor de um limite existente.
  - `DELETE /budgets/{id}`: remove (`204`).
  - `POST /budgets/copy` com `{fromWeek, weeks}`: copia para as próximas `weeks` semanas.
- **Motivo**: definir é "pôr este limite neste escopo e semana", que é exatamente um `PUT` idempotente; o
  `ON CONFLICT DO UPDATE` implementa a substituição (FR-005) numa instrução atômica. Ajustar por `id` cobre o
  que a spec chama de ajustar e dá o 404 de identificador de outro workspace (cenário 5 da história 5).
- **Alternativas descartadas**: `POST` para criar mais `409` quando existe (obriga o cliente a tratar dois
  caminhos e abre a corrida entre checar e gravar); `PATCH` (não há outro campo para mudar).

## 6. Definir é um upsert numa instrução, com a categoria travada

- **Decisão**: `@WorkspaceScoped(write = true) @Transactional`. Passos: (1) se há categoria, lê
  `kind, archived` de `categories` com `FOR SHARE` filtrando por `workspace_id` (`404` se não existe, `400`
  campo `categoryId` se é de receita, `409` se arquivada); (2) `INSERT ... ON CONFLICT (workspace_id,
  week_start, category_id) DO UPDATE SET amount_cents = EXCLUDED.amount_cents, updated_at = now()
  RETURNING ..., (xmax = 0) AS created`.
- **Motivo**: `FOR SHARE` faz um arquivamento simultâneo (um `UPDATE` da categoria) esperar este commit, ou
  ser visto aqui: nunca nasce nem muda limite em categoria arquivada (SC-007). Duas definições simultâneas
  do mesmo escopo e semana: a segunda espera a primeira e atualiza; sobra um limite, com o valor da última
  gravação (SC-005), sem `catch`. `created_by` e `created_at` ficam de fora do `DO UPDATE` (FR-005).
- **Alternativas descartadas**: `SELECT` e depois `INSERT` ou `UPDATE` (corrida); capturar a violação de
  unicidade e tentar de novo (proibido pelo princípio VI).

## 7. Ajustar valor e remover

- **Decisão**: ajustar: lê o limite com `FOR UPDATE` filtrando por `workspace_id` (`404` se não existe),
  trava a categoria com `FOR SHARE` se houver e recusa (`409`) se arquivada, depois `UPDATE amount_cents,
  updated_at`. Remover: `DELETE ... WHERE id AND workspace_id` e `404` se nenhuma linha saiu; sem olhar a
  categoria.
- **Motivo**: FR-012 e cenário 4 da história 4: alterar em categoria arquivada é recusado; remover sempre
  funciona. A trava da categoria vem depois da trava do limite; o arquivamento só trava a categoria, então
  não há ciclo de espera.
- **Alternativas descartadas**: apagar e recriar para ajustar (perderia `created_by` e `created_at`).

## 8. Acompanhamento numa leitura consistente, duas consultas

- **Decisão**: `@WorkspaceScoped @Transactional(readOnly = true, isolation = REPEATABLE_READ)`:
  1. limites da semana com a categoria (nome, pai, arquivada): `weekly_budgets b left join categories c on
     c.id = b.category_id and c.workspace_id = b.workspace_id where b.workspace_id = :w and b.week_start = :s`;
  2. despesas da semana por categoria: `select t.category_id, c.parent_id, -sum(t.amount_cents) from
     transactions t left join categories c on ... where t.workspace_id = :w and t.deleted_at is null and
     t.type = 'EXPENSE' and t.occurred_on between :start and :end group by t.category_id, c.parent_id`.
  O gasto de cada limite, o total, o fora dos limites, o restante, o percentual e o estado saem em Java.
- **Motivo**: FR-021 (um só instante) e as somas exatas com `addExact`. Os índices parciais da `V1` por data e
  por categoria atendem a consulta 2; a tabela de limites tem dezenas de linhas por semana.
- **Regras de cobertura**: limite geral cobre tudo (inclusive sem categoria); limite de categoria `X` cobre
  lançamentos cuja categoria é `X` ou tem `X` como pai; "fora dos limites de categoria" é a soma das despesas
  cuja categoria não é nem tem como pai nenhuma categoria com limite (despesa sem categoria sempre entra).
- **Alternativas descartadas**: uma consulta única com `join` de limites e lançamentos (um lançamento cobre
  vários limites, o que multiplicaria linhas); soma no banco por limite (N consultas).

## 9. Estado e percentual como regra pura

- **Decisão**: `BudgetRules` com: `percent(spent, limit)` (duas casas, `HALF_UP`, nulo com limite zero);
  `status(spent, limit)`: limite zero: sem gasto `WITHIN`, com gasto `EXCEEDED`; senão `spent * 5 < limit * 4`
  (abaixo de 80%) `WITHIN`, `spent <= limit` `WARNING`, acima `EXCEEDED`, tudo com inteiros exatos
  (`multiplyExact`), nunca com o percentual arredondado; ordenação (estado, maior gasto, nome minúsculo com o
  geral primeiro, id).
- **Motivo**: SC-003 exige 79,99%, 80%, 100% e 100,01% exatos. Comparar inteiros evita erro de arredondamento
  (por exemplo 79,995% arredondaria para 80,00).
- **Alternativas descartadas**: comparar o percentual já arredondado; `double`.

## 10. Cópia: um único `INSERT ... SELECT`

- **Decisão**: `@WorkspaceScoped(write = true) @Transactional`. (1) valida `fromWeek` (`Period.ofWeek`) e
  `weeks` (1 a 52) e que a última semana de destino ainda cabe em 2100 (`Period.ofWeek` do destino, ou
  comparação com a faixa) antes de qualquer gravação; (2) lê os limites da origem juntando `categories`
  (`FOR SHARE OF c`, ordenado por id) e separa os de categoria arquivada; (3) um só
  `INSERT INTO weekly_budgets (...) SELECT ... FROM weekly_budgets b CROSS JOIN generate_series(1, :n) i
  WHERE b.id IN (:copyable) ON CONFLICT DO NOTHING RETURNING id`; (4) `criados` = linhas devolvidas;
  `ignoradosPorJaExistirem` = (limites copiáveis × N) − criados; `ignoradosPorCategoriaArquivada` = arquivados
  × N.
- **Motivo**: uma instrução é atômica (FR-014) e o `ON CONFLICT DO NOTHING` nunca altera um limite que já
  existe. As contagens saem do que de fato foi gravado, então batem mesmo se alguém definir um limite no
  meio (SC-006). `created_by` da cópia é a pessoa que copiou.
- **Contagem por semana ou por limite**: as contagens são de limites individuais (cada par limite × semana
  de destino conta uma vez), como a spec pede ("quantos limites foram criados").
- **Alternativas descartadas**: laço de `INSERT` por limite e por semana no Java (milhares de idas ao banco e
  mais pontos de falha); capturar `DuplicateKeyException` por linha (proibido pelo princípio VI).

## 11. Semana corrente e fuso

- **Decisão**: `Period.resolveWeek(String week, Instant now)`: `week` ausente vira a semana que contém hoje no
  fuso de Brasília; texto vazio ou malformado é `400` no campo `week`. Reaproveita `ZONE` e a conversão de
  `Period`.
- **Motivo**: o painel já faz o mesmo com o mês; evita duplicar a regra de calendário.

## 12. Erros e cabeçalhos

- **Decisão**: `BudgetNotFoundException` (`404`, "Não encontrado") e `BudgetConflictException` (`409`,
  "Conflito de estado": categoria arquivada). Categoria inexistente reaproveita `CategoryNotFoundException`
  (`404`). Campos inválidos são `400` com `errors[].field`. Papel `VIEWER` em escrita: `403` do aspecto.
  `Cache-Control: no-store` já vem da `SecurityConfig` para toda resposta autenticada.
- **Motivo**: segue o padrão de `transaction` e `category`; nenhum detalhe interno vaza.

## 13. Desempenho

- **Decisão**: sem índice novo para os lançamentos. A medição com 20.000 lançamentos e 40 limites decide; se
  passar de 1 s, o índice entra numa migration seguinte com a medição registrada.
- **Motivo**: princípio VII. A `005` mediu 37 a 55 ms para consultas parecidas com os mesmos índices.
