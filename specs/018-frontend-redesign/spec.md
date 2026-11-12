# Feature Specification: Redesign do front-end ("a carteira aberta")

**Feature Branch**: `018-frontend-redesign`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Eu fiz um plano de redesign e já atualizei o design system (`frontend/src/design-system`) e o `public`. Refaça o que já foi feito (entrar e cadastro, navegação, início, contas, categorias, lançamentos, perfil) com os componentes novos: Carteira (WalletHero), portão da carteira (WalletGate), moedas de categoria (CategoryCoin), tostão de atenção (AttentionDot), cabeçalho de página, folha lateral/inferior (Sheet), campo de valor (AmountInput), escolhas (OptionTile, CoinPicker), navegação com botão de lançar e usuário. Depois, as próximas telas já nascem nesse visual."

## Contexto

O design system novo traz peças que mudam a moldura do aplicativo: a navegação ganha o botão **Lançar** (no centro da barra de baixo e no topo do menu lateral, com a dica da tecla **N**) e o usuário no rodapé do menu lateral; cada tela ganha um cabeçalho de página; o **Início** passa a ter a Carteira (saldo em destaque); **entrar** e **criar conta** ficam dentro da silhueta da carteira; o registro de lançamento vira uma **folha** (lateral no desktop, de baixo no celular); categorias aparecem como **moedas** coloridas; o que pede atenção (pendente) leva o **tostão** dourado.

Esta spec não muda a API, as regras nem os textos de negócio das specs 015 a 017: muda a moldura e o jeito de mostrar e preencher.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Navegar pela moldura nova (Priority: P1)

A pessoa usa o menu lateral (desktop) ou a barra de baixo (celular) com o botão de lançar e vê o nome das telas com o cabeçalho de página.

**Independent Test**: logada, a navegação mostra Início, Lançamentos, Contas e Categorias, o botão **Lançar** e o usuário (no desktop leva ao Perfil; no celular o Perfil é um item da barra); cada tela tem um título (h1) no cabeçalho; a tecla **N** abre o lançamento fora de campos de texto.

**Acceptance Scenarios**:

1. **Given** uma pessoa logada no desktop, **When** a tela abre, **Then** o menu lateral mostra a marca, o botão "Lançar" com a dica N, os itens Início, Lançamentos, Contas e Categorias e, no rodapé, o nome dela com o workspace; tocar no rodapé abre o Perfil.
2. **Given** o celular, **When** a tela abre, **Then** a barra de baixo mostra Início, Lançamentos, o botão redondo de lançar no meio, Contas e Perfil; Categorias fica acessível pela tela de Contas.
3. **Given** quem só pode ler (leitor), **When** a navegação aparece, **Then** o botão de lançar não existe e a tecla N não faz nada.
4. **Given** qualquer tela, **When** ela abre, **Then** tem um único `h1` (o título do cabeçalho de página) e o foco vai ao conteúdo ao trocar de tela, como antes.

---

### User Story 2 - Entrar e criar conta na carteira (Priority: P1)

**Independent Test**: a tela de entrar e a de criar conta usam o portão da carteira, com os mesmos campos, validações e mensagens de antes; o fecho dourado é o botão de enviar.

**Acceptance Scenarios**:

1. **Given** a tela de entrar, **When** ela abre, **Then** mostra o título, os campos de e-mail e senha, o aviso de erro quando houver e o botão "Entrar"; o envio duplo, o bloqueio por tentativas e os demais comportamentos da 015 continuam iguais.
2. **Given** a tela de criar conta, **When** ela abre, **Then** vale o mesmo para nome, e-mail, senha e confirmação.

---

### User Story 3 - Início com a Carteira (Priority: P1)

**Independent Test**: o Início mostra a Carteira com o saldo total das contas ativas do workspace (ou a frase de vazio quando não há conta), o workspace atual com o papel da pessoa e as contas com seus saldos.

**Acceptance Scenarios**:

1. **Given** um workspace com contas, **When** o Início abre, **Then** a Carteira mostra o saldo total (soma dos saldos das contas ativas) com os centavos menores, e abaixo vêm as contas.
2. **Given** um workspace sem contas, **When** o Início abre, **Then** a Carteira mostra "Crie sua primeira conta" e o caminho para criá-la (quem pode escrever).
3. **Given** a falha ao carregar as contas, **When** o Início abre, **Then** mostra o aviso com "Tentar de novo"; o resto da tela continua utilizável.

---

### User Story 4 - Lançar numa folha (Priority: P1)

**Independent Test**: "Lançar" (botão, tecla N ou "Novo lançamento" na lista) abre a folha com o valor em destaque, o tipo, a data, a descrição, a conta, a categoria em moedas e as observações; salvar cria o lançamento e fecha a folha com o aviso.

**Acceptance Scenarios**:

