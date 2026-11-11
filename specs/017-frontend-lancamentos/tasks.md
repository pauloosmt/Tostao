---

description: "Tarefas da feature 017-frontend-lancamentos"
---

# Tasks: Lançamentos no front-end

**Input**: documentos em `/specs/017-frontend-lancamentos/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/ui-contract.md](contracts/ui-contract.md), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição I, II, IV, V e VIII; SC-004 e SC-005 pedem o formulário e a transferência provados; SC-002 e SC-003 pedem paginação e filtros; SC-007 e SC-008 pedem papéis e troca de workspace; SC-009 pede acessibilidade).

**Organization**: só front-end. Ordem: a base pura (selos da linha, datas, API, validação, linhas de exibição, a API de mentira dos testes), depois as peças de interface (área de texto, filtros, referência, paginação), a lista, os formulários (criar, transferir, editar, efetivar, excluir) e a prova entre telas, a verificação e o roteiro real.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação: `FE` = `frontend`, `SRC` = `frontend/src`
- **Commits**: pequenos (**no máximo uns 6 a 8 arquivos, uma ideia**), Conventional Commits, mensagem curta em inglês, **sem co-author**; código junto com os testes que o provam; sem commit só para marcar tarefa (a marca `[X]` vai no commit que fecha a tarefa). Antes de reescrever qualquer commit, conferir `git log origin/main..HEAD`; nunca fazer push
- **Rodar neste computador**: a partir de `frontend/`, `npm run verify`; conferir o **código de saída**, sem passar por `| tail`; **só commitar se o `verify` passou** (`&&` de verdade)
- **O guard-rail proíbe** a palavra do cabeçalho de autorização em qualquer arquivo de `src`, o `?token=` e parecidos (testes inclusive; montar o texto em pedaços) e origens `http(s)://` externas
- **Testes pesados**: o jsdom é lento; digitar texto longo com `user.type` estoura o tempo sob carga (usar `user.paste`), uma lista grande não passa de ~300 itens, e esperas por estado transitório ("Carregando…") usam uma API que só responde quando o teste libera
- **Sem dependência nova**; **README**: não mexer (os READMEs são curtos; o completo vem ao fim do projeto)
- **Cuidado com o shell**: não usar crases dentro de `node -e "..."` (o shell as executa); para editar vários arquivos, escrever o script em arquivo

---

## Phase 1: Base pura

- [X] T001 `SRC/design-system/src/components/TransactionRow.tsx`: propriedade `tags?: string[]` (selos neutros ao lado de "Pendente", na linha de apoio), aditiva; testes novos em `TransactionRow.test.tsx` (sem `tags` nada muda; com `tags` aparecem e a linha continua um botão só)
- [X] T002 [P] `SRC/dates/dates.ts` e `dates.test.ts`: `todayISO()` com a data local (nunca `toISOString`), `parseISODate`, `validateDateText` (formato, dia que existe, ano de 2000 a 2100, 29 de fevereiro), `formatDay(iso, hoje)` ("Hoje", "Ontem", "3 de nov. de 2026"), com testes de virada de dia e de ano e de fuso (data local à noite)
- [X] T003 [P] `SRC/api/types.ts` (os tipos de [data-model.md](data-model.md)) e `SRC/api/transactions.ts` (listar com filtros, ler, criar, editar, efetivar, excluir) com `SRC/api/transactions.test.ts`: caminhos e métodos, só os filtros preenchidos vão como parâmetro (`from`, `to`, `accountId`, `categoryId`, `type`, `status`, `q`, `page`, `size`), `q` codificado (`%` e `_`), o corpo exatamente como recebido, nada de credencial no endereço
- [X] T004 `SRC/validation/transactionForm.ts` e `transactionForm.test.ts`: regras de [data-model.md](data-model.md) (valor de 1 centavo ao limite, data, descrição 200 e 201, observações 1.000 e 1.001, conta, categoria, origem e destino iguais), montadores `buildCreateTransaction` e `buildUpdateTransaction` (centavos inteiros positivos, sem categoria na transferência, sem destino fora dela, `status` só `PENDING` na criação e sempre na edição, observações vazias omitidas, sem o tipo na edição), `transactionToForm` e `transferEnds` (origem e destino pelo sinal do valor e pela outra ponta, qualquer ponta)
- [X] T005 [P] `SRC/transactions/rows.ts` e `rows.test.ts`: a linha de exibição (descrição, conta, categoria ou "Sem categoria", "Para X"/"De Y" na transferência, valor absoluto e tipo do `TransactionRow`, pendente, selos "Parcela N de M", "Recorrente", "Importado"), o agrupamento por dia das linhas consecutivas, tipo e estado desconhecidos sem quebrar, nomes de contas e categorias arquivadas
- [X] T006 `SRC/test/fakeTransactions.ts` e `fakeTransactions.test.ts`: a API de mentira com memória para lançamentos (lista paginada de 20 em 20, do mais recente ao mais antigo, filtrada por `from`, `to`, `accountId`, `categoryId` com subcategorias, `type`, `status` e `q`; criar, editar, efetivar e excluir; a transferência de duas pontas; as regras de `400` do contrato), provada contra o comportamento do contrato, usada pelos testes de tela

