# Feature Specification: Ícones de categoria e subcategoria

**Feature Branch**: `024-icones-de-categoria`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Gere esse redesign dos ícones de categoria" (o pacote colado pelo usuário: 39 ícones de categoria, moeda cheia para categoria e vazada para subcategoria, ícone sugerido pelo nome, `IconPicker` e `SwatchPicker`, o formulário de nova categoria e subcategoria, e a validação do ícone no servidor).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver as categorias com ícone (Priority: P1)

**Independent Test**: a lista de categorias mostra a moeda cheia (cor e ícone) de cada categoria e, para cada subcategoria, um chip com a moeda vazada na cor da mãe; a lista de lançamentos e a escolha de categoria do lançamento usam as mesmas moedas.

**Acceptance Scenarios**:

1. **Given** categorias com `icon` gravado, **When** a lista de categorias abre, **Then** cada principal aparece com moeda cheia de 44 px (cor dela, ícone gravado) e cada subcategoria como um chip com a moeda vazada de 22 px, o contorno na cor da mãe; o nome está sempre em texto ao lado.
2. **Given** uma categoria antiga sem `icon`, **When** aparece em qualquer tela, **Then** a moeda sugere um ícone pelo nome ("Mercado" ganha o carrinho) e, sem sugestão ("Xodó"), mostra a inicial; nenhuma migração de dados.
3. **Given** a lista de lançamentos, **When** a linha tem categoria, **Then** a moeda da linha mostra o ícone da categoria (ou a sugestão, ou a inicial), na cor dela; a subcategoria usa a cor da mãe.
4. **Given** a escolha de categoria na folha de lançar, **Then** cada moeda mostra o ícone.

---

### User Story 2 - Criar e editar categoria e subcategoria com ícone e cor (Priority: P1)

**Independent Test**: criar "Academia" sem tocar no seletor grava `icon = "dumbbell"`; criar uma subcategoria dentro de "Lazer" mostra a moeda vazada na cor de Lazer e não grava cor.

**Acceptance Scenarios**:

1. **Given** quem pode escrever, **When** abre o formulário de categoria, **Then** vê uma pré-visualização da moeda (64 px), o nome, o tipo (despesa ou receita, nas principais), "Fica dentro de" (Nenhuma ou uma principal ativa do mesmo tipo), a cor (8 cores da casa; subcategoria usa a da mãe, sem escolher) e o ícone (sugestões pelo nome, "sem ícone, usar a inicial", busca e a grade dos 39 por grupo).
2. **Given** o nome digitado e nenhum ícone escolhido, **Then** o ícone acompanha o nome (a sugestão); depois do primeiro toque no seletor, vale a escolha da pessoa, mesmo que o nome mude.
3. **Given** "sem ícone", **When** salva, **Then** `icon` não é enviado (a moeda usa a sugestão do nome ou a inicial); com um ícone escolhido ou sugerido, grava a chave (ex.: `dumbbell`).
4. **Given** uma subcategoria, **When** salva, **Then** não envia `color`; ao editar uma categoria, o formulário começa com o ícone e a cor gravados (a sugestão só age em quem nunca escolheu).
5. **Given** as regras de antes (nome obrigatório e de até 60 caracteres, nome em uso, principal arquivada, recusas da API), **Then** continuam valendo, com as mesmas mensagens.

---

### User Story 3 - O servidor só aceita os 39 ícones (Priority: P1)

**Acceptance Scenarios**:

1. **Given** `icon` nulo ou uma das 39 chaves, **When** a API recebe criar ou editar categoria, **Then** aceita.
2. **Given** qualquer outro valor (`"qualquercoisa"`), **Then** responde 400 no campo `icon`.

### Edge Cases

- O ícone gravado que o app não conhece (outra versão) cai na sugestão pelo nome ou na inicial, nunca quebra.
- Cor `null` (subcategoria) usa a cor da mãe; categoria sem cor usa a cor neutra.
- Quem só lê vê as moedas e nenhuma ação.

## Requirements *(mandatory)*

- **FR-001**: O design system MUST receber `categoryIcons`, `IconPicker`, `SwatchPicker`, o novo `CategoryCoin` (cheia e vazada) e as propriedades `categoryIcon` (`TransactionRow`) e `icon` (`CoinPicker`), como no pacote.
- **FR-002**: O servidor MUST validar `icon` em criar e editar categoria: nulo ou uma das 39 chaves (400 em `icon` nos demais casos).
- **FR-003**: A tela de categorias, a lista de lançamentos e a folha de lançar MUST mostrar as moedas com ícone; subcategoria sempre com a cor da mãe.
- **FR-004**: O formulário de categoria MUST ter pré-visualização, tipo, "Fica dentro de", `SwatchPicker` e `IconPicker`, e enviar só `icon` válido e `color` (nunca em subcategoria).
- **FR-005**: Sem dependência nova; JavaScript inicial ≤ 150 KB; acessibilidade (axe); teste do modo estrito para qualquer `<dialog>` novo (o formulário usa o `SheetDialog`, já coberto).

## Success Criteria

- **SC-001**: As conferências da seção "Pronto quando" do pacote passam (testes e roteiro real).
- **SC-002**: Poucos testes de valor; o back-end ganha só os casos novos de `icon`.

## Assumptions

- O DTO de lançamento **não** muda: a moeda da linha resolve ícone e cor pela lista de categorias que o front já carrega (a seção 6 do pacote sugeria devolver `icon` e `color` no lançamento; não é necessário e evita mexer num contrato estável).
- A coluna `categories.icon` já existe (`V1__init.sql`); nenhuma migration nova.
