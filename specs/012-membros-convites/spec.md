# Feature Specification: Membros e convites

**Feature Branch**: `012-membros-convites`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "Membros e convites: o dono de um workspace convida outras pessoas pelo e-mail, escolhendo o papel (editor ou leitor), e a pessoa convidada aceita o convite e passa a fazer parte do workspace. Hoje cada pessoa só tem o próprio workspace pessoal e os papéis EDITOR e VIEWER existem mas não há como atribuí-los. O convite gera um código secreto de uso único, mostrado uma só vez ao dono, que o repassa à pessoa por qualquer meio (o sistema não envia e-mail). O código vale por 7 dias, só funciona para a pessoa cujo e-mail foi convidado, e só pode ser usado uma vez. Qualquer membro vê quem faz parte do workspace e com que papel. O dono lista e revoga convites pendentes, muda o papel de um membro (entre editor e leitor) e remove um membro; qualquer membro que não é o dono pode sair do workspace. O dono nunca é removido nem sai (passar a titularidade fica fora desta funcionalidade). Mudar o papel ou remover vale na hora, para a próxima requisição da pessoa. Só o dono gerencia; membros e convites de um workspace nunca aparecem em outro; quem não é membro recebe 'não encontrado'. O aceite é a única operação que não fica sob um workspace, porque a pessoa ainda não é membro: /api/v1/invitations/accept. Rotas de gestão sob /api/v1/workspaces/{wid}/members e /api/v1/workspaces/{wid}/invitations. Segue o padrão das specs 001 a 004."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Convidar uma pessoa e ela entrar no workspace (Priority: P1)

A dona do workspace quer que o marido lance as despesas da casa. Ela informa o e-mail dele e o papel de editor; o
sistema gera um código. Ela o manda para ele pelo WhatsApp. Ele cria a conta (ou entra na que já tem), informa o
código e passa a ver e lançar no workspace dela.

**Why this priority**: é o motivo de existir da funcionalidade. Os papéis de editor e leitor já são aplicados em todo
o sistema (specs 001 a 011), mas hoje não há como colocar ninguém neles.

**Independent Test**: com uma dona e uma segunda pessoa cadastrada, a dona convida o e-mail dela como EDITOR, a
segunda pessoa aceita com o código e consegue criar um lançamento no workspace da dona.

**Acceptance Scenarios**:

1. **Given** o dono de um workspace, **When** ele convida um e-mail válido com o papel EDITOR ou VIEWER, **Then** o
   sistema cria o convite pendente, informa o e-mail, o papel, a validade (7 dias) e devolve o código secreto **uma
   só vez**; nenhuma outra resposta do sistema mostra o código de novo.
2. **Given** um convite pendente e uma pessoa autenticada cujo e-mail é o convidado, **When** ela informa o código
   para aceitar, **Then** passa a ser membro do workspace com o papel do convite, o convite fica usado e a resposta
   traz o workspace e o papel.
3. **Given** a pessoa que acabou de aceitar, **When** ela faz a primeira requisição no workspace, **Then** já tem o
   acesso do papel (um EDITOR escreve, um VIEWER só lê), sem precisar sair e entrar de novo.
4. **Given** o convite aceito, **When** a pessoa tenta usar o mesmo código de novo, **Then** é recusada.
5. **Given** um e-mail que ainda não tem conta, **When** o dono o convida, **Then** o convite é criado do mesmo jeito;
   a pessoa cria a conta depois (o cadastro é aberto, spec 001) e aceita com o código.
6. **Given** um código de convite, **When** uma pessoa autenticada com **outro** e-mail tenta aceitar, **Then** é
   recusada com a mesma resposta de código inválido, sem revelar para quem era o convite.
7. **Given** um código inexistente, expirado (mais de 7 dias), revogado ou já usado, **When** alguém tenta aceitar,
   **Then** recebe sempre a mesma resposta de "convite inválido ou expirado", que não distingue os casos.
8. **Given** um e-mail que já é membro do workspace, **When** o dono o convida, **Then** a operação é recusada como
   conflito e nada é criado.
9. **Given** um convite pendente para um e-mail, **When** o dono convida o mesmo e-mail de novo, **Then** o convite
   anterior deixa de valer, um novo é criado com um novo código e a nova validade (é assim que se "reenvia").
