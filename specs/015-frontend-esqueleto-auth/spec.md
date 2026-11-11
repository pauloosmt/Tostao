# Feature Specification: Esqueleto do front-end e autenticação

**Feature Branch**: `015-frontend-esqueleto-auth`

**Created**: 2026-11-11

**Status**: Draft

**Input**: User description: "Esqueleto do front-end do Tostão: a pessoa cria a conta, entra, continua logada ao recarregar, escolhe o workspace e sai, num aplicativo instalável (PWA) construído sobre o design system do projeto. É a base de todas as telas seguintes: o cliente da API, a sessão, a navegação, o tratamento de erros e o tema."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Entrar e ver o início (Priority: P1)

A dona abre o Tostão no celular, informa e-mail e senha e cai no início, que a cumprimenta e mostra em qual workspace
ela está.

**Why this priority**: sem entrar não existe nenhuma outra tela. É também a primeira prova de que o front fala com a API
só pelo domínio do app e só com o cookie de sessão.

**Independent Test**: com uma conta existente, informar e-mail e senha corretos leva ao início com o nome da pessoa e o
workspace; senha errada mostra o erro sem revelar se o e-mail existe.

**Acceptance Scenarios**:

1. **Given** uma pessoa sem sessão, **When** abre o aplicativo, **Then** vê a tela de entrada com os campos e-mail e senha,
   o botão "Entrar" e o caminho para criar conta; nenhuma tela interna aparece.
2. **Given** e-mail e senha corretos, **When** ela envia, **Then** entra, vê o início com o nome dela e o workspace atual
   (nome e papel), e a sessão fica guardada pelo cookie da API (nada de token no armazenamento do navegador).
3. **Given** e-mail inexistente ou senha errada, **When** ela envia, **Then** vê a mesma mensagem nos dois casos
   ("e-mail ou senha incorretos"), a senha é limpa e o foco volta para o campo de senha.
4. **Given** muitas tentativas erradas (a API responde que a pessoa está bloqueada), **When** ela tenta de novo, **Then** vê
   quanto tempo falta para tentar outra vez (o valor que a API informa), e o botão não deixa enviar durante a espera.
5. **Given** campos vazios ou um e-mail sem formato de e-mail, **When** ela envia, **Then** vê o erro no próprio campo, sem
   chamar a API, e o foco vai para o primeiro campo com erro.
6. **Given** o envio em andamento, **When** a pessoa toca de novo em "Entrar", **Then** nada é enviado duas vezes (o botão
   fica indisponível e avisa que está entrando).
7. **Given** a API fora do ar ou sem rede, **When** ela envia, **Then** vê que não foi possível falar com o servidor e pode
   tentar de novo; nunca fica carregando para sempre.

---

### User Story 2 - Criar a conta (Priority: P1)

Uma pessoa nova cria a conta com nome, e-mail e senha e já entra, com o workspace pessoal pronto.

**Why this priority**: é como qualquer pessoa começa a usar o aplicativo.

**Independent Test**: preencher nome, e-mail e uma senha válida cria a conta, entra e mostra o início com o workspace pessoal;
e-mail já usado mostra o erro no campo de e-mail.

**Acceptance Scenarios**:

1. **Given** a tela de entrada, **When** a pessoa escolhe "Criar conta", **Then** vê o formulário de cadastro com nome,
   e-mail e senha e o caminho de volta para entrar.
2. **Given** dados válidos, **When** ela envia, **Then** a conta é criada, ela entra e vê o início com o workspace pessoal.
3. **Given** a senha, **When** ela digita, **Then** vê a regra (8 a 72 bytes em UTF-8, com ao menos uma letra e um número)
   e o campo avisa o que falta; a senha pode ser mostrada ou escondida.
4. **Given** nome com mais de 100 caracteres, e-mail com mais de 254 ou sem formato, ou senha fora da regra, **When** ela
   envia, **Then** cada campo mostra o seu erro e nada é enviado.
5. **Given** um e-mail já cadastrado, **When** ela envia, **Then** vê o erro no campo de e-mail e os outros campos mantêm o
   que foi digitado (menos a senha, que é limpa).
6. **Given** erros de campo devolvidos pela API (por exemplo, uma regra que o front não conhece), **When** chegam, **Then** cada
   mensagem aparece no campo certo; erro sem campo aparece num aviso geral do formulário.

