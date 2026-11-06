---

description: "Tarefas da feature 005-dashboard-mensal"
---

# Tasks: Dashboard mensal e semanal

**Input**: documentos em `/specs/005-dashboard-mensal/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS. A constituição (princípios II, V e VIII) e o FR-018 exigem teste de acesso cruzado, teste unitário das regras de saldo e integração com Postgres real.

**Organization**: tarefas agrupadas por user story. Só o backend entra nesta feature (as telas ficam no roadmap de front-end). Não há fase de Setup nem migration: nenhuma dependência, tabela ou configuração nova. Todas as histórias usam o mesmo `DashboardService`, que cresce história a história: até a tarefa que preenche cada bloco da resposta, esse bloco sai vazio (listas vazias, zeros) apenas em desenvolvimento.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- **[P]**: pode rodar em paralelo (arquivos diferentes, sem dependência de tarefa incompleta)
- **[Story]**: US1 a US7, conforme a spec
- Abreviação de caminhos usada abaixo (nunca em código):
  `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`
- **Commits**: um commit por tarefa ou por pequeno grupo coeso (poucos arquivos), em Conventional Commits, sem co-author. Antes de cada commit rodar os testes que a tarefa tocou; ao fim de cada fase rodar `./mvnw verify`
- **Convenção do projeto**: todo `JdbcClient` da aplicação conecta como `tostao_app`. Os testes usam o bean `OwnerJdbc` (conexão do dono, ignora RLS) só para semear e inspecionar dados. `WorkspaceFixtures` já tem `workspaceWithOwner`, `addMember`, `insertAccount`, `insertCategory`, `seedDefaults`, `insertLedgerTransaction` (com data e `deleted`), `insertTransferPair` (com data). Testes com MockMvc usam `.cookie(session(fx.token()))`
- **Valores**: despesas e receitas saem positivas no painel; o resultado é receitas menos despesas. Somas em Java com `Math.addExact`; percentuais em `BigDecimal` com escala 2 e `RoundingMode.HALF_UP`

---

## Phase 1: Foundational (bloqueia todas as user stories)

**Purpose**: período, regras puras, DTOs e cenário de teste compartilhados

**⚠️ CRITICAL**: nenhuma user story começa antes deste checkpoint

- [ ] T001 [P] Criar `MAIN/dashboard/Period.java`, classe final imutável (sem Spring, sem banco): campos `PeriodType type` (enum `MONTH`, `WEEK`, no mesmo arquivo ou em `PeriodType.java`), `String id`, `LocalDate start`, `LocalDate end`. Fábricas: `ofMonth(String)` (regex `^\d{4}-\d{2}$`, mês 01 a 12, ano de 2000 a 2100; senão `FieldValidationException("month", "mês inválido: use AAAA-MM entre 2000-01 e 2100-12")`), `ofWeek(String)` (regex `^\d{4}-W\d{2}$`, ano ISO de 2000 a 2100, número de 01 até o total de semanas do ano ISO, calculado com `LocalDate.of(ano, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)`; início = `LocalDate.of(ano, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, n).with(DayOfWeek.MONDAY)`, fim = início + 6; senão `FieldValidationException("week", "semana inválida: use AAAA-Www, semana ISO existente entre 2000 e 2100")`) e `resolve(String month, String week, Instant now)` (os dois não nulos → `FieldValidationException("week", "informe apenas month ou week, não os dois")`; só `month`, só `week`; nenhum → o mês de `LocalDate.ofInstant(now, ZoneId.of("America/Sao_Paulo"))`; string vazia é valor inválido, nunca "ausente"). Métodos: `previous()` (mês anterior; semana: início − 7 dias, mesmo comprimento, id calculado, sem validar a faixa 2000–2100), `List<LocalDate> days()`, getters. O início da semana (segunda-feira) é uma constante única. Nunca lança outra exceção para entrada malformada (sem `DateTimeException` ou `NumberFormatException` vazando)
- [ ] T002 [P] Criar `TEST/dashboard/PeriodTest.java`, teste **unitário** (JUnit puro, sem Spring nem Testcontainers; constituição VIII): mês `2026-11` → 01/11 a 30/11 e 30 dias; `2024-02` → 29 dias; `2025-02` → 28 dias; `2000-01` e `2100-12` aceitos; `1999-12`, `2101-01`, `2026-13`, `2026-00`, `2026-1`, `novembro`, `2026-11-05`, `""`, `" 2026-11"` recusados com campo `month`; semana `2026-W45` → 02/11 a 08/11 e 7 dias; `2026-W53` aceita (02/01…), `2025-W53` recusada, `2026-W00`, `2026-W54`, `2026W45`, `1999-W52`, `2101-W01` recusadas com campo `week`; `2000-W01` começa em 03/01/2000 e `2100-W52` existe; semana que atravessa o ano (`2025-W01` começa em 30/12/2024, `2026-W53` termina em 03/01/2027); `previous()` de `2026-11` é `2026-10`, de `2026-01` é `2025-12`, de `2000-01` é `1999-12`, de `2026-W45` é `2026-W44`, de `2026-W01` é `2025-W52`, de `2000-W01` é `1999-W52` (sem erro); `resolve` com os dois → campo `week`; sem nenhum devolve o mês de `now` no fuso de Brasília (`2026-11-30T23:30:00-03:00` é novembro; `2026-12-01T01:00:00Z`, que no Brasil ainda é 30/11 às 22h, é novembro; `2026-12-01T03:00:00Z` é dezembro)
- [ ] T003 [P] Criar `MAIN/dashboard/DashboardRules.java`, classe final com métodos estáticos puros (sem Spring, sem banco) para as regras do painel: `BigDecimal percent(long part, long total)` (`part × 100 / total`, escala 2, `HALF_UP`, com `BigDecimal`/`BigInteger` sem estourar `long`; `total` zero lança `IllegalArgumentException`, pois sem lançamentos não há grupos); `BigDecimal changePercent(long change, long previous)` (`change × 100 / |previous|`, escala 2, `HALF_UP`; `null` quando `previous` é zero); `List<CategoryGroup> groups(List<CategoryRow> rows, long typeTotal)` onde `CategoryRow(UUID rootId, String rootName, UUID categoryId, String categoryName, long totalCents)` (linha com `rootId` nulo vira o grupo "Sem categoria" com `categoryId` nulo e sem filhos; linha com `categoryId` igual a `rootId` soma em `directCents`; outra vira filho; `totalCents` do grupo = `directCents` + filhos com `addExact`; percentual de grupos e filhos sobre `typeTotal`; ordem de grupos e filhos por `totalCents` decrescente, depois nome em minúsculas, depois identificador, com "Sem categoria" tratado como um grupo comum de nome "Sem categoria" e identificador nulo por último nos empates); `List<Long> runningBalances(long opening, List<LocalDate> days, Map<LocalDate, Long> movementByDay)` (saldo ao fim de cada dia = abertura + movimento acumulado com `addExact`, dias sem movimento repetem o saldo); `long sumExact(Collection<Long>)`
- [ ] T004 [P] Criar `TEST/dashboard/DashboardRulesTest.java`, teste **unitário** (JUnit puro, constituição VIII): `percent(150, 400)` = `37.50`; `percent(1, 3)` = `33.33`; `percent(2, 3)` = `66.67` (metade para cima); `percent(5, 8)` = `62.50`; `percent(0, 100)` = `0.00`; `percent` com valores próximos de 10^15 sem estourar; `percent(1, 0)` lança `IllegalArgumentException`; `changePercent(-5000, 20000)` = `-25.00`; `changePercent(5000, -20000)` = `25.00` (base absoluta: o sinal indica o sentido); `changePercent(100, 0)` é `null`; `groups`: a soma dos `totalCents` dos grupos é igual ao total do tipo; grupo com direto e dois filhos tem `totalCents` = direto + filhos e `directCents` correto; ordem por total decrescente com desempate por nome sem diferenciar maiúsculas e depois por identificador; "Sem categoria" tem `categoryId` nulo e `children` vazio; lista vazia → resultado vazio; `runningBalances(80000, [d1..d3], {d1: -10000, d3: 250000})` = `[70000, 70000, 320000]`; o último saldo = abertura + soma do movimento; `sumExact` lança `ArithmeticException` em estouro
- [ ] T005 [P] Criar `MAIN/dashboard/dto/DashboardResponse.java`, `record` com os registros aninhados do contrato [contracts/openapi.yaml](contracts/openapi.yaml): `PeriodInfo(PeriodType type, String id, LocalDate startDate, LocalDate endDate)`, `Totals(long incomeCents, long expenseCents, long resultCents)`, `CategoryItem(UUID categoryId, String name, long totalCents, BigDecimal percent)`, `CategoryGroup(UUID categoryId, String name, long directCents, long totalCents, BigDecimal percent, List<CategoryItem> children)`, `DayPoint(LocalDate date, long incomeCents, long expenseCents, long balanceCents)`, `Change(long previousCents, long changeCents, BigDecimal changePercent)`, `Comparison(PeriodInfo previousPeriod, Change income, Change expense, Change result)`, `AccountBalance(UUID id, String name, String type, long balanceCents, long openingBalanceCents, long closingBalanceCents)`, `Balances(List<AccountBalance> accounts, long totalBalanceCents, long openingTotalCents, long closingTotalCents)`; o `DashboardResponse` traz `period`, `totals`, `incomeByCategory`, `expenseByCategory`, `daily`, `comparison`, `balances`. Só saída: nenhuma anotação de entrada. Conferir que `BigDecimal` sai como número JSON (não texto) e que `percent` 37.50 sai `37.50`. `DashboardRules` (T003) usa `CategoryGroup` e `CategoryItem` daqui
- [ ] T006 [P] Criar `TEST/dashboard/DashboardScenario.java`, helper de teste que monta o cenário do [quickstart.md](quickstart.md) com `WorkspaceFixtures`: workspace com dono; contas `Corrente` (`CHECKING`, saldo inicial 100000) e `Poupança` (`SAVINGS`, 0); categorias `Alimentação` (EXPENSE) com a subcategoria `Mercado`, e `Salário` (INCOME); lançamentos: `EXPENSE` 20000 na Corrente em 2026-10-30 (Alimentação), `EXPENSE` 10000 em 2026-11-02 (Alimentação direto), `EXPENSE` 5000 em 2026-11-03 (Mercado), `INCOME` 250000 em 2026-11-05 (Salário), transferência 30000 Corrente→Poupança em 2026-11-06, e um `EXPENSE` 7777 em 2026-11-04 já excluído (`deleted = true`). Devolve um `record` com `Fixture`, ids das contas e das categorias. Mais um método `JsonPath`/`ResultActions` `dashboard(MockMvc, Fixture, String query)` que faz `GET /api/v1/workspaces/{wid}/dashboard?query` com o cookie de sessão

**Checkpoint**: regras puras testadas (`PeriodTest`, `DashboardRulesTest` verdes), DTOs e cenário prontos; `./mvnw verify` verde

---

## Phase 2: User Story 1 - Ver o resumo do mês (Priority: P1) 🎯 MVP

**Goal**: totais de receitas, despesas e resultado de um mês, sem transferências nem excluídos

**Independent Test**: `GET /api/v1/workspaces/{wid}/dashboard?month=2026-11` no cenário devolve receitas 250000, despesas 15000 e resultado 235000

### Tests for User Story 1

- [ ] T007 [P] [US1] `TEST/dashboard/MonthlyDashboardTest.java` (Testcontainers + MockMvc, usando `DashboardScenario`): `month=2026-11` devolve 200 com `period` (`MONTH`, `2026-11`, `2026-11-01`, `2026-11-30`), `totals.incomeCents` 250000, `expenseCents` 15000 e `resultCents` 235000 (a transferência e o excluído não contam); resultado negativo quando só há despesa (R$ 80,00 de despesa e nenhuma receita → `-8000`); lançamentos no dia 1 e no dia 30 do mês contam, os de 31/10 e 01/12 não (limites inclusivos); lançamento em conta e em categoria arquivadas conta; lançamento de receita ou despesa **sem categoria** (semeado direto) conta no total; mês sem lançamentos devolve totais zerados (200, não erro); `R$ 0,10 + R$ 0,20` somam exatamente `30`; sem `month` nem `week` devolve o mês corrente, comparando `period.id` com o calculado por `Period.resolve(null, null, Instant.now())` (campo `type` `MONTH`); dois workspaces com lançamentos: cada painel só soma o próprio

### Implementation for User Story 1

- [ ] T008 [US1] Criar `MAIN/dashboard/DashboardService.java` (`@Service`, injeta `JdbcClient`) com `@WorkspaceScoped @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ) public DashboardResponse dashboard(UUID workspaceId, Period period)`. Executar a **consulta 1** de [data-model.md](data-model.md) (receita e despesa por dia, sem `TRANSFER`, sem excluídos, filtro `workspace_id`, intervalo `[period.previous().start(), period.end()]`, parâmetros nomeados, `sum(...)::bigint`); separar as linhas do período e do anterior por data; somar com `addExact` para `totals` (`resultCents` = receitas − despesas). Montar `PeriodInfo`. Os demais blocos (grupos, `daily`, `comparison`, `balances`) saem vazios/zerados por enquanto. Sem `catch` dentro da transação
- [ ] T009 [US1] Criar `MAIN/dashboard/DashboardController.java` (`@RestController`, `@RequestMapping("/api/v1/workspaces/{wid}/dashboard")`, só `@GetMapping`): aceita `@RequestParam(required = false) String month`, resolve `Period.resolve(month, null, clock.instant())` **antes** de chamar o serviço (a validação acontece antes de qualquer transação) e devolve o `DashboardResponse`. Injeta o `Clock` existente. Nenhum método de escrita

**Checkpoint**: US1 funciona e `MonthlyDashboardTest` passa; `./mvnw verify` verde

---

## Phase 3: User Story 2 - Para onde o dinheiro foi, por categoria (Priority: P1)

**Goal**: grupos por categoria principal com subcategorias, totais e percentuais, para receitas e despesas

**Independent Test**: no cenário, `expenseByCategory` tem "Alimentação" com total 15000, direto 10000 e a subcategoria "Mercado" com 5000

### Tests for User Story 2

- [ ] T010 [P] [US2] `TEST/dashboard/CategoryGroupsTest.java` (Testcontainers + MockMvc): no cenário, `expenseByCategory` tem um grupo "Alimentação" com `directCents` 10000, `totalCents` 15000, `percent` `100.00` e um filho "Mercado" com 5000 e `33.33`; `incomeByCategory` tem "Salário" com 250000 e `100.00`; receitas e despesas vêm em listas separadas, cada uma sobre o próprio total; várias categorias (Alimentação 6000, Transporte 3000, Lazer 1000) vêm da maior para a menor (`60.00`, `30.00`, `10.00`) e empate de total ordena por nome sem diferenciar maiúsculas; categoria sem lançamento no período não aparece; categoria **arquivada** com lançamento aparece, e subcategoria arquivada aparece dentro da principal; lançamento só na principal (sem filhos) → `children` vazio e `directCents` = `totalCents`; lançamentos de receita/despesa **sem categoria** formam o grupo "Sem categoria" (`categoryId` nulo, `children` vazio) e entram no total do tipo e nos percentuais; a soma dos `totalCents` dos grupos de cada tipo é exatamente o total do tipo; a soma de `directCents` + filhos de cada grupo = `totalCents`; mês sem despesas → lista de grupos vazia (e `percent` não é calculado); transferências nunca viram grupo; excluídos não contam

### Implementation for User Story 2

- [ ] T011 [US2] Em `MAIN/dashboard/DashboardService.java`, executar a **consulta 2** de [data-model.md](data-model.md) (receita e despesa por categoria no período, `left join categories c` e `left join categories root on root.id = coalesce(c.parent_id, c.id)`, ambos com `workspace_id` igual ao do lançamento, sem filtrar `archived`, sem `TRANSFER`, sem excluídos, agrupando por tipo, raiz e categoria) e montar `incomeByCategory` e `expenseByCategory` com `DashboardRules.groups` usando os totais de `totals`

**Checkpoint**: US2 funciona e `CategoryGroupsTest` passa; `./mvnw verify` verde

---

## Phase 4: User Story 3 - Evolução diária e comparação com o período anterior (Priority: P2)

**Goal**: um item por dia do período e a variação dos totais em relação ao período anterior

**Independent Test**: no cenário, `daily` de novembro tem 30 itens; `comparison.expense` tem anterior 20000, variação `-5000` e `-25.00`

### Tests for User Story 3

- [ ] T012 [P] [US3] `TEST/dashboard/ComparisonTest.java` (Testcontainers + MockMvc): `daily` de `month=2026-11` tem 30 itens em ordem de calendário, com despesa 10000 no dia 02, 5000 no dia 03, receita 250000 no dia 05 e zeros nos dias sem movimento; fevereiro de 2024 tem 29 itens e o de 2025, 28; a soma de `incomeCents` dos dias e a de `expenseCents` são iguais aos totais; `comparison.previousPeriod` é `2026-10`; `comparison.expense`: `previousCents` 20000, `changeCents` `-5000`, `changePercent` `-25.00`; `comparison.income`: anterior 0, `changeCents` 250000 e `changePercent` **nulo** (não é erro); `comparison.result`: anterior `-20000`, atual `235000`, `changeCents` 255000 e `changePercent` `1275.00` (base absoluta); `month=2000-01` devolve a comparação com `previousPeriod` `1999-12`, tudo zerado e sem erro; uma despesa de R$ 0,01 no mês anterior e nenhuma no atual dá `changeCents` `-1` e `changePercent` `-100.00`; transferência no mês anterior não entra na comparação

### Implementation for User Story 3

- [ ] T013 [US3] Em `MAIN/dashboard/DashboardService.java`, usar as linhas da consulta 1 (já lidas para o período anterior) para montar `daily` (um `DayPoint` por `period.days()`, com zero nos dias sem linha; `balanceCents` ainda 0 até a US5) e `comparison` (`PeriodInfo` do período anterior; `Change` de receitas, despesas e resultado com `DashboardRules.changePercent`, `previousCents` somados com `addExact`)

**Checkpoint**: US3 funciona e `ComparisonTest` passa; `./mvnw verify` verde

---

## Phase 5: User Story 4 - Acompanhar semana a semana (Priority: P2)

**Goal**: o mesmo painel para uma semana ISO (segunda a domingo), escolhida por `week=AAAA-Www`

**Independent Test**: `GET .../dashboard?week=2026-W45` no cenário devolve os 7 dias de 02/11 a 08/11, receitas 250000, despesas 15000 e comparação com a `2026-W44`

### Tests for User Story 4

- [ ] T014 [P] [US4] `TEST/dashboard/WeeklyDashboardTest.java` (Testcontainers + MockMvc): `week=2026-W45` devolve `period` (`WEEK`, `2026-W45`, `2026-11-02`, `2026-11-08`), `totals` 250000 / 15000 / 235000, `expenseByCategory` com Alimentação 15000 e Mercado 5000, `daily` com 7 itens de 02/11 a 08/11 e soma igual aos totais, `comparison.previousPeriod` `2026-W44` (02… 26/10 a 01/11) com despesas anteriores 20000 (o lançamento de 30/10), variação `-5000` e `-25.00`; lançamentos de segunda e de domingo da semana contam, os do domingo anterior e da segunda seguinte não (limites inclusivos); semana que atravessa a virada de mês (26/10 a 01/11 = `2026-W44`) conta os sete dias dos dois meses; semana que atravessa a virada de ano (`2025-W01`, 30/12/2024 a 05/01/2025) conta os sete dias; semana sem lançamentos → totais zerados e 7 dias de zeros; `2026-W53` (28/12/2026 a 03/01/2027) é aceita; as regras de transferência, excluído, arquivadas e "Sem categoria" valem igual; sem `month`, `week` presente → o mês não é usado (o painel é o da semana)

### Implementation for User Story 4

- [ ] T015 [US4] Em `MAIN/dashboard/DashboardController.java`, aceitar também `@RequestParam(required = false) String week` e resolver com `Period.resolve(month, week, clock.instant())`; o `DashboardService` não muda (o período já traz início, fim, anterior e dias). Se `WeeklyDashboardTest` revelar diferença de comportamento entre mês e semana, corrigir em `Period`/`DashboardService`

**Checkpoint**: US4 funciona e `WeeklyDashboardTest` passa; `./mvnw verify` verde

---

## Phase 6: User Story 5 - Saldos no painel: de hoje, de abertura, de fechamento e por dia (Priority: P2)

**Goal**: saldo atual, de abertura e de fechamento por conta ativa e no total, e o saldo total ao fim de cada dia

**Independent Test**: no cenário, semana 2026-W45: Corrente abre com 80000 e fecha com 285000, Poupança 0 e 30000, total 80000 e 315000; `daily[].balanceCents` = 70000, 65000, 65000, 315000, 315000, 315000, 315000

### Tests for User Story 5

- [ ] T016 [P] [US5] `TEST/dashboard/DashboardBalancesTest.java` (Testcontainers + MockMvc): no cenário, `week=2026-W45`: `balances.accounts` tem Corrente (`openingBalanceCents` 80000, `closingBalanceCents` 285000, `balanceCents` 285000) e Poupança (0, 30000, 30000), por nome; `openingTotalCents` 80000, `closingTotalCents` 315000, `totalBalanceCents` 315000; `daily[].balanceCents` na ordem: 70000, 65000, 65000, 315000, 315000, 315000, 315000 (o dia da transferência não muda o total), e o do último dia é igual a `closingTotalCents`; os saldos de hoje e o total são **idênticos** aos de `GET /reports/balance` (conta a conta e total) chamado no mesmo teste; mês passado: o saldo de hoje continua sendo o de hoje, enquanto abertura e fechamento são os do mês; data de corte antes de qualquer lançamento → abertura = soma dos saldos iniciais; conta **arquivada** fica fora de `accounts`, do total e do saldo diário (inclusive uma transferência de conta ativa para arquivada muda o total do dia, como na `002`); lançamento excluído não conta em nenhum saldo, nem de datas passadas; lançamento com data futura dentro do período conta no fechamento e nos saldos diários dos dias seguintes; cartão de crédito com saldo negativo reduz o total; o saldo inicial vale em qualquer data de corte; workspace sem contas → listas vazias, totais zero e saldos diários zero

### Implementation for User Story 5

- [ ] T017 [US5] Em `MAIN/dashboard/DashboardService.java`, executar a **consulta 3a** (saldos por conta ativa: saldo inicial, soma até o dia anterior ao início, soma até o fim do período e soma total, com `filter (where ...)`, filtro `workspace_id` nos dois lados do `left join`, `not a.archived`, ordem por nome em minúsculas e id) e a **consulta 3b** (movimento por dia das contas ativas no período, com transferências) de [data-model.md](data-model.md); calcular abertura, fechamento e saldo de hoje por conta, os totais com `addExact`, e `daily[].balanceCents` com `DashboardRules.runningBalances(openingTotal, period.days(), movimento)`; preencher `balances` e o `balanceCents` de cada `DayPoint`

**Checkpoint**: US5 funciona e `DashboardBalancesTest` passa; `./mvnw verify` verde

---

## Phase 7: User Story 6 - Só quem é do workspace vê, e só o dele (Priority: P1)

**Goal**: papéis e isolamento por workspace nas duas camadas, e leitura que não altera nada

**Independent Test**: não-membro recebe 404, VIEWER lê, sem sessão recebe 401, `POST`/`PUT`/`DELETE` recebem 405

### Tests for User Story 6

- [ ] T018 [P] [US6] `TEST/dashboard/DashboardAccessTest.java` (acesso cruzado, constituição II): usuário de outro workspace tentando `GET /workspaces/{wid}/dashboard` (com `month` e com `week`) recebe 404 igual ao de workspace inexistente (mesmo corpo, sem revelar nada); VIEWER, EDITOR e OWNER leem (200); sem cookie → 401; `POST`, `PUT`, `PATCH` e `DELETE` na rota → 405 e nenhuma linha de `transactions`, `accounts` e `categories` muda (contagem e `updated_at` antes e depois, pelo `OwnerJdbc`); dois workspaces com dados: o painel de cada um não inclui lançamentos, contas nem categorias do outro (totais, grupos e saldos)
- [ ] T019 [P] [US6] `TEST/dashboard/DashboardRlsTest.java` (RLS, segunda camada): conectando como `tostao_app` com o contexto de um workspace, as quatro consultas do painel (SQL de [data-model.md](data-model.md), executado direto, sem o filtro `workspace_id` do serviço) só enxergam dados desse workspace; sem contexto de workspace não veem nada; contexto de outro workspace não vê os dados do primeiro
- [ ] T020 [P] [US6] `TEST/dashboard/DashboardServiceFilterTest.java` (primeira camada isolada): com a conexão do **dono** (RLS desligado), chamar `DashboardService.dashboard` direto para um workspace com dados de outro workspace no mesmo banco e conferir que nada do outro workspace entra em nenhuma soma, grupo ou saldo (o filtro explícito de `workspace_id` e as junções seguram sozinhos); mesmo para `Period` de mês e de semana
- [ ] T021 [P] [US6] `TEST/dashboard/DashboardReadOnlyTest.java`: consultar o painel (mês, semana, mês vazio) não altera nenhum dado do workspace — contagem de linhas, soma de `updated_at` e de `amount_cents` de `transactions`, `accounts` e `categories` iguais antes e depois; a transação do serviço é somente leitura (tentar um `update` na mesma conexão falha com erro de transação read-only, chamando o serviço por um ponto de extensão do teste ou conferindo `TransactionSynchronizationManager.isCurrentTransactionReadOnly()` num `@TestConfiguration` que decora o `JdbcClient`; escolher a forma mais simples que prova SC-010)

**Checkpoint**: US6 provada nas duas camadas; `./mvnw verify` verde

---

## Phase 8: User Story 7 - Pedir um mês ou uma semana inválidos (Priority: P3)

**Goal**: erro claro com o campo, em vez de painel errado ou falha interna

**Independent Test**: `month=2026-13` e `week=2025-W53` devolvem 400 com `errors[0].field`

### Tests for User Story 7

- [ ] T022 [P] [US7] `TEST/dashboard/DashboardValidationTest.java` (Testcontainers + MockMvc): 400 com `errors[0].field` = `month` para `2026-13`, `2026-00`, `2026-1`, `novembro`, `2026-11-05`, `1999-12`, `2101-01`, `month=` (vazio) e `%20`; 400 com campo `week` para `2025-W53`, `2026-W54`, `2026-W00`, `2026W45`, `1999-W52`, `2101-W01`, `week=` (vazio) e `2026-w45` (minúscula); `month=2026-11&week=2026-W45` → 400 com campo `week` e a mensagem nomeando os dois; `month=2026-11&month=2026-10` → 400 (campo `month`); `week=2026-W53` e `month=2000-01` e `month=2100-12` → 200; mês válido sem lançamentos → 200 com painel zerado; nenhuma resposta de erro traz stack trace nem detalhe interno e todas usam `application/problem+json`; parâmetro desconhecido (`foo=bar`) é ignorado (200). Se alguma resposta sair diferente do esperado, corrigir em `Period`/`DashboardController`

**Checkpoint**: US7 provada; `./mvnw verify` verde

---

## Phase 9: Polish e provas transversais

**Purpose**: contrato, provas com dados aleatórios, consistência, desempenho, documentação e validação real

- [ ] T023 [P] Em `TEST/OpenApiContractTest.java`, acrescentar `DASHBOARD_CONTRACT` (`specs/005-dashboard-mensal/contracts/openapi.yaml`), um teste `dashboardContractFileDeclaresTheExpectedRoutes` (só `/api/v1/workspaces/{wid}/dashboard`, só `get`) e incluir o contrato em `everyOperationInTheContractsExistsInTheGeneratedDocs`; teste de que a rota gerada pelo springdoc não declara `post`, `put`, `patch` nem `delete`
- [ ] T024 [P] `TEST/dashboard/DashboardRandomDataTest.java` (SC-001, SC-003, SC-004, SC-005, SC-013): dados aleatórios com **semente fixa** (ex.: 20261107) — vários meses, receitas, despesas, transferências entre contas ativas e para arquivadas, excluídos, categorias principais, subcategorias, arquivadas e lançamentos sem categoria, datas nos limites de mês e de semana. Para uma lista de meses e semanas, comparar **cada número** do painel (totais, grupos e filhos, percentuais, 7 ou 28–31 dias, comparação, saldos de abertura, de fechamento, de hoje e por dia) com um **cálculo independente** escrito no teste em Java puro sobre as mesmas linhas lidas pelo `OwnerJdbc` (não reaproveita `DashboardRules` nem as consultas do serviço); conferir também as identidades: soma dos grupos = total do tipo, soma dos dias = totais, último saldo diário = fechamento, saldo de hoje e total idênticos a `GET /reports/balance`
- [ ] T025 [P] `TEST/dashboard/DashboardConsistencyTest.java` (SC-009, FR-013): uma thread insere, edita (via `OwnerJdbc`) e exclui logicamente lançamentos e outra consulta o painel em laço (mês e semana); em **todas** as respostas, soma dos grupos = total do tipo, soma dos dias = totais, último saldo diário = `closingTotalCents`, e `resultCents` = receitas − despesas; nenhuma resposta é 500
- [ ] T026 [P] `TEST/dashboard/DashboardPerformanceTest.java` (SC-008): semear 20.000 lançamentos (`generate_series`, datas em vários meses, algumas transferências e excluídos, categorias e subcategorias) em um workspace e medir a mediana de 5 chamadas para o mês corrente, um mês qualquer e uma semana qualquer; cada mediana abaixo de 1000 ms; registrar os tempos medidos no log do teste. Se falhar, olhar `EXPLAIN` das consultas antes de pensar em índice (índice novo só por migration `V6`, e só com a medição na mão)
- [ ] T027 [P] Atualizar `README.md` com exemplos de `curl` do painel (`dashboard?month=2026-11`, `dashboard?week=2026-W45`) e uma linha dizendo que as despesas saem positivas, o início da semana é a segunda-feira e os saldos de abertura e de fechamento valem para contas ativas
- [ ] T028 Rodar `cd backend && ./mvnw -B verify` e registrar o resultado (quantidade de testes, falhas, tempos de `DashboardPerformanceTest`); corrigir o que falhar antes de seguir
- [ ] T029 Validar o [quickstart.md](quickstart.md) de verdade: `docker compose up -d --build` (sem migration nova; histórico do Flyway continua na versão 5), executar os passos 2 a 7 com `curl` (JSON com acento como arquivo UTF-8), conferir cada número esperado, depois a limpeza do passo 8 (`users`, `workspaces`, `accounts`, `categories`, `transactions` = 0 no banco local). Guardar o roteiro como script no scratchpad
- [ ] T030 Marcar todas as tarefas como `[X]` neste arquivo, atualizar a nota do Obsidian (`D:\Paulo\Projetos\Tostão\Tostão.md`: marcar `005-dashboard-mensal` com data, o que faz, decisões e regras aprendidas, e que a fase 1 está concluída) e fazer o commit final de documentação

---

## Dependencies & Execution Order

- **Foundational (T001–T006)** bloqueia tudo. T001/T003/T005 são independentes entre si; T002 depende de T001, T004 de T003 (e de T005 para os tipos), T006 de nada além do que já existe.
- **US1 (T007–T009)** vem primeiro: T008 depende de T001 e T005; T009 de T008. Os testes (T007) podem ser escritos antes e falham até T009.
- **US2 (T010–T011)**, **US3 (T012–T013)**, **US5 (T016–T017)** acrescentam blocos ao `DashboardService` (mesmo arquivo): rodar em sequência, não em paralelo. **US4 (T014–T015)** só mexe no controller e pode vir depois da US1.
- **US6 (T018–T021)** e **US7 (T022)** são só testes e podem rodar em paralelo entre si depois da US1 (a US6 completa depois da US5, para provar o painel inteiro).
- **Polish (T023–T030)**: T023–T027 em paralelo; T028 depois de todos os testes; T029 depois de T028; T030 por último.

### Parallel Example

```text
Foundational: T001, T003, T005, T006 juntos; depois T002 e T004
Depois da US5: T018, T019, T020, T021, T022 juntos
Polish: T023, T024, T025, T026, T027 juntos
```

## Implementation Strategy

- **MVP**: Foundational + US1 (resumo do mês) já entrega o total do mês. Como a spec fecha a fase 1, o alvo é a feature inteira (US1 a US7), em ordem de prioridade: P1 (US1, US2, US6), P2 (US3, US4, US5), P3 (US7).
- **Incremental**: cada história é um commit pequeno com os testes dela verdes; ao fim de cada fase, `./mvnw verify`.
- **Prova antes de pronto**: nada é marcado como concluído sem teste verde; o desempenho (T026) e o quickstart real (T029) são provas obrigatórias, não opcionais.
