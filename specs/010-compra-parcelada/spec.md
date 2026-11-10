# Feature Specification: Compra parcelada

**Feature Branch**: `010-compra-parcelada`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "Compra parcelada: a pessoa registra uma despesa dividida em várias parcelas mensais (de 2 a 48) de uma só vez, informando o valor total, o número de parcelas, a data da primeira, a conta, a categoria e a descrição. O sistema cria uma despesa por parcela, uma por mês, ligadas por um grupo, cada uma com o número da parcela e o total de parcelas. O valor total é dividido em centavos exatos: as primeiras parcelas levam um centavo a mais quando a divisão não é exata, e a soma das parcelas é sempre igual ao total. A primeira parcela nasce efetivada (ou pendente, se a pessoa pedir) e todas as seguintes nascem pendentes, pela regra da spec 009 (o pendente não conta no realizado até ser efetivado). A pessoa vê o plano de parcelas (todas as parcelas, quanto já foi efetivado e quanto falta) e pode cancelar o que ainda está pendente, sem tocar no que já foi efetivado. Cada parcela é um lançamento comum da spec 004: pode ser listada, editada, efetivada (spec 009) e excluída. Numa conta de cartão de crédito, cada parcela efetivada entra na fatura do mês da sua data (spec 008). Papéis: OWNER e EDITOR escrevem, VIEWER só lê; isolamento por workspace em duas camadas (RLS + filtro). Rotas sob /api/v1/workspaces/{wid}/installment-purchases. Segue o padrão das specs 004, 008 e 009."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Registrar uma compra parcelada de uma vez (Priority: P1)

A pessoa comprou um notebook de R$ 3.000,00 em 3 vezes. Em vez de lançar três despesas à mão, ela informa o total,
o número de parcelas e a data da primeira, e o sistema cria as três parcelas, uma por mês.

**Why this priority**: é o motivo de existir da funcionalidade. Hoje a pessoa lança cada parcela na mão, erra o
valor ou esquece uma, e as parcelas futuras distorcem o saldo de hoje.

**Independent Test**: registrar R$ 3.000,00 em 3 parcelas a partir de 10/11/2026 e conferir três despesas de
R$ 1.000,00, em 10/11, 10/12 e 10/01, ligadas entre si, a primeira efetivada e as outras duas pendentes.

**Acceptance Scenarios**:

1. **Given** uma conta ativa, uma categoria de despesa ativa, um total de R$ 3.000,00, 3 parcelas e a primeira em
   2026-11-10, **When** a pessoa registra a compra, **Then** são criadas 3 despesas de R$ 1.000,00 em 2026-11-10,
   2026-12-10 e 2027-01-10, todas na mesma conta e categoria, e a resposta traz o plano completo.
2. **Given** o total de R$ 100,00 em 3 parcelas, **When** a pessoa registra, **Then** as parcelas são R$ 33,34,
   R$ 33,33 e R$ 33,33: a divisão é em centavos exatos, as primeiras parcelas levam o centavo que sobra, e a soma
   é sempre igual ao total.
3. **Given** a compra registrada, **When** a pessoa consulta as parcelas, **Then** a primeira está efetivada e
   todas as seguintes estão pendentes, e só a primeira conta no saldo, no dashboard e nos orçamentos (regra da
   spec 009).
4. **Given** a pessoa pede que a primeira parcela também seja pendente, **When** registra, **Then** todas as
   parcelas nascem pendentes.
5. **Given** a primeira parcela no dia 31, **When** a compra é registrada, **Then** cada parcela cai no mesmo dia
   do mês, ou no último dia do mês quando ele não tem esse dia (31/01, 28/02, 31/03), sem acumular o ajuste.
6. **Given** a descrição "Notebook", **When** a compra é registrada em 3 parcelas, **Then** cada parcela recebe a
   descrição com o seu número, como "Notebook (1/3)", "Notebook (2/3)" e "Notebook (3/3)".