---

### User Story 3 - Continuar logada e sair (Priority: P1)

A dona fecha o aplicativo e volta no dia seguinte: continua logada. Quando a sessão acaba, ou quando ela sai, volta para a
tela de entrada sem ver dado nenhum.

**Why this priority**: uma sessão que se perde a cada recarga, ou que não encerra direito, inutiliza o aplicativo e vaza dados.

**Independent Test**: com sessão válida, recarregar a página mantém a pessoa no mesmo lugar; sair leva à entrada, e voltar com o
botão do navegador não mostra nada interno.

**Acceptance Scenarios**:

1. **Given** uma sessão válida, **When** a pessoa abre ou recarrega o aplicativo, **Then** a sessão é restaurada sozinha e ela
   vê a tela em que estava, depois de um carregamento curto que não mostra a tela de entrada por engano.
2. **Given** uma pessoa que abriu um endereço interno sem estar logada, **When** ela entra, **Then** vai para esse endereço, e
   não para o início.
3. **Given** a sessão expirada ou encerrada em outro aparelho, **When** qualquer chamada da API responde "sem sessão", **Then**
   ela volta para a tela de entrada com o aviso "Sua sessão terminou. Entre de novo.", e nenhum dado da sessão anterior fica
   na tela nem na memória.
4. **Given** a pessoa logada, **When** escolhe "Sair", **Then** a sessão é encerrada na API, ela volta para a entrada e o
   botão "voltar" do navegador não mostra tela interna.
5. **Given** a pessoa logada, **When** escolhe "Sair de todos os aparelhos" e confirma, **Then** todas as sessões dela são
   encerradas e ela volta para a entrada.
6. **Given** duas abas abertas, **When** ela sai numa delas, **Then** a outra, na próxima ação, volta para a entrada.
7. **Given** a falha ao encerrar a sessão (sem rede), **When** ela escolhe "Sair", **Then** o aplicativo avisa que não conseguiu
   e mantém a pessoa logada (não finge que saiu).

---

### User Story 4 - A estrutura do aplicativo e o workspace atual (Priority: P1)

Dentro do aplicativo a dona vê sempre a mesma estrutura: a navegação, o workspace em que está e o seu perfil. É nela que as
próximas telas (contas, lançamentos, dashboard) vão entrar.

**Why this priority**: todas as telas seguintes dependem desta estrutura, do workspace atual e do tratamento comum de erro e
de carregamento.

**Independent Test**: logada, a pessoa vê o início e o perfil; se participa de mais de um workspace, troca entre eles e a
escolha continua depois de recarregar.

**Acceptance Scenarios**:

1. **Given** a pessoa logada num celular (menos de 720 px), **When** usa o aplicativo, **Then** a navegação é a barra inferior;
   em tela larga (960 px ou mais), é o menu lateral; nas duas, o item atual fica marcado e a navegação funciona pelo teclado.
2. **Given** a navegação, **When** ela olha as opções, **Then** vê apenas telas que existem ("Início" e "Perfil"); as próximas
   specs acrescentam as demais. Um endereço que não existe mostra uma página "não encontrada" com o caminho de volta.
3. **Given** a pessoa em um ou mais workspaces, **When** abre o aplicativo, **Then** o workspace atual é o último escolhido por
   ela neste aparelho, ou o primeiro da lista se nunca escolheu ou se o escolhido não existe mais para ela.
4. **Given** mais de um workspace, **When** ela troca no perfil, **Then** o aplicativo passa a mostrar o novo (nome e papel) e
   guarda a escolha neste aparelho (só o identificador do workspace, nunca dado sensível).
5. **Given** o perfil, **When** ela o abre, **Then** vê nome, e-mail, os workspaces com o papel dela em cada um (dono, editor ou
   leitor, em português), o tema e as ações "Sair" e "Sair de todos os aparelhos".
6. **Given** qualquer tela, **When** uma chamada da API falha por erro do servidor ou da rede, **Then** a tela mostra um aviso
   claro com "Tentar de novo"; erro de validação aparece no campo; "não encontrado" mostra a página própria; nenhum erro vira
   tela em branco.
