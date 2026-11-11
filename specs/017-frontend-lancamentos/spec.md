# Feature Specification: Lançamentos no front-end

**Feature Branch**: `017-frontend-lancamentos`

**Created**: 2026-11-11

**Status**: Draft

**Input**: User description: "O coração do Tostão no front: a pessoa vê os lançamentos do workspace do mais recente para o mais antigo, com filtros e páginas; registra receitas, despesas e transferências entre contas; marca um lançamento como pendente e o efetiva depois; edita e exclui. Usa a API das specs 004 e 009 (e mostra, sem criar, o que vem das specs 010, 011 e 013), a base da 015 e as contas e categorias da 016."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver os lançamentos (Priority: P1)

A dona abre "Lançamentos" e vê o que entrou e saiu, do mais recente para o mais antigo, com a conta, a categoria e a data de cada um.

**Why this priority**: é a tela que a pessoa mais usa; sem ela, registrar não tem para onde voltar. É também a primeira lista paginada do aplicativo.

**Independent Test**: com um workspace que tem receitas, despesas e transferências, abrir "Lançamentos" mostra a primeira página, em ordem, com o valor com sinal e a cor certa, e as outras páginas ficam a um toque.

**Acceptance Scenarios**:

1. **Given** uma pessoa logada, **When** ela abre "Lançamentos" pela navegação, **Then** vê os lançamentos mais recentes primeiro, cada linha com a descrição, a conta, a categoria, a data e o valor (receita positiva, despesa negativa), e a informação de qual página está vendo e quantas existem.
2. **Given** mais lançamentos do que cabem em uma página, **When** ela escolhe "Próxima" ou "Anterior", **Then** vê a página pedida, sem perder os filtros; os botões ficam indisponíveis nos extremos.
3. **Given** uma transferência, **When** a lista aparece, **Then** as duas pontas aparecem como "Transferência", cada uma na sua conta, com a direção indicada ("Para Reserva" na saída, "De Carteira" na entrada); o valor da transferência aparece sem sinal, no estilo próprio do design system.
4. **Given** um lançamento pendente, **When** a lista aparece, **Then** a linha o marca como "Pendente"; os que vieram de uma compra parcelada, de uma recorrência ou de uma importação aparecem com o selo correspondente ("Parcela 2 de 10", "Recorrente", "Importado").
5. **Given** uma conta ou categoria arquivada que ainda é usada por lançamentos antigos, **When** a lista aparece, **Then** o nome dela continua aparecendo (nunca um identificador).
6. **Given** um workspace sem lançamentos, **When** a pessoa abre a tela, **Then** vê um estado vazio que explica o que fazer e, para quem pode registrar, oferece "Novo lançamento"; com filtros que não encontram nada, vê "Nenhum lançamento encontrado" e o caminho para limpar os filtros.
7. **Given** carregamento, erro de rede ou API fora do ar, **When** a tela abre, **Then** vê o estado de carregamento e, em caso de falha, o aviso com "Tentar de novo"; uma página que falha nunca esconde as já vistas por engano.

---

### User Story 2 - Registrar uma receita ou uma despesa (Priority: P1)

A dona registra uma despesa (ou receita) informando o valor, a data, a descrição, a conta e a categoria.

**Why this priority**: é a ação central do aplicativo.

**Independent Test**: preencher valor, data, descrição, conta e categoria cria o lançamento, que aparece na lista com o valor com o sinal do tipo; o saldo da conta muda de acordo.

**Acceptance Scenarios**:

1. **Given** a lista e o papel de dono ou editor, **When** a pessoa escolhe "Novo lançamento", **Then** vê o formulário com o tipo (despesa, receita ou transferência), o valor, a data (hoje), a descrição, a conta, a categoria e as observações, e o botão para salvar.
2. **Given** o tipo despesa (ou receita), **When** a pessoa escolhe a categoria, **Then** só vê as categorias ativas daquele tipo, as subcategorias aparecendo sob a principal ("Alimentação › Mercado"); trocar o tipo troca as categorias oferecidas e descarta a escolhida se ela não serve ao novo tipo.
3. **Given** o valor, **When** a pessoa o digita em reais, **Then** vale o campo de dinheiro da 016, só com valores maiores que zero (o sinal vem do tipo, nunca da digitação); zero, negativo ou fora do limite aparece como erro no campo.
4. **Given** dados válidos, **When** ela salva, **Then** volta à lista com um aviso de que o lançamento foi registrado, e ele aparece com o valor com sinal; contas arquivadas e categorias arquivadas não são oferecidas.
5. **Given** descrição vazia ou com mais de 200 caracteres, observações com mais de 1.000, data que não existe ou fora de 2000 a 2100, conta ou categoria não escolhida, **When** ela salva, **Then** cada campo mostra o seu erro, nada é enviado e o foco vai ao primeiro campo com erro.
6. **Given** a opção "Pendente" (o lançamento ainda não aconteceu), **When** ela salva, **Then** o lançamento é criado como pendente e aparece marcado, sem mexer no saldo realizado.
7. **Given** o envio em andamento, **When** a pessoa toca de novo em salvar, **Then** nada é enviado duas vezes.
8. **Given** uma conta ou categoria que a API recusa (por exemplo arquivada em outro aparelho), **When** ela salva, **Then** o erro aparece no campo certo ou no aviso geral, e os valores digitados ficam.

---

### User Story 3 - Registrar uma transferência (Priority: P1)

A dona move dinheiro de uma conta para outra e o aplicativo registra as duas pontas de uma vez.

**Why this priority**: sem transferência, mover dinheiro entre contas distorceria receitas e despesas.

**Independent Test**: escolher origem, destino, valor e data cria uma transferência que aparece nas duas contas (a saída na origem, a entrada no destino), sem categoria.

**Acceptance Scenarios**:

1. **Given** o tipo "Transferência", **When** ele é escolhido, **Then** o formulário mostra "De" e "Para" (duas contas) no lugar da conta e da categoria; a categoria não existe nesse tipo.
2. **Given** origem e destino iguais, **When** ela salva, **Then** o campo do destino mostra que precisam ser diferentes e nada é enviado.
3. **Given** dados válidos, **When** ela salva, **Then** volta à lista com o aviso, e as duas pontas aparecem (a saída na conta de origem, a entrada na de destino).
4. **Given** mudar o tipo de transferência para despesa ou receita antes de salvar, **When** a pessoa troca, **Then** a conta de destino e o que não vale para o novo tipo são descartados e a categoria passa a ser pedida.

---

### User Story 4 - Filtrar e buscar (Priority: P2)

A dona encontra um lançamento filtrando por período, conta, categoria, tipo, estado ou por um trecho da descrição.

**Why this priority**: com o tempo a lista cresce; sem filtros ela perde utilidade.

**Independent Test**: aplicar período, conta e uma busca reduz a lista aos lançamentos que atendem a todos os filtros; limpar os filtros devolve a lista inteira.

**Acceptance Scenarios**:

1. **Given** a lista, **When** a pessoa abre "Filtros", **Then** vê período (de/até), conta, categoria, tipo, estado e busca, e pode aplicar e limpar.
2. **Given** filtros aplicados, **When** a lista carrega, **Then** só mostra o que atende a todos, volta para a primeira página, e mostra quais filtros estão ativos (com a contagem).
3. **Given** a categoria principal escolhida como filtro, **When** a lista carrega, **Then** inclui os lançamentos das subcategorias dela.
4. **Given** um período em que a data final é anterior à inicial, **When** ela aplica, **Then** o campo mostra o erro e nada é consultado.
5. **Given** um trecho de busca com `%` ou `_`, **When** ela aplica, **Then** esses caracteres valem literalmente (como a API garante), e a busca passa de 100 caracteres é recusada no campo.
6. **Given** filtros aplicados, **When** a pessoa abre um lançamento e volta, **Then** a lista volta com os mesmos filtros e a mesma página; trocar de workspace ou sair limpa tudo.

