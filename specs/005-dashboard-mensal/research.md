# Research: Dashboard mensal e semanal

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Sem migration, sem tabela, sem índice novo

- **Decisão**: nenhuma mudança de schema. O painel é uma leitura agregada sobre `transactions`, `accounts` e
  `categories`.
- **Motivo**: a spec proíbe gravação (FR-014) e o saldo é calculado a cada consulta (princípio V). As três
  tabelas já têm RLS de leitura por workspace (`transactions_read`, `accounts_read`, `categories_read`) e os
  índices parciais `transactions_workspace_date_idx (workspace_id, occurred_on)` e
  `transactions_workspace_account_date_idx` atendem as consultas de período e de saldo.
- **Alternativas descartadas**: tabela de resumo por dia ou por mês (dado derivado que pode divergir, contra
  o princípio VII); visão materializada (precisa de atualização a cada lançamento).

## 2. Uma rota com `month` ou `week`

- **Decisão**: `GET /api/v1/workspaces/{wid}/dashboard?month=AAAA-MM` ou `?week=AAAA-Www`. Os dois juntos são
  400 (campo `week`, mensagem nomeando os dois). Nenhum dos dois: mês corrente.
- **Motivo**: o conteúdo é o mesmo nos dois períodos, só muda o intervalo; uma rota evita duplicar contrato,
  testes e tela. O nome da rota é `dashboard` (o da spec), não `monthly`.
- **Alternativas descartadas**: duas rotas (`/dashboard/monthly` e `/dashboard/weekly`) duplicariam o
  contrato; parâmetro `period=WEEK&value=...` seria mais genérico do que a escala pede.

## 3. Períodos: regra pura e formatos estritos

- **Decisão**: o tipo `Period` guarda tipo (`MONTH` ou `WEEK`), identificador, início e fim, e sabe calcular
  o período anterior (mês anterior; semana anterior = 7 dias antes) e a lista de dias.
  - Mês: `^\d{4}-\d{2}$`, mês 01 a 12, ano de 2000 a 2100. `2026-1`, `2026-13`, `novembro`, `2026-11-05` e
    vazio são 400 no campo `month`.
  - Semana: `^\d{4}-W\d{2}$`, ano ISO de 2000 a 2100, número de 01 até o total de semanas daquele ano ISO
    (52 ou 53, decidido por `LocalDate.of(ano, 12, 28)` e `IsoFields.WEEK_OF_WEEK_BASED_YEAR`, pois 28/12 é
    sempre da última semana ISO). Início: segunda-feira da semana (`LocalDate.of(ano, 1, 4)` com
    `IsoFields.WEEK_OF_WEEK_BASED_YEAR` e `DayOfWeek.MONDAY`). `2026-W53` existe (2026 tem 53 semanas);
    `2025-W53` não. Campo `week`.
  - O período anterior de `2000-01` e de `2000-W01` cai antes de 2000; não é erro: só não tem lançamentos
    (a data mínima do banco é 2000-01-01).
- **Mês corrente**: `LocalDate.now(clock.withZone("America/Sao_Paulo"))`, em uma função pura que recebe o
  `Instant` (testada sem relógio real). O `Clock` do projeto já existe (UTC, usado no JWT); o fuso é só
  aplicado aqui.
- **Alternativas descartadas**: `java.time.temporal.WeekFields.of(Locale.forLanguageTag("pt-BR"))` (começa no
  domingo e a numeração depende do locale; a spec fixa segunda e ISO 8601); parse leniente de `YearMonth`
  (aceitaria `2026-1`).
- **Início da semana**: segunda-feira é uma constante única de `Period`; mudar para domingo seria uma troca
  localizada e dos testes (decisão pendente da spec, padrão ISO).

## 4. Uma leitura consistente

- **Decisão**: `@WorkspaceScoped` + `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` no método do
  serviço, igual à listagem da `004`. Todas as consultas rodam nessa transação e a resposta é montada depois.
- **Motivo**: FR-013 e SC-009. Com `READ COMMITTED`, uma gravação entre duas consultas faria o total divergir
  da soma dos grupos. `REPEATABLE READ` no Postgres é snapshot: leitura nunca falha por conflito de
  escrita, então não há retry nem `catch` (princípio VI).
- **Prova**: teste com threads inserindo e excluindo lançamentos enquanto o painel é consultado em laço; em
  todas as respostas, soma dos grupos = total, soma dos dias = total e último saldo diário = fechamento.

## 5. Consulta 1: receita e despesa por dia

- **Decisão**: uma consulta para `[início do período anterior, fim do período]`:
  `select occurred_on, sum(amount_cents) filter (where type = 'INCOME'), -sum(amount_cents) filter (where
  type = 'EXPENSE') from transactions where workspace_id = :w and deleted_at is null and type <> 'TRANSFER'
  and occurred_on between :from and :to group by occurred_on`. O Java separa as linhas do período e as do
  anterior pelo intervalo, soma com `addExact` e monta: totais, os dias (com zero nos dias sem linha), a
  comparação.
- **Motivo**: um passe só, no máximo ~62 linhas na saída. Os totais derivam dos dias, então a soma dos dias
  é igual ao total por construção, e o teste só confirma contra um cálculo independente.
- **Convenção**: despesa sai positiva (valor guardado é negativo; a consulta nega o `sum`).

## 6. Consulta 2: grupos por categoria

