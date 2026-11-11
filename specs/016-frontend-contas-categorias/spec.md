# Feature Specification: Contas e categorias no front-end

**Feature Branch**: `016-frontend-contas-categorias`

**Created**: 2026-11-11

**Status**: Draft

**Input**: User description: "As primeiras telas com dados financeiros do Tostão: a pessoa vê as contas e o saldo total, cria, edita, arquiva e restaura contas (inclusive cartão de crédito), e organiza as categorias de receita e despesa com subcategorias, tudo no workspace atual e respeitando o papel de cada um (dono e editor alteram, leitor só consulta). Usa a API das specs 002 e 003 e a base da spec 015."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver as contas e o saldo total (Priority: P1)

A dona abre "Contas" e vê, de uma vez, quanto tem no total e em cada conta, com o tipo de cada uma. Quando há contas arquivadas,
ela pode escolher vê-las.

**Why this priority**: é a primeira tela com dados financeiros e a base para todas as seguintes (lançamentos, orçamentos e
cartão dependem de escolher uma conta). Prova também que o aplicativo lê da API só o workspace atual.

**Independent Test**: com um workspace que tem contas de tipos variados, abrir "Contas" mostra o saldo total, uma linha por conta
ativa com nome, tipo em português e saldo, e o total confere com a soma das contas listadas.

**Acceptance Scenarios**:

1. **Given** uma pessoa logada, **When** ela abre "Contas" pela navegação, **Then** vê o saldo total do workspace atual e a lista
   das contas ativas, cada uma com nome, tipo (conta corrente, poupança, dinheiro, cartão de crédito, investimento) e saldo.
2. **Given** um cartão de crédito com limite, fechamento e vencimento cadastrados, **When** a lista aparece, **Then** a linha dele
   mostra esses dados como apoio (por exemplo "Fecha dia 10 · vence dia 17 · limite R$ 5.000,00"), sem inventar o que não foi
   informado.
3. **Given** uma conta com saldo negativo, **When** a lista aparece, **Then** o valor negativo é mostrado com o sinal e é anunciado
   como negativo para quem usa leitor de tela.
4. **Given** um workspace sem nenhuma conta, **When** a pessoa abre "Contas", **Then** vê um estado vazio que explica o que fazer e,
   para quem pode criar, oferece "Criar conta"; nunca uma tela em branco.
5. **Given** contas arquivadas, **When** a pessoa escolhe "Mostrar arquivadas", **Then** elas aparecem marcadas como arquivadas e
   fora do saldo total; ao desligar a opção, somem de novo.
6. **Given** a API fora do ar ou sem rede, **When** a lista carrega, **Then** aparece o aviso de erro com "Tentar de novo"; ao
   tentar de novo com a API de volta, a lista aparece. Enquanto carrega, a pessoa vê um estado de carregamento.

---

### User Story 2 - Criar uma conta (Priority: P1)

A dona cria uma conta informando o nome, o tipo e o saldo inicial; se for cartão de crédito, informa também o limite e os dias de
fechamento e de vencimento.

**Why this priority**: sem conta não existe lançamento. É a primeira ação de escrita do aplicativo.

**Independent Test**: preencher nome, tipo e saldo inicial válidos cria a conta, que aparece na lista com o saldo informado; um
nome já usado mostra o erro no campo do nome.

**Acceptance Scenarios**:

1. **Given** a lista de contas e o papel de dono ou editor, **When** a pessoa escolhe "Criar conta", **Then** vê o formulário com
   nome, tipo e saldo inicial, e o botão para salvar.
2. **Given** dados válidos, **When** ela salva, **Then** volta à lista, que mostra a conta nova com o saldo informado e o total
   atualizado, com um aviso de que a conta foi criada.
3. **Given** o tipo "cartão de crédito", **When** ele é escolhido, **Then** aparecem os campos limite, dia de fechamento e dia de
   vencimento (opcionais); para os outros tipos esses campos não aparecem e nada deles é enviado.
4. **Given** o saldo inicial, **When** a pessoa o digita em reais (por exemplo `1.234,56`, `1234,56` ou `-50`), **Then** o valor é
   entendido com exatidão em centavos; um valor com mais de duas casas decimais, que não seja um número ou fora do limite aceito
   é recusado no campo, com a explicação do que vale.
5. **Given** nome vazio, nome com mais de 100 caracteres, dia fora de 1 a 31 ou limite negativo, **When** ela salva, **Then** cada
   campo mostra o seu erro, nada é enviado e o foco vai para o primeiro campo com erro.