7. **Given** o carregamento de qualquer tela, **When** demora, **Then** a pessoa vê um estado de carregamento (nunca uma tela
   vazia), e quem usa leitor de tela é avisada.

---

### User Story 5 - Instalar como aplicativo, com a cara do Tostão (Priority: P2)

A dona instala o Tostão na tela inicial do celular e ele abre como um aplicativo, com o ícone, o nome e as cores da marca, nos
modos claro e escuro.

**Why this priority**: o produto foi pensado como PWA; instalar e abrir rápido é parte do uso diário. Não bloqueia as funções.

**Independent Test**: o navegador oferece "instalar"; o ícone aparece nos tamanhos 192, 512 e maskable; sem rede, o aplicativo abre
e avisa que está sem conexão em vez de quebrar.

**Acceptance Scenarios**:

1. **Given** o aplicativo servido por HTTPS, **When** o navegador avalia a instalação, **Then** o manifest tem nome, nome curto,
   cores, idioma pt-BR, modo "standalone" e os ícones de 192, 512 e maskable, além do ícone do Apple (iOS) e do favicon.
2. **Given** a pessoa sem rede, **When** abre o aplicativo já instalado, **Then** a estrutura abre, e as telas que dependem da
   API dizem que estão sem conexão, com "Tentar de novo"; nenhum dado de API fica guardado para uso offline.
3. **Given** uma versão nova publicada, **When** a pessoa abre o aplicativo, **Then** ele se atualiza sozinho na abertura seguinte
   (sem pedir nada) e sem prender uma versão antiga.
4. **Given** o sistema em modo claro ou escuro, **When** o aplicativo abre, **Then** segue o tema do sistema; a pessoa pode
   forçar claro ou escuro no perfil e a escolha continua neste aparelho.
5. **Given** as fontes do aplicativo, **When** a página carrega, **Then** nenhuma fonte, script, imagem ou folha de estilo vem
   de outro domínio: o navegador fala só com o domínio do aplicativo.

---

### Edge Cases

- Endereço com `?token=`, `?password=`, `?code=` ou parecido: o aplicativo não os lê, não os repassa à API e a verificação
  automática do projeto impede escrevê-los no código.
- Vários envios rápidos em qualquer formulário: um só é enviado.
- Resposta da API que o aplicativo não conhece (corpo sem o formato esperado): vira o aviso geral de erro, nunca uma tela quebrada.
- Pessoa sem nenhum workspace na resposta: mostra uma tela explicando que não há workspace e oferece "Sair" (não deve ocorrer,
  porque o cadastro cria o pessoal).
- O workspace guardado neste aparelho pertence a outra conta: ao entrar com outra conta, vale o primeiro workspace dela, nunca o
  guardado.
- Trocar de conta no mesmo aparelho: nada da conta anterior (nome, workspaces, escolhas de tela) aparece na seguinte.
- Gerenciador de senhas e preenchimento automático: os campos declaram o que são (e-mail, senha atual, nova senha, nome).
- Tela estreita (320 px) e zoom de 200%: nada fica cortado nem exige rolagem horizontal.
- Teclado: toda ação é alcançável e a ordem do foco segue a ordem visual; o foco nunca se perde ao trocar de tela.
- Resposta 429 sem o tempo de espera informado: mostra a mensagem de bloqueio sem contagem.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O aplicativo MUST oferecer cadastro (nome, e-mail, senha) e entrada (e-mail, senha) pela API da spec 001, e MUST
  iniciar a sessão apenas pelo cookie `HttpOnly` que a API define; MUST NOT guardar token, senha ou código no armazenamento do
  navegador, na memória além do formulário em uso, no endereço ou na consulta do endereço.
- **FR-002**: Toda chamada à API MUST passar por um único cliente que: usa só o caminho `/api/*` do próprio domínio, envia o
  cookie da sessão, monta o corpo e lê a resposta como JSON, converte o erro da API (Problem Details) num erro tipado com
  situação, título, detalhe e erros por campo, e MUST NOT montar `Authorization` à mão.
- **FR-003**: O cliente MUST tratar a resposta "sem sessão" (401) de qualquer chamada autenticada encerrando a sessão no
  aplicativo, voltando à entrada com o aviso de sessão terminada e preservando o endereço pedido para depois de entrar. O 401 da
  própria tentativa de entrada MUST ser tratado como credencial inválida, não como sessão terminada.
