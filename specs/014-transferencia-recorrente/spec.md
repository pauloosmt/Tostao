# Feature Specification: Transferência recorrente

**Feature Branch**: `014-transferencia-recorrente`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "Transferência recorrente: a pessoa cria uma regra que repete uma transferência entre duas contas do workspace (por exemplo, todo dia 5, R$ 500 da conta corrente para a poupança), com a mesma frequência, início, fim e efetivação automática das recorrências de receita e despesa (spec 011). Cada ocorrência é uma transferência comum, com as duas pontas, que nasce pendente e pode ser efetivada sozinha no dia."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Criar uma transferência recorrente e ver as ocorrências adiante (Priority: P1)

A dona separa R$ 500 por mês para a reserva. Ela cria uma recorrência de transferência da `Corrente` para a
`Poupança`, mensal, começando dia 5. O sistema passa a manter, 12 meses à frente, uma transferência pendente por mês,
cada uma com as duas pontas (saída da corrente, entrada na poupança).

**Why this priority**: é o motivo de existir da funcionalidade. A recorrência da spec 011 só cobre receita e despesa,
então hoje a pessoa tem de lançar a reserva mês a mês.

**Independent Test**: criar a recorrência mensal e ver as ocorrências geradas, cada uma como um par de lançamentos de
transferência, sem que o saldo de nenhuma conta mude.

**Acceptance Scenarios**:

1. **Given** duas contas ativas do workspace, **When** a pessoa cria uma recorrência do tipo transferência com conta
   de origem, conta de destino, valor, descrição, frequência e início, **Then** a recorrência é criada e as ocorrências
   até 12 meses depois de hoje são geradas, no mesmo calendário da spec 011 (início somado a semanas, meses ou anos,
   sem acumular o ajuste de fim de mês).
2. **Given** uma ocorrência gerada, **When** a pessoa a consulta, **Then** ela é uma transferência comum (spec 004): duas
   pontas ligadas pelo mesmo grupo, a saída (valor negativo) na conta de origem e a entrada (valor positivo) na conta de
   destino, na mesma data, **pendentes**, com a descrição, a observação, o marcador de efetivação automática e a autoria
   da recorrência, e sem categoria.
3. **Given** ocorrências pendentes, **When** o saldo, o dashboard, os orçamentos e as faturas são consultados, **Then**
   nenhuma ocorrência pendente muda nenhum número realizado (spec 009); só passam a contar quando efetivadas.
4. **Given** uma recorrência de transferência, **When** a pessoa informa categoria, **Then** é recusada indicando o
   campo; e **When** informa destino numa recorrência de receita ou despesa, **Then** também é recusada indicando o campo.
5. **Given** o destino igual à origem, destino ausente, valor fora de 1 a 1.000.000.000.000 centavos, descrição vazia,
   início ou fim fora das regras da spec 011, **When** a pessoa cria, **Then** é recusada indicando o campo e nada é
   gravado.
6. **Given** uma conta de origem ou de destino arquivada, **When** a pessoa cria, **Then** é recusada como conflito;
   de outro workspace ou inexistente, responde "não encontrado". A criação confere e trava as duas contas antes de
   gravar, e é tudo ou nada: uma falha no meio não deixa recorrência nem ocorrência alguma.
7. **Given** uma conta de cartão de crédito como destino, **When** a recorrência é criada, **Then** é aceita (pagamento
   periódico do cartão): cada ocorrência efetivada conta como pagamento na fatura, como qualquer transferência (spec 008).

---

### User Story 2 - A transferência acontece sozinha no dia (Priority: P1)

A dona não quer lembrar de confirmar a reserva todo mês. Ela liga a efetivação automática na recorrência; no dia de
cada ocorrência o sistema efetiva a transferência, as duas pontas juntas.

**Why this priority**: sem isso a pessoa teria de efetivar cada transferência à mão; é a metade do valor.

**Independent Test**: com a efetivação automática ligada e uma ocorrência com data até hoje, a execução automática a
efetiva: as duas contas mudam em exatos centavos e a soma dos saldos não muda.

**Acceptance Scenarios**:

1. **Given** uma recorrência com a efetivação automática ligada, **When** a execução automática roda e há ocorrências
   com data até hoje, **Then** as duas pontas de cada uma são efetivadas juntas, com o mesmo efeito da efetivação manual
   (spec 009), e o marcador sai desligado nas duas.
