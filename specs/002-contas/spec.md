# Feature Specification: Contas

**Feature Branch**: `002-contas`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Cadastro e consulta de contas financeiras dentro de um workspace, com saldo calculado. Tipos de conta: corrente, poupança, dinheiro, cartão de crédito e investimento. Cada conta tem nome, tipo, moeda (BRL), saldo inicial em centavos e, no cartão de crédito, limite de crédito e dias de fechamento e vencimento da fatura. A pessoa pode listar, criar, editar e arquivar contas (arquivar em vez de apagar, para preservar o histórico). O saldo de cada conta é calculado a partir do saldo inicial somado aos lançamentos da conta (os lançamentos entram na spec 004; nesta spec o saldo é o saldo inicial, mas o cálculo já deve considerar lançamentos quando existirem), e existe também o saldo total do workspace. Valores sempre em centavos inteiros, sem ponto flutuante. Todos os dados ficam isolados por workspace (membro acessa, outro workspace recebe 404), e só OWNER e EDITOR alteram contas; VIEWER apenas consulta."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Cadastrar e listar as minhas contas (Priority: P1)

Dentro do seu workspace, a pessoa cadastra as contas onde guarda ou deve dinheiro (conta corrente,
poupança, dinheiro em espécie, cartão de crédito, investimento), informando nome, tipo e o saldo
inicial. No cartão de crédito, informa também o limite e os dias de fechamento e de vencimento da
fatura. Depois vê a lista das suas contas ativas.

**Why this priority**: sem contas não existe onde lançar nada. É a base das specs de categorias,
lançamentos e dashboard, e sozinha já permite montar o retrato inicial do dinheiro da pessoa.

**Independent Test**: criar uma conta de cada tipo e conferir que a lista traz todas, com os dados
informados e valores em centavos.

**Acceptance Scenarios**:

1. **Given** um workspace do qual a pessoa é OWNER ou EDITOR, **When** ela cadastra uma conta corrente
   com nome e saldo inicial, **Then** a conta aparece na lista com o tipo, a moeda BRL e o saldo inicial
   informado.
2. **Given** um cadastro de cartão de crédito, **When** a pessoa informa limite, dia de fechamento e dia
   de vencimento válidos, **Then** a conta é criada guardando esses três valores.
3. **Given** um cadastro de conta que não é cartão de crédito, **When** a pessoa informa limite ou dias
   de fechamento e vencimento, **Then** o cadastro é recusado indicando que esses campos só valem para
   cartão de crédito.
4. **Given** nome vazio, nome longo demais, tipo desconhecido ou valor fora dos limites, **When** a
   pessoa tenta cadastrar, **Then** o cadastro é recusado indicando o campo inválido e nada é criado.
5. **Given** uma conta ativa com o nome "Nubank", **When** a pessoa tenta cadastrar outra conta ativa com
   o nome "nubank " (maiúsculas ou espaços diferentes), **Then** o cadastro é recusado por nome repetido.
6. **Given** um workspace sem contas, **When** a pessoa consulta a lista, **Then** recebe uma lista vazia.

---

### User Story 2 - Ver o saldo de cada conta e o saldo total (Priority: P1)

A pessoa vê quanto tem em cada conta e quanto tem no total. O saldo de uma conta é o saldo inicial
somado aos valores dos lançamentos daquela conta. Sem lançamentos, o saldo é o saldo inicial. Quando
os lançamentos passarem a existir, o saldo os considera automaticamente, sem ninguém precisar
recalcular nada.

**Why this priority**: ver o saldo é a razão de ter as contas. O cálculo precisa nascer correto agora,
porque as specs seguintes só vão acrescentar lançamentos, não mudar a regra.

**Independent Test**: com uma conta de saldo inicial R$ 1.000,00, inserir lançamentos de entrada e de
saída para ela e conferir que o saldo da conta e o total mudam exatamente pela soma deles.

**Acceptance Scenarios**:

1. **Given** uma conta com saldo inicial de R$ 1.000,00 e nenhum lançamento, **When** a pessoa consulta o
   saldo, **Then** o saldo da conta é R$ 1.000,00.
2. **Given** a mesma conta com um lançamento de entrada de R$ 250,00 e um de saída de R$ 100,00,
   **When** a pessoa consulta o saldo, **Then** o saldo é R$ 1.150,00.
3. **Given** um lançamento excluído (exclusão lógica) numa conta, **When** a pessoa consulta o saldo,
   **Then** esse lançamento não entra na soma.
4. **Given** várias contas ativas, **When** a pessoa consulta o saldo total, **Then** o total é a soma
   dos saldos de todas as contas ativas, e o valor devido de um cartão de crédito (saldo negativo)
   reduz o total.
5. **Given** uma conta arquivada, **When** a pessoa consulta o saldo total, **Then** o saldo dessa conta
   não entra no total.
6. **Given** valores com centavos (por exemplo R$ 0,10 e R$ 0,20), **When** o sistema soma, **Then** o
   resultado é exato (R$ 0,30), sem erro de arredondamento.
7. **Given** um workspace sem contas, **When** a pessoa consulta o saldo total, **Then** o total é zero.

---

### User Story 3 - Só quem pode altera, e só no seu workspace (Priority: P1)

Somente OWNER e EDITOR criam, editam e arquivam contas. VIEWER apenas consulta. E nenhuma pessoa vê ou
altera contas de um workspace do qual não é membro: a tentativa recebe a mesma resposta de um workspace
que não existe.

**Why this priority**: é a garantia de privacidade e de permissão do produto, e a constituição exige
teste de acesso cruzado em toda rota de dados. Precisa estar pronta junto com as contas.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER no
workspace da primeira, tentar ler, criar, editar e arquivar contas e conferir as respostas.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta listar, criar, editar,
   arquivar ou consultar saldos, **Then** recebe "não encontrado", igual à resposta de um workspace
   inexistente, e nada é revelado nem alterado.
2. **Given** uma pessoa com o papel VIEWER, **When** ela lista contas e consulta saldos, **Then** vê tudo
   normalmente.
3. **Given** uma pessoa com o papel VIEWER, **When** ela tenta criar, editar ou arquivar uma conta,
   **Then** a ação é recusada por falta de permissão e nada muda.
4. **Given** uma pessoa com o papel EDITOR, **When** ela cria, edita e arquiva contas, **Then** as ações
   funcionam.
5. **Given** o identificador de uma conta de outro workspace usado numa rota do meu workspace,
   **When** a pessoa tenta editá-la ou arquivá-la, **Then** recebe "não encontrado" e a conta não muda.
6. **Given** alguém sem sessão, **When** tenta qualquer operação de contas, **Then** é recusado por falta
   de autenticação.

---

### User Story 4 - Corrigir uma conta (Priority: P2)

A pessoa edita nome, saldo inicial e, no cartão, limite e dias de fechamento e vencimento, para corrigir
um erro de cadastro ou refletir uma mudança. O tipo da conta não muda depois de criada.

**Why this priority**: erros de cadastro acontecem e precisam de conserto, mas o produto funciona sem
isso nos primeiros dias de uso.

**Independent Test**: criar uma conta, editar o nome e o saldo inicial e conferir que a lista e o saldo
refletem a mudança.

**Acceptance Scenarios**:

1. **Given** uma conta ativa, **When** a pessoa altera o nome, **Then** a lista mostra o novo nome.
2. **Given** uma conta com lançamentos, **When** a pessoa altera o saldo inicial, **Then** o saldo da
   conta passa a ser o novo saldo inicial somado aos mesmos lançamentos.
3. **Given** uma conta de qualquer tipo, **When** a pessoa tenta mudar o tipo, **Then** a alteração é
   recusada indicando que o tipo não pode ser mudado.
