---

description: "Tarefas da feature 016-frontend-contas-categorias"
---

# Tasks: Contas e categorias no front-end

**Input**: documentos em `/specs/016-frontend-contas-categorias/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/ui-contract.md](contracts/ui-contract.md), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição I, II, IV, V e VIII; SC-002 pede o campo de dinheiro provado nos limites; SC-005 e SC-006 pedem papéis e troca de workspace; SC-008 pede acessibilidade).

**Organization**: só front-end. Ordem: mudanças pequenas no design system e na base pura (dinheiro, carregamento, formulários, papéis), depois as peças de interface e a API, depois as telas de contas, as de categorias, e por fim a prova entre telas (papéis e workspace), a verificação e o roteiro real.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação: `FE` = `frontend`, `SRC` = `frontend/src`
- **Commits**: pequenos (**no máximo uns 6 a 8 arquivos, uma ideia**), Conventional Commits, mensagem curta em inglês, **sem co-author**; código junto com os testes que o provam; sem commit só para marcar tarefa (a marca `[X]` vai no commit que fecha a tarefa). Antes de reescrever qualquer commit, conferir `git log origin/main..HEAD`; nunca fazer push
- **Rodar neste computador**: a partir de `frontend/`, `npm run verify` (lint, tipos, testes, build, orçamento, testes do build). Conferir o **código de saída**, sem passar por `| tail`
- **O guard-rail proíbe** a palavra do cabeçalho de autorização em qualquer arquivo de `src`, o `?token=` e parecidos em qualquer arquivo de `src` (testes inclusive; montar o texto em pedaços quando o teste precisa dele) e origens `http(s)://` externas
- **Sem dependência nova**: tudo com React, `react-router` e o que a 015 já trouxe
- **Mensagens e rótulos**: os de [data-model.md](data-model.md); tudo em português do Brasil

---

## Phase 1: Design system e base pura

- [X] T001 `SRC/design-system/src/components/AccountCard.tsx`: tipo desconhecido cai em "Conta" com o ícone de banco (hoje cai em "Conta corrente"); `AccountCard.test.tsx`: os cinco tipos, o desconhecido com o rótulo genérico, arquivada marcada, saldo negativo anunciado
- [X] T002 `SRC/ui/ConfirmDialog.tsx`: opção `tone` (`danger`, o atual, ou `primary`), com os testes novos em `SRC/ui/ui.test.tsx` (os existentes passam sem mudar)
- [X] T003 [P] `SRC/money/reais.ts` (`parseReais`, `formatReaisInput`, constantes de limite e mensagens) e `SRC/money/reais.test.ts`: a tabela de [research.md](research.md) §4, limites exatos (±R$ 10.000.000.000,00 e um centavo além), três casas decimais, ponto de milhar mal formado, `R$` e espaços, sinais `-` e `−`, vazio, texto, `1,2,3`, e ida e volta (`format` depois `parse`) para valores variados
- [X] T004 [P] `SRC/data/useResource.ts` e `useResource.test.tsx`: carrega, erro e `reload`; mudar a chave volta a "carregando" sem dado antigo; a resposta de uma chave que ficou para trás e a de componente desmontado são descartadas; erro não vira dado
- [X] T005 [P] `SRC/forms/useSubmit.ts` e `useSubmit.test.tsx`: executa uma ação por vez (duas chamadas seguidas executam uma), expõe `submitting`, libera ao fim e também quando a ação falha
- [X] T006 `SRC/pages/LoginPage.tsx` e `SRC/pages/RegisterPage.tsx` passam a usar `useSubmit` no lugar do guarda local, sem mudar comportamento (os testes das duas páginas passam sem alteração)
- [X] T007 [P] `SRC/forms/apiErrors.ts` e `apiErrors.test.ts`: `400` com `errors[]` vira mensagens por campo (campos desconhecidos viram o aviso geral, o primeiro por campo vence), `409` com o campo do nome, `403` permissão, `404` não encontrada, rede e `5xx` o aviso de servidor com nova tentativa, outros `4xx` o aviso genérico
- [X] T008 [P] `SRC/permissions/usePermissions.ts`, `WriteGate.tsx` e `permissions.test.tsx`: `canWrite` para dono e editor e não para leitor, sem workspace nenhum não escreve; o `WriteGate` mostra o filho a quem escreve e o aviso de papel com o caminho de volta ao leitor; axe