10. **Given** o papel OWNER, e-mail inválido (sem formato de e-mail, vazio ou com mais de 254 caracteres) ou papel
    inexistente, **When** o dono convida, **Then** a operação é recusada indicando o campo e nada é criado.
11. **Given** um workspace com 20 membros ou 20 convites pendentes, **When** o dono tenta convidar mais, **Then** a
    operação é recusada como conflito (limite de abuso) e nada é criado; aceitar um convite num workspace já com 20
    membros também é recusado.
12. **Given** a pessoa que aceitou, **When** ela consulta os seus dados (`/api/v1/me`, spec 001), **Then** o workspace
    do dono aparece junto com o pessoal, cada um com o papel dela.

---

### User Story 2 - Ver quem faz parte do workspace (Priority: P1)

Qualquer pessoa do workspace vê a lista de membros: nome, e-mail, papel e desde quando participa.

**Why this priority**: sem enxergar quem está no workspace, o dono não sabe a quem mudar o papel ou remover, e os
membros não sabem com quem compartilham os dados.

**Independent Test**: com um dono, um editor e um leitor, cada um lista os membros e vê os três, com os papéis certos.

**Acceptance Scenarios**:

1. **Given** um workspace com mais de um membro, **When** qualquer membro (OWNER, EDITOR ou VIEWER) lista os membros,
   **Then** vê todos, com o identificador, o nome, o e-mail, o papel e a data de entrada, o dono primeiro.
2. **Given** uma lista de membros, **When** ela é consultada, **Then** nunca traz senha, hash de senha, versão de
   sessão nem qualquer dado de outro workspace dos membros.
3. **Given** uma pessoa que não é membro do workspace, **When** ela tenta listar, **Then** recebe "não encontrado",
   igual à resposta de um workspace inexistente.

---

### User Story 3 - Gerenciar membros e convites (Priority: P2)

O dono muda o editor para leitor, remove alguém que não participa mais e revoga um convite que mandou para o e-mail
errado. Um membro que não é o dono pode sair do workspace por conta própria.

**Why this priority**: sem isso, o convite seria sem volta: um papel dado errado ou uma pessoa que saiu da família
continuaria com acesso para sempre.

**Independent Test**: o dono muda um editor para leitor e confere que ele não escreve mais na requisição seguinte; remove
o leitor e confere que ele não enxerga mais o workspace.

**Acceptance Scenarios**:

1. **Given** um membro EDITOR ou VIEWER, **When** o dono muda o papel dele para o outro, **Then** a mudança vale na
   próxima requisição da pessoa (um EDITOR rebaixado a VIEWER deixa de conseguir escrever; um VIEWER promovido passa a
   escrever).
2. **Given** o dono, **When** ele tenta mudar o próprio papel ou o de outro OWNER, ou dar o papel OWNER a alguém,
   **Then** a operação é recusada e nada muda.
3. **Given** um membro que não é o dono, **When** o dono o remove, **Then** ele deixa de ser membro, o workspace some da
   lista dele e qualquer requisição dele ao workspace recebe "não encontrado", na hora.
4. **Given** o dono, **When** ele tenta remover a si mesmo ou sair, **Then** a operação é recusada e nada muda.
5. **Given** um membro EDITOR ou VIEWER, **When** ele sai do workspace, **Then** deixa de ser membro, na hora.
6. **Given** uma pessoa removida ou que saiu, **When** o dono a convida de novo, **Then** funciona como um convite novo.
7. **Given** o dono, **When** ele lista os convites, **Then** vê os pendentes (e-mail, papel, quem convidou, quando
   foi criado e quando expira), sem o código.
8. **Given** um convite pendente, **When** o dono o revoga, **Then** o código deixa de funcionar e o convite sai da
   lista de pendentes; revogar de novo, ou um convite já usado, responde "não encontrado".
9. **Given** um convite expirado, **When** o dono lista os convites, **Then** ele não aparece como pendente.
10. **Given** um identificador de membro ou de convite de outro workspace, inexistente ou malformado, **When** o dono
    tenta alterá-lo, **Then** recebe "não encontrado" e nada muda.
11. **Given** a remoção ou a saída de um membro, **When** ela acontece, **Then** os lançamentos, contas e categorias que
    ele criou continuam no workspace, com o "criado por" intacto (os dados são do workspace).

---

