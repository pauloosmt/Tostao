# Feature Specification: Lançamentos

**Feature Branch**: `004-lancamentos`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Lançamentos financeiros dentro de um workspace: receita, despesa e transferência entre contas. Cada lançamento tem conta, valor em centavos inteiros (receita positiva, despesa negativa, guardado com sinal), data de ocorrência, descrição e, em receita e despesa, uma categoria do mesmo tipo; transferência não tem categoria e é gravada como duas linhas ligadas (saída na conta de origem e entrada na de destino, mesmo valor, contas diferentes). Criar, consultar, editar e excluir (exclusão lógica: o lançamento some das listas e do saldo mas permanece guardado), listar paginado com filtros por período, conta, categoria, tipo e texto na descrição, do mais recente para o mais antigo. Conta arquivada e categoria arquivada não recebem novos lançamentos nem passam a ser usadas numa edição, mas os lançamentos antigos delas continuam valendo. O saldo das contas passa a refletir os lançamentos na hora. Registra quem criou. OWNER e EDITOR escrevem, VIEWER só lê; isolamento por workspace nas duas camadas. Segue o padrão das specs 002-contas e 003-categorias."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Registrar uma receita ou uma despesa (Priority: P1)

A pessoa registra o que entrou e o que saiu do dinheiro dela: escolhe a conta, informa o tipo (receita ou
despesa), o valor, a data, uma descrição e a categoria. O sistema guarda a receita como valor positivo e a
despesa como valor negativo, e o saldo da conta já reflete o lançamento na consulta seguinte.

**Why this priority**: é a razão de existir do produto. Sem lançamentos as contas ficam paradas no saldo
inicial e o dashboard (spec 005) não tem o que mostrar.

**Independent Test**: com uma conta de saldo inicial R$ 1.000,00, registrar uma despesa de R$ 100,00 e uma
receita de R$ 250,00 e conferir que o saldo da conta passa a R$ 1.150,00.

**Acceptance Scenarios**:

1. **Given** uma conta ativa e uma categoria de despesa ativa, **When** a pessoa registra uma despesa de
   R$ 100,00 nessa conta e nessa categoria, **Then** o lançamento é criado com valor negativo, aparece na
   consulta e o saldo da conta cai R$ 100,00.
2. **Given** uma conta ativa e uma categoria de receita ativa, **When** a pessoa registra uma receita de
   R$ 250,00, **Then** o lançamento é criado com valor positivo e o saldo da conta sobe R$ 250,00.
3. **Given** o cadastro de uma receita ou de uma despesa, **When** a pessoa informa o valor, **Then** informa
   sempre um valor positivo em centavos; o sinal vem do tipo. Valor zero, negativo, com casas decimais além
   dos centavos ou acima do limite é recusado.
4. **Given** uma despesa, **When** a pessoa escolhe uma categoria de receita (ou uma receita com categoria
   de despesa), **Then** o cadastro é recusado indicando o campo da categoria.
5. **Given** uma receita ou despesa sem categoria, **When** a pessoa tenta cadastrar, **Then** o cadastro é
   recusado: toda receita e despesa tem categoria.
6. **Given** descrição vazia ou longa demais, data inválida, tipo desconhecido ou conta inexistente,
   **When** a pessoa tenta cadastrar, **Then** o cadastro é recusado indicando o campo inválido e nada é
   criado.
7. **Given** uma conta arquivada ou uma categoria arquivada, **When** a pessoa tenta registrar um lançamento
   com elas, **Then** o cadastro é recusado e nada é criado.
8. **Given** uma conta ou uma categoria de outro workspace, **When** a pessoa tenta usá-la num lançamento do
   seu workspace, **Then** recebe "não encontrado" e nada é criado.
9. **Given** um lançamento criado, **When** a pessoa o consulta, **Then** vê quem o criou, a data de criação
   e a de última alteração.

---

### User Story 2 - Transferir dinheiro entre contas (Priority: P1)

