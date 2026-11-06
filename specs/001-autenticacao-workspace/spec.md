# Feature Specification: Autenticação e Workspace

**Feature Branch**: `001-autenticacao-workspace`

**Created**: 2026-11-05

**Status**: Draft

**Input**: User description: "Cadastro e login de usuários por e-mail e senha, com sessão em cookie, e criação automática de um workspace pessoal para cada novo usuário, onde ele é OWNER. Todo dado do sistema pertence a um workspace; um usuário só acessa dados dos workspaces dos quais é membro, e tentar acessar dados de outro workspace resulta em 403 ou 404. Inclui: GET /me com dados do usuário e seus workspaces, logout, limite de tentativas de login, e o isolamento de dados por workspace em duas camadas (políticas RLS no banco com role de aplicação sem BYPASSRLS, e filtro explícito por workspace na camada de serviço). Contexto: app pessoal de finanças (Tostão), hoje com um único usuário, mas preparado para vários."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Criar conta e começar com um workspace pessoal (Priority: P1)

Uma pessoa nova informa nome, e-mail e senha e cria sua conta. Ao concluir, já está autenticada e
possui um workspace pessoal, no qual é a dona (OWNER), pronto para receber contas e lançamentos nas
próximas funcionalidades.

**Why this priority**: sem conta e sem workspace não existe onde guardar nenhum dado financeiro. É a
base de todo o resto do produto e, sozinha, já permite a primeira entrada no app.

**Independent Test**: cadastrar um e-mail novo e confirmar, em seguida, que a pessoa está autenticada
e que a consulta aos seus dados mostra exatamente um workspace pessoal em que ela é OWNER.

**Acceptance Scenarios**:

1. **Given** um e-mail ainda não cadastrado, **When** a pessoa envia nome, e-mail e uma senha válida,
   **Then** a conta é criada, um workspace pessoal é criado com ela como OWNER e ela fica autenticada.
2. **Given** um e-mail já cadastrado (inclusive com letras maiúsculas diferentes), **When** alguém tenta
   cadastrá-lo de novo, **Then** o cadastro é recusado com uma mensagem clara e nenhum dado novo é criado.
3. **Given** uma senha fora das regras ou um e-mail inválido, **When** a pessoa tenta se cadastrar,
   **Then** o cadastro é recusado indicando qual campo precisa ser corrigido, sem criar conta nem workspace.
4. **Given** uma falha em qualquer etapa do cadastro, **When** o cadastro é interrompido, **Then** não
   sobra conta sem workspace nem workspace sem dono.

---

### User Story 2 - Entrar, permanecer autenticada e sair (Priority: P1)

Quem já tem conta entra com e-mail e senha, permanece autenticada enquanto usa o app e pode sair quando
quiser. Ao sair, a sessão deixa de valer.

**Why this priority**: sem entrar de novo não há como voltar aos próprios dados em outro dia ou outro
aparelho (celular e computador). É o que torna o app utilizável de verdade.

**Independent Test**: entrar com credenciais corretas e acessar uma área protegida; sair e confirmar que
a mesma área passa a ser recusada.

**Acceptance Scenarios**:

1. **Given** uma conta existente, **When** a pessoa informa e-mail e senha corretos, **Then** ela fica
   autenticada e vê quem é e quais workspaces possui.
2. **Given** e-mail inexistente ou senha errada, **When** a pessoa tenta entrar, **Then** a entrada é
   recusada com a mesma mensagem genérica nos dois casos, sem revelar se o e-mail existe.
3. **Given** uma pessoa autenticada, **When** ela fecha o app e volta dias depois, **Then** continua
   autenticada sem precisar entrar de novo, até sair pelo próprio perfil.
4. **Given** uma pessoa autenticada, **When** ela sai, **Then** a sessão deixa de valer imediatamente e
   qualquer área protegida volta a exigir entrada.
5. **Given** alguém sem sessão, **When** tenta acessar qualquer área protegida, **Then** o acesso é
   recusado por falta de autenticação.