- **FR-004**: A entrada MUST mostrar a mesma mensagem para e-mail inexistente e senha errada, MUST mostrar o tempo de espera
  informado pela API no bloqueio (429), e MUST impedir novo envio durante a espera.
- **FR-005**: Os formulários MUST validar no navegador as mesmas regras da API (nome de 1 a 100 caracteres; e-mail com formato e
  até 254; senha de 8 a 72 bytes em UTF-8 com letra e número) para dar retorno imediato, e MUST exibir, no campo certo, os erros
  de campo devolvidos pela API; a API continua sendo a autoridade. Erro sem campo MUST aparecer num aviso geral.
- **FR-006**: Ao abrir ou recarregar, o aplicativo MUST restaurar a sessão consultando os dados da pessoa e seus workspaces
  (`/me`), mostrando um estado de carregamento enquanto isso, e MUST ir para a entrada se não houver sessão.
- **FR-007**: O aplicativo MUST oferecer "Sair" e "Sair de todos os aparelhos" (com confirmação), MUST limpar da tela e da memória
  tudo o que veio da sessão ao sair, e MUST manter a pessoa logada, avisando, se o encerramento falhar.
- **FR-008**: O aplicativo MUST ter uma estrutura de navegação única com as telas "Início" e "Perfil": barra inferior abaixo de
  720 px e menu lateral a partir de 960 px, com o item atual marcado (`aria-current`), acessível pelo teclado; endereço
  inexistente MUST mostrar a página "não encontrada".
- **FR-009**: O aplicativo MUST manter o workspace atual: o último escolhido neste aparelho, ou o primeiro da lista quando não há
  escolha ou ela não vale mais para a pessoa; MUST guardar só o identificador do workspace, por pessoa; e MUST mostrar nome e papel
  (dono, editor ou leitor) do workspace atual.
- **FR-010**: O perfil MUST mostrar nome, e-mail, os workspaces com o papel de cada um e permitir trocar o workspace atual, escolher
  o tema (sistema, claro ou escuro) e sair.
- **FR-011**: Toda tela MUST ter estados de carregamento, de erro (com "Tentar de novo") e de vazio, e MUST avisar leitores de
  tela das mudanças de estado e de erro (região com `aria-live`/`role="alert"`). Nenhum erro MUST resultar em tela em branco.
- **FR-012**: O aplicativo MUST usar o design system do projeto como única base de interface (componentes, tokens e temas claro e
  escuro), MUST seguir o tema do sistema por padrão e MUST lembrar neste aparelho a escolha manual de tema.
- **FR-013**: O aplicativo MUST ser um PWA instalável: manifest com nome, nome curto, cores, idioma pt-BR, modo "standalone" e
  ícones de 192, 512 e maskable, ícone do Apple e favicon; atualização automática; e service worker que MUST NOT guardar
  respostas de `/api/*`.
- **FR-014**: Nenhum recurso do aplicativo (fontes, scripts, estilos, imagens) MUST ser carregado de outro domínio; as fontes MUST
  ser servidas pelo próprio aplicativo. A verificação automática do projeto MUST recusar origens externas no código e nos estilos.
- **FR-015**: A verificação automática do front-end (lint e guard-rails da constituição) MUST continuar bloqueando `Authorization`
  manual e token, senha ou código em rota ou consulta, e o build e os testes do front-end MUST rodar na verificação do projeto.
- **FR-016**: As telas MUST funcionar de 320 px a telas largas, com zoom de até 200% sem rolagem horizontal, com contraste do design
  system, foco visível e alvos de toque de ao menos 44 px, e MUST ser usáveis só com o teclado.
- **FR-017**: Todos os textos MUST estar em português do Brasil, e valores monetários, quando aparecerem, MUST usar o formato do
  design system (centavos inteiros formatados em reais).
- **FR-018**: A tela de um componente interativo MUST evitar envio duplicado: enquanto uma ação está em andamento, o controle fica
  indisponível e indica o andamento.

### Key Entities *(include if feature involves data)*