6. **Given** um nome que já pertence a uma conta ativa, **When** ela salva, **Then** o campo do nome mostra "Já existe uma conta
   ativa com este nome." e os outros valores ficam como estavam.
7. **Given** o envio em andamento, **When** a pessoa toca de novo em salvar, **Then** nada é enviado duas vezes.
8. **Given** o formulário preenchido, **When** a pessoa cancela ou volta, **Then** retorna à lista sem criar nada.

---

### User Story 3 - Editar uma conta (Priority: P2)

A dona corrige o nome, o saldo inicial ou os dados do cartão de uma conta, para acertar um erro de cadastro.

**Why this priority**: corrigir um cadastro errado é comum, mas o aplicativo já é útil sem isso.

**Independent Test**: editar o nome e o saldo inicial de uma conta e conferir que a lista e o total refletem a mudança; o tipo da
conta não pode ser trocado.

**Acceptance Scenarios**:

1. **Given** uma conta ativa e o papel de dono ou editor, **When** a pessoa a escolhe na lista, **Then** vê o formulário
   preenchido com os dados atuais, com o tipo mostrado mas não editável.
2. **Given** dados válidos alterados, **When** ela salva, **Then** volta à lista com os valores novos e o total recalculado.
3. **Given** uma conta arquivada, **When** a pessoa tenta abri-la para editar, **Then** vê que é preciso restaurá-la antes, sem
   formulário de edição.
4. **Given** uma conta que deixou de existir (apagada em outro aparelho, por exemplo), **When** a pessoa tenta salvar, **Then** vê
   que a conta não foi encontrada e volta à lista atualizada.
5. **Given** as mesmas regras de nome, saldo e cartão do cadastro, **When** a pessoa edita, **Then** elas valem da mesma forma
   (campo a campo, nome repetido, envio duplicado).

---

### User Story 4 - Arquivar e restaurar contas (Priority: P2)

Quando uma conta deixa de ser usada, a dona a arquiva (com confirmação) em vez de apagar, e pode restaurá-la depois.

**Why this priority**: arquivar mantém o histórico e tira a conta do caminho; é a única forma de "remover" uma conta.

**Independent Test**: arquivar uma conta, conferir que ela sai da lista padrão e do total, aparece em "Mostrar arquivadas" e volta a
valer depois de restaurada.

**Acceptance Scenarios**:

1. **Given** uma conta ativa, **When** a pessoa escolhe "Arquivar", **Then** vê uma confirmação que explica que a conta sai da lista
   e do saldo total e que os lançamentos dela continuam existindo; ao confirmar, a conta some da lista e o total é atualizado.
2. **Given** a confirmação aberta, **When** a pessoa cancela (botão ou tecla Esc), **Then** nada muda.
3. **Given** uma conta arquivada na lista de arquivadas, **When** a pessoa escolhe "Restaurar", **Then** ela volta à lista padrão e
   ao total.
4. **Given** uma conta arquivada cujo nome já foi tomado por uma conta ativa, **When** a pessoa tenta restaurar, **Then** vê que o
   nome já está em uso e que precisa renomear a conta ativa antes; a conta continua arquivada.
5. **Given** a falha da API ao arquivar ou restaurar, **When** a ação termina, **Then** a pessoa vê o aviso, a conta continua como
   estava e pode tentar de novo.

---

### User Story 5 - Ver as categorias (Priority: P1)

A dona abre "Categorias" e vê as categorias de despesa e de receita, cada uma com as suas subcategorias e a cor que a identifica.

**Why this priority**: as categorias organizam todos os lançamentos; a tela mostra a estrutura que os lançamentos vão usar.

**Independent Test**: com um workspace que tem categorias principais e subcategorias dos dois tipos, a tela mostra cada tipo com as
suas principais e, sob cada uma, as suas subcategorias, com a cor.

**Acceptance Scenarios**:

1. **Given** uma pessoa logada, **When** ela abre "Categorias", **Then** vê o tipo "Despesas" (padrão) e pode alternar para
   "Receitas"; em cada um, as categorias principais em ordem alfabética, e sob cada uma as suas subcategorias.
2. **Given** categorias com cor, **When** a lista aparece, **Then** cada uma mostra a sua cor como um ponto ao lado do nome; as sem
   cor aparecem sem ponto, nunca com uma cor inventada.