### User Story 4 - Só o dono gerencia, e tudo fica no seu workspace (Priority: P1)

Somente o OWNER convida, lista e revoga convites, muda papéis e remove membros. EDITOR e VIEWER consultam os membros e
podem sair. Membros e convites de um workspace nunca aparecem em outro.

**Why this priority**: a constituição exige o teste de acesso cruzado em toda rota de dados e a separação de papéis, e
convidar é a operação mais sensível do sistema: dá acesso a dados financeiros.

**Independent Test**: com três workspaces, um editor e um leitor, tentar cada operação com cada papel e conferir as
respostas; tentar aceitar um convite em nome de outra pessoa.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta listar membros, convidar, listar ou revogar
   convites, mudar papel ou remover, **Then** recebe "não encontrado", igual à resposta de um workspace inexistente, e
   nada é revelado nem alterado.
2. **Given** uma pessoa EDITOR ou VIEWER, **When** ela tenta convidar, listar ou revogar convites, mudar papel ou
   remover outra pessoa, **Then** a ação é recusada por falta de permissão e nada muda; listar os membros e sair
   continuam permitidos.
3. **Given** alguém sem sessão, **When** tenta qualquer operação, **Then** é recusado por falta de autenticação.
4. **Given** o código de um convite do workspace A, **When** ele é usado para entrar no workspace B, **Then** é
   impossível: o código só dá o acesso do convite que o gerou.
5. **Given** o código secreto, **When** o sistema o guarda, **Then** guarda só uma impressão irreversível dele (nunca o
   código), e nenhum log, resposta de erro ou listagem o contém.
6. **Given** várias tentativas de aceitar com códigos errados, **When** elas acontecem, **Then** continuam todas
   recusadas com a mesma resposta (o código tem entropia suficiente para não ser adivinhado) e uma tentativa de aceitar
   nunca revela se um e-mail foi ou não convidado.
7. **Given** duas pessoas tentando aceitar o mesmo código ao mesmo tempo, **When** isso acontece, **Then** só uma
   consegue e a outra é recusada.

---

### Edge Cases

- E-mail convidado em maiúsculas, com espaços nas pontas ou com variações de caixa: é normalizado como no cadastro
  (spec 001); "Ana@X.com" e "ana@x.com" são o mesmo e-mail.
- A pessoa aceita o convite com a conta cujo e-mail é o convidado, mas muda o e-mail depois: não existe mudança de
  e-mail hoje (spec 001), então o e-mail da conta no momento do aceite é o que vale.
- Convite aceito por quem já é membro (por exemplo, convidada duas vezes antes de aceitar a primeira): recusado como
  conflito; o convite continua pendente até expirar ou ser revogado.
- A pessoa removida ainda tem a sessão aberta: a sessão segue valendo para o resto do sistema (o workspace pessoal
  dela), mas o workspace de onde saiu passa a responder "não encontrado".
- Dono que convida um e-mail que pertence a um workspace pessoal de outra pessoa: nada de especial; o aceite acrescenta
  o workspace, sem tirar o pessoal.
- O workspace pessoal de uma pessoa nunca tem o papel dela mudado nem pode ser deixado por ela: ela é a dona.
- Convite expirado há muito tempo: some da lista de pendentes e pode ser limpo pelo sistema; o código continua inválido.
- Campos fora da lista permitida (workspace, quem convidou, estado, datas) são recusados.
- O código só aparece na resposta de criação do convite; se o dono o perder, revoga ou reconvida (o reconvite gera outro).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir ao OWNER convidar uma pessoa por e-mail, com o papel EDITOR ou VIEWER, e MUST
  devolver, só na resposta dessa criação, um código secreto de uso único. O papel OWNER MUST ser recusado.
- **FR-002**: O código MUST ter entropia de pelo menos 128 bits, MUST valer por 7 dias a partir da criação, MUST
  funcionar uma só vez e MUST estar ligado a um só e-mail e a um só workspace.
- **FR-003**: O sistema MUST guardar só uma impressão irreversível do código (nunca o código) e MUST NUNCA incluir o
  código em listagens, em consultas posteriores, em mensagens de erro nem em registros do sistema.
- **FR-004**: O e-mail do convite MUST ser normalizado como no cadastro (spec 001) e MUST ter formato de e-mail e no
  máximo 254 caracteres; fora disso MUST ser recusado indicando o campo.