6. **Given** uma pessoa autenticada em dois aparelhos, **When** ela escolhe sair de todos os aparelhos
   em um deles, **Then** a sessão do outro aparelho também deixa de valer na próxima requisição, e uma
   nova entrada volta a funcionar normalmente.
7. **Given** uma pessoa autenticada em dois aparelhos, **When** ela sai apenas em um deles, **Then** a
   sessão do outro aparelho continua válida.

---

### User Story 3 - Cada pessoa vê somente os dados dos seus workspaces (Priority: P1)

Uma pessoa autenticada só enxerga e altera dados de workspaces dos quais é membro. Qualquer tentativa de
acessar dados de outro workspace, por identificador adivinhado ou copiado, é recusada, e o sistema não
confirma que aquele workspace existe.

**Why this priority**: é a promessa central de privacidade do produto e a razão de existir a noção de
workspace. Todo módulo futuro (contas, categorias, lançamentos) depende dessa garantia, e corrigi-la
depois é caro e arriscado.

**Independent Test**: criar duas contas distintas, cada uma com seu workspace; autenticada como a
primeira, tentar ler e alterar dados do workspace da segunda e confirmar que tudo é recusado.

**Acceptance Scenarios**:

1. **Given** duas pessoas com workspaces diferentes, **When** a primeira usa o identificador do workspace
   da segunda para consultar ou alterar dados, **Then** o acesso é recusado e nenhum dado da segunda é
   revelado ou modificado.
2. **Given** um identificador de workspace que não existe, **When** uma pessoa autenticada o consulta,
   **Then** a resposta é indistinguível da resposta para um workspace de outra pessoa.
3. **Given** uma pessoa membro de um workspace, **When** ela acessa dados desse workspace, **Then** o
   acesso funciona normalmente.
4. **Given** uma falha de filtro em uma das duas camadas de proteção, **When** uma consulta tenta atingir
   dados de outro workspace, **Then** a outra camada ainda impede o acesso, de modo que o vazamento exige
   falha simultânea nas duas.
5. **Given** o cadastro de uma nova pessoa e o login, **When** o sistema precisa localizar um usuário pelo
   e-mail antes de saber quem ele é, **Then** isso acontece por um caminho restrito e explícito, sem
   enfraquecer a proteção geral dos demais dados.

---

### User Story 4 - Consultar quem sou e quais workspaces tenho (Priority: P2)

Uma pessoa autenticada consulta seus próprios dados (nome, e-mail) e a lista dos workspaces de que faz
parte, com o papel em cada um. O app usa isso ao abrir para saber em qual workspace trabalhar.

**Why this priority**: o front-end precisa dessa informação para funcionar, mas ela só tem utilidade
depois que cadastro e entrada existem.

**Independent Test**: autenticar e consultar os próprios dados; conferir nome, e-mail e a lista de
workspaces com papéis, sem nenhum dado sensível como a senha.

**Acceptance Scenarios**:

1. **Given** uma pessoa autenticada, **When** ela consulta seus dados, **Then** recebe nome, e-mail e a
   lista de seus workspaces com o papel em cada um.
2. **Given** qualquer consulta de dados do usuário, **When** a resposta é entregue, **Then** ela nunca
   contém a senha, seu hash ou outro campo interno.
3. **Given** alguém sem sessão válida, **When** consulta seus dados, **Then** a consulta é recusada por
   falta de autenticação.

---

### User Story 5 - Resistir a tentativas repetidas de adivinhar senha (Priority: P2)

Depois de várias tentativas de entrada com falha em sequência, o sistema passa a recusar novas tentativas
por um tempo, protegendo a conta contra adivinhação de senha. A pessoa dona da conta volta a conseguir
entrar depois do período de espera.

**Why this priority**: protege dados financeiros contra ataque por força bruta, mas o produto funciona
sem isso para uso próprio e local. Entra logo após o fluxo principal.