---

### User Story 5 - Editar um lançamento e efetivar um pendente (Priority: P2)

A dona corrige um lançamento e, quando um pendente acontece, o efetiva.

**Why this priority**: erros de digitação são comuns e o pendente só tem valor se puder virar realizado.

**Independent Test**: abrir um lançamento, mudar o valor e salvar atualiza a lista; efetivar um pendente tira a marca de pendente.

**Acceptance Scenarios**:

1. **Given** uma linha da lista e o papel de dono ou editor, **When** a pessoa a toca, **Then** abre o formulário preenchido com o tipo mostrado e não editável; o leitor que toca na linha vê os dados do lançamento sem poder editar.
2. **Given** dados válidos alterados, **When** ela salva, **Then** volta à lista com o aviso; as mesmas regras do cadastro valem.
3. **Given** uma transferência, **When** a pessoa abre qualquer uma das pontas, **Then** o formulário mostra a origem e o destino de verdade (não importa em qual ponta ela tocou), e salvar muda as duas pontas juntas.
4. **Given** um lançamento pendente, **When** a pessoa escolhe "Efetivar", **Then** ele passa a efetivado (numa transferência, as duas pontas), a marca de pendente some e a lista é atualizada; efetivar um já efetivado não faz nada e não dá erro.
5. **Given** um lançamento efetivado, **When** a pessoa marca "Pendente" e salva, **Then** ele volta a ser pendente.
6. **Given** um lançamento que deixou de existir (excluído em outro aparelho), **When** a pessoa tenta salvar, efetivar ou excluir, **Then** vê que não foi encontrado e volta à lista atualizada.
7. **Given** uma parcela, uma ocorrência de recorrência ou um lançamento importado, **When** a pessoa o edita, **Then** as regras e recusas da API são mostradas como em qualquer outro lançamento (a tela não as inventa).

---

### User Story 6 - Excluir um lançamento (Priority: P2)

A dona exclui um lançamento lançado por engano, com confirmação.

**Why this priority**: sem excluir, um engano fica para sempre no saldo.

**Independent Test**: excluir um lançamento (após confirmar) o tira da lista e muda o saldo da conta; numa transferência as duas pontas somem.

**Acceptance Scenarios**:

1. **Given** um lançamento aberto para edição, **When** a pessoa escolhe "Excluir", **Then** vê a confirmação explicando o efeito (sai da lista e do saldo; numa transferência, as duas pontas saem; numa parcela ou recorrência, só ela), com o foco em "Cancelar".
2. **Given** a confirmação aberta, **When** a pessoa cancela (botão ou Esc), **Then** nada muda.
3. **Given** a confirmação, **When** ela confirma, **Then** o lançamento é excluído, a pessoa volta à lista com o aviso e ele não aparece mais.
4. **Given** a falha da API ao excluir, **When** a ação termina, **Then** a pessoa vê o aviso, o lançamento continua como estava e ela pode tentar de novo.

---

### User Story 7 - Cada papel vê só o que pode fazer, e o workspace manda (Priority: P1)

Quem é leitor consulta tudo e não vê ações de alterar; trocar de workspace mostra só os lançamentos do novo.

**Why this priority**: o aplicativo não pode oferecer ações que a API vai recusar, nem misturar workspaces.

**Independent Test**: com um leitor, a lista e o detalhe aparecem sem nenhuma ação de alterar; trocar de workspace mostra os lançamentos, as contas e as categorias do novo, sem nada do anterior, e zera os filtros.

**Acceptance Scenarios**:

1. **Given** o papel de leitor, **When** ele abre a lista ou um lançamento, **Then** vê tudo e nenhuma ação de criar, editar, efetivar ou excluir; abrir o formulário de criar por endereço mostra o aviso de papel com a volta.
2. **Given** a API que recusa uma alteração por permissão (o papel mudou em outro aparelho), **When** a pessoa salva, efetiva ou exclui, **Then** vê a mensagem de permissão e nada muda.
3. **Given** mais de um workspace, **When** ela troca o workspace atual, **Then** lista, filtros, contas e categorias passam a ser os do novo e nenhum dado do anterior permanece; uma resposta atrasada do anterior é descartada.
4. **Given** um endereço de lançamento que não é do workspace atual ou não existe, **When** a tela abre, **Then** vê "não encontrado" com a volta, igual nos dois casos.
5. **Given** a sessão que termina durante o uso (401), **When** uma chamada falha por isso, **Then** vale a base: entrada com o aviso e depois o endereço retomado.

---

### Edge Cases

- Valor em reais com formatos variados: o campo de dinheiro da 016 vale; aqui só se acrescenta "maior que zero".
- Valor no limite da API (R$ 10.000.000.000,00) aceito; um centavo além, recusado no campo.
- Data de hoje no fuso do aparelho; uma data futura vale (é como se registra o que vai acontecer); fora de 2000 a 2100, erro.
- Descrição só com espaços conta como vazia; espaços das pontas são tirados antes de enviar; observações vazias não são enviadas.
- Conta ou categoria arquivada depois de o formulário abrir: a API recusa e a tela mostra o erro, sem perder o que foi digitado.
- Muitas páginas (centenas): os controles mostram a página atual e o total; pular direto para uma página inexistente cai na última ou mostra a lista vazia da API, sem quebrar.
- Filtros que escondem tudo: estado "nenhum encontrado" com "Limpar filtros", nunca uma tela vazia.
- Lançamentos de um mesmo dia: agrupados sob o dia (hoje, ontem ou a data), na ordem da API.
- Transferência aberta pela ponta de destino: o formulário não troca origem e destino de lugar por engano.
- Um lançamento de transferência não tem categoria; o formulário não a pede, e o que a API devolver como categoria nula não aparece como erro.
- Tipo desconhecido ou estado desconhecido vindo da API (versão futura): a linha aparece como "Lançamento", sem quebrar a lista.
- Resposta da API sem o formato esperado: vira o aviso geral de erro, nunca uma tela quebrada.
- Tela estreita (320 px) e zoom de 200%: linhas, filtros e formulário sem rolagem horizontal; o valor nunca é cortado.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: A navegação MUST ganhar "Lançamentos" (além de Início, Contas, Categorias e Perfil), na barra inferior e no menu lateral, com o item atual marcado; as rotas MUST ser `/lancamentos`, `/lancamentos/novo` e `/lancamentos/:id`, todas exigindo sessão.
- **FR-002**: Toda chamada MUST usar o cliente único da base, no workspace atual, e MUST descartar a resposta de uma consulta que ficou para trás quando o workspace, a página ou um filtro muda.
- **FR-003**: A lista MUST mostrar os lançamentos na ordem da API (do mais recente ao mais antigo), agrupados por dia, cada linha com descrição, conta, categoria (ou "Sem categoria"), valor com sinal e cor do tipo, e a marca de pendente; as pontas de transferência MUST mostrar a conta da outra ponta; parcelas, recorrências e importados MUST ter o seu selo; nomes de contas e categorias arquivadas MUST continuar aparecendo.
- **FR-004**: A lista MUST ser paginada pela API (20 por página), mostrar a página atual e o total, e oferecer "Anterior" e "Próxima", indisponíveis nos extremos; mudar um filtro MUST voltar à primeira página.
- **FR-005**: Os filtros MUST ser período (de/até, com data final não anterior à inicial), conta, categoria (a principal inclui as subcategorias), tipo, estado e busca (até 100 caracteres), combinados com "e"; MUST mostrar os ativos com a contagem; MUST poder ser limpos; MUST sobreviver à ida a um lançamento e à volta; MUST ser zerados ao trocar de workspace e ao sair; e MUST NOT ir para o endereço, para o armazenamento do navegador nem para a API além dos parâmetros do contrato.
- **FR-006**: O formulário de lançamento MUST pedir tipo (só na criação), valor, data, descrição, conta (ou origem e destino na transferência), categoria (só em receita e despesa) e observações, mais "Pendente"; MUST validar no navegador as mesmas regras da API (valor de 1 centavo ao limite, data de 2000 a 2100, descrição de 1 a 200, observações até 1.000, destino diferente da origem, categoria do mesmo tipo e ativa) e mostrar no campo os erros de campo da API.
- **FR-007**: O valor MUST ser digitado pelo campo de dinheiro da 016, sempre positivo (o sinal vem do tipo); o corpo enviado MUST levar centavos inteiros positivos; MUST NOT haver aritmética de ponto flutuante.
- **FR-008**: Trocar o tipo no formulário de criação MUST descartar o que não vale para o novo tipo (destino e origem, ou categoria) e filtrar as categorias oferecidas pelo tipo; as contas e categorias oferecidas MUST ser só as ativas, e a categoria de uma subcategoria MUST aparecer com o nome da principal.
- **FR-009**: Uma transferência MUST ser aberta com a origem e o destino reais, qualquer que seja a ponta tocada, e MUST ser salva (e excluída, e efetivada) como um todo; o tipo MUST NOT poder ser mudado na edição.
- **FR-010**: "Efetivar" MUST estar disponível a quem altera, em lançamentos pendentes, MUST chamar a API de efetivação e atualizar a lista; MUST ser idempotente na tela; e marcar ou desmarcar "Pendente" na edição MUST enviar o estado correspondente.
- **FR-011**: Excluir MUST pedir confirmação com foco em "Cancelar", explicando o efeito; a falha MUST manter o lançamento como estava; o sucesso MUST voltar à lista com o aviso.
- **FR-012**: As ações de criar, editar, efetivar e excluir MUST aparecer só para dono e editor do workspace atual; o leitor MUST poder abrir o lançamento para consulta, sem ações, e ao abrir o formulário de criar por endereço MUST ver o aviso de papel; a recusa `403` da API MUST ser mostrada como falta de permissão.
- **FR-013**: Erros MUST seguir o padrão da base (campo, aviso geral, "Tentar de novo", `404` como "não encontrado" com volta, `401` pelo cliente); a edição de parcelas, recorrências e importados MUST mostrar a recusa da API como vier.
- **FR-014**: Cada tela MUST ter estados de carregamento, erro e vazio, anunciados a leitores de tela; salvar, efetivar e excluir MUST indicar andamento e impedir envio duplicado; o resultado de cada ação MUST ser anunciado.
- **FR-015**: Trocar o workspace atual MUST recarregar tudo do novo, sem deixar dado do anterior; um endereço de lançamento de outro workspace MUST aparecer como não encontrado.
- **FR-016**: Valores MUST usar o formato do design system (centavos inteiros formatados em reais) e as linhas MUST vir do `TransactionRow` do design system; todos os textos em português do Brasil.
- **FR-017**: As telas MUST seguir a base: 320 px a telas largas, zoom de 200% sem rolagem horizontal, alvos de toque de ao menos 44 px, foco visível, uso só com o teclado, foco no lugar certo ao abrir um formulário, voltar à lista e depois de um erro.
- **FR-018**: A verificação do front-end (lint e guard-rails, tipos, testes, build, orçamento de 150 KB do JavaScript inicial) MUST continuar valendo, e o service worker MUST continuar sem guardar respostas de `/api/*`.