- **FR-005**: Convidar um e-mail que já é membro MUST ser recusado como conflito. Convidar um e-mail com convite
  pendente MUST invalidar o convite anterior e criar um novo (um pendente por e-mail e workspace).
- **FR-006**: Cada workspace MUST ter no máximo 20 membros e 20 convites pendentes; ultrapassar MUST ser recusado como
  conflito, tanto ao convidar quanto ao aceitar.
- **FR-007**: O sistema MUST permitir a uma pessoa autenticada aceitar um convite informando o código. O aceite MUST
  funcionar só se o código existe, não expirou, não foi usado nem revogado **e** o e-mail da conta da pessoa (lido do
  sistema, nunca do corpo) é o do convite. Qualquer falha dessas MUST responder igual, sem distinguir o motivo.
- **FR-008**: O aceite MUST ser atômico e MUST valer só uma vez, mesmo com aceites simultâneos: cria o vínculo com o
  papel do convite e marca o convite como usado numa operação só. A resposta MUST trazer o workspace e o papel.
- **FR-009**: Aceitar sendo já membro do workspace MUST ser recusado como conflito, sem consumir o convite.
- **FR-010**: Qualquer membro (OWNER, EDITOR ou VIEWER) MUST poder listar os membros do workspace: identificador, nome,
  e-mail, papel e data de entrada, o dono primeiro. A lista MUST NUNCA trazer senha, hash, versão de sessão nem dado
  de outro workspace.
- **FR-011**: O OWNER MUST poder listar os convites pendentes (e-mail, papel, quem convidou, criação e expiração, sem
  o código) e revogar um convite pendente. Convite expirado, usado ou revogado MUST NOT aparecer como pendente.
- **FR-012**: O OWNER MUST poder mudar o papel de um membro EDITOR ou VIEWER para o outro. Mudar o papel do OWNER, o
  próprio papel ou dar o papel OWNER MUST ser recusado.
- **FR-013**: O OWNER MUST poder remover um membro que não é OWNER. Um membro EDITOR ou VIEWER MUST poder sair do
  workspace. O OWNER MUST NÃO poder ser removido nem sair.
- **FR-014**: Mudar o papel, remover ou sair MUST valer na próxima requisição da pessoa afetada, sem exigir nova
  sessão. Os dados que a pessoa criou MUST continuar no workspace com o "criado por" intacto.
- **FR-015**: Somente o OWNER MUST poder convidar, listar e revogar convites, mudar papéis e remover membros. EDITOR e
  VIEWER MUST poder apenas listar os membros e sair. A recusa por papel MUST usar a resposta de "sem permissão",
  distinta de "não encontrado".
- **FR-016**: Toda operação sob um workspace MUST conferir que a pessoa autenticada é membro dele. Para quem não é
  membro, workspace inexistente, membro ou convite de outro workspace ou identificador malformado, a resposta MUST ser
  "não encontrado", sem revelar nada. A operação de aceite MUST exigir sessão e MUST NÃO depender de workspace.
- **FR-017**: O isolamento por workspace MUST existir nas duas camadas independentes exigidas pela constituição (camada
  de dados e camada de serviço) e MUST ter teste automatizado de acesso cruzado para cada operação nova. O aceite, que
  roda antes de a pessoa ser membro, MUST fazer só o que a regra descreve e MUST NÃO dar acesso a nada além do vínculo
  do convite.
- **FR-018**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da lista permitida
  e indicando o campo inválido nas recusas.
- **FR-019**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do projeto, sem
  vazar detalhes internos; as respostas autenticadas MUST instruir navegadores e intermediários a não guardá-las em
  cache (e a resposta que traz o código, em particular).
- **FR-020**: O sistema MUST registrar quem criou cada convite (a pessoa autenticada) e quando foi usado, e esses campos
  MUST NUNCA ser informados pelo cliente.
- **FR-021**: O sistema MUST NÃO enviar e-mail nem mensagem alguma: o dono repassa o código por outro meio.
- **FR-022**: Os workspaces da pessoa (a consulta `/api/v1/me` da spec 001) MUST passar a incluir os de que ela aceitou o
  convite, cada um com o seu papel, e MUST deixar de incluir os de que ela foi removida ou saiu. Nenhuma rota nova é
  necessária para isso.