**Checkpoint**: dinheiro, carregamento, envio, erros e papéis estão provados sem nenhuma tela nova

---

## Phase 2: Peças de interface e API

- [X] T009 [P] `SRC/ui/SelectField.tsx` e `CheckboxField.tsx` com `SRC/ui/fields.test.tsx`: rótulo, dica e erro ligados ao campo, opções, valor controlado, teclado, estado desabilitado, axe
- [X] T010 `SRC/ui/MoneyField.tsx` com `SRC/ui/MoneyField.test.tsx`: prefixo "R$", valor em texto, o erro de `parseReais` mostrado como erro do campo, `inputMode="decimal"`, ligado ao `Field` do design system; axe
- [X] T011 [P] `SRC/categories/palette.ts` e `SRC/ui/ColorPicker.tsx` com `SRC/ui/ColorPicker.test.tsx`: dez cores com nome em português e "Sem cor", cada uma um rádio com nome acessível, a escolhida marcada, o valor controlado, uma cor fora da paleta aparece como "Cor atual", teclado (setas e Espaço), axe
- [X] T012 `SRC/api/types.ts` (os tipos de [data-model.md](data-model.md)), `SRC/api/accounts.ts`, `SRC/api/categories.ts` e `SRC/api/resources.test.ts`; ajudante do prefixo do workspace em `SRC/test/fakeApi.ts`: caminhos e métodos de cada chamada, `includeArchived` só quando marcado, o corpo exatamente como recebido, o identificador codificado no caminho, nada de credencial no endereço (o conteúdo do corpo, como os campos de cartão só em cartão, é dos montadores de corpo da T013 e da T018)

**Checkpoint**: as peças e a API estão provadas sem nenhuma tela

---

## Phase 3: Contas

- [X] T013 [US2] `SRC/accounts/accountTypes.ts` (tradução API <-> design system, rótulos do seletor, tipo desconhecido) e `SRC/validation/accountForm.ts` (validação e os montadores `buildCreateAccount` e `buildUpdateAccount`: centavos inteiros, campos de cartão só em cartão e só os preenchidos, nome sem os espaços das pontas), com `accountTypes.test.ts` e `accountForm.test.ts`: nome 100 e 101, tipo obrigatório na criação, saldo pelo campo de dinheiro, limite negativo, dias 0, 1, 31 e 32, campos de cartão só quando cartão, valores digitados em campos de cartão descartados ao trocar o tipo, mensagens do [data-model.md](data-model.md)
- [X] T014 [US1] `SRC/accounts/AccountsPage.tsx`, a navegação com "Contas" (`SRC/layout/AppShell.tsx`) e as rotas `/contas` em `SRC/App.tsx`, com `SRC/accounts/AccountsPage.test.tsx`: total do relatório e uma linha por conta ativa, tipo em português, cartão com fechamento, vencimento e limite (só o que foi informado), saldo negativo anunciado, tipo desconhecido, estado vazio (com "Criar conta" só para quem escreve), "Mostrar arquivadas" (marcadas, fora do total), carregando, erro com "Tentar de novo", o total confere com a soma das linhas, a lista não mostra ações ao leitor, uma lista de 500 contas aparece inteira e utilizável, axe
- [X] T015 [US2] `SRC/accounts/AccountFormPage.tsx` (criação) e a rota `/contas/nova`, com `AccountFormPage.test.tsx`: criar conta corrente e cartão (campos de cartão só no cartão e só enviados no cartão), volta à lista com o aviso e o total novo, cada erro de campo no campo e o foco no primeiro com erro, `409` no nome com os outros valores mantidos, `400` da API no campo, `403` com a mensagem de permissão, falha de servidor com "Tentar de novo", envio duplicado, cancelar sem criar, foco, axe
- [X] T016 [US3] edição em `SRC/accounts/AccountFormPage.tsx` e a rota `/contas/:id`, com os testes novos: abre preenchida com o saldo formatado, tipo visível e não editável, salvar atualiza lista e total, conta arquivada pede restaurar antes (sem formulário), conta inexistente é "não encontrada", `404` ao salvar volta à lista atualizada, recarregar em `/contas/:id` abre direto
- [X] T017 [US4] arquivar e restaurar em `SRC/accounts/AccountsPage.tsx`, com os testes novos: a confirmação explica o efeito, cancelar (botão e Esc) não chama a API, confirmar arquiva e atualiza lista e total, restaurar volta direto, `409` do restaurar explicado, falha mantém a conta e deixa tentar de novo, envio duplicado, o anúncio do resultado

