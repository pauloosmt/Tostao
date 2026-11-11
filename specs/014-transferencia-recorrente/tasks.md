---

description: "Tarefas da feature 014-transferencia-recorrente"
---

# Tasks: Transferência recorrente

**Input**: documentos em `/specs/014-transferencia-recorrente/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição II, V e VIII; FR-006 pede idempotência do par; FR-013 pede as duas camadas de isolamento; FR-015 pede prova de não regressão).

**Organization**: só backend; uma migration (`V15`). Ordem: schema e função (provados direto no banco), depois a criação, o detalhe e o encerramento, e as provas de ocorrências, efeitos, acesso e camadas. Contrato, README e quickstart real fecham.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação de caminhos: `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `MIG` = `backend/src/main/resources/db/migration`
- **Commits**: pequenos (**no máximo uns 6 arquivos, uma ideia**), Conventional Commits, mensagem curta em inglês, **sem co-author**; código junto com os testes que o provam; sem commit só para marcar tarefa (a marca `[X]` vai no commit que fecha a tarefa). Antes de reescrever qualquer commit, conferir `git log origin/main..HEAD`
- **Rodar Maven neste computador**: `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`, Docker Desktop aberto e no PATH. Mandar o log para um arquivo, com `timeout`, a partir de `backend/`
- **Reuso**: `RecurrenceScenario`, `RecurrenceCalendar`, `PendingScenario` e `AutomationService.runOnce(LocalDate)` da 011; a base compartilhada isola recorrências e lançamentos vencidos de outros testes (`@BeforeEach` como nos testes da 011)

---

## Phase 1: Base

- [X] T001 Criar `MIG/V15__recurring_transfer.sql` como no [data-model.md](data-model.md): `destination_account_id`, `category_id` opcional, tipo `TRANSFER`, as restrições `recurrences_destination_fkey` e `recurrences_shape`, o `CHECK` das ocorrências sem exigir tipo, o novo índice `transactions_recurrence_occurrence_key` com `(amount_cents > 0)` e a nova `generate_recurrence_occurrences` (`CREATE OR REPLACE`, duas pontas na mesma instrução, grupo `md5` determinístico, `ON CONFLICT` no índice novo, `ORDER BY`). Criar `TEST/recurrence/TransferRecurrenceSchemaTest.java` (restrições da forma; índice: uma saída e uma entrada por número e nada além; o `CHECK` de ocorrência) e `TEST/recurrence/TransferGenerationFunctionTest.java` (a função direto no banco: calendário igual ao da despesa, par completo com o mesmo grupo e sinais opostos, receita e despesa como antes, ativa só com as duas contas desarquivadas, idempotência em série, excluída não volta, vencidas e futuras). Em `TEST/recurrence/RecurrenceSchemaTest.java` atualizar só o caso que recusava uma ponta de transferência como ocorrência (agora aceita; a parcela que é ocorrência segue recusada)
- [X] T002 [P] Mover `requireUsableAccounts` de `MAIN/transaction/TransactionService.java` para `MAIN/transaction/UsableRefs.java` (público) e fazer o serviço usá-lo de lá. Refatoração sem mudança de comportamento: provada pelos testes de transferência existentes

**Checkpoint**: V15 aplicada e provada; a suíte da 011 segue verde; nada mais mudou

---

## Phase 2: US1 - Criar (P1)

- [X] T003 [US1] Em `MAIN/recurrence/RecurrenceRules.java` aceitar `TRANSFER` em `requireType` e acrescentar `requireShape(type, accountId, destinationAccountId, categoryId)` (transferência: destino obrigatório, diferente da origem, sem categoria; demais: categoria obrigatória, sem destino; cada recusa no campo). Atualizar em `TEST/recurrence/RecurrenceRulesTest.java` o caso que recusava a transferência e criar `TEST/recurrence/TransferRecurrenceRulesTest.java` com a tabela da forma
- [X] T004 [US1] Em `MAIN/recurrence/dto/CreateRecurrenceRequest.java` acrescentar `destinationAccountId` (logo depois de `accountId`) e tirar o `@NotNull` de `categoryId`, mantendo o construtor antigo (sem destino); em `RecurrenceResponse` e `RecurrenceDetailResponse` acrescentar `destinationAccountId` e aceitar `categoryId` nulo; em `MAIN/recurrence/RecurrenceService.java` ramificar `create` por tipo (forma, contas via `requireUsableAccounts`, categoria só nos demais, `insert` com o destino) e ler `destination_account_id` no resumo, e no detalhe somar a saída de cada ocorrência de transferência uma vez nos totais (a criação devolve o detalhe). Criar `TEST/recurrence/CreateTransferRecurrenceTest.java` (criar, ocorrências em pares pendentes sem categoria, nada muda nos saldos, cartão como destino, cada recusa por campo, conta arquivada 409, conta de outro workspace 404, atomicidade com falha no meio, receita e despesa iguais às da 011) e em `TEST/recurrence/CreateRecurrenceTest.java` tirar `TRANSFER` da lista de tipos inválidos

**Checkpoint**: criar uma transferência recorrente funciona de ponta a ponta; a suíte da 011 verde

---

## Phase 3: US3 - Ver e encerrar (P2)