A pessoa move dinheiro de uma conta para outra (por exemplo, da conta corrente para a poupança, ou para pagar
um cartão). Informa a conta de origem, a de destino, o valor, a data e a descrição. O sistema grava a saída
na origem e a entrada no destino de uma só vez, ligadas entre si, sem categoria. O saldo total do workspace
não muda.

**Why this priority**: sem transferência a pessoa teria de lançar uma despesa e uma receita falsas, o que
distorce os relatórios. É parte do MVP definido no roadmap.

**Independent Test**: transferir R$ 300,00 da conta A para a conta B e conferir que A cai R$ 300,00, B sobe
R$ 300,00 e o saldo total fica igual.

**Acceptance Scenarios**:

1. **Given** duas contas ativas e diferentes do workspace, **When** a pessoa transfere R$ 300,00 da primeira
   para a segunda, **Then** são criados dois lançamentos ligados: uma saída de R$ 300,00 na origem e uma
   entrada de R$ 300,00 no destino, e os saldos das duas contas mudam na hora.
2. **Given** uma transferência feita, **When** a pessoa consulta o saldo total, **Then** ele é o mesmo de
   antes da transferência.
3. **Given** a conta de origem igual à de destino, **When** a pessoa tenta transferir, **Then** a operação é
   recusada indicando o campo.
4. **Given** uma transferência com categoria informada, **When** a pessoa tenta cadastrar, **Then** é
   recusada: transferência não tem categoria.
5. **Given** uma conta de origem ou de destino arquivada, ou de outro workspace, **When** a pessoa tenta
   transferir, **Then** a operação é recusada (arquivada) ou responde "não encontrado" (outro workspace), e
   nenhuma das duas pontas é criada.
6. **Given** uma falha em qualquer ponto da gravação, **When** a transferência não termina, **Then** nenhuma
   das duas pontas fica gravada: a transferência existe inteira ou não existe.
7. **Given** uma transferência para ou de um cartão de crédito, **When** a pessoa a registra, **Then** é
   aceita como qualquer outra (é o jeito de pagar a fatura).
8. **Given** valor zero, negativo, decimal ou acima do limite, **When** a pessoa tenta transferir, **Then** a
   operação é recusada indicando o campo.

---

### User Story 3 - Consultar e filtrar o extrato (Priority: P1)

A pessoa vê seus lançamentos, do mais recente para o mais antigo, em páginas. Pode filtrar por período,
conta, categoria, tipo e por um trecho do texto da descrição, e combinar os filtros. Pode abrir um
lançamento pelo identificador.

**Why this priority**: sem consultar não há como conferir o que foi lançado nem corrigir erros. É o uso
diário do produto.

**Independent Test**: com lançamentos de várias contas, categorias, tipos e datas, aplicar cada filtro e
conferir que só voltam os lançamentos esperados, na ordem certa, e que paginar cobre todos sem repetir nem
perder nenhum.

**Acceptance Scenarios**:

1. **Given** vários lançamentos, **When** a pessoa consulta sem filtros, **Then** recebe a primeira página
   ordenada da data mais recente para a mais antiga (empate de data: o criado por último primeiro), com o
   total de lançamentos e de páginas.
2. **Given** mais lançamentos do que cabem numa página, **When** a pessoa percorre todas as páginas,
   **Then** cada lançamento aparece exatamente uma vez.
3. **Given** um período (data inicial e/ou final, ambas incluídas), **When** a pessoa filtra, **Then** só
   voltam lançamentos dentro dele. Data inicial maior que a final é recusada.
4. **Given** uma conta, **When** a pessoa filtra por ela, **Then** voltam os lançamentos dessa conta,
   incluindo a saída ou a entrada de uma transferência que a tocou.
5. **Given** uma categoria principal, **When** a pessoa filtra por ela, **Then** voltam os lançamentos dela e
   os de suas subcategorias; filtrando por uma subcategoria, voltam só os dela.