7. **Given** um número de parcelas menor que 2, maior que 48, não inteiro ou ausente, **When** a pessoa registra,
   **Then** a operação é recusada indicando o campo e nada é criado.
8. **Given** um total menor que o número de parcelas (menos de 1 centavo por parcela), zero, negativo, com casas
   decimais ou acima do limite de R$ 10 bilhões, **When** a pessoa registra, **Then** a operação é recusada
   indicando o campo e nada é criado.
9. **Given** uma data da primeira parcela fora de 2000 a 2100, ou uma compra cuja última parcela passaria de
   2100-12-31, **When** a pessoa registra, **Then** a operação é recusada indicando o campo e nada é criado.
10. **Given** uma conta ou categoria arquivada, de outro workspace, inexistente, ou uma categoria de receita,
    **When** a pessoa registra, **Then** a operação é recusada (arquivada: conflito; outro workspace ou
    inexistente: "não encontrado"; categoria de receita: campo inválido) e nada é criado.
11. **Given** uma falha em qualquer ponto da criação, **When** ela não termina, **Then** nenhuma parcela fica
    gravada: a compra acontece inteira ou não acontece.

---

### User Story 2 - Ver o plano de parcelas (Priority: P1)

A pessoa abre a compra parcelada e vê todas as parcelas, quais já foram efetivadas, quanto já foi pago e quanto
falta.

**Why this priority**: sem enxergar o plano, a pessoa não sabe o que ainda deve nem quando.

**Independent Test**: com uma compra de R$ 3.000,00 em 3 parcelas e a primeira efetivada, conferir R$ 1.000,00
efetivados, R$ 2.000,00 pendentes e as três parcelas em ordem.

**Acceptance Scenarios**:

1. **Given** uma compra parcelada, **When** a pessoa consulta o plano, **Then** vê o identificador do grupo, a
   descrição base, o número de parcelas, a conta, a categoria, o total das parcelas que existem, o total já
   efetivado, o total pendente e a lista das parcelas em ordem de número.
2. **Given** uma parcela efetivada depois (spec 009), **When** a pessoa consulta o plano, **Then** o total
   efetivado e o pendente refletem a mudança.
3. **Given** uma parcela excluída (exclusão lógica da spec 004), **When** a pessoa consulta o plano, **Then** ela
   não aparece na lista nem entra nos totais, e o plano continua mostrando o número original de parcelas.
4. **Given** a listagem de lançamentos, **When** a pessoa lista, **Then** cada parcela traz o grupo, o número e o
   total de parcelas; lançamentos que não são parcelas trazem esses campos vazios.
5. **Given** um identificador de plano de outro workspace, inexistente ou malformado, **When** a pessoa o
   consulta, **Then** recebe "não encontrado".

---

### User Story 3 - Cancelar o que ainda falta (Priority: P2)

A pessoa devolveu o produto, ou quer parar de pagar o que ainda não pagou. Ela cancela o plano: as parcelas ainda
pendentes são excluídas, e as já efetivadas ficam como estão.

**Why this priority**: útil, mas cada parcela já pode ser excluída uma a uma pela spec 004.

**Independent Test**: numa compra de 3 parcelas com a primeira efetivada, cancelar e conferir que as duas pendentes
somem e a efetivada continua.

**Acceptance Scenarios**:

1. **Given** um plano com parcelas efetivadas e pendentes, **When** a pessoa cancela, **Then** as parcelas
   pendentes são excluídas (exclusão lógica), as efetivadas continuam, e a resposta informa quantas foram
   canceladas.
2. **Given** um plano sem nenhuma parcela pendente, **When** a pessoa cancela, **Then** a operação responde com
   sucesso sem efeito (zero canceladas).
3. **Given** um cancelamento repetido, **When** a pessoa o repete, **Then** responde com sucesso sem efeito.
4. **Given** o cancelamento, **When** ele termina, **Then** o saldo, o dashboard, os orçamentos e a fatura (que só
   contam efetivados) não mudam, e o saldo previsto deixa de incluir as parcelas canceladas.
