---

description: "Tarefas da feature 008-fatura-cartao"
---

# Tasks: Fatura do cartão de crédito

**Input**: documentos em `/specs/008-fatura-cartao/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição II, V e VIII; FR-014 pede acesso cruzado por consulta).

**Organization**: só backend, só leitura, **sem migration**. A ordem prioriza ter a funcionalidade funcionando: primeiro a regra pura de calendário com seus testes (sem Docker), depois o serviço e as três rotas de ponta a ponta, depois a prova mais funda (isolamento por camada, dados aleatórios, desempenho, contrato).

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação de caminhos: `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`
- **Commits**: pequenos, Conventional Commits, **sem co-author**; testes rodados antes de cada commit; nunca push sem pedido
- **Rodar Maven neste computador**: `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`, Docker Desktop aberto e no PATH
- **Valores**: centavos inteiros (`long`), soma com `Math.addExact`

---

## Phase 1: Regras puras e base

- [ ] T001 [P] Criar `MAIN/invoice/InvoiceStatus.java` (`OPEN`, `CLOSED`, `PAID`, `OVERDUE`) e `MAIN/invoice/InvoiceCalendar.java`, classe final com métodos estáticos puros: `record Dates(YearMonth month, LocalDate periodStart, LocalDate closing, LocalDate due, LocalDate nextClosing)`; `Dates dates(int closingDay, int dueDay, YearMonth dueMonth)` conforme [data-model.md](data-model.md) (vencimento `min(D, len)`; mês do fechamento = `dueMonth` se `D > C` senão `dueMonth - 1`; fechamento `min(C, len)`; `periodStart` = fechamento da fatura de `dueMonth - 1` mais 1 dia; `nextClosing` = fechamento da fatura de `dueMonth + 1`); `InvoiceStatus status(LocalDate today, LocalDate closing, LocalDate due, long remainingCents)` (`today <= closing` → `OPEN`; `remaining == 0` → `PAID`; `today > due` → `OVERDUE`; senão `CLOSED`); `YearMonth openDueMonth(int closingDay, int dueDay, LocalDate today)` (candidatos `today - 1 .. today + 2` meses, o primeiro cujo `periodStart <= today <= closing`)
- [ ] T002 [P] Criar `TEST/invoice/InvoiceCalendarTest.java`, teste **unitário** (JUnit puro, sem Docker): 20/28 em 2024-11 → fechamento 2024-11-20, vencimento 2024-11-28, `periodStart` 2024-10-21, `nextClosing` 2024-12-20; 25/5 em 2024-12 → fechamento 2024-11-25, vencimento 2024-12-05, `periodStart` 2024-10-26; 10/10 (`D <= C`) → fechamento no mês anterior; 31/31 em 2027-02 → fechamento e vencimento 2027-02-28 e `periodStart` 2027-01-01, em 2028-02 → 29/02, em 2026-04 → 30/04; 29/30 em fevereiro de ano comum e bissexto; 1/31 e 31/1; virada de ano (2024-01 com fechamento no mês anterior = 2023-12); **partição (SC-002)**: para **todas** as 31×31 combinações de `closingDay` e `dueDay` e 36 meses seguidos (inclusive 2027-02 e 2028-02), o `periodStart` de cada fatura é o dia seguinte ao fechamento da anterior, `periodStart <= closing`, e o fechamento cresce estritamente; **situação**: no dia do fechamento é `OPEN`, no dia seguinte com restante é `CLOSED`, no dia do vencimento com restante ainda `CLOSED`, no dia seguinte `OVERDUE`, com restante zero `PAID` (antes e depois do vencimento) e `OPEN` nunca vira `PAID`; **fatura aberta**: para cada dia de 3 anos e as combinações 20/28, 25/5, 10/10, 31/31, `openDueMonth` devolve uma fatura cujo período contém o dia, e só uma
- [ ] T003 [P] Em `MAIN/dashboard/Period.java` acrescentar `public static LocalDate today(Instant now)` (a data de `now` no fuso `ZONE` de Brasília) e testar em `TEST/dashboard/PeriodTest.java`: `2024-11-20T02:59:59Z` → 2024-11-19; `2024-11-20T03:00:00Z` → 2024-11-20
- [ ] T004 [P] Criar `MAIN/common/error/InvoiceNotAvailableException.java` (recebe a mensagem) e o handler em `ApiExceptionHandler` → 409 título "Conflito de estado", no estilo dos handlers de orçamento
- [ ] T005 [P] Criar os DTOs em `MAIN/invoice/dto/`: `InvoiceLine(UUID id, LocalDate date, String description, UUID categoryId, String categoryName, String type, long chargeCents)`, `InvoiceResponse(String month, LocalDate periodStart, LocalDate closingDate, LocalDate dueDate, long totalCents, long paidCents, long remainingCents, InvoiceStatus status, List<InvoiceLine> lines)`, `InvoiceListItem` (os mesmos campos, sem `lines`), `InvoiceListResponse(List<InvoiceListItem> invoices)`, `CardSummaryResponse(UUID accountId, String name, boolean archived, Long limitCents, long owedCents, Long availableCents, OpenInvoice openInvoice)` com `OpenInvoice(String month, LocalDate periodStart, LocalDate closingDate, LocalDate dueDate, long totalCents)`

**Checkpoint**: `InvoiceCalendarTest` e `PeriodTest` verdes (sem Docker)

---

## Phase 2: US1 + US3 - Ver a fatura de um mês, pagamentos e situação (P1/P2) 🎯 MVP

- [ ] T006 [US1] Criar `MAIN/invoice/InvoiceService.java` (`@Service`, injeta `JdbcClient` e `AccountService`) com `@WorkspaceScoped @Transactional(readOnly = true, isolation = REPEATABLE_READ) public InvoiceResponse invoice(UUID workspaceId, UUID accountId, YearMonth month, LocalDate today)`: lê `type, closing_day, due_day` de `accounts` com `where id = :a and workspace_id = :w` (nada → `AccountNotFoundException`; tipo diferente de `CREDIT_CARD` → `InvoiceNotAvailableException("A conta não é um cartão de crédito")`; dia nulo → `InvoiceNotAvailableException("O cartão não tem ... cadastrado: informe closingDay e/ou dueDay")` nomeando o que falta); `InvoiceCalendar.dates`; linhas: `select t.id, t.occurred_on, t.description, t.category_id, c.name, t.type, -t.amount_cents as charge from transactions t left join categories c on c.id = t.category_id and c.workspace_id = t.workspace_id where t.workspace_id = :w and t.account_id = :a and t.deleted_at is null and t.occurred_on between :start and :end and not (t.type = 'TRANSFER' and t.amount_cents > 0) order by t.occurred_on, t.id`; pago: `select coalesce(sum(amount_cents), 0)::bigint from transactions where workspace_id = :w and account_id = :a and deleted_at is null and type = 'TRANSFER' and amount_cents > 0 and occurred_on > :closing and occurred_on <= :nextClosing` (zero se a fatura está `OPEN`); total com `addExact`; `remaining = max(total - paid, 0)`; situação por `InvoiceCalendar.status`. O parâmetro `today` vem de fora para o serviço ser testável
- [ ] T007 [US1] Criar `MAIN/invoice/InvoiceController.java` (`@RestController`, `@RequestMapping("/api/v1/workspaces/{wid}/accounts/{accountId}")`, injeta `Clock`): `GET /invoices/{month}` validando `Period.ofMonth(month)` **antes** do serviço e passando `Period.today(clock.instant())`
- [ ] T008 [US1] [US3] `TEST/invoice/InvoiceScenario.java` (helper: workspace com dono, `Corrente`, `Poupança`, cartão `Visa` 20/28 com limite 500000, categoria de despesa, os lançamentos do passo 2 do [quickstart.md](quickstart.md) e helpers de MockMvc) e `TEST/invoice/InvoiceTest.java` (Testcontainers + MockMvc): fatura `2024-11` com `periodStart` 2024-10-21, `closingDate` 2024-11-20, `dueDate` 2024-11-28, `totalCents` 62000, as cinco linhas em ordem, sem a compra de 20/10 nem a de 21/11 nem a excluída nem o pagamento, `paidCents` 25000, `remainingCents` 37000 e `OVERDUE`; `2024-10` total 12345; `2024-12` total 5000; compra no dia do fechamento entra nela e a do dia seguinte na próxima; pagamento extra que cobre → `PAID` e restante zero; pagamento maior que o total → restante 0 e `paidCents` o valor real; mês sem lançamentos → lista vazia, total 0, `PAID`; só estornos → total negativo, restante 0, `PAID`; cartão 25/5 e 31/31 (inclusive `2027-02` e `2028-02`) com as datas certas; saldo inicial do cartão não entra; cartão arquivado continua consultável; soma exata 10 + 20 = 30; mês mal escrito (`2024-1`, `202411`, `2024-13`, `1999-12`, `2101-01`) → 400 `month`

**Checkpoint**: fatura de um mês funciona de ponta a ponta; `./mvnw verify` verde

---

## Phase 3: US2 - Resumo do cartão (P1)

- [ ] T009 [US2] Em `InvoiceService` acrescentar `@WorkspaceScoped @Transactional(readOnly = true, isolation = REPEATABLE_READ) public CardSummaryResponse summary(UUID workspaceId, UUID accountId, LocalDate today)`: `accounts.get(workspaceId, accountId)` (404 se não existe); tipo diferente de cartão → `InvoiceNotAvailableException`; `owed = max(-balance, 0)`; `available = limit == null ? null : limit - owed`; se tem os dois dias, a fatura aberta (`InvoiceCalendar.openDueMonth` + total do período como na T006), senão `null`. Em `InvoiceController`: `GET /card-summary`
- [ ] T010 [US2] `TEST/invoice/CardSummaryTest.java`: limite 500000 e devido 54345 → disponível 445655 e `owedCents` igual ao saldo negativo de `GET /accounts/{id}` (FR-016); cartão sem limite → `limitCents` e `availableCents` nulos; saldo positivo (pagamento a mais) → devido 0 e disponível limite + crédito; devido acima do limite → disponível negativo; `openInvoice` é a que contém hoje (mês esperado calculado no teste com o mesmo fuso, tolerando a virada de dia) com fechamento dia 20 e total do período; cartão sem dias → 200 com `openInvoice` nulo; conta que não é cartão → 409

---

## Phase 4: US4 - Lista de faturas (P2)

- [ ] T011 [US4] Em `InvoiceService` acrescentar `@WorkspaceScoped @Transactional(readOnly = true, isolation = REPEATABLE_READ) public InvoiceListResponse invoices(UUID workspaceId, UUID accountId, YearMonth from, YearMonth to, LocalDate today)`: valida o cartão e os dias como na T006; uma consulta agregada `select occurred_on, type, (amount_cents > 0) as inflow, sum(amount_cents)::bigint from transactions where workspace_id = :w and account_id = :a and deleted_at is null and occurred_on between :min and :max group by 1, 2, 3` (`min` = `periodStart` da primeira, `max` = `nextClosing` da última); distribui em Java, por fatura, as linhas do período (excluindo `TRANSFER` com `inflow`) e os pagamentos; ordena do vencimento mais recente para o mais antigo. Em `InvoiceController`: `GET /invoices?from=&to=` com `from`/`to` opcionais (sem nenhum: `to` = mês de vencimento da fatura aberta e `from` = `to - 5`, limitado a 2000-01); `Period.ofMonth(text, "from")` e `"to"`; recusa `from > to` (campo `from`) e mais de 36 meses (campo `to`), antes do serviço
- [ ] T012 [US4] `TEST/invoice/InvoiceListTest.java`: `from=2024-09&to=2024-12` → 4 faturas de 2024-12 a 2024-09, cada uma **igual** (datas, total, pago, restante, situação) à consulta individual do mesmo mês e sem `lines`; sem parâmetros → 6 faturas até a aberta; cartão sem lançamentos → faturas com total 0; `from=2024-1`, `to=2101-01`, `from` depois de `to`, 37 meses → 400 com o campo; 36 meses aceitos; um mês só (`from` = `to`)

---

## Phase 5: US5 - Cartões, papéis e isolamento (P1)

- [ ] T013 [P] [US5] `TEST/invoice/InvoiceAccessTest.java` (Testcontainers + MockMvc): conta `Poupança` nas três rotas → 409 "não é um cartão"; cartão sem `closingDay` e sem `dueDay`, só com um dos dois → 409 nomeando o que falta em fatura e lista, resumo 200; **VIEWER**, EDITOR e OWNER leem as três rotas; não-membro nas **três** rotas → 404 igual ao de workspace inexistente (mesmo `title` e `detail`); `accountId` de outro workspace, inexistente (`UUID` aleatório) e malformado → 404; sem cookie → 401; as consultas não alteram `accounts`, `transactions` nem `categories` (impressão digital igual, SC-011); a fatura de um workspace nunca soma lançamentos de outro (mesma conta de nome igual, dois workspaces)
- [ ] T014 [P] [US5] `TEST/invoice/InvoiceLayersTest.java` (SC-008): camada de dados sozinha: como `tostao_app` no contexto do workspace A, as consultas de linhas, pago e agregada por dia (copiar do serviço, constantes pacote-visíveis) não enxergam lançamentos do B; camada de serviço sozinha: com uma conexão do dono (RLS neutralizado), `InvoiceService.invoice`, `invoices` e `summary` no contexto de A com `accountId` do B → `AccountNotFoundException`, e uma conta de A não soma lançamentos do B (impressão digital igual)

---

## Phase 6: Polish

- [ ] T015 [P] `TEST/invoice/InvoiceRandomDataTest.java` (semente fixa 20261109 e +1): para cartões com combinações aleatórias de `closingDay` e `dueDay` (inclusive 29, 30, 31) e ~500 lançamentos aleatórios em 30 meses (despesas, receitas, saídas e entradas por transferência, excluídos, com datas nos dias de fechamento, no dia seguinte e em fim de mês), o total de cada fatura bate com um **cálculo independente no teste** (percorrendo os lançamentos, sem reaproveitar `InvoiceCalendar`), a lista é igual às consultas individuais, e **a soma dos totais de faturas consecutivas é igual à soma das linhas que contam** (SC-002, SC-003)
- [ ] T016 [P] `TEST/invoice/InvoicePerformanceTest.java` (SC-009): workspace com 20.000 lançamentos espalhados em 3 anos, um cartão com ~7.000; mediana de 5 chamadas de fatura, resumo e lista de 36 meses, cada uma abaixo de 1000 ms; imprimir as medianas; afirmar que a resposta é real. Se passar de 1 s, índice numa migration com a medição registrada
- [ ] T017 Em `TEST/OpenApiContractTest.java` acrescentar `INVOICES_CONTRACT` (`specs/008-fatura-cartao/contracts/openapi.yaml`): declara exatamente `GET` em `/invoices/{month}`, `/invoices` e `/card-summary`; `/v3/api-docs` expõe as mesmas operações e nenhuma de escrita; inclui no laço "todos os contratos"
- [ ] T018 Rodar `./mvnw -B verify` completo; corrigir qualquer falha **nova** sem enfraquecer testes (o `LoginTimingTest` já falhava antes e é da spec 001) e registrar a contagem
- [ ] T019 [P] Atualizar `README.md` com os `curl` da fatura (fatura, lista e resumo) e a nota de que a fatura é calculada, só de leitura, e usa os dias de hoje
- [ ] T020 Executar o [quickstart.md](quickstart.md) contra o Docker Compose reconstruído (`docker compose up -d --build`), com limpeza ao fim; marcar todas as tarefas como `[X]` e commitar; não fazer push

---

## Dependencies & Execution Order

- **Phase 1** é a base e quase toda sem Docker. T001 antes de T002; T003 a T005 em paralelo
- **US1+US3** (T006 a T008) depende da Phase 1. **US2** (T009, T010) e **US4** (T011, T012) dependem de T006 e T007
- **US5** (T013, T014) depende das três rotas. **Polish** (T015 a T020) por último; T018 depois de T017; T020 por último
- Tarefas que editam `InvoiceService.java` e `InvoiceController.java` são sequenciais

## Implementation Strategy

1. Regra pura de calendário provada primeiro (T001 a T003): é onde está o risco de cálculo e roda sem Docker.
2. Fatura de um mês de ponta a ponta (MVP), depois resumo e lista.
3. Isolamento por camada, dados aleatórios, desempenho e contrato fecham a prova.
4. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