3. **Given** categorias arquivadas, **When** a pessoa escolhe "Mostrar arquivadas", **Then** elas aparecem marcadas como arquivadas;
   ao desligar a opção, somem.
4. **Given** um tipo sem nenhuma categoria, **When** a pessoa o abre, **Then** vê um estado vazio que explica o que fazer e, para
   quem pode criar, oferece "Criar categoria".
5. **Given** carregamento, erro de rede ou API fora do ar, **When** a tela abre, **Then** vê o estado de carregamento e, em caso
   de falha, o aviso com "Tentar de novo".

---

### User Story 6 - Criar categorias e subcategorias (Priority: P1)

A dona cria uma categoria principal (por exemplo "Alimentação", de despesa) e subcategorias dentro dela ("Mercado", "Restaurante").

**Why this priority**: sem categorias o aplicativo não consegue organizar nenhum gasto.

**Independent Test**: criar uma principal de despesa com cor e uma subcategoria sob ela, e ver as duas na árvore, no tipo certo.

**Acceptance Scenarios**:

1. **Given** a tela de categorias com o tipo "Despesas" aberto, **When** a pessoa escolhe "Criar categoria", **Then** vê o formulário
   com nome e cor, já com o tipo do que está aberto; pode trocar o tipo antes de salvar.
2. **Given** dados válidos, **When** ela salva, **Then** volta à lista, no tipo da categoria criada, que mostra a categoria nova e um
   aviso de que foi criada.
3. **Given** uma categoria principal ativa, **When** a pessoa escolhe "Adicionar subcategoria" nela, **Then** vê o formulário com
   nome e cor, e a subcategoria nasce com o mesmo tipo da principal (o tipo não é perguntado).
4. **Given** a cor, **When** a pessoa a escolhe, **Then** vê uma paleta de cores para escolher (nenhuma obrigatória), com a opção
   de nenhuma cor, e cada cor tem nome acessível.
5. **Given** nome vazio ou com mais de 60 caracteres, **When** ela salva, **Then** o campo mostra o erro e nada é enviado.
6. **Given** um nome já usado por outra categoria ativa do mesmo nível, **When** ela salva, **Then** o campo do nome mostra "Já existe
   uma categoria ativa com este nome aqui." e os outros valores ficam.
7. **Given** o envio em andamento, **When** a pessoa toca de novo em salvar, **Then** nada é enviado duas vezes.

---

### User Story 7 - Editar, arquivar e restaurar categorias (Priority: P2)

A dona renomeia ou troca a cor de uma categoria e arquiva as que não usa mais, podendo restaurá-las.

**Why this priority**: mantém a estrutura de categorias em ordem com o tempo; não impede o uso do aplicativo.

**Independent Test**: renomear uma categoria e mudar a cor, arquivar uma principal e ver as subcategorias dela arquivadas junto, e
restaurar só a principal.

**Acceptance Scenarios**:

1. **Given** uma categoria ativa e o papel de dono ou editor, **When** a pessoa escolhe editá-la, **Then** vê o formulário com nome
   e cor atuais, com o tipo (e a principal, se for subcategoria) mostrados mas não editáveis.
2. **Given** a edição salva, **When** a lista volta, **Then** mostra o nome e a cor novos, e o ícone que a categoria já tinha
   (definido por outra via) continua o mesmo: editar nunca o apaga.
3. **Given** uma categoria principal ativa com subcategorias, **When** a pessoa a arquiva, **Then** a confirmação avisa que as
   subcategorias ativas também serão arquivadas; ao confirmar, todas saem da lista padrão.
4. **Given** uma categoria arquivada, **When** a pessoa a restaura, **Then** ela volta à lista; se era principal, as subcategorias
   continuam arquivadas e cada uma precisa ser restaurada à parte, e a tela diz isso.
5. **Given** uma subcategoria arquivada cuja principal está arquivada, **When** a pessoa tenta restaurá-la, **Then** vê que precisa
   restaurar a principal antes; a subcategoria continua arquivada.
6. **Given** um nome já usado por categoria ativa do mesmo nível, **When** a pessoa renomeia ou restaura, **Then** vê o erro de
   nome em uso e nada muda.

---

### User Story 8 - Cada papel vê só o que pode fazer, e o workspace manda (Priority: P1)

Quem é leitor consulta tudo e não vê botões de alterar; quem é dono ou editor altera. Trocar de workspace mostra só os dados do novo.

**Why this priority**: o aplicativo não pode oferecer ações que a API vai recusar, nem mostrar dados de um workspace dentro de outro.