5. **Given** um plano de outro workspace, inexistente ou malformado, **When** a pessoa tenta cancelar, **Then**
   recebe "não encontrado" e nada muda.

---

### User Story 4 - Só quem pode altera, e só no seu workspace (Priority: P1)

Somente OWNER e EDITOR registram e cancelam compras parceladas. VIEWER apenas consulta. Quem não é membro do
workspace recebe a mesma resposta de um workspace que não existe.

**Why this priority**: a constituição exige o teste de acesso cruzado em toda rota de dados e a separação de
papéis.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER, tentar registrar,
consultar e cancelar e conferir as respostas.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta registrar, consultar ou cancelar,
   **Then** recebe "não encontrado", igual à resposta de um workspace inexistente, e nada é revelado nem alterado.
2. **Given** uma pessoa com o papel VIEWER, **When** ela consulta um plano, **Then** vê tudo normalmente.
3. **Given** uma pessoa com o papel VIEWER, **When** ela tenta registrar ou cancelar, **Then** a ação é recusada
   por falta de permissão e nada muda.
4. **Given** uma pessoa com o papel EDITOR, **When** ela registra ou cancela, **Then** funciona, inclusive sobre
   plano criado por outra pessoa; o "criado por" nunca muda.
5. **Given** alguém sem sessão, **When** tenta qualquer operação, **Then** é recusado por falta de autenticação.

---

### Edge Cases

- Parcela editada à mão (valor, data ou categoria): continua sendo um lançamento comum; o plano mostra os valores
  reais, então a soma pode deixar de ser igual ao total original. O sistema não corrige nem recalcula as outras.
- Parcela excluída à mão e depois cancelamento do plano: só as pendentes que ainda existem são canceladas.
- Conta ou categoria arquivada depois da compra: as parcelas pendentes continuam existindo e podem ser
  efetivadas (spec 009); só novas compras parceladas exigem conta e categoria ativas.
- Compra parcelada num cartão de crédito: cada parcela efetivada entra na fatura do mês da sua data (spec 008); a
  parcela pendente só entra quando for efetivada.
- Parcelas com a data no passado: aceitas (a compra pode ter sido feita meses atrás); continuam pendentes se
  forem pendentes, pois o sistema nunca efetiva por causa da data (spec 009).
- Descrição de 190 caracteres: aceita, para o sufixo " (48/48)" caber nos 200 da descrição de um lançamento;
  acima disso a compra é recusada indicando o campo.
- Compras iguais registradas duas vezes: aceitas, cada uma com o seu grupo.
- Valores muito altos: continuam exatos, sem estouro.
- Campos fora da lista permitida (workspace, quem criou, grupo, número de parcelas de cada lançamento, estado das
  parcelas seguintes) são recusados.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir registrar uma compra parcelada informando conta, categoria de despesa,
  valor total em centavos, número de parcelas (de 2 a 48), data da primeira parcela, descrição e, opcionalmente,
  observação e o estado da primeira parcela (efetivada, o padrão, ou pendente).
- **FR-002**: O sistema MUST criar uma despesa por parcela, todas na mesma conta e categoria, ligadas por um
  grupo, cada uma com o seu número (de 1 ao total) e o total de parcelas.
- **FR-003**: O valor total MUST ser dividido em centavos inteiros: cada parcela vale o total dividido pelo número
  de parcelas, arredondado para baixo, e o resto (de 0 a número de parcelas menos 1 centavos) é distribuído um
  centavo por parcela, a partir da primeira. A soma das parcelas MUST ser sempre igual ao total.
- **FR-004**: A data da parcela de número N MUST ser a data da primeira somada a N-1 meses, calculada sempre a
  partir da data da primeira (sem acumular ajustes), com o dia limitado ao último dia do mês quando necessário. Toda
  data MUST ficar entre 2000-01-01 e 2100-12-31; senão a compra MUST ser recusada indicando o campo.
- **FR-005**: A primeira parcela MUST nascer efetivada, ou pendente se a pessoa pedir; todas as seguintes MUST
  nascer pendentes. O estado MUST seguir a regra da spec 009: o pendente nunca conta no realizado.