6. **Given** um tipo (receita, despesa ou transferência), **When** a pessoa filtra, **Then** voltam só
   lançamentos desse tipo.
7. **Given** um trecho de texto, **When** a pessoa filtra, **Then** voltam os lançamentos cuja descrição
   contém o trecho, sem diferenciar maiúsculas e minúsculas. Os caracteres `%` e `_` no trecho valem
   literalmente, nunca como curinga.
8. **Given** filtros combinados, **When** a pessoa consulta, **Then** voltam só os lançamentos que atendem a
   todos ao mesmo tempo.
9. **Given** um lançamento excluído, **When** a pessoa lista ou consulta, **Then** ele não aparece em
   nenhuma lista e a consulta direta dele responde "não encontrado".
10. **Given** tamanho de página zero, negativo, acima do máximo, página negativa ou filtro com valor inválido
    (tipo desconhecido, identificador malformado, data inválida), **When** a pessoa consulta, **Then** a
    consulta é recusada indicando o campo.
11. **Given** um workspace sem lançamentos, **When** a pessoa consulta, **Then** recebe uma página vazia com
    total zero.

---

### User Story 4 - Só quem pode altera, e só no seu workspace (Priority: P1)

Somente OWNER e EDITOR criam, editam e excluem lançamentos. VIEWER apenas consulta. Nenhuma pessoa vê ou
altera lançamentos de um workspace do qual não é membro: a tentativa recebe a mesma resposta de um workspace
que não existe.

**Why this priority**: é a garantia de privacidade e de permissão do produto, e a constituição exige teste
de acesso cruzado em toda rota de dados. Lançamentos são o dado mais sensível do sistema.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER no workspace da
primeira, tentar ler, criar, transferir, editar e excluir lançamentos e conferir as respostas.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta listar, consultar, criar,
   transferir, editar ou excluir lançamentos, **Then** recebe "não encontrado", igual à resposta de um
   workspace inexistente, e nada é revelado nem alterado.
2. **Given** uma pessoa com o papel VIEWER, **When** ela lista e consulta lançamentos, **Then** vê tudo
   normalmente.
3. **Given** uma pessoa com o papel VIEWER, **When** ela tenta criar, transferir, editar ou excluir,
   **Then** a ação é recusada por falta de permissão e nada muda.
4. **Given** uma pessoa com o papel EDITOR, **When** ela cria, edita e exclui lançamentos, **Then** as ações
   funcionam.
5. **Given** o identificador de um lançamento de outro workspace usado numa rota do meu workspace,
   **When** a pessoa tenta consultá-lo, editá-lo ou excluí-lo, **Then** recebe "não encontrado" e o
   lançamento não muda.
6. **Given** alguém sem sessão, **When** tenta qualquer operação de lançamentos, **Then** é recusado por falta
   de autenticação.
7. **Given** um lançamento criado por outra pessoa do mesmo workspace, **When** um EDITOR o edita ou exclui,
   **Then** funciona: o workspace é compartilhado, e o "criado por" nunca muda.

---

### User Story 5 - Corrigir um lançamento (Priority: P2)

A pessoa corrige um lançamento: conta, valor, data, descrição, observação e categoria. O tipo não muda
depois de criado. Numa transferência, a correção vale para as duas pontas ao mesmo tempo.

**Why this priority**: erros de digitação acontecem e precisam de conserto, mas o registro e a consulta já
entregam valor sem isso.

**Independent Test**: criar uma despesa, corrigir o valor e a categoria e conferir que a consulta e o saldo
da conta refletem a mudança.

**Acceptance Scenarios**:

1. **Given** um lançamento de receita ou despesa, **When** a pessoa altera o valor, **Then** o saldo da conta
   passa a refletir o novo valor no lugar do antigo.
2. **Given** um lançamento, **When** a pessoa altera a conta, **Then** o saldo da conta antiga e o da nova
   mudam na hora.
3. **Given** um lançamento, **When** a pessoa altera data, descrição, observação ou categoria, **Then** a
   consulta mostra os novos valores e a data de última alteração é atualizada.