## Phase 2: Peças de interface

- [X] T007 [P] `SRC/ui/TextAreaField.tsx` e `SRC/ui/textarea.test.tsx`: rótulo, dica, erro ligados, contador de caracteres, teclado, desabilitada, axe
- [X] T008 `SRC/transactions/useFilters.ts` e `filters.tsx` (`TransactionFiltersProvider`, `useTransactionFilters`) com `filters.test.tsx`: o padrão vazio e página 1; aplicar volta à página 1; limpar; outro workspace vê o padrão (derivado); sobrevive a desmontar e montar telas dentro do provider; o `key` por pessoa os zera; nada vai ao endereço nem ao armazenamento
- [X] T009 [P] `SRC/transactions/reference.ts` e `reference.test.tsx`: `useReferenceData` (contas e categorias com as arquivadas, mapas de nome, listas ativas, opções de categoria por tipo com "Principal › Sub", opção da conta ou categoria arquivada que o lançamento já usa), carregando e erro com nova tentativa, descarte ao trocar de workspace
- [X] T010 [P] `SRC/transactions/Pager.tsx` e `pager.test.tsx`: "Página N de M", "Anterior" e "Próxima" indisponíveis nos extremos, uma só página sem controles, nomes acessíveis, axe

**Checkpoint**: datas, API, validação, linhas, a API de mentira e as peças estão provadas sem nenhuma tela

---

## Phase 3: Lista e filtros

- [X] T011 [US1] `SRC/transactions/TransactionsPage.tsx`, "Lançamentos" na navegação (`SRC/layout/AppShell.tsx`), a rota `/lancamentos` em `SRC/App.tsx` e o provider em `SRC/auth/RequireAuth.tsx`, com `TransactionsPage.test.tsx`: ordem da API, grupos por dia, linhas com valor, conta, categoria, selos e pendente, as duas pontas da transferência, nomes de arquivadas, estado vazio (com "Novo lançamento" só para quem altera), carregando, erro com "Tentar de novo" (lista e referência), paginação (próxima, anterior, extremos, resposta atrasada descartada, página além do fim cai na última), a linha leva a `/lancamentos/:id`, lista de 100 itens, axe
- [X] T012 [US4] `SRC/transactions/TransactionFilters.tsx` e a integração na lista, com `TransactionFilters.test.tsx`: abrir e fechar o painel, cada filtro chega à API pelo parâmetro certo e a combinação, aplicar volta à página 1, contagem em "Filtros (N)", limpar, data final antes da inicial e busca de 101 caracteres recusadas no campo, `%` e `_` literais, "nenhum encontrado" com "Limpar filtros", os filtros sobrevivem a abrir um lançamento e voltar, trocar de workspace os zera, axe

## Phase 4: Formulários

- [ ] T013 [US2] `SRC/transactions/TransactionForm.tsx`, `TransactionFormPage.tsx` (`NewTransactionPage`) e a rota `/lancamentos/novo`, com `NewTransactionPage.test.tsx`: receita e despesa (tipo, valor, data de hoje, descrição, conta, categoria do tipo, observações), só categorias e contas ativas, trocar o tipo troca e descarta a categoria, valor zero e negativo e fora do limite, descrição 200 e 201, data inválida e fora de 2000 a 2100, foco no primeiro erro, `400` no campo, `403`, falha de rede com "Tentar de novo", envio duplicado, cancelar, volta à lista com o aviso, o leitor vê o aviso de papel, axe
- [ ] T014 [US3] a transferência em `TransactionForm.tsx`, com os testes novos: "De" e "Para" no lugar de conta e categoria, origem igual ao destino recusada no campo do destino, o corpo leva origem e destino e nenhuma categoria, trocar o tipo descarta destino ou categoria e volta a pedir a categoria, as duas pontas na lista depois de salvar
- [ ] T015 [US5] edição e consulta em `TransactionFormPage.tsx` (`TransactionPage`) e a rota `/lancamentos/:id`, com `EditTransactionPage.test.tsx`: abre preenchido com o tipo visível e não editável, salvar atualiza e volta com o aviso, a transferência abre com origem e destino certos pela ponta tocada (as duas pontas), salvar envia origem e destino (nunca o tipo), as mesmas regras do cadastro, `404` ao abrir (não encontrado) e ao salvar (volta à lista atualizada), conta ou categoria arquivada do lançamento aparece como opção marcada, a recusa da API de parcela, recorrência e importado aparece como vier, o leitor vê os dados só para leitura sem ações, axe
- [ ] T016 [US5] o estado pendente e "Efetivar": a caixa "Pendente" na criação (envia `PENDING`) e na edição (envia o estado), "Efetivar" na página de um pendente (chama a API, volta com o aviso, some para efetivados, efetivar um efetivado não dá erro), a falha mantém o lançamento, envio duplicado, o leitor não vê "Efetivar", com os testes novos
- [ ] T017 [US6] "Excluir" em `TransactionFormPage.tsx`, com os testes novos: confirmação de perigo com foco em "Cancelar" e o texto do efeito pelo tipo (lançamento, transferência, parcela, recorrência), cancelar por botão e por Esc não chama a API, confirmar exclui e volta com o aviso, a falha mantém o lançamento, `404` volta à lista, envio duplicado, o leitor não vê "Excluir"