**Independent Test**: errar a senha repetidamente até o bloqueio, confirmar que até a senha correta é
recusada durante a espera e que a entrada volta a funcionar depois dela.

**Acceptance Scenarios**:

1. **Given** uma conta que acumulou 5 falhas de entrada consecutivas, **When** vem uma nova tentativa
   (mesmo com a senha correta), **Then** ela é recusada com a indicação de que há muitas tentativas e de
   quando tentar de novo.
2. **Given** o período de espera encerrado, **When** a pessoa tenta entrar com a senha correta, **Then**
   a entrada é aceita e o contador de falhas é zerado.
3. **Given** uma entrada bem-sucedida, **When** havia falhas anteriores abaixo do limite, **Then** o
   contador de falhas é zerado.
4. **Given** tentativas contra um e-mail que não existe, **When** o limite é atingido, **Then** o
   comportamento é o mesmo de um e-mail existente, para não revelar quais e-mails estão cadastrados.

---

### Edge Cases

- Cadastros simultâneos com o mesmo e-mail: apenas um conclui, o outro é recusado como e-mail já
  existente, sem erro interno e sem conta duplicada.
- E-mail com espaços nas pontas ou letras maiúsculas: tratado como o mesmo e-mail normalizado, tanto no
  cadastro quanto na entrada.
- Sessão expirada ou adulterada: tratada como ausência de sessão, nunca como erro do servidor.
- Entrada com sessão ainda válida: é aceita normalmente e substitui a sessão anterior.
- Corpo da requisição com campos extras (por exemplo, tentar informar papel ou identificador de
  workspace no cadastro): campos não permitidos são ignorados ou recusados, nunca aceitos.
- Texto muito longo em nome, e-mail ou senha: recusado com limite explícito, sem derrubar o sistema.
- Pessoa membro de nenhum workspace por falha de dados: o sistema trata como estado inválido e não
  concede acesso a nada.
- Sair sem estar autenticada: responde com sucesso sem efeito colateral.
- Aparelho perdido ou roubado: a pessoa entra em outro aparelho e escolhe sair de todos, e a sessão do
  aparelho perdido deixa de valer, mesmo sem ela ter acesso a ele.
- Sair de todos os aparelhos sem estar autenticada: recusado por falta de autenticação.
- Servidor reiniciado: o bloqueio por tentativas falhas não depende de memória de um único processo a
  ponto de ser trivialmente contornado reiniciando o serviço.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir criar uma conta informando nome, e-mail e senha.
- **FR-002**: O sistema MUST normalizar o e-mail (remover espaços das pontas e ignorar maiúsculas) e MUST
  garantir que não existam duas contas com o mesmo e-mail normalizado.
- **FR-003**: O sistema MUST validar nome, e-mail e senha antes de qualquer gravação, com limites de
  tamanho explícitos, e MUST recusar o cadastro indicando o campo inválido. A senha MUST ter ao menos 8
  caracteres.
- **FR-004**: O sistema MUST guardar a senha apenas em forma de hash forte e MUST NUNCA devolvê-la, nem o
  hash, em nenhuma resposta ou registro de log.
- **FR-005**: Ao concluir um cadastro, o sistema MUST criar, de forma atômica, a conta, um workspace
  pessoal e a associação da pessoa a esse workspace com o papel OWNER; se qualquer etapa falhar, nada
  MUST ser gravado.
- **FR-006**: O sistema MUST verificar se o e-mail já existe antes de tentar criar a conta, em vez de
  depender de tentar gravar e tratar a falha.
- **FR-007**: O sistema MUST permitir entrar com e-mail e senha e, em caso de sucesso, estabelecer uma
  sessão que o navegador envia automaticamente, sem que o código do front-end precise lidar com a
  credencial da sessão.
- **FR-008**: A credencial da sessão MUST NUNCA ser exposta em URL, em corpo de resposta ou em qualquer
  lugar acessível ao código da página.