4. **Given** um lançamento, **When** a pessoa tenta mudar o tipo, **Then** a alteração é recusada indicando
   que o tipo não pode ser mudado (para trocar, exclui e cria outro).
5. **Given** uma transferência, **When** a pessoa edita qualquer uma das duas pontas (valor, data,
   descrição, observação, conta de origem ou de destino), **Then** as duas pontas mudam juntas e continuam
   coerentes: mesmo valor, mesma data, contas diferentes.
6. **Given** uma edição que aponta para uma conta ou categoria arquivada, **When** a pessoa salva, **Then** a
   edição é recusada. Se a conta ou a categoria já era a do lançamento e continua a mesma, a edição dos
   demais campos é aceita, porque os lançamentos antigos de contas e categorias arquivadas continuam valendo.
7. **Given** as mesmas validações do cadastro (valor, descrição, data, categoria do tipo certo, contas
   diferentes na transferência), **When** a pessoa salva uma edição inválida, **Then** a edição é recusada
   indicando o campo e o lançamento não muda.
8. **Given** um lançamento excluído, **When** a pessoa tenta editá-lo, **Then** recebe "não encontrado".

---

### User Story 6 - Excluir um lançamento (Priority: P2)

A pessoa exclui um lançamento lançado por engano. Ele deixa de aparecer nas listas e de contar no saldo, mas
continua guardado. Numa transferência, as duas pontas são excluídas juntas.

**Why this priority**: lançar errado é comum, mas o produto já é útil antes disso.

**Independent Test**: criar uma despesa, excluí-la e conferir que some da lista e que o saldo da conta
volta ao valor de antes.

**Acceptance Scenarios**:

1. **Given** uma receita ou despesa, **When** a pessoa a exclui, **Then** ela some das listas e da consulta
   direta, o saldo da conta deixa de contá-la, e o registro permanece guardado com a data da exclusão.
2. **Given** uma transferência, **When** a pessoa exclui qualquer uma das pontas, **Then** as duas pontas são
   excluídas juntas e os saldos das duas contas voltam ao valor de antes.
3. **Given** um lançamento já excluído, **When** a pessoa tenta excluí-lo de novo, **Then** recebe "não
   encontrado".
4. **Given** qualquer lançamento, **When** a pessoa procura uma forma de apagá-lo de verdade, **Then** não
   existe: o sistema só faz exclusão lógica.
5. **Given** um lançamento numa conta ou categoria arquivada, **When** a pessoa o exclui, **Then** a exclusão
   funciona (arquivar não trava o conserto do histórico).

---

### Edge Cases

- Dois lançamentos idênticos (mesma conta, valor, data e descrição): permitidos; a pessoa pode de fato ter
  feito duas compras iguais.
- Data no futuro: permitida (lançamento antecipado). Datas só são aceitas com o ano entre 2000 e 2100, para
  barrar erro de digitação.
- Valor acima do limite aceito (R$ 10 bilhões em módulo) ou com casas decimais além dos centavos: recusado; o
  sistema só aceita centavos inteiros, sem ponto flutuante.
- Descrição aparada nas pontas, de 1 a 200 caracteres; observação opcional de até 1.000 caracteres.
- Conta ou categoria arquivada no meio do cadastro (corrida): nunca nasce lançamento novo numa conta ou
  categoria arquivada; a tentativa é recusada.
- Duas pessoas editando o mesmo lançamento ao mesmo tempo: vale a última gravação, e uma transferência nunca
  fica com as duas pontas incoerentes entre si.
- Excluir uma transferência enquanto outra pessoa a edita: o resultado é sempre ou "excluída" ou "editada e
  depois excluída"; nunca uma ponta excluída e a outra viva.
- Categoria principal com subcategorias pode ser usada diretamente num lançamento; subcategoria também.
- Corpo da requisição com campos extras, como informar o workspace, quem criou, o sinal do valor, o grupo da
  transferência, a data de exclusão ou as datas de criação e de alteração: campos não permitidos são
  recusados, nunca aceitos.