**Independent Test**: com um leitor, as telas listam tudo e não têm botões de criar, editar, arquivar nem restaurar; trocar de
workspace no perfil mostra as contas e categorias do novo, sem nada do anterior.

**Acceptance Scenarios**:

1. **Given** uma pessoa com papel de leitor no workspace atual, **When** ela abre "Contas" ou "Categorias", **Then** vê as listas
   e nenhuma ação de criar, editar, arquivar ou restaurar.
2. **Given** um leitor que abre diretamente o endereço de um formulário (criar ou editar), **When** a tela abre, **Then** vê um
   aviso de que o papel dele só permite consultar, com o caminho de volta à lista; nenhum formulário aparece.
3. **Given** a API que recusa uma alteração por permissão (por exemplo o papel mudou em outro aparelho), **When** a pessoa tenta
   salvar, **Then** vê uma mensagem clara de que não tem permissão e nada é alterado na tela.
4. **Given** uma pessoa em mais de um workspace, **When** ela troca o workspace atual no perfil, **Then** as telas de contas e
   categorias passam a mostrar os dados do novo, e nenhum dado do anterior permanece na tela.
5. **Given** uma pessoa abrindo uma conta ou categoria por um endereço que não é do workspace atual (ou não existe), **When** a tela
   abre, **Then** vê "não encontrada" com o caminho de volta, sem pista de existir em outro workspace.
6. **Given** a sessão que termina durante o uso (401), **When** qualquer chamada falha por isso, **Then** vale o comportamento da
   base: volta à entrada com o aviso e depois retoma o endereço.

---

### Edge Cases

- Saldo inicial com vírgula e ponto de milhar (`1.234,56`), só vírgula (`12,5`), com sinal (`-50`), com espaços ao redor, vazio ou `R$`
  digitado: o campo aceita o que é claramente um valor em reais, recusa o resto, e nunca arredonda em silêncio (três casas decimais é erro).
- Valores nos limites da API (±R$ 10.000.000.000,00 para saldo; limite de cartão de 0 até esse valor): aceitos; um centavo além, recusados no campo.
- Dia de fechamento ou vencimento iguais, ou o vencimento antes do fechamento: permitido (cada banco decide); só valem 1 a 31.
- Tipo desconhecido vindo da API (versão futura): a conta aparece com um rótulo genérico, nunca quebra a lista.
- Resposta da API sem o formato esperado: vira o aviso geral de erro, nunca uma tela quebrada.
- Nome com espaços sobrando: enviado sem os espaços das pontas; só espaços conta como vazio.
- Lista muito grande (centenas de contas ou categorias): a tela continua utilizável e rolável, sem travar.
- Trocar o workspace enquanto uma lista carrega: a resposta do workspace anterior chega depois e é descartada.
- Recarregar a página em `/contas/<id>`: abre direto o formulário daquela conta (se ela existir), sem passar pela lista.
- Contas e categorias em que a pessoa não tem papel de alteração, mas que ela abre por endereço: ver a US8.
- Nenhuma ação destrutiva sem confirmação: apenas arquivar pede confirmação; restaurar e salvar não.
- Tela estreita (320 px) e zoom de 200%: cartões de conta, árvore de categorias e formulários sem rolagem horizontal.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A navegação MUST ganhar "Contas" e "Categorias" (além de "Início" e "Perfil"), na barra inferior e no menu lateral, com o
  item atual marcado e acessível pelo teclado; as rotas MUST ser `/contas`, `/contas/nova`, `/contas/:id`, `/categorias`,
  `/categorias/nova`, `/categorias/:id` e `/categorias/:id/subcategorias/nova`, todas exigindo sessão.
- **FR-002**: Toda chamada MUST usar o cliente único da base, no workspace atual (`/workspaces/{id}/...`), e MUST descartar a
  resposta de uma consulta que ficou para trás quando o workspace, a tela ou a opção de arquivadas muda.
- **FR-003**: A tela de contas MUST mostrar o saldo total das contas ativas (o do relatório de saldo da API, consistente com a lista) e
  uma linha por conta com nome, tipo em português, saldo (com sinal e anúncio de negativo) e, nos cartões, os dados informados
  (fechamento, vencimento e limite) como apoio; contas arquivadas só com "Mostrar arquivadas", marcadas e fora do total.