- **FR-023**: A funcionalidade MUST NÃO mudar o resultado de nenhuma consulta nem operação existente: o isolamento e os
  papéis das specs 001 a 011 continuam exatamente como eram, e os testes anteriores continuam valendo.

### Key Entities *(include if feature involves data)*

- **Membro**: o vínculo de uma pessoa com um workspace, com o papel (OWNER, EDITOR ou VIEWER). Já existe (spec 001);
  esta funcionalidade acrescenta como ele nasce (por convite aceito), muda de papel e termina.
- **Convite**: o pedido do dono para uma pessoa entrar no workspace. Tem o e-mail convidado, o papel, quem convidou, a
  criação, a expiração e o estado (pendente, usado ou revogado). O código secreto não é guardado, só a impressão dele.
- **Código do convite**: o segredo de uso único que o dono recebe uma vez e repassa à pessoa.
- **Pessoa**: o usuário (spec 001), identificada pelo e-mail normalizado.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes, um convite aceito cria exatamente um vínculo com o papel do convite, e a pessoa
  consegue (EDITOR) ou não consegue (VIEWER) escrever no workspace já na requisição seguinte.
- **SC-002**: Em 100% dos testes, o código só funciona uma vez, só para o e-mail convidado, só no workspace do convite e
  só dentro de 7 dias; todo código inexistente, expirado, usado, revogado ou de outro e-mail recebe a mesma resposta.
- **SC-003**: Em 100% dos testes, o código nunca aparece em listagem, consulta posterior, mensagem de erro nem registro
  do sistema, e o banco guarda só a impressão dele.
- **SC-004**: Com 20 aceites simultâneos do mesmo código, exatamente um tem sucesso, e exatamente um vínculo é criado.
- **SC-005**: Em 100% dos testes de papéis, mudar o papel, remover e sair valem na requisição seguinte, o OWNER nunca é
  removido nem muda de papel, e os dados criados por quem saiu continuam no workspace.
- **SC-006**: Em 100% dos testes de isolamento, tentar listar, convidar, revogar, mudar papel ou remover num workspace do
  qual a pessoa não é membro recebe a mesma resposta de um workspace inexistente, e nada é revelado ou alterado.
- **SC-007**: Em 100% dos testes de permissão, só o OWNER gerencia; EDITOR e VIEWER listam os membros e saem, e nenhuma
  operação de gestão tem efeito por eles.
- **SC-008**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das tentativas de
  gerir membros e convites de outro workspace continuam recusadas pela outra camada.
- **SC-009**: Em 100% dos testes de validação, e-mail, papel, limites e campos extras inválidos são recusados indicando o
  campo, e nada é gravado.
- **SC-010**: Todos os testes das specs 001 a 011 continuam passando sem mudar o resultado esperado (FR-023).
- **SC-011**: Convidar, aceitar e listar os membros respondem em menos de 1 segundo cada.

## Assumptions

- Esta funcionalidade é só de backend; as telas fazem parte do roadmap de front-end. Fecha o "convidar outras pessoas e
  mudar papéis" que a spec 001 deixou para a fase 3.
- O sistema não envia e-mail. O dono recebe o código na resposta e o repassa por qualquer meio (mensagem, voz). O e-mail
  do convite serve para amarrar o código a uma pessoa, não para entregá-lo.
- O cadastro continua aberto (spec 001): a pessoa convidada cria a conta normalmente. Restringir cadastros só a quem
  tem convite não faz parte desta funcionalidade.
- Cada workspace tem um só OWNER (a pessoa que o criou); passar a titularidade, ter vários donos e excluir um workspace
  ficam fora desta funcionalidade. Criar workspaces adicionais também.
- O código tem 7 dias de validade e o limite é de 20 membros e 20 convites pendentes por workspace, valores de partida
  para limitar abuso; podem ser ajustados sem mudar o desenho.
- Todo membro enxerga o nome e o e-mail dos outros membros do mesmo workspace; ninguém enxerga dados de pessoas de
  outros workspaces.
- A autorização é lida do banco a cada requisição (specs 001 e 003); por isso mudar o papel, remover e sair valem na
  hora, sem invalidar sessões.
- Moeda única (BRL).
- Depende da `001-autenticacao-workspace` (sessão, papéis, isolamento, cadastro e e-mail normalizado) e das regras da
  constituição (v1.0.0), em especial os princípios I a V e VIII.