- Identificador de lançamento malformado ou inexistente: resposta de "não encontrado", sem erro interno.
- Consulta com milhares de lançamentos: continua exata e rápida (ver critérios de sucesso).
- Trecho de texto de busca muito longo: recusado acima de 100 caracteres.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir registrar um lançamento de receita ou de despesa informando conta,
  tipo, valor, data, descrição e categoria; e MUST permitir registrar uma transferência informando conta de
  origem, conta de destino, valor, data e descrição. Observação é opcional nos três.
- **FR-002**: O valor informado MUST ser um inteiro positivo em centavos, de no máximo 1.000.000.000.000
  (R$ 10 bilhões), nunca com casas decimais nem em ponto flutuante. O sistema MUST guardar a receita como
  valor positivo e a despesa como valor negativo. Nas consultas, o valor MUST sair com sinal.
- **FR-003**: A descrição MUST ter de 1 a 200 caracteres após remover espaços das pontas. A observação, se
  existir, MUST ter até 1.000 caracteres. A data MUST ser uma data válida com ano entre 2000 e 2100; datas
  futuras são aceitas.
- **FR-004**: Toda receita e toda despesa MUST ter uma categoria ativa do mesmo workspace e do mesmo tipo do
  lançamento (receita com categoria de receita, despesa com categoria de despesa). Categoria principal e
  subcategoria são aceitas. Transferência MUST NOT ter categoria.
- **FR-005**: A conta do lançamento MUST estar ativa e pertencer ao mesmo workspace. Lançamento com conta ou
  categoria arquivada MUST ser recusado. Conta ou categoria de outro workspace, ou inexistente, MUST
  responder "não encontrado".
- **FR-006**: Uma transferência MUST ser gravada como dois lançamentos ligados entre si: uma saída (valor
  negativo) na conta de origem e uma entrada (valor positivo) na conta de destino, com o mesmo valor em
  módulo, a mesma data, a mesma descrição e a mesma observação. As contas MUST ser diferentes e ambas ativas.
  A criação MUST ser atômica: as duas pontas existem ou nenhuma existe.
- **FR-007**: O tipo de um lançamento MUST NUNCA mudar depois de criado. Uma transferência MUST NUNCA ser
  editada, excluída ou ficar com uma só ponta: qualquer operação sobre uma ponta vale para as duas, de forma
  atômica, e as duas pontas ficam sempre coerentes (mesmo valor em módulo, mesma data, mesma descrição, mesma
  observação, contas diferentes).
- **FR-008**: O sistema MUST permitir consultar um lançamento pelo identificador, dentro do seu workspace.
  Cada lançamento MUST informar seu identificador, conta, tipo, valor com sinal, data, descrição,
  observação, categoria (nula na transferência), quem o criou, datas de criação e de última alteração e,
  na transferência, o identificador do grupo e a conta da outra ponta.
- **FR-009**: O sistema MUST listar os lançamentos do workspace em páginas, do mais recente para o mais
  antigo (mesma data: o criado por último primeiro), numa ordem estável que nunca repete nem perde
  lançamento ao percorrer as páginas. Cada ponta de uma transferência aparece como um lançamento da própria
  conta, ligado à outra pelo grupo. A resposta MUST informar o total de lançamentos e o total de páginas.
- **FR-010**: A paginação MUST aceitar número da página (a partir da primeira) e tamanho da página, com
  padrão de 20 e máximo de 100 por página. Valores fora desses limites MUST ser recusados indicando o campo.
- **FR-011**: A listagem MUST aceitar, combinados com "e", os filtros: data inicial e data final (ambas
  incluídas), conta, categoria, tipo e trecho de texto da descrição. Filtrar por categoria principal MUST
  incluir as subcategorias dela. O texto MUST ser comparado sem diferenciar maiúsculas e minúsculas, tratando
  `%` e `_` como caracteres comuns, com no máximo 100 caracteres. Filtro com valor inválido ou período
  invertido MUST ser recusado indicando o campo.