- **FR-004**: O formulário de conta MUST pedir nome (1 a 100 caracteres), tipo e saldo inicial; MUST mostrar limite e dias de
  fechamento e vencimento só para cartão de crédito; MUST enviar esses três campos só nos cartões; MUST validar no navegador as mesmas
  regras da API (limites e dias de 1 a 31), exibir no campo os erros de campo da API e o aviso geral para o resto.
- **FR-005**: O campo de dinheiro MUST converter o texto digitado em reais para centavos inteiros com exatidão (sem aritmética de ponto
  flutuante), aceitar milhar com ponto e decimais com vírgula, sinal negativo e espaços ao redor, recusar mais de duas casas decimais, texto que
  não é número e valores fora do limite da API, com a mensagem no campo, e mostrar o valor formatado em reais ao ser preenchido para edição.
- **FR-006**: O tipo da conta MUST ser escolhido só na criação; na edição MUST ser mostrado e não editável. Uma conta arquivada MUST NOT
  abrir em edição: a tela pede para restaurar antes.
- **FR-007**: Arquivar uma conta MUST pedir confirmação (explicando que sai da lista e do saldo total e que os lançamentos continuam);
  restaurar MUST ser direto; o conflito de nome ao restaurar MUST ser explicado; a falha de qualquer ação MUST manter a conta como estava.
- **FR-008**: A tela de categorias MUST mostrar o tipo Despesas (padrão) e o tipo Receitas, cada um com as categorias principais em ordem
  alfabética e as subcategorias sob cada uma, com a cor como ponto (sem ponto quando não há cor); arquivadas só com "Mostrar arquivadas", marcadas.
- **FR-009**: O formulário de categoria MUST pedir nome (1 a 60 caracteres) e permitir escolher a cor de uma paleta (ou nenhuma); na
  principal MUST também pedir o tipo (padrão: o aberto na lista); na subcategoria o tipo MUST ser o da principal e não ser perguntado.
- **FR-010**: Ao editar uma categoria, o tipo e a principal MUST aparecer sem poder mudar, e o ícone existente MUST ser reenviado
  como está (a API grava o ícone enviado, então omiti-lo o apagaria); MUST NOT haver edição de ícone nesta spec.
- **FR-011**: Arquivar uma categoria principal MUST pedir confirmação avisando que as subcategorias ativas também serão arquivadas;
  arquivar uma subcategoria pede confirmação simples; restaurar MUST ser direto, dizendo que as subcategorias de uma principal não voltam
  junto; o erro de nome em uso e o de principal arquivada MUST ser explicados.
- **FR-012**: As ações de criar, editar, arquivar e restaurar MUST aparecer só para dono e editor do workspace atual; o leitor MUST ver
  as listas sem elas, e ao abrir um formulário por endereço MUST ver o aviso de papel e o caminho de volta. A resposta 403 da API MUST ser
  mostrada como falta de permissão.
- **FR-013**: Erros MUST seguir o padrão da base: erros de campo (400) no campo certo; nome em uso (409) no campo do nome; não
  encontrado (404) como "não encontrada" com volta à lista atualizada; erro de rede ou servidor com "Tentar de novo"; 401 pelo cliente.
- **FR-014**: Cada tela MUST ter estados de carregamento, erro e vazio, anunciados a leitores de tela; salvar, arquivar e restaurar MUST
  indicar andamento e impedir envio duplicado; o resultado de uma ação (criada, salva, arquivada, restaurada) MUST ser anunciado.
- **FR-015**: Trocar o workspace atual MUST recarregar as listas do novo workspace sem deixar dado do anterior na tela, e um endereço de
  conta ou categoria que não é do workspace atual MUST aparecer como não encontrado.
- **FR-016**: Valores monetários MUST usar o formato do design system (centavos inteiros formatados em reais); os componentes de conta e
  de categoria MUST vir do design system (cartão de conta, campos, botões, e o ponto de cor das categorias no estilo do selo); todos os
  textos MUST estar em português do Brasil.
- **FR-017**: As telas MUST seguir a base: funcionar de 320 px a telas largas, com zoom de até 200% sem rolagem horizontal, alvos de
  toque de ao menos 44 px, foco visível, uso só com o teclado, e o foco no lugar certo ao abrir um formulário, ao voltar à lista e
  depois de um erro.
- **FR-018**: A verificação automática do front-end MUST continuar valendo (lint e guard-rails, tipos, testes, build e orçamento de
  tamanho de 150 KB do JavaScript inicial) e o service worker MUST continuar sem guardar respostas de `/api/*`.

### Key Entities *(include if feature involves data)*