2. **Given** a recorrência sem o marcador, **When** a data chega, **Then** as ocorrências continuam pendentes.
3. **Given** a execução automática (ao iniciar e a cada hora), **When** roda de novo ou ao mesmo tempo em mais de uma
   execução, **Then** cada ocorrência existe como **um** par de pontas, nunca uma ponta só e nunca duplicada, e uma
   ocorrência que a pessoa excluiu (as duas pontas) não volta.
4. **Given** uma recorrência ativa, **When** uma de suas duas contas é arquivada, **Then** a geração deixa de criar novas
   ocorrências dela (as já geradas ficam) e volta a gerar se a conta for restaurada; uma recorrência com problema não
   impede as outras de serem processadas.
5. **Given** a execução automática, **When** gera ocorrências de uma recorrência de transferência, **Then** o faz com a
   mesma função e o mesmo calendário da criação (uma única definição).

---

### User Story 3 - Ver e encerrar (Priority: P2)

A dona vê as recorrências, inclusive as de transferência, e encerra a reserva quando não faz mais sentido.

**Why this priority**: o controle do que está programado; menos central que criar e efetivar.

**Independent Test**: encerrar uma recorrência de transferência com ocorrências efetivadas, vencidas e futuras mantém as
efetivadas e as vencidas e exclui, nas duas pontas, só as futuras pendentes.

**Acceptance Scenarios**:

1. **Given** recorrências de receita, despesa e transferência, **When** a pessoa as lista, **Then** vê todas; as de
   transferência trazem a conta de origem e a de destino e **não** trazem categoria; as demais trazem categoria e **não**
   trazem destino.
2. **Given** uma recorrência de transferência, **When** a pessoa a consulta, **Then** vê o resumo, as ocorrências (cada
   ponta como um lançamento, como na listagem da spec 004) e os totais, em que **cada ocorrência conta uma vez** (pelo valor
   da transferência), separados em efetivados e pendentes.
3. **Given** uma recorrência ativa, **When** a pessoa a encerra, **Then** ela para de gerar e as duas pontas das ocorrências
   pendentes com data depois de hoje são excluídas; as efetivadas e as pendentes vencidas ficam, e a resposta informa
   quantas **ocorrências** (e não pontas) foram excluídas. Encerrar de novo responde zero, sem efeito.
4. **Given** uma ocorrência de transferência, **When** a pessoa a edita, efetiva à mão ou exclui, **Then** age como em
   qualquer transferência (spec 004): as duas pontas mudam juntas, e a recorrência não a recalcula nem a recria.

---

### User Story 4 - Só quem pode altera, e só no seu workspace (Priority: P1)

**Why this priority**: o isolamento e os papéis valem desde o primeiro dia, como nas demais funcionalidades.

**Independent Test**: com dois workspaces, criar uma recorrência de transferência usando a conta do outro responde "não
encontrado"; o leitor lista mas não cria nem encerra.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** tenta criar, consultar, listar ou encerrar, **Then** recebe a
   mesma resposta de um workspace inexistente.
2. **Given** um membro VIEWER, **When** tenta criar ou encerrar, **Then** é recusado por falta de permissão; consultar e
   listar funcionam. OWNER e EDITOR criam e encerram.
3. **Given** uma conta de origem ou de destino de **outro** workspace, **When** aparece numa criação, **Then** responde "não
   encontrado" e nada é gravado.
4. **Given** a execução automática com recorrências de transferência em dois workspaces, **When** roda, **Then** nunca gera
   nem efetiva nada no workspace errado.

---

### Edge Cases

- Recorrência de receita ou despesa (spec 011): nada muda para ela, nem nas rotas, nem nos números, nem no calendário.
- Uma ponta só: nenhuma operação deixa uma ocorrência de transferência com uma ponta só; criação, geração, edição,
  efetivação, exclusão e encerramento mexem sempre nas duas.
- Início no passado (até 366 dias): gera as ocorrências vencidas e as futuras, todas pendentes; as vencidas só são
  efetivadas pela execução automática se a recorrência tiver o marcador, na próxima execução.
- Ocorrência com a data editada para depois do horizonte de 12 meses: continua sendo a ocorrência do número dela; nenhuma
  outra a substitui.
- Origem e destino iguais, mesmo escritos de formas diferentes: recusados pelo identificador.
- Valores muito altos: continuam exatos, sem estouro, nas duas pontas.
- Campos fora da lista permitida (workspace, quem criou, recorrência de origem, número da ocorrência, grupo da transferência)
  são recusados.