- **FR-012**: O sistema MUST permitir editar conta, valor, data, descrição, observação e categoria de um
  lançamento de receita ou despesa, e valor, data, descrição, observação, conta de origem e conta de destino
  de uma transferência, com as mesmas validações do cadastro. A edição MUST recusar passar a usar uma conta
  ou categoria arquivada, mas MUST aceitar manter a conta ou a categoria que o lançamento já tinha, mesmo
  arquivada.
- **FR-013**: O sistema MUST permitir excluir um lançamento, e a exclusão MUST ser lógica: o lançamento
  permanece guardado com a data da exclusão e MUST NUNCA ser apagado fisicamente. Lançamento excluído MUST
  sumir de todas as listas e do saldo, e consulta, edição e nova exclusão dele MUST responder "não
  encontrado". Excluir uma ponta de transferência MUST excluir as duas.
- **FR-014**: O saldo de cada conta e o saldo total (spec 002) MUST refletir os lançamentos não excluídos
  na consulta seguinte a qualquer criação, edição ou exclusão, sem ninguém precisar recalcular nada.
- **FR-015**: O sistema MUST registrar quem criou o lançamento, a partir da pessoa autenticada, e esse campo
  MUST NUNCA ser informado pelo cliente nem alterado depois.
- **FR-016**: Somente os papéis OWNER e EDITOR MUST poder criar, transferir, editar e excluir lançamentos. O
  papel VIEWER MUST poder apenas consultar. A recusa por papel MUST usar resposta de "sem permissão",
  distinta de "não encontrado".
- **FR-017**: Toda operação de lançamentos MUST ficar sob o identificador do workspace e MUST conferir que a
  pessoa autenticada é membro dele. Para quem não é membro, ou para workspace inexistente, a resposta MUST
  ser a mesma de "não encontrado", sem revelar nada.
- **FR-018**: Um lançamento MUST pertencer a exatamente um workspace, e sua conta e sua categoria MUST ser
  desse mesmo workspace. Um lançamento MUST NUNCA ser acessado por identificador a partir de outro
  workspace.
- **FR-019**: O isolamento por workspace dos lançamentos MUST existir nas duas camadas independentes
  exigidas pela constituição (camada de dados e camada de serviço) e MUST ter teste automatizado de acesso
  cruzado para cada operação.
- **FR-020**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da lista
  permitida (workspace, quem criou, sinal do valor, grupo da transferência, datas de criação, alteração e
  exclusão) e indicando o campo inválido nas recusas.
- **FR-021**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do
  projeto, sem vazar detalhes internos.
- **FR-022**: As respostas autenticadas MUST instruir navegadores e intermediários a não guardar cache.

### Key Entities *(include if feature involves data)*

- **Lançamento**: movimento de dinheiro numa conta. Tem conta, tipo (receita, despesa ou transferência),
  valor com sinal em centavos, data, descrição, observação opcional, categoria (só em receita e despesa),
  quem o criou, datas de criação, de alteração e de exclusão (lógica). Pertence a exatamente um workspace.
- **Transferência**: par de lançamentos ligados por um grupo, uma saída e uma entrada de mesmo valor em
  contas diferentes. Existe sempre inteira; é criada, editada e excluída como uma unidade.
- **Conta** (spec 002): onde o lançamento acontece; seu saldo soma os lançamentos não excluídos.
- **Categoria** (spec 003): classifica receitas e despesas; o tipo dela tem de bater com o do lançamento.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes, o saldo de cada conta é o saldo inicial mais a soma dos lançamentos não
  excluídos da conta, em centavos exatos, depois de qualquer sequência de criações, edições e exclusões
  (inclusive somas como R$ 0,10 + R$ 0,20 = R$ 0,30).