- **Conta**: identificador, nome, tipo (corrente, poupança, dinheiro, cartão de crédito, investimento), moeda, saldo inicial e saldo
  atual (centavos), se arquivada e, nos cartões, limite, dia de fechamento e dia de vencimento.
- **Saldo total**: soma dos saldos das contas ativas do workspace, vinda do relatório de saldo da API.
- **Categoria**: identificador, nome, tipo (receita ou despesa), categoria principal (se for subcategoria), cor, ícone e se está
  arquivada. As principais trazem as suas subcategorias.
- **Paleta de cores**: o conjunto fixo de cores que a pessoa pode escolher para uma categoria (hexadecimal de 6 dígitos).
- **Papel**: dono, editor ou leitor no workspace atual; decide quais ações aparecem.
- **Workspace atual**: o da base (015); toda lista e toda ação valem para ele.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes de lista, a tela mostra o saldo total e uma linha por conta ativa (e as arquivadas só quando pedido), o
  total confere com a soma das linhas, e o saldo negativo, o cartão e o tipo desconhecido aparecem como descrito.
- **SC-002**: Em 100% dos testes do campo de dinheiro, os formatos aceitos viram o número exato de centavos e os recusados mostram a
  mensagem no campo, incluindo os limites da API, três casas decimais, texto livre, sinal e espaços.
- **SC-003**: Em 100% dos testes de formulário (conta e categoria), o sucesso volta à lista com o resultado e o aviso, cada erro de campo
  aparece no campo, o nome em uso aparece no campo do nome, e o envio duplicado chama a API uma vez só.
- **SC-004**: Em 100% dos testes de arquivar e restaurar, a confirmação cancela sem mudar nada, o sucesso atualiza a lista e o total, a
  falha mantém o estado, e os conflitos (nome em uso, principal arquivada) são explicados.
- **SC-005**: Em 100% dos testes de papéis, o leitor não vê nenhuma ação de alteração nem abre formulário por endereço, e a recusa 403
  da API vira mensagem de permissão; dono e editor veem todas as ações.
- **SC-006**: Em 100% dos testes de workspace, trocar o workspace recarrega as listas do novo e nenhum dado do anterior permanece, e uma
  resposta atrasada do workspace anterior é descartada.
- **SC-007**: Em 100% dos testes de categoria, a edição reenvia o ícone existente e o tipo e a principal nunca mudam, a subcategoria
  herda o tipo da principal, e arquivar uma principal avisa que arrasta as subcategorias.
- **SC-008**: Em 100% das verificações de acessibilidade automatizadas das telas novas (listas, formulários, confirmações, estados
  vazio, erro e de papel), não há violação de nível sério ou crítico; campos têm rótulo e erros são anunciados; o foco vai ao primeiro
  campo com erro e ao voltar à lista.
- **SC-009**: A verificação do front-end (lint com os guard-rails, tipos, testes, build, orçamento de 150 KB e testes do build) passa, e
  as 1414 verificações do back-end continuam passando sem alteração.
- **SC-010**: No roteiro com a API real, a pessoa cria uma conta e uma categoria com subcategoria, edita, arquiva e restaura cada uma, e a
  lista e o saldo total refletem cada passo, com a limpeza dos dados de teste ao fim.

## Assumptions

- Depende da `015-frontend-esqueleto-auth` (cliente da API, sessão, navegação, workspace atual, estados e design system), das specs
  `002-contas` e `003-categorias` do back-end (a API não muda) e da constituição (v1.0.0).
- A moeda é só o real (BRL); o aplicativo não oferece escolher moeda.
- O ícone da categoria não é editável nesta spec; o que já existir é preservado. As cores vêm de uma paleta fixa, sem seletor livre.
- Não há reordenação manual (a ordem é a da API, alfabética), nem exclusão definitiva (só arquivar), nem mover uma subcategoria para outra
  principal, nem mudar o tipo de uma conta ou de uma categoria (a API não permite).
- Lançamentos, transferências, extratos e o detalhe de movimentação de uma conta ficam para as specs seguintes; a lista de contas não
  mostra movimentos. O saldo exibido é o que a API calcula.
- O início continua simples (a spec do painel mensal o enriquece); esta spec só acrescenta as duas telas e a navegação.
- Quem é dono ou editor altera; quem é leitor consulta. A API continua sendo a autoridade e recusa o que o papel não permite.
- Não há convites nem membros nesta spec (ficam para a spec de membros).