**Checkpoint**: todos os fluxos de conta funcionam em teste

---

## Phase 4: Categorias

- [X] T018 [US6] `SRC/validation/categoryForm.ts` (validação e os montadores `buildCreateCategory` e `buildUpdateCategory`) com `categoryForm.test.ts`: nome 60 e 61, tipo obrigatório na principal, a principal envia `kind` e a subcategoria envia `parentId` e **não** `kind`, a edição envia só `name`, `color` e o `icon` atual (nunca `kind` nem `parentId`), nome sem os espaços das pontas, mensagens do [data-model.md](data-model.md)
- [ ] T019 [US5] `SRC/categories/CategoriesPage.tsx`, a navegação com "Categorias" e as rotas `/categorias` em `SRC/App.tsx`, com `CategoriesPage.test.tsx`: Despesas (padrão) e Receitas, principais em ordem com as subcategorias sob cada uma, o ponto de cor (sem ponto quando não há cor), "Mostrar arquivadas" (marcadas), estado vazio por tipo (com "Criar categoria" só para quem escreve), carregando, erro com "Tentar de novo", a lista não mostra ações ao leitor, axe
- [ ] T020 [US6] `SRC/categories/CategoryFormPage.tsx` (criar principal e criar subcategoria) e as rotas `/categorias/nova` e `/categorias/:id/subcategorias/nova`, com `CategoryFormPage.test.tsx`: a principal nasce com o tipo aberto na lista (e permite trocar), a subcategoria herda o tipo e não o pergunta, a principal da subcategoria precisa estar ativa, cor da paleta ou nenhuma, volta à lista no tipo certo com o aviso, `400` e `409` no campo, `403`, servidor fora do ar, envio duplicado, axe
- [ ] T021 [US7] edição, arquivar e restaurar: `SRC/categories/CategoryFormPage.tsx` com a rota `/categorias/:id` e `CategoriesPage.tsx`, com os testes novos: edição mostra o tipo e a principal sem poder mudar, **o ícone existente é reenviado** (o corpo do `PUT` tem o ícone original), a cor atual fora da paleta é preservada até a pessoa trocar, arquivar principal avisa das subcategorias, arquivar subcategoria pede confirmação simples, restaurar principal diz que as subcategorias não voltam junto, restaurar subcategoria com a principal arquivada e nome em uso são explicados, `404` e inexistente

**Checkpoint**: todos os fluxos de categoria funcionam em teste

---

## Phase 5: Papéis, workspace e prova