**Checkpoint**: todos os fluxos de lançamentos funcionam em teste

---

## Phase 5: Papéis, workspace e prova

- [ ] T018 [US7] `SRC/transactions/access.test.tsx`: o leitor vê lista e consulta sem nenhuma ação e o endereço de criar mostra o aviso; a recusa `403` da API (criar, salvar, efetivar, excluir) vira a mensagem de permissão; trocar de workspace no perfil recarrega lista, contas e categorias do novo, zera os filtros e descarta a resposta atrasada; o endereço de lançamento de outro workspace é "não encontrado"; um `401` na lista leva à entrada com o aviso
- [ ] T019 Rodar `npm run verify` completo e o `./mvnw -B verify` do back-end; conferir o orçamento (dividir por rota se passar de 130 KB); corrigir qualquer falha nova sem enfraquecer testes; registrar as contagens
- [ ] T020 Executar o [quickstart.md](quickstart.md): o roteiro HTTP contra a API do Compose (receita, despesa, transferência, pendente, filtros, efetivar, excluir, regras, papéis, saldos, limpeza de `qs17*`) e o que dá para conferir no navegador; registrar no quickstart o que foi e o que ficou para quem usa o projeto; marcar as tarefas restantes como `[X]` junto do último commit que as fecha; não fazer push

---

## Dependencies & Execution Order

- **Phase 1**: T001, T002, T003, T005 independentes; T004 depois de T002 e T003; T005 depois de T003; T006 depois de T003
- **Phase 2**: T007 independente; T008 depois de T003; T009 depois de T003; T010 independente
- **Phase 3**: T011 depois de T001, T005, T006, T008, T009 e T010; T012 depois de T002, T007 e T011
- **Phase 4**: T013 depois de T004, T006, T007, T009 e T011; T014 depois de T013; T015 depois de T014; T016 depois de T015; T017 depois de T016
- **Phase 5**: T018 depois de T017; T019 depois de tudo; T020 por último

## Commits previstos (cada um pequeno, com os testes que o provam)

1. `feat(frontend): let the transaction row show extra tags` — T001
2. `feat(frontend): add the date helpers` — T002
3. `feat(frontend): add the transactions API` — T003
4. `feat(frontend): add the transaction rules` — T004
5. `feat(frontend): add the transaction display rows` — T005
6. `test(frontend): add the in-memory transactions API` — T006
7. `feat(frontend): add the text area field` — T007
8. `feat(frontend): keep the transaction filters by workspace` — T008
9. `feat(frontend): add the reference data loader` — T009
10. `feat(frontend): add the pager` — T010
11. `feat(frontend): add the transactions list` — T011
12. `feat(frontend): filter the transactions` — T012
13. `feat(frontend): add the transaction form` — T013
14. `feat(frontend): add transfers to the form` — T014
15. `feat(frontend): edit a transaction` — T015
16. `feat(frontend): mark transactions pending and clear them` — T016
17. `feat(frontend): delete a transaction` — T017
18. `test(frontend): prove roles and workspace switching for transactions` — T018
19. `docs(spec): record the transactions validation` — T019 e T020, com as marcas no último commit

## Implementation Strategy

1. A base pura primeiro (T001 a T006): datas, API, regras, linhas e a API de mentira provadas sem tela.
2. As peças de interface (T007 a T010), também sem tela.
3. A lista (T011) e os filtros (T012); só depois os formulários (T013 a T017), que reaproveitam tudo.
4. T018 prova papéis e workspace; T019 e T020 fecham a verificação e o roteiro real.
5. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