4. **Given** uma edição com valores inválidos ou um nome já usado por outra conta ativa, **When** a
   pessoa salva, **Then** a edição é recusada indicando o motivo e a conta não muda.
5. **Given** uma conta arquivada, **When** a pessoa tenta editar seus dados, **Then** a edição é recusada
   (só é possível restaurá-la).

---

### User Story 5 - Arquivar e restaurar contas (Priority: P2)

Quando uma conta deixa de ser usada (conta encerrada, cartão cancelado), a pessoa a arquiva em vez de
apagá-la. A conta some da lista padrão e do saldo total, mas o histórico dela é preservado. A pessoa
pode ver as contas arquivadas e restaurar uma delas.

**Why this priority**: mantém a lista limpa sem perder histórico, mas o produto já é útil antes disso.

**Independent Test**: arquivar uma conta, conferir que ela some da lista padrão e do total, aparece ao
pedir as arquivadas e volta a valer depois de restaurada.

**Acceptance Scenarios**:

1. **Given** uma conta ativa, **When** a pessoa a arquiva, **Then** ela sai da lista padrão e do saldo
   total, e continua guardada com todo o seu histórico.
2. **Given** contas arquivadas, **When** a pessoa pede a lista incluindo as arquivadas, **Then** vê as
   ativas e as arquivadas, cada uma marcada como tal.
3. **Given** uma conta arquivada, **When** a pessoa a restaura, **Then** ela volta à lista padrão e ao
   saldo total.
4. **Given** uma conta arquivada cujo nome já foi tomado por uma conta ativa, **When** a pessoa tenta
   restaurá-la, **Then** a restauração é recusada por nome repetido.
5. **Given** qualquer conta, **When** a pessoa procura uma forma de apagá-la, **Then** não existe: o
   sistema só arquiva.
6. **Given** uma conta já arquivada, **When** a pessoa a arquiva de novo, **Then** a resposta é de
   sucesso e nada muda.

---

### Edge Cases

- Saldo inicial negativo: permitido em qualquer tipo (conta no cheque especial, cartão com dívida já
  existente).
- Valores muito grandes: acima do limite aceito (R$ 10 bilhões em módulo) são recusados, para a soma
  nunca estourar.
- Valor com casas decimais além dos centavos ou em formato não inteiro: recusado; o sistema só aceita
  centavos inteiros.
- Dias de fechamento ou de vencimento fora de 1 a 31: recusado. Informar só um dos dois é aceito (ver
  premissas).
- Dia 31 em mês com menos dias: guarda-se o dia 31 como informado; o ajuste para o último dia do mês é
  responsabilidade da fatura, na fase 2.
- Duas pessoas editando a mesma conta ao mesmo tempo: vale a última gravação.
- Conta com lançamentos e saldo diferente de zero sendo arquivada: permitido; o saldo sai do total.
- Corpo da requisição com campos extras, como tentar informar o workspace, o saldo calculado ou o
  estado de arquivada no cadastro: campos não permitidos são recusados, nunca aceitos.
- Identificador de conta malformado ou inexistente: resposta de "não encontrado", sem erro interno.
- Consulta de saldo com milhares de lançamentos: continua exata e rápida (ver critérios de sucesso).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir cadastrar uma conta em um workspace informando nome, tipo e saldo
  inicial. Os tipos aceitos são corrente, poupança, dinheiro, cartão de crédito e investimento.
- **FR-002**: O nome MUST ter de 1 a 100 caracteres após remover espaços das pontas. Não pode haver duas
  contas ativas no mesmo workspace com o mesmo nome, sem diferenciar maiúsculas e minúsculas.
- **FR-003**: Todo valor em dinheiro MUST ser inteiro em centavos, nunca com casas decimais nem em ponto
  flutuante. O módulo de qualquer valor monetário aceito MUST ser de no máximo 1.000.000.000.000
  centavos (R$ 10 bilhões).
- **FR-004**: O saldo inicial MUST poder ser negativo, zero ou positivo, em qualquer tipo de conta.
- **FR-005**: A moeda da conta MUST ser a moeda base do workspace (BRL) e não pode ser escolhida nem
  alterada nesta funcionalidade.