- **FR-006**: A descrição de cada parcela MUST ser a descrição informada seguida de " (N/T)", com N o número da
  parcela e T o total. A descrição informada MUST ter no máximo 190 caracteres, já sem os espaços das pontas, e
  MUST NOT ser vazia.
- **FR-007**: O valor total MUST ser um inteiro em centavos, de 1 por parcela até 1.000.000.000.000 (R$ 10
  bilhões); fora disso MUST ser recusado indicando o campo. O número de parcelas MUST ser um inteiro de 2 a 48.
- **FR-008**: A conta MUST existir no workspace e estar ativa; a categoria MUST ser de despesa, existir no
  workspace e estar ativa. Conta ou categoria de outro workspace ou inexistente MUST responder "não encontrado";
  arquivada MUST ser recusada como conflito; categoria de receita MUST ser recusada indicando o campo.
- **FR-009**: A criação MUST ser atômica: ou todas as parcelas ficam gravadas ou nenhuma. Conta e categoria MUST
  ser conferidas e travadas antes de gravar, como na criação de lançamentos (spec 004).
- **FR-010**: Cada parcela MUST ser um lançamento comum: MUST aparecer na listagem, poder ser editada, efetivada
  (spec 009) e excluída (spec 004). O grupo, o número e o total de parcelas MUST NUNCA mudar depois de criados.
- **FR-011**: A resposta de cada lançamento MUST trazer o grupo, o número e o total de parcelas (vazios nos que não
  são parcelas).
- **FR-012**: O sistema MUST oferecer a consulta do plano de um grupo: identificador, descrição base, número de
  parcelas, conta, categoria, total das parcelas que existem, total efetivado, total pendente e as parcelas em
  ordem de número, sem as excluídas.
- **FR-013**: O sistema MUST permitir cancelar o plano: excluir logicamente as parcelas que ainda estão pendentes,
  manter as efetivadas e informar quantas foram canceladas. Cancelar um plano sem pendentes, ou repetir o
  cancelamento, MUST responder com sucesso sem efeito.
- **FR-014**: O sistema MUST registrar quem criou cada parcela, a partir da pessoa autenticada, e esse campo MUST
  NUNCA ser informado pelo cliente nem alterado.
- **FR-015**: Somente os papéis OWNER e EDITOR MUST poder registrar e cancelar. O papel VIEWER MUST poder apenas
  consultar. A recusa por papel MUST usar resposta de "sem permissão", distinta de "não encontrado".
- **FR-016**: Toda operação MUST ficar sob o identificador do workspace e MUST conferir que a pessoa autenticada é
  membro dele. Para quem não é membro, workspace inexistente, plano de outro workspace ou malformado, a resposta
  MUST ser "não encontrado", sem revelar nada.
- **FR-017**: O isolamento por workspace MUST existir nas duas camadas independentes exigidas pela constituição
  (camada de dados e camada de serviço) e MUST ter teste automatizado de acesso cruzado para cada operação nova.
- **FR-018**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da lista
  permitida e indicando o campo inválido nas recusas.
- **FR-019**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do projeto,
  sem vazar detalhes internos; as respostas autenticadas MUST instruir navegadores e intermediários a não
  guardá-las em cache.
- **FR-020**: A funcionalidade MUST NÃO mudar o resultado de nenhuma consulta existente: um lançamento que não é
  parcela continua exatamente como era, e os testes das specs anteriores continuam valendo.

### Key Entities *(include if feature involves data)*

- **Compra parcelada (plano)**: um grupo de parcelas ligadas por um identificador. Não é guardada como registro
  próprio: é o conjunto das despesas com o mesmo grupo. Tem a descrição base, o número de parcelas, a conta e a
  categoria das suas parcelas.
- **Parcela**: um lançamento de despesa (spec 004) que pertence a um plano, com o número da parcela e o total de
  parcelas. Estado efetivado ou pendente (spec 009).