- Mudar valor, contas ou frequência de uma recorrência não existe (como na spec 011): encerra-se e cria-se outra.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST aceitar o tipo transferência na criação de uma recorrência, com conta de origem, conta de
  destino, valor em centavos, descrição, observação opcional, frequência (semanal, mensal ou anual), data de início, data de
  fim opcional e efetivação automática opcional (desligada por padrão).
- **FR-002**: Numa recorrência de transferência o destino MUST ser obrigatório e diferente da origem, e a categoria MUST ser
  recusada; numa de receita ou despesa o destino MUST ser recusado e a categoria continua obrigatória. Cada recusa MUST
  indicar o campo.
- **FR-003**: Valor, descrição, observação, início e fim MUST seguir as regras da spec 011 (valor de 1 a 1.000.000.000.000
  centavos; descrição de 1 a 200 caracteres sem espaços nas pontas; observação até 1.000; início entre 366 dias antes de hoje e
  2100-12-31; fim entre o início e 2100-12-31).
- **FR-004**: As contas de origem e de destino MUST existir no workspace e estar ativas. De outro workspace ou inexistente MUST
  responder "não encontrado"; arquivada MUST ser recusada como conflito. A criação MUST conferir e travar as duas contas, em
  ordem de identificador, antes de gravar, como na criação de uma transferência (spec 004).
- **FR-005**: A ocorrência de número N MUST ser uma transferência comum de duas pontas, na data do calendário da spec 011
  (FR-009 dela): a saída (valor negativo) na conta de origem e a entrada (valor positivo) na de destino, ligadas por um grupo
  de transferência, pendentes, com a descrição, a observação, o marcador de efetivação automática e a autoria da recorrência,
  sem categoria.
- **FR-006**: A geração MUST ser idempotente e atômica por ocorrência: uma ocorrência (recorrência e número) MUST existir no
  máximo uma vez como par de pontas, inclusive sob execuções simultâneas; MUST NOT existir ocorrência com uma ponta só; e uma
  ocorrência excluída (as duas pontas) MUST NOT ser recriada.
- **FR-007**: A geração MUST considerar ativa a recorrência de transferência só enquanto as duas contas não estão
  arquivadas; as ocorrências já geradas MUST ser mantidas.
- **FR-008**: A criação MUST usar a mesma geração da execução automática (função única), mantendo geradas as ocorrências até
  12 meses depois de hoje, e MUST ser tudo ou nada.
- **FR-009**: A efetivação automática MUST efetivar as duas pontas de uma ocorrência juntas, com o efeito da efetivação
  manual (spec 009) e o marcador desligado nas duas; sem o marcador, a data nunca efetiva.
- **FR-010**: As respostas de recorrência MUST trazer a conta de destino (nula para receita e despesa) e a categoria (nula para
  transferência), sem mudar nenhum campo existente. O detalhe MUST listar as ocorrências com as duas pontas (cada ponta como
  um lançamento, spec 004) e MUST contar cada ocorrência de transferência **uma vez** nos totais (total, efetivado e pendente).
- **FR-011**: Encerrar uma recorrência de transferência MUST excluir logicamente as duas pontas das ocorrências pendentes,
  vivas e com data depois de hoje, manter as efetivadas e as pendentes vencidas, ser idempotente, e informar a quantidade de
  **ocorrências** excluídas.
- **FR-012**: Uma ocorrência de transferência MUST ser editável, efetivável e excluível como qualquer transferência, e a
  recorrência MUST NOT recalculá-la nem recriá-la.
- **FR-013**: Criar e encerrar MUST exigir OWNER ou EDITOR; VIEWER consulta e lista. Quem não é membro do workspace MUST receber a
  mesma resposta de um workspace inexistente. As duas camadas de isolamento (políticas do banco e filtro explícito por workspace)
  MUST valer cada uma sozinha.
- **FR-014**: Ocorrências pendentes MUST NOT entrar no saldo, no dashboard, nos orçamentos nem na fatura; efetivadas MUST entrar em
  exatos centavos nas duas contas, sem mudar a soma dos saldos.
- **FR-015**: As recorrências de receita e despesa MUST continuar se comportando como na spec 011, e os testes das specs 001 a 013
  MUST continuar passando sem mudar o resultado esperado. A **única** mudança de comportamento é a que esta spec pede: o tipo
  transferência deixa de ser recusado na criação de uma recorrência (FR-006 da spec 011). Os testes que registravam essa recusa
  (a lista de tipos inválidos da criação, a regra de tipo e a restrição do banco) são atualizados para o novo comportamento, e só
  eles.