- **Decisão**: `select t.type, root.id, root.name, c.id, c.name, -- ou nulos
  sum(case when type = 'EXPENSE' then -amount_cents else amount_cents end)` com
  `left join categories c on c.id = t.category_id and c.workspace_id = t.workspace_id` e
  `left join categories root on root.id = coalesce(c.parent_id, c.id) and root.workspace_id = t.workspace_id`,
  agrupando por `t.type, root.id, root.name, c.id, c.name`. Filtro `workspace_id`, `deleted_at is null`,
  `type <> 'TRANSFER'` e período.
- **Montagem em Java** (`DashboardRules`): por tipo, cada linha com `c.id = root.id` soma em `directCents`
  do grupo; cada linha com `c.id <> root.id` vira item (subcategoria) do grupo; o total do grupo é
  `directCents` mais os itens; linhas com `category_id` nulo viram o grupo "Sem categoria" (`categoryId`
  nulo, sem itens). Ordenação: total decrescente, depois nome sem diferenciar maiúsculas, depois
  identificador; os itens do mesmo jeito.
- **Motivo**: categoria arquivada e subcategoria arquivada entram (os `left join` não filtram `archived`);
  a hierarquia é de dois níveis (`V4`), então o `coalesce(parent_id, id)` basta, sem recursão.
- **Percentual**: total do grupo (e do item) sobre o total do tipo, `BigDecimal` com escala 2 e metade para
  cima; com total do tipo zero não há linhas, então não há divisão.
- **Alternativas descartadas**: consulta recursiva (a hierarquia tem dois níveis); montar a árvore a partir
  da lista de categorias da `003` (traria categorias sem uso e mais leituras).

## 7. Consulta 3: saldos de contas ativas e saldo por dia

- **Decisão** (a): uma consulta sobre `accounts a left join transactions t` (filtro de workspace nos dois
  lados, `t.deleted_at is null`, `not a.archived`) devolve, por conta ativa: `initial_balance_cents`, a soma
  dos lançamentos com data anterior ao início, a soma com data até o fim do período e a soma total, com
  `filter (where ...)`. Saldo de abertura = inicial + antes; fechamento = inicial + até o fim; atual =
  inicial + total. O total de cada um é a soma em Java com `addExact`, na mesma regra do
  `BalanceReportController` da `002` (todas as contas ativas; cartão devido já é saldo negativo).
- **Decisão** (b): uma consulta do **movimento por dia** das contas ativas no período:
  `select t.occurred_on, sum(t.amount_cents) from transactions t join accounts a on a.id = t.account_id and
  a.workspace_id = t.workspace_id where ... not a.archived and t.deleted_at is null and t.occurred_on between
  :from and :to group by t.occurred_on`. O saldo total ao fim do dia é a abertura total mais o acumulado do
  movimento; o último dia fecha exatamente com o fechamento total.
- **Motivo**: o movimento inclui transferências (entre contas ativas elas se anulam; entre uma ativa e uma
  arquivada, mudam o total, como na `002`). Reusar `AccountService.list` não serve, porque ele só tem o saldo
  de hoje; o cálculo de hoje sai da mesma consulta (a), e um teste confere que é idêntico ao
  `GET /reports/balance`.
- **Conta sem data de abertura**: o saldo inicial vale em qualquer data de corte (a `002` não tem data de
  abertura). Arquivamento considera o estado de hoje.

## 8. Comparação com o período anterior

- **Decisão**: para receitas, despesas e resultado: valor anterior, variação em centavos (atual menos
  anterior) e variação percentual = `variação × 100 / |anterior|`, `BigDecimal` escala 2, metade para cima;
  nula quando o anterior é zero. A resposta traz também o identificador e as datas do período anterior.
- **Motivo**: base absoluta para o sinal indicar o sentido (spec, premissa). O período anterior é calculado
  pelo `Period`, mesmo antes de 2000.

## 9. Desempenho

- **Decisão**: sem índice novo. As consultas 1 e 2 filtram por `workspace_id` e intervalo de datas (índice
  parcial de data); a 3 varre os lançamentos das contas ativas do workspace, como o saldo da `002` já faz
  (`002`, SC-007). Meta de 1 s com 20.000 lançamentos; o teste de desempenho mede mês corrente, mês
  qualquer e semana.
- **Se falhar**: primeiro olhar o plano com `EXPLAIN`; índice novo só depois e por migration.

## 10. Erros e formato de saída

- **Decisão**: validação do período antes de abrir a transação. Erros viram `FieldValidationException`
  (campo `month` ou `week`, 400 em Problem Details), sem mudança no `ApiExceptionHandler`. A resposta é um
  DTO próprio (`DashboardResponse`); `no-store` já vem da `SecurityConfig`. Método diferente de `GET` na
  rota dá 405 pelo próprio Spring MVC (FR-014).
- **Parâmetros repetidos** (`month=a&month=b`): o Spring junta como `a,b`, que o formato estrito recusa
  com 400.

## 11. Testes

- **Decisão**: dados semeados pelo `OwnerJdbc`/`WorkspaceFixtures`. `DashboardRandomDataTest` gera
  lançamentos aleatórios com semente fixa (datas em 3 meses e nos limites, receita, despesa, transferência,
  excluídos, categorias principais, subcategorias, arquivadas e sem categoria) e compara cada número do
  painel, em mês e em semana, com um cálculo independente em Java puro sobre os mesmos dados.
  `DashboardConsistencyTest` roda gravações em threads durante leituras repetidas. O teste de acesso cruzado,
  o de RLS (consultas diretas como `tostao_app` sem o filtro do serviço) e o de filtro do serviço (conexão
  do dono, sem RLS) provam as duas camadas.
- **Motivo**: prova as regras com Postgres real; o cálculo independente evita testar o código com ele mesmo.

## Resultado

Nenhum item ficou em aberto: todas as dúvidas do Technical Context foram resolvidas acima.