### Key Entities *(include if feature involves data)*

- **Lançamento**: identificador, tipo (receita, despesa ou transferência), conta, valor em centavos com sinal, data, descrição, observações, categoria, estado (efetivado ou pendente), e, quando houver, o grupo da transferência e a conta da outra ponta, a parcela (número e total), a recorrência e se veio de importação.
- **Página de lançamentos**: itens, página atual, tamanho, total de itens e total de páginas.
- **Filtros**: período, conta, categoria, tipo, estado e busca; vivem só na memória da sessão, por workspace.
- **Conta e categoria**: as da 016, usadas para escolher no formulário e para mostrar os nomes na lista.
- **Papel** e **workspace atual**: os da 015.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes de lista, a tela mostra os lançamentos na ordem da API, agrupados por dia, com valor, sinal, conta, categoria, marcas e selos corretos; as duas pontas de uma transferência aparecem cada uma na sua conta; nomes de contas e categorias arquivadas continuam aparecendo.
- **SC-002**: Em 100% dos testes de paginação, "Anterior" e "Próxima" levam à página certa, ficam indisponíveis nos extremos, preservam os filtros, e uma resposta atrasada de outra página ou de outro filtro é descartada.
- **SC-003**: Em 100% dos testes de filtro, cada filtro e a combinação deles chegam à API pelos parâmetros do contrato, mudar um filtro volta à primeira página, a data final antes da inicial é recusada no campo, a busca passa de 100 caracteres é recusada, e limpar devolve a lista inteira.
- **SC-004**: Em 100% dos testes de formulário, o sucesso volta à lista com o aviso, cada erro de campo aparece no campo com o foco no primeiro, o valor vai em centavos inteiros positivos, a troca de tipo descarta o que não vale, e o envio duplicado chama a API uma vez só.
- **SC-005**: Em 100% dos testes de transferência, origem e destino iguais são recusados, o corpo leva origem, destino e nenhuma categoria, a ponta de destino abre com a origem e o destino certos, e salvar, efetivar e excluir valem para o conjunto.
- **SC-006**: Em 100% dos testes de efetivar e excluir, a confirmação cancela sem mudar nada, o sucesso atualiza a lista, a falha mantém o estado, e efetivar um efetivado não dá erro.
- **SC-007**: Em 100% dos testes de papéis, o leitor não vê nenhuma ação de alterar nem abre o formulário de criar por endereço, e a recusa `403` vira mensagem de permissão; dono e editor veem todas as ações.
- **SC-008**: Em 100% dos testes de workspace, trocar o workspace recarrega lista, contas e categorias do novo, zera os filtros e descarta resposta atrasada do anterior.
- **SC-009**: Em 100% das verificações de acessibilidade automatizadas das telas novas (lista, filtros, formulários, confirmação, estados vazio, erro e de papel), não há violação de nível sério ou crítico; campos têm rótulo e erros são anunciados.
- **SC-010**: A verificação do front-end passa dentro do orçamento de 150 KB, e as 1414 verificações do back-end continuam passando sem alteração.
- **SC-011**: No roteiro com a API real, a pessoa registra despesa, receita, transferência e um pendente, filtra, edita, efetiva e exclui, e os saldos das contas e a lista refletem cada passo, com a limpeza dos dados de teste ao fim.

## Assumptions

- Depende da `015-frontend-esqueleto-auth` (cliente, sessão, navegação, workspace atual, papéis), da `016-frontend-contas-categorias` (campo de dinheiro, carregamento por chave, formulários, confirmações, contas e categorias), das specs `004-lancamentos` e `009-lancamento-pendente` do back-end (a API não muda) e da constituição (v1.0.0).
- Compra parcelada, recorrências, "efetivar sozinho" (`autoClear`) e importação de extratos têm telas próprias nas specs seguintes; aqui a lista só os mostra com o selo, e a edição segue as regras da API.
- Não há resumo nem totais por período na lista (o painel mensal e os orçamentos vêm nas próximas specs); o saldo de cada conta continua sendo o da tela de contas.
- Os filtros vivem em memória da sessão (por workspace); nada deles vai para o armazenamento do navegador nem para o endereço.
- A lista tem 20 itens por página (o padrão da API) e usa botões de página, não rolagem infinita.
- A moeda é só o real (BRL). Não há anexos, etiquetas, divisão de lançamento nem lançamento em lote.
- Quem é dono ou editor altera; quem é leitor consulta. A API continua sendo a autoridade.