1. **Given** quem pode escrever, **When** abre a folha, **Then** o foco vai ao campo de valor, Esc ou "Fechar" fecham, e o foco volta ao que abriu a folha.
2. **Given** a folha, **When** a pessoa escolhe o tipo, **Then** vê despesa, receita ou transferência; as categorias do tipo aparecem como moedas e a transferência mostra De e Para; a data tem os atalhos "Hoje" e "Ontem".
3. **Given** dados válidos, **When** salva, **Then** vale tudo o que a 017 definiu (centavos exatos, pendente, recusas da API no campo certo, envio único); a lista atrás atualiza.
4. **Given** um lançamento existente, **When** a pessoa o abre, **Then** edita na mesma folha (o tipo fixo, como na 017), efetiva e exclui com confirmação; o leitor vê só os detalhes.

---

### User Story 5 - Lançamentos, contas, categorias e perfil no visual novo (Priority: P2)

**Independent Test**: a lista de lançamentos usa moedas de categoria e o tostão em "Pendente"; os filtros abrem numa folha; contas, categorias e perfil usam o cabeçalho de página; as categorias aparecem como moedas na cor da categoria.

**Acceptance Scenarios**:

1. **Given** a lista, **When** abre, **Then** o cabeçalho mostra o título, "N lançamentos" e o botão "Novo lançamento"; as linhas mostram a moeda da categoria (a seta, quando não há categoria) e o tostão ao lado de "Pendente".
2. **Given** a lista, **When** a pessoa toca em "Filtros", **Then** os filtros abrem numa folha (Esc fecha) com os mesmos campos e regras da 017.
3. **Given** a tela de contas, **When** abre, **Then** mostra o cabeçalho com "Nova conta", as contas e o caminho para as categorias.
4. **Given** a tela de categorias, **When** abre, **Then** cada categoria aparece com a moeda na cor dela (nome sempre em texto).
5. **Given** o perfil, **When** abre, **Then** mostra o avatar com as iniciais, os dados, os workspaces e o tema.

---

### Edge Cases

- Fontes: continuam servidas pelo próprio aplicativo (o design system novo importava do Google Fonts, o que a constituição proíbe: o guard-rail barra).
- A linha de lançamento com ação continua um botão de verdade (teclado e leitor de tela), mesmo no visual novo.
- Cor de categoria ausente ou inválida: a moeda usa a cor neutra; o nome é sempre texto ao lado.
- Celular estreito (320 px) e zoom de 200%: sem rolagem horizontal; a folha de baixo rola por dentro.
- Tema claro, escuro e do sistema: contraste dos fechos dourados e do tostão conferido (axe).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O aplicativo MUST usar a navegação nova (lançar, usuário, itens) com o `h1` em todas as telas e o foco no conteúdo ao trocar de tela.
- **FR-002**: Entrar e criar conta MUST usar o portão da carteira, sem mudar campos, validações, mensagens nem comportamentos da 015.
- **FR-003**: O Início MUST mostrar a Carteira com o saldo total das contas ativas, ou a frase de vazio, e as contas.
- **FR-004**: O registro, a edição, o efetivar e o excluir MUST acontecer na folha, com as regras da 017 intactas (centavos exatos, tipo fixo na edição, status sempre enviado, recusas no campo certo, envio único).
- **FR-005**: A lista de lançamentos MUST mostrar moeda de categoria e tostão de atenção, manter a linha como botão e abrir os filtros numa folha.
- **FR-006**: Contas, categorias e perfil MUST usar o cabeçalho de página e as peças novas (moeda, avatar).
- **FR-007**: A tecla N MUST abrir o lançamento quando a pessoa pode escrever e o foco não está num campo de texto; para quem só lê, nada acontece.
- **FR-008**: Nenhuma fonte, script, estilo ou imagem MAY vir de outra origem (guard-rail); nenhuma dependência nova.
- **FR-009**: O que já era acessível MUST continuar: rótulos, foco, Esc, contraste (axe nas telas principais).

### Key Entities

Nenhuma nova. A folha de lançar usa o formulário e as regras de `validation/transactionForm.ts` da 017.

## Success Criteria *(mandatory)*

- **SC-001**: Todas as telas das specs 015 a 017 aparecem no visual novo, com os mesmos fluxos (entrar, sair, contas, categorias, lançar, editar, efetivar, excluir, filtros, papéis).
- **SC-002**: A rodada de testes cobre os fluxos principais de cada tela e continua verde; o número de testes **diminui** em relação aos 890 de antes (pedido do usuário), sem perder as provas de regra (centavos, papéis, workspace).
- **SC-003**: JavaScript inicial dentro dos 150 KB comprimidos.
- **SC-004**: Roteiro real no navegador: lançar pelo botão e pela tecla N, em desktop e em 360 px.

## Assumptions

- Sem plano de redesign escrito no repositório: vale o que os componentes do design system dizem (as pistas nos comentários: "Use tx-wallet--stack no celular", "tx-sheet--bottom no celular", etc.).
- Categorias no celular: a barra de baixo tem Início, Lançamentos, Lançar, Contas e Perfil; Categorias fica num atalho dentro de Contas.
- O resumo do mês na Carteira (o fecho "Sobram R$ …") e o seletor de mês ficam para a spec do dashboard, que já os usa.
- Fora do escopo: back-end, novas regras, README.