- **Conta** (spec 002) e **Categoria** (spec 003): onde as parcelas são lançadas; precisam estar ativas ao
  registrar a compra.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes, a soma das parcelas é igual ao total em centavos exatos, para totais e números
  de parcelas aleatórios com semente fixa (inclusive R$ 100,00 em 3 vezes, totais menores que 100 centavos e totais
  muito altos), e nenhuma parcela difere de outra em mais de 1 centavo.
- **SC-002**: Em 100% dos testes de calendário, as datas das parcelas seguem a data da primeira somada a meses,
  sem acumular ajuste de fim de mês (por exemplo 31/01, 28/02, 31/03, 30/04), inclusive em ano bissexto e na
  virada de ano, e nunca passam de 2100-12-31.
- **SC-003**: Em 100% dos testes, a primeira parcela nasce efetivada (ou pendente, se pedido) e todas as outras
  pendentes, e as pendentes nunca entram no saldo, no dashboard, nos orçamentos nem na fatura.
- **SC-004**: Em 100% dos testes de atomicidade, uma falha no meio da criação não deixa nenhuma parcela gravada.
- **SC-005**: Em 100% dos testes, cancelar um plano exclui só as pendentes que existem, mantém as efetivadas, é
  idempotente e não muda nenhum número realizado.
- **SC-006**: Em 100% dos testes, o total efetivado e o pendente do plano batem com uma soma independente feita no
  teste sobre as parcelas não excluídas, depois de efetivar, excluir e editar parcelas.
- **SC-007**: Em 100% dos testes de isolamento, registrar, consultar ou cancelar num workspace do qual a pessoa
  não é membro recebe a mesma resposta de um workspace inexistente, e nada é revelado ou alterado.
- **SC-008**: Em 100% dos testes de permissão, VIEWER consegue consultar e nenhuma operação de escrita tem efeito;
  OWNER e EDITOR conseguem.
- **SC-009**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de acessar ou cancelar plano de outro workspace continuam recusadas pela outra camada.
- **SC-010**: Em 100% dos testes de validação, parcelas, total, datas, descrição, conta, categoria e campos extras
  inválidos são recusados indicando o campo, e nada é gravado.
- **SC-011**: Todos os testes das specs 001 a 009 continuam passando sem mudar o resultado esperado (FR-020).
- **SC-012**: Registrar uma compra de 48 parcelas, consultar o plano e listar lançamentos num workspace com 20.000
  lançamentos respondem em menos de 1 segundo cada.

## Assumptions

- Esta funcionalidade é só de backend; as telas fazem parte do roadmap de front-end. Fecha o "parcelas" que a spec
  004 deixou para depois e a "compra parcelada" que a spec 008 deixou de fora da fatura.
- O parcelamento é mensal e sem juros: o valor total é só dividido. Juros, IOF, entrada mais parcelas e parcelas de
  valores diferentes definidos pela pessoa ficam fora desta funcionalidade.
- As parcelas seguintes nascem pendentes e **não** viram efetivadas sozinhas com o tempo: quem efetiva é a pessoa
  (spec 009). Efetivar automaticamente na data fica para a funcionalidade de recorrências e agendamentos.
- Como o pendente não conta no realizado (spec 009), numa conta de cartão de crédito só as parcelas efetivadas
  entram na fatura. Mostrar as parcelas futuras na fatura ("comprometido") fica para uma funcionalidade futura.
- O plano não é um registro próprio: é o conjunto de parcelas com o mesmo grupo. Por isso não existe "editar o
  plano": editar uma parcela é editar um lançamento comum, e o plano mostra o que existe.
- Alterar o valor de uma parcela não redistribui a diferença nas outras; cabe à pessoa ajustá-las.
- Moeda única (BRL).
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `002-contas`, da `003-categorias`, da
  `004-lancamentos` (lançamentos e exclusão lógica), da `008-fatura-cartao` e da `009-lancamento-pendente` (estado
  pendente), além das regras da constituição (v1.0.0), em especial os princípios II, IV, V, VI e VIII.