- **FR-009**: Em falha de entrada, o sistema MUST responder com a mesma mensagem genérica quando o e-mail
  não existe e quando a senha está errada, e o tempo de resposta não deve diferenciar os casos de forma
  evidente.
- **FR-010**: A sessão MUST permanecer válida até a pessoa sair pelo perfil, sem exigir nova entrada por
  inatividade. Como limite de segurança, ela é renovada a cada requisição autenticada e só deixa de valer
  após 365 dias sem nenhum uso.
- **FR-011**: O sistema MUST permitir sair, invalidando a sessão no navegador imediatamente; sair sem
  estar autenticada MUST responder com sucesso.
- **FR-025**: O sistema MUST permitir sair de todos os aparelhos: uma ação que invalida de uma vez todas
  as sessões da pessoa, em qualquer aparelho, que passam a ser recusadas na próxima requisição. Sair
  comum (FR-011) encerra só a sessão do aparelho atual. Uma nova entrada depois de sair de todos os
  aparelhos MUST funcionar normalmente.
- **FR-012**: O sistema MUST contar falhas consecutivas de entrada por conta e, ao atingir 5 falhas,
  recusar novas tentativas por 15 minutos, inclusive com a senha correta, informando quando tentar de
  novo. O contador MUST ser zerado após uma entrada bem-sucedida ou ao fim da espera.
- **FR-013**: O limite de tentativas MUST se aplicar também a e-mails inexistentes, com o mesmo
  comportamento observável, e MUST sobreviver à reinicialização do serviço.
- **FR-014**: O sistema MUST oferecer uma consulta, para a pessoa autenticada, com seu nome, seu e-mail e
  a lista de workspaces de que faz parte, cada um com identificador, nome e papel.
- **FR-015**: Toda rota de dados MUST ficar sob o identificador do workspace e MUST conferir, antes de
  qualquer consulta, que a pessoa autenticada é membro desse workspace.
- **FR-016**: Quando a pessoa não é membro do workspace informado, ou ele não existe, o sistema MUST
  recusar o acesso com resposta indistinguível entre os dois casos (acesso negado ou não encontrado) e
  MUST NUNCA revelar a existência do workspace.
- **FR-017**: Todo dado do sistema MUST pertencer a exatamente um workspace, e todo acesso a dados MUST
  ser restrito aos workspaces de que a pessoa autenticada é membro.
- **FR-018**: O isolamento por workspace MUST existir em duas camadas independentes: uma na camada de
  dados, que impede a leitura e escrita de linhas de outro workspace mesmo que o código da aplicação
  erre, e outra na camada de serviço, que filtra explicitamente por workspace em toda consulta.
- **FR-019**: A aplicação MUST acessar o banco com uma identidade que não tenha poder de ignorar a
  proteção da camada de dados, e o workspace da requisição MUST ser definido de forma limitada à
  transação em curso, sem vazar para outra requisição que reaproveite a mesma conexão.
- **FR-020**: As únicas operações que precisam ver dados antes de o workspace ser conhecido (localizar um
  usuário pelo e-mail para entrar ou cadastrar, listar os workspaces da própria pessoa) MUST passar por
  caminhos estreitos e explícitos, sem desligar a proteção geral.
- **FR-021**: O sistema MUST permitir os papéis OWNER, EDITOR e VIEWER na associação entre pessoa e
  workspace. Nesta funcionalidade somente OWNER é atribuído, e nenhuma rota permite mudar papéis.
- **FR-022**: Todas as respostas MUST usar os formatos de saída próprios (nunca estruturas internas de
  armazenamento) e, em caso de erro, um formato padronizado que não vaze detalhes internos.
- **FR-023**: Respostas autenticadas MUST instruir navegadores e intermediários a não guardá-las em cache.
- **FR-024**: Existe um teste automatizado, para cada rota de dados, que tenta acessar dados de outro
  workspace e espera acesso negado ou não encontrado.

### Key Entities *(include if feature involves data)*