### Key Entities *(include if feature involves data)*

- **Recorrência** (spec 011): ganha o tipo transferência, com conta de origem, conta de destino e sem categoria.
- **Ocorrência de transferência**: uma transferência comum (spec 004) de duas pontas gerada por uma recorrência, com a
  recorrência de origem e o número, as duas pontas com o mesmo número. Nasce pendente (spec 009).
- **Efetivação automática** e **Execução automática** (spec 011): inalteradas; a execução passa a gerar também estas ocorrências.
- **Conta** (spec 002): origem e destino; precisam estar ativas.
- **Papel** (spec 001): o que a pessoa pode fazer no workspace.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes de calendário, as datas das ocorrências de transferência são as mesmas da recorrência de despesa com
  o mesmo início e frequência (31/01, 28/02, 31/03, 30/04; 29/02 anual), e cada uma é um par de pontas de sinais opostos, mesma data,
  mesmo valor em módulo e mesmo grupo.
- **SC-002**: Em 100% dos testes, depois de qualquer número de execuções (inclusive simultâneas), cada ocorrência existe como
  exatamente um par, nunca uma ponta só; uma ocorrência excluída nunca volta.
- **SC-003**: Em 100% dos testes de efetivação, a execução automática efetiva as duas pontas juntas com o mesmo efeito da manual, a
  soma dos saldos não muda e cada conta muda em exatos centavos; sem o marcador, nada é efetivado pela data.
- **SC-004**: Em 100% dos testes de atomicidade, uma falha no meio da criação não deixa nem a recorrência nem ocorrência alguma.
- **SC-005**: Em 100% dos testes, encerrar exclui as duas pontas só das pendentes futuras, mantém as efetivadas e as vencidas, é
  idempotente e informa ocorrências (não pontas).
- **SC-006**: Em 100% dos testes de integração, ocorrências pendentes nunca entram no saldo, no dashboard, nos orçamentos nem na fatura,
  e um pagamento recorrente de cartão efetivado entra na fatura como pagamento.
- **SC-007**: Em 100% dos testes de isolamento e de permissão, os resultados são os da spec 011 para a transferência recorrente, e a
  execução automática nunca age no workspace errado.
- **SC-008**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das tentativas de acessar ou
  alterar recorrência ou ocorrência de outro workspace continuam recusadas pela outra camada.
- **SC-009**: Em 100% dos testes de validação, tipo, contas, categoria, valor, datas e campos extras inválidos são recusados indicando
  o campo, e nada é gravado.
- **SC-010**: Todos os testes das specs 001 a 013 continuam passando sem mudar o resultado esperado, exceto os que registravam a
  recusa do tipo transferência na recorrência, que são atualizados e listados no commit (FR-015).
- **SC-011**: Uma execução automática sobre um workspace com 20.000 lançamentos, 200 recorrências (um terço de transferência) e 2.000
  lançamentos vencidos com efetivação automática termina em menos de 5 segundos, e criar, consultar e listar respondem em menos de 1
  segundo cada.

## Assumptions

- Esta funcionalidade é só de backend; as telas fazem parte do roadmap de front-end.
- A transferência recorrente é uma **extensão** da recorrência da spec 011: mesma rota, mesmas regras de calendário, de horizonte (12
  meses), de início (até 366 dias atrás), de encerramento e de execução automática. Só muda o que a transferência exige (destino, sem
  categoria, duas pontas).
- A regra da recorrência não é editável: para mudar valor, contas ou frequência, a pessoa encerra e cria outra.
- As ocorrências nascem sempre pendentes; só a efetivação automática (ou a pessoa) as efetiva.
- "Hoje" é o dia no fuso `America/Sao_Paulo`, o mesmo da spec 011.
- Transferir para uma conta de cartão de crédito vale como pagamento da fatura (spec 008); transferir **de** um cartão é aceito pelas
  regras de transferência comuns.
- Nenhuma notificação é enviada.
- Moeda única (BRL).
- Depende da `011-recorrencias-agendamentos` (recorrência, geração e execução automática), da `004-lancamentos` (transferência de duas
  pontas e exclusão lógica), da `009-lancamento-pendente` (estado pendente e efetivação), da `008-fatura-cartao` (pagamento do cartão) e
  da `002-contas`, além das regras da constituição (v1.0.0), em especial os princípios II, IV, V, VI e VIII.