- **FR-006**: Limite de crédito, dia de fechamento e dia de vencimento MUST ser aceitos somente em conta
  de cartão de crédito; informá-los em outro tipo MUST ser recusado. O limite MUST ser zero ou
  positivo, e os dias MUST estar entre 1 e 31. Os três campos são opcionais no cartão.
- **FR-007**: O sistema MUST listar as contas de um workspace, por padrão só as ativas, em ordem estável
  (por nome, sem diferenciar maiúsculas). A pessoa MUST poder pedir também as arquivadas.
- **FR-008**: O sistema MUST permitir consultar uma conta pelo identificador, dentro do seu workspace.
- **FR-009**: O sistema MUST permitir editar nome, saldo inicial e, no cartão, limite, dia de fechamento
  e dia de vencimento de uma conta ativa. O tipo MUST NUNCA mudar depois de criada. As mesmas regras de
  validação do cadastro valem na edição.
- **FR-010**: O sistema MUST permitir arquivar uma conta, preservando todos os seus dados e lançamentos,
  e restaurar uma conta arquivada. Arquivar uma conta já arquivada, ou restaurar uma ativa, MUST
  responder com sucesso sem efeito. Restaurar MUST ser recusado se o nome já estiver em uso por uma
  conta ativa. Uma conta arquivada MUST NUNCA ser editada além da restauração.
- **FR-011**: O sistema MUST NÃO oferecer nenhuma forma de apagar uma conta.
- **FR-012**: O saldo de uma conta MUST ser calculado a cada consulta como o saldo inicial somado aos
  valores de todos os lançamentos não excluídos da conta, e MUST NUNCA ser guardado como valor
  próprio. Receitas somam, despesas subtraem e transferências afetam cada conta pelo valor da sua
  própria linha.
- **FR-013**: O sistema MUST oferecer o saldo total do workspace: a soma dos saldos de todas as contas
  ativas, em que o valor devido num cartão de crédito reduz o total. Contas arquivadas não entram.
  Sem contas ativas, o total é zero.
- **FR-014**: A lista de contas MUST trazer o saldo calculado de cada conta, e uma consulta de saldos MUST
  trazer cada conta com o seu saldo e o total, tudo consistente entre si no mesmo instante.
- **FR-015**: Somente os papéis OWNER e EDITOR MUST poder criar, editar, arquivar e restaurar contas.
  O papel VIEWER MUST poder apenas consultar contas e saldos. A recusa por papel MUST usar resposta de
  "sem permissão", distinta de "não encontrado".
- **FR-016**: Toda operação de contas MUST ficar sob o identificador do workspace e MUST conferir que a
  pessoa autenticada é membro dele. Para quem não é membro, ou para workspace inexistente, a resposta
  MUST ser a mesma de "não encontrado", sem revelar nada.
- **FR-017**: Uma conta MUST pertencer a exatamente um workspace e MUST NUNCA ser acessada por
  identificador a partir de outro workspace: o identificador da conta só vale dentro do workspace dela.
- **FR-018**: O isolamento por workspace das contas MUST existir nas duas camadas independentes exigidas
  pela constituição (camada de dados e camada de serviço) e MUST ter teste automatizado de acesso
  cruzado para cada operação.
- **FR-019**: A criação MUST registrar quando a conta foi criada e quando foi alterada pela última vez,
  e esses dois campos MUST ser informados nas consultas.
- **FR-020**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da
  lista permitida (workspace, saldo calculado, estado de arquivada, datas) e indicando o campo inválido
  nas recusas.
- **FR-021**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do
  projeto, sem vazar detalhes internos.
- **FR-022**: As respostas autenticadas MUST instruir navegadores e intermediários a não guardá-las em
  cache.

### Key Entities *(include if feature involves data)*