- **Usuário**: pessoa com conta. Tem nome, e-mail (único, sem diferenciar maiúsculas), senha guardada só
  como hash e data de criação. Pode pertencer a vários workspaces.
- **Workspace**: unidade de isolamento dos dados. Tem nome, moeda base (BRL) e data de criação. Todo dado
  do sistema pertence a um workspace.
- **Associação ao workspace**: liga um usuário a um workspace com um papel (OWNER, EDITOR ou VIEWER).
  Identifica de forma única o par usuário e workspace.
- **Registro de tentativas de entrada**: contagem de falhas consecutivas por e-mail normalizado e o
  instante até o qual novas tentativas estão bloqueadas. Existe também para e-mails sem conta.
- **Sessão**: prova temporária de que a pessoa está autenticada, guardada no navegador. Tem prazo de
  expiração e é renovada com o uso.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Uma pessoa nova cria a conta e chega ao estado "autenticada, com workspace pessoal" em menos
  de 1 minuto, em uma única tela de cadastro.
- **SC-002**: Em 100% dos testes de isolamento, tentativas de ler ou alterar dados de outro workspace são
  recusadas, para todas as rotas de dados existentes.
- **SC-003**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100%
  das tentativas de acesso a outro workspace continuam recusadas pela outra camada.
- **SC-004**: Nenhuma resposta do sistema, em nenhum cenário testado, contém senha, hash de senha ou a
  credencial da sessão no corpo.
- **SC-005**: Na sexta tentativa de entrada em sequência após 5 falhas, 100% das tentativas são recusadas
  durante os 15 minutos de espera, e a primeira tentativa correta depois desse prazo é aceita.
- **SC-006**: Para e-mail inexistente e senha errada, as mensagens de erro são idênticas e a diferença de
  tempo de resposta entre os dois casos é pequena demais para distinguir a olho nu (abaixo de 100 ms em
  média).
- **SC-007**: Cadastros simultâneos com o mesmo e-mail resultam em exatamente uma conta e em nenhum erro
  interno visível à pessoa.
- **SC-008**: Entrar e consultar os próprios dados leva menos de 2 segundos em uso normal.
- **SC-009**: Depois de sair de todos os aparelhos, 100% das requisições feitas com qualquer sessão
  anterior são recusadas, e as sessões criadas depois continuam válidas.

## Assumptions

- O cadastro é aberto: qualquer pessoa que alcance o app pode criar conta. Como hoje o uso é só do dono,
  restringir cadastros (convite ou lista permitida) fica para a funcionalidade de convites da fase 3.
- A senha exige ao menos 8 caracteres e ao menos uma letra e um número; regras além disso (listas de
  senhas vazadas, 2FA) estão fora do escopo.
- Recuperação de senha, confirmação de e-mail, login social e autenticação em dois fatores estão fora do
  escopo desta funcionalidade.
- Cada pessoa recebe exatamente um workspace pessoal no cadastro; criar workspaces adicionais, convidar
  outras pessoas e mudar papéis são funcionalidades da fase 3.
- A pessoa fica autenticada até sair pelo perfil (sem expiração por inatividade no uso normal); só uma
  ausência total de 365 dias encerra a sessão. Um mesmo usuário pode ter sessões em vários
  aparelhos ao mesmo tempo (celular e computador).
- O bloqueio por tentativas falhas é por conta (e-mail normalizado), com 5 falhas e espera de 15
  minutos; limitar por endereço de origem fica fora do escopo, por depender da infraestrutura de hospedagem.
- Esta funcionalidade ainda não cria contas, categorias nem lançamentos; as regras de isolamento são
  entregues e testadas aqui, e os módulos seguintes (002 a 005) as herdam.
- Depende da constituição do projeto (v1.0.0), em especial dos princípios I a III (segredos e autorização
  no servidor, isolamento em duas camadas, autenticação por cookie) e do esquema de dados da migration V1.
- Idioma da interface e das mensagens: português do Brasil; moeda base do workspace: BRL.