- **Sessão**: a prova de que a pessoa entrou. Vive só no cookie `HttpOnly` da API; o aplicativo nunca a lê nem a guarda.
- **Pessoa**: identificador, nome e e-mail, vindos de `/me`.
- **Workspace**: identificador, nome, moeda base e o papel da pessoa nele (dono, editor ou leitor).
- **Workspace atual**: o workspace em uso; só o identificador é lembrado neste aparelho, por pessoa.
- **Erro da API**: situação, título, detalhe e erros por campo (Problem Details), convertido num erro tipado pelo cliente.
- **Tema**: sistema, claro ou escuro; a escolha manual é lembrada neste aparelho.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes de entrada e cadastro, os fluxos de sucesso e cada erro (credencial inválida, bloqueio com espera,
  e-mail já usado, erro de campo, erro geral, servidor fora do ar) levam à tela e à mensagem descritas, e a senha nunca permanece
  no campo depois de uma falha.
- **SC-002**: Em 100% dos testes, nenhuma requisição do aplicativo leva cabeçalho `Authorization`, nem token, senha ou código no
  endereço, e todas vão para `/api/*` do próprio domínio com o cookie.
- **SC-003**: Em 100% dos testes de sessão, recarregar mantém a pessoa, uma resposta 401 de qualquer chamada leva à entrada com o aviso
  e o endereço pedido é retomado depois de entrar, sair não deixa dado da pessoa na tela nem na memória, e a falha ao sair mantém a
  pessoa logada.
- **SC-004**: Em 100% dos testes de workspace, a escolha vale por pessoa, sobrevive à recarga, e um identificador que não pertence
  à pessoa cai no primeiro workspace dela.
- **SC-005**: Em 100% dos testes de navegação, a barra inferior aparece abaixo de 720 px e o menu lateral a partir de 960 px, o item
  atual é marcado, e todo fluxo é concluível só com o teclado, com o foco no lugar certo depois de cada erro e de cada troca de tela.
- **SC-006**: O build gera um manifest com os ícones de 192, 512 e maskable (arquivos existentes), o ícone do Apple e o favicon, e a
  página não referencia nenhum domínio externo (fontes incluídas); a verificação automática falha se alguém reintroduzir um.
- **SC-007**: O service worker não guarda nenhuma resposta de `/api/*`, e sem rede o aplicativo abre e mostra o aviso de
  conexão em vez de quebrar.
- **SC-008**: A tela de entrada fica utilizável em menos de 2 segundos em uma conexão 4G simulada, com o JavaScript inicial de até
  150 KB comprimidos.
- **SC-009**: Em 100% das verificações de acessibilidade automatizadas das telas (entrada, cadastro, início, perfil, não encontrada),
  não há violação de nível sério ou crítico; formulários têm rótulos associados e erros anunciados.
- **SC-010**: O lint (com os guard-rails), a checagem de tipos, o build e os testes do front-end passam na verificação do projeto, e
  as 1414 verificações do back-end continuam passando sem alteração.

## Assumptions

- O aplicativo é só em português do Brasil e pensado primeiro para o celular; o desktop usa o menu lateral do design system.
- A interface usa exclusivamente o design system do projeto (`frontend/src/design-system`); nenhuma biblioteca de componentes externa.
- Esta spec não inclui telas de dados financeiros: contas, categorias, lançamentos, dashboard, orçamentos, cartão, recorrências,
  membros e importação ficam para as specs seguintes, que entram na navegação.
- Não há recuperação de senha nem troca de senha: a API não tem essas rotas. Também não há edição do perfil, criação ou renomeação
  de workspace.
- A sessão dura o que a API define (cookie de um ano, reemitido a cada requisição autenticada); o aplicativo só reage ao 401.
- O desenvolvimento local usa o proxy do Vite para `/api` (espelhando o rewrite da Vercel); em produção o domínio é o da Vercel.
- O papel (dono, editor, leitor) é exibido e será usado pelas telas seguintes para esconder ações que a pessoa não pode fazer; a
  API continua sendo a autoridade e recusa o que não é permitido.
- O ajuste da `public/` (ícones no lugar certo, no manifest), a troca das fontes externas por fontes locais e o botão acessível da
  linha de lançamento do design system fazem parte desta spec, como parte do esqueleto.
- Depende da `001-autenticacao-workspace` (cadastro, entrada, saída, `/me`, cookie e bloqueio por tentativas) e das regras da
  constituição (v1.0.0), em especial os princípios I, III e IV e a seção de guard-rails.