- **Conta**: lugar onde a pessoa guarda ou deve dinheiro. Tem nome, tipo (corrente, poupança, dinheiro,
  cartão de crédito ou investimento), moeda (a do workspace), saldo inicial em centavos, estado
  (ativa ou arquivada) e datas de criação e de última alteração. No cartão de crédito, tem ainda limite
  de crédito e dias de fechamento e de vencimento da fatura. Pertence a exatamente um workspace.
- **Saldo da conta**: valor calculado, nunca guardado: saldo inicial somado aos valores dos lançamentos
  não excluídos da conta.
- **Saldo total**: valor calculado do workspace: soma dos saldos das contas ativas.
- **Lançamento** (definido na spec 004): movimento de entrada ou saída de uma conta. Aqui só importa que
  cada lançamento pertence a uma conta e tem um valor com sinal em centavos.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Uma pessoa cadastra uma conta e a vê na lista em uma única ação de cadastro, sem passos
  adicionais.
- **SC-002**: Em 100% dos testes, o saldo de cada conta é exatamente o saldo inicial mais a soma dos
  lançamentos não excluídos, em centavos, inclusive com somas como R$ 0,10 + R$ 0,20 = R$ 0,30.
- **SC-003**: O saldo total é, em 100% dos testes, igual à soma dos saldos das contas ativas mostradas na
  lista, e contas arquivadas nunca alteram o total.
- **SC-004**: Em 100% dos testes de isolamento, tentativas de listar, consultar, criar, editar ou
  arquivar contas de um workspace do qual a pessoa não é membro recebem a mesma resposta de um
  workspace inexistente, e nenhum dado é revelado ou alterado.
- **SC-005**: Em 100% dos testes de permissão, VIEWER consegue consultar e nenhuma operação de alteração
  tem efeito; OWNER e EDITOR conseguem alterar.
- **SC-006**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100%
  das tentativas de acesso a contas de outro workspace continuam recusadas pela outra camada.
- **SC-007**: Listar as contas com saldos de um workspace com 50 contas e 20.000 lançamentos responde em
  menos de 1 segundo.
- **SC-008**: Nenhuma operação de contas oferece apagar uma conta, e 100% das contas arquivadas mantêm
  todos os seus dados e lançamentos.

## Assumptions

- Esta funcionalidade é só de backend; as telas de contas fazem parte do roadmap de front-end.
- Os lançamentos (spec 004) ainda não têm rotas. A tabela de lançamentos já existe no banco, então o
  cálculo de saldo é exercitado nos testes inserindo lançamentos diretamente.
- A moeda é sempre BRL, a moeda base do workspace; múltiplas moedas são da fase 4.
- Um saldo inicial negativo é válido em qualquer tipo de conta, e o cartão de crédito é representado por
  saldo negativo quando há dívida.
- O saldo total soma todas as contas ativas, inclusive investimento e o valor devido no cartão
  (que reduz o total). Separar "patrimônio" de "dinheiro disponível" não faz parte desta funcionalidade.
- Limite e dias de fechamento e vencimento são opcionais no cartão nesta funcionalidade; a spec da
  fatura (fase 2) passa a exigi-los quando precisar.
- O tipo da conta não muda depois de criado, para não deixar campos de cartão sem sentido em uma conta
  de outro tipo. Para mudar de tipo, a pessoa cria outra conta e arquiva a antiga.
- Nome repetido é verificado só entre contas ativas; uma conta arquivada pode ter o nome de outra ativa,
  e a restauração é que checa o conflito.
- Conta arquivada não recebe novos lançamentos (regra a ser aplicada na spec 004) e só pode ser
  restaurada, não editada.
- Não há limite de quantidade de contas por workspace; o uso esperado é de poucas dezenas.
- A lista de contas não é paginada, dado o volume pequeno esperado.
- Depende da `001-autenticacao-workspace`: sessão, papéis (OWNER, EDITOR, VIEWER), isolamento por workspace
  e as regras da constituição (v1.0.0), em especial os princípios II, IV e V.