- **SC-002**: Em 100% dos testes de transferência, o saldo total do workspace fica igual antes e depois de
  criar, editar ou excluir a transferência, e as duas pontas nunca ficam incoerentes entre si, nem sob
  falha ou concorrência.
- **SC-003**: Em 100% dos testes de listagem, percorrer todas as páginas devolve cada lançamento não
  excluído exatamente uma vez, na ordem do mais recente para o mais antigo, com ou sem filtros.
- **SC-004**: Em 100% dos testes de filtro, cada filtro (período, conta, categoria com subcategorias, tipo
  e texto) e as combinações deles devolvem exatamente os lançamentos esperados.
- **SC-005**: Em 100% dos testes de isolamento, tentativas de listar, consultar, criar, transferir, editar
  ou excluir lançamentos de um workspace do qual a pessoa não é membro recebem a mesma resposta de um
  workspace inexistente, e nenhum dado é revelado ou alterado; o mesmo vale para conta e categoria de outro
  workspace usadas num lançamento.
- **SC-006**: Em 100% dos testes de permissão, VIEWER consegue consultar e nenhuma operação de alteração tem
  efeito; OWNER e EDITOR conseguem alterar.
- **SC-007**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de acesso a lançamentos de outro workspace continuam recusadas pela outra camada.
- **SC-008**: Uma página da listagem de um workspace com 20.000 lançamentos responde em menos de 1 segundo,
  com ou sem filtros, e o saldo das contas continua exato e rápido (spec 002, SC-007).
- **SC-009**: Nenhuma operação de lançamentos apaga um lançamento de verdade: 100% dos lançamentos
  excluídos permanecem guardados com a data da exclusão.
- **SC-010**: Em 100% dos testes, nenhum lançamento novo nasce em conta ou categoria arquivada, nem de tipo
  diferente do da categoria, inclusive quando a conta ou a categoria é arquivada ao mesmo tempo do cadastro.

## Assumptions

- Esta funcionalidade é só de backend; as telas de lançamentos fazem parte do roadmap de front-end.
- Na listagem, cada ponta de uma transferência aparece como um lançamento da conta dela (visão de extrato
  por conta), ligado à outra ponta pelo grupo e pela conta da outra ponta. Os identificadores são os das
  pontas; qualquer operação de edição ou exclusão sobre uma ponta vale para a transferência inteira. O
  dashboard (spec 005) deve ignorar transferências nos totais de receita e despesa.
- Toda receita e despesa tem categoria; para o que não se encaixa, existe a categoria padrão "Outros". Isso
  mantém o dashboard sem um grupo "sem categoria".
- A pessoa informa o valor sempre positivo e o tipo diz o sinal; o valor com sinal aparece só na saída.
- O tipo do lançamento não muda depois de criado, para não deixar categoria de tipo errado nem transferência
  pela metade. Para trocar, a pessoa exclui e cria outro.
- Moeda única (BRL): uma transferência entre contas nunca precisa de conversão.
- O estado "pendente" do lançamento (para lançamentos futuros) e anexos, tags, recorrências e parcelas são
  das fases 2 e 3. Nesta funcionalidade todo lançamento conta como efetivado.
- Não há restauração de lançamento excluído nesta funcionalidade; desfazer uma exclusão é cadastrar de novo.
- Conta e categoria arquivadas continuam aparecendo nos lançamentos antigos, nos filtros e nos saldos da
  própria conta; só não aceitam novos usos.
- Lançamentos idênticos são permitidos; não há detecção de duplicidade.
- Filtrar por conta ou categoria que não existe (ou é de outro workspace) devolve uma página vazia, sem
  "não encontrado" e sem revelar nada; "não encontrado" fica para quando a conta ou a categoria é usada
  num lançamento.
- Busca por texto é por trecho da descrição, sem diferenciar maiúsculas e minúsculas; não ignora acentos.
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `002-contas` (contas, saldo
  calculado e arquivamento) e da `003-categorias` (categorias, tipo e arquivamento), além das regras da
  constituição (v1.0.0), em especial os princípios II, IV, V e VI.