- [ ] T022 [US8] `SRC/accounts/access.test.tsx` (ou o nome que couber): com leitor, as duas listas sem nenhuma ação e os cinco endereços de formulário mostram o aviso de papel com a volta; com editor e dono as ações aparecem; a recusa `403` da API vira a mensagem de permissão; trocar de workspace no perfil recarrega as duas listas do novo workspace e nenhum dado do anterior fica; a resposta atrasada do workspace anterior é descartada; conta e categoria de outro workspace ou inexistentes são "não encontrada"; a sessão terminada (401) leva à entrada com o aviso
- [ ] T023 Rodar `npm run verify` completo e o `./mvnw -B verify` do back-end; conferir o orçamento (dividir por rota se passar de 130 KB); corrigir qualquer falha nova sem enfraquecer testes; registrar as contagens
- [ ] T024 [P] Atualizar `FE/README.md` (estrutura: `accounts/`, `categories/`, `money/`, `data/`, `forms/`, `permissions/`; a regra de dinheiro em centavos) e o `README.md` da raiz se preciso
- [ ] T025 Executar o [quickstart.md](quickstart.md): o roteiro HTTP contra a API do Compose (contas, cartão, arquivar e restaurar, categorias, ícone preservado, papéis, limpeza de `qs16*`) e o que dá para conferir no navegador; registrar no quickstart o que foi e o que ficou para quem usa o projeto; marcar as tarefas restantes como `[X]` junto do último commit que as fecha; não fazer push

---

## Dependencies & Execution Order

- **Phase 1**: T001, T002, T003, T004, T005, T007 e T008 independentes entre si; T006 depois de T005
- **Phase 2**: T009 e T011 independentes; T010 depois de T003 e T009; T012 depois de T003 (usa centavos)
- **Phase 3**: T013 depois de T003; T014 depois de T001, T004, T008 e T012; T015 depois de T005, T007, T009, T010, T013 e T014; T016 depois de T015; T017 depois de T002 e T014
- **Phase 4**: T018 depois de T011; T019 depois de T004, T008, T011 e T012; T020 depois de T005, T007, T009, T011, T018 e T019; T021 depois de T002 e T020
- **Phase 5**: T022 depois de T017 e T021; T023 depois de tudo; T025 por último

## Commits previstos (cada um pequeno, com os testes que o provam)

1. `feat(frontend): give unknown account types a generic label` — T001
2. `feat(frontend): let the confirm dialog use a calmer tone` — T002
3. `feat(frontend): add the exact reais parser` — T003
4. `feat(frontend): add the resource loader` — T004
5. `feat(frontend): add the submit guard` — T005
6. `refactor(frontend): share the submit guard in sign-in and sign-up` — T006
7. `feat(frontend): map API errors to the form` — T007
8. `feat(frontend): add the write permission` — T008
9. `feat(frontend): add the select and checkbox fields` — T009
10. `feat(frontend): add the money field` — T010
11. `feat(frontend): add the color picker` — T011
12. `feat(frontend): add the accounts and categories API` — T012
13. `feat(frontend): add the account rules` — T013
14. `feat(frontend): add the accounts list` — T014
15. `feat(frontend): add the account form` — T015
16. `feat(frontend): edit an account` — T016
17. `feat(frontend): archive and restore accounts` — T017
18. `feat(frontend): add the category rules` — T018
19. `feat(frontend): add the categories list` — T019
20. `feat(frontend): add the category form` — T020
21. `feat(frontend): edit, archive and restore categories` — T021
22. `test(frontend): prove roles and workspace switching` — T022
23. `docs: describe the accounts and categories in the README` — T024, com as marcas de T023 e T025 no último commit

## Implementation Strategy

1. A base pura primeiro (T001 a T008): dinheiro, carregamento, envio, erros e papéis provados sem tela.
2. As peças de interface e a API (T009 a T012), também sem tela.
3. Contas, da lista ao arquivar (T013 a T017); só depois categorias (T018 a T021), que reaproveitam tudo.
4. T022 prova papéis e workspace nas duas áreas; T023 a T025 fecham a verificação e o roteiro real.
5. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