- [X] T005 [US3] Em `MAIN/recurrence/RecurrenceService.java`: em `end`, travar por id, excluir as duas pontas das pendentes futuras e contar ocorrências distintas (`RETURNING occurrence_number`). Criar `TEST/recurrence/EndTransferRecurrenceTest.java` (lista com transferência e demais; detalhe com as duas pontas e totais por ocorrência; encerrar mantém efetivadas e vencidas e exclui os pares futuros; conta ocorrências; idempotente; encerrar e gerar em corrida não deixa par futuro; editar, efetivar e excluir uma ocorrência como transferência comum)

---

## Phase 4: US2 - Efetivar sozinha (P1)

- [X] T006 [US2] Criar `TEST/recurrence/TransferOccurrencesTest.java`: a execução automática (`runOnce`) gera as ocorrências e efetiva as duas pontas juntas com o marcador (saldos em exatos centavos, soma inalterada, marcador desligado nas duas); sem marcador nada é efetivado; execuções repetidas e simultâneas deixam um par por ocorrência e nunca uma ponta só; excluída não volta; conta arquivada pausa a geração e restaurar volta; uma recorrência com problema não impede as outras; dois workspaces sem mistura

---

## Phase 5: Provas (US4 e integração)

- [X] T007 [P] [US1] Criar `TEST/recurrence/TransferRecurrenceEffectsTest.java`: ocorrências pendentes nunca entram no saldo, no dashboard, nos orçamentos nem na fatura; efetivadas entram em exatos centavos nas duas contas; um pagamento recorrente do cartão efetivado conta como pagamento na fatura; o detalhe e a listagem de lançamentos mostram as duas pontas
- [X] T008 [P] [US4] Criar `TEST/recurrence/TransferRecurrenceAccessTest.java` (VIEWER lista e consulta e não cria nem encerra; não membro tem a mesma resposta de um workspace inexistente; sem sessão 401; conta de outro workspace 404; identificadores malformados) e `TEST/recurrence/TransferRecurrenceLayersTest.java` (camada de dados: o SQL como `tostao_app` sem filtro de workspace é barrado pelo RLS, inclusive a geração entre workspaces; camada de serviço: com a conexão do dono o filtro explícito isola sozinho)
- [X] T009 [P] [US2] Criar `TEST/recurrence/TransferRecurrencePerformanceTest.java`: execução automática com 20.000 lançamentos, 200 recorrências (um terço de transferência) e 2.000 vencidos com marcador em menos de 5 s; criar, consultar e listar em menos de 1 s (mediana de três, com aquecimento)

---

## Phase 6: Polish

- [ ] T010 Em `TEST/OpenApiContractTest.java` acrescentar `TRANSFER_RECURRENCE_CONTRACT` (`specs/014-transferencia-recorrente/contracts/openapi.yaml`) e conferir que declara as mesmas quatro rotas da 011, que `/v3/api-docs` expõe `destinationAccountId` no pedido e nas respostas, que `categoryId` não é obrigatório no pedido, e incluir o contrato no teste de "todas as operações do contrato existem"
- [ ] T011 Rodar `./mvnw -B verify` completo; corrigir qualquer falha **nova** sem enfraquecer testes e registrar a contagem. **Nenhum teste das specs 001 a 013 pode ter sido alterado além dos três que registravam a recusa da transferência** (conferir com `git diff`)
- [ ] T012 [P] Atualizar `README.md`: transferência recorrente (o tipo `TRANSFER` na rota de recorrências, destino e sem categoria, as duas pontas pendentes, efetivação automática juntas, encerrar conta ocorrências, cartão como destino)
- [ ] T013 Executar o [quickstart.md](quickstart.md) contra o Docker Compose reconstruído, com `Flyway` na versão 15 e limpeza ao fim (inclusive `recurrences`); marcar as tarefas restantes como `[X]` junto do último commit que as fecha; não fazer push

---

## Dependencies & Execution Order

- **Phase 1**: T001 primeiro; T002 independente
- **Phase 2**: T003 depois de T001; T004 depois de T002 e T003
- **Phase 3**: T005 depois de T004
- **Phase 4**: T006 depois de T004
- **Phase 5**: T007 a T009 em paralelo depois de T005 e T006
- **Phase 6**: T010 depois de T004; T011 depois de tudo; T013 por último

## Commits previstos (cada um pequeno, com os testes que o provam)

1. `feat(db): allow transfers in recurrences` — T001
2. `refactor(transaction): share the two-account check` — T002
3. `feat(recurrence): check the shape of a recurring transfer` — T003
4. `feat(recurrence): create a recurring transfer` — T004
5. `feat(recurrence): list and end recurring transfers by occurrence` — T005
6. `test(recurrence): prove the occurrences of a recurring transfer` — T006
7. `test(recurrence): prove the effects of a recurring transfer` — T007
8. `test(recurrence): prove access and isolation of recurring transfers` — T008
9. `test(recurrence): prove the recurring transfer stays fast` — T009
10. `test(api): check the recurring transfer contract` — T010
11. `docs: describe the recurring transfer in the README` — T012, com as marcas de T011 e T013 no último commit

## Implementation Strategy

1. Schema e função primeiro (T001), provados direto no banco: é pequeno e destrava o resto.
2. Regras e criação (T003, T004), depois detalhe e encerramento (T005): a criação e a visão completa são o MVP.
3. As ocorrências e a efetivação (T006) são a prova principal de que o par nunca se parte; efeitos (T007), acesso e camadas (T008) e desempenho (T009) completam.
4. Contrato, README e quickstart real fecham a prova.
5. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
