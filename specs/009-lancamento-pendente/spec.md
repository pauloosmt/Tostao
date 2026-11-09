# Feature Specification: Lançamento pendente

**Feature Branch**: `009-lancamento-pendente`

**Created**: 2026-11-09

**Status**: Draft

**Input**: User description: "Lançamento pendente: um lançamento (receita, despesa ou transferência) pode ser registrado como pendente, isto é, previsto e ainda não efetivado, e depois efetivado. Hoje todo lançamento conta como efetivado (coluna de estado já existe no banco, com os valores pendente e efetivado, padrão efetivado, mas nenhuma API a usa). A regra única da funcionalidade: o lançamento pendente NÃO conta em nada que seja realizado, ou seja, no saldo das contas, nos totais, grupos e saldos do dashboard (spec 005), no gasto dos orçamentos semanal e mensal (specs 006 e 007) e na fatura do cartão (spec 008). Ele só aparece na listagem de lançamentos (com o estado e um filtro por estado) e nas visões de previsto: o saldo previsto de cada conta e do total, e um bloco 'pendente' no dashboard. A pessoa cria o lançamento já como pendente, efetiva um pendente (numa transferência as duas pontas mudam juntas), e pode editar o estado pela edição, inclusive voltar um efetivado a pendente. Quem não informa o estado continua criando lançamentos efetivados, como hoje. Papéis: OWNER e EDITOR escrevem, VIEWER só lê; isolamento por workspace em duas camadas (RLS + filtro). Rotas sob /api/v1/workspaces/{wid}/transactions. Segue o padrão das specs 002, 004, 005, 006, 007 e 008."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Registrar um lançamento previsto sem mexer no realizado (Priority: P1)

A pessoa sabe que vai pagar o aluguel na sexta ou receber um adiantamento na semana que vem, e registra o
lançamento como **pendente**. Enquanto está pendente, ele aparece na lista de lançamentos, mas não muda o saldo
das contas, nem os totais do mês, nem o gasto dos orçamentos, nem a fatura do cartão.

**Why this priority**: é o motivo de existir da funcionalidade. Hoje a pessoa só pode lançar o que já aconteceu,
ou lançar o futuro e vê-lo distorcer o saldo de hoje.

**Independent Test**: criar uma despesa pendente de R$ 800,00 numa conta com saldo de R$ 1.000,00 e conferir
que o saldo da conta continua R$ 1.000,00, que a despesa aparece na lista como pendente e que o dashboard e o
orçamento do período não a contam.

**Acceptance Scenarios**:

1. **Given** uma despesa criada com o estado "pendente", **When** a pessoa consulta a conta, **Then** o saldo
   continua igual ao de antes e o lançamento aparece na listagem com o estado "pendente".
2. **Given** nenhum estado informado na criação, **When** o lançamento é criado, **Then** ele é efetivado,
   exatamente como antes desta funcionalidade.
3. **Given** uma receita pendente e uma despesa pendente no mês, **When** a pessoa consulta o dashboard do mês,
   **Then** os totais de receita, despesa e resultado, os grupos por categoria, a evolução diária e os saldos
   não os incluem.
4. **Given** uma despesa pendente numa categoria com limite no orçamento semanal ou mensal, **When** a pessoa
   consulta o acompanhamento, **Then** o gasto do limite, o total gasto e o gasto fora dos limites não a incluem.
5. **Given** uma compra pendente num cartão de crédito, **When** a pessoa consulta a fatura do mês, o resumo ou
   a lista de faturas, **Then** o total, as linhas e o devido do cartão não a incluem.
6. **Given** uma transferência criada como pendente, **When** a pessoa consulta as duas contas, **Then** nenhum
   dos dois saldos muda, e as duas pontas aparecem com o estado "pendente".
7. **Given** um lançamento pendente com data no passado ou no futuro, **When** ele é criado, **Then** é aceito
   nos dois casos (a data segue as mesmas regras da spec 004: de 2000 a 2100).
8. **Given** um estado diferente de "pendente" e "efetivado", **When** a pessoa tenta criar, **Then** a
   operação é recusada indicando o campo e nada é criado.
9. **Given** um lançamento pendente excluído, **When** a pessoa consulta qualquer visão, **Then** ele não conta
   em lugar nenhum (exclusão lógica da spec 004, sem mudança).

---

### User Story 2 - Efetivar um lançamento pendente (Priority: P1)

Quando o dinheiro de fato entra ou sai, a pessoa **efetiva** o lançamento. A partir daí ele conta no saldo, no
dashboard, nos orçamentos e na fatura, como qualquer lançamento efetivado.

**Why this priority**: sem efetivar, o pendente nunca viraria realizado e a funcionalidade não fecha o ciclo.

**Independent Test**: criar uma despesa pendente de R$ 800,00, efetivá-la e conferir que o saldo da conta cai
R$ 800,00 e que o gasto do orçamento sobe R$ 800,00.

**Acceptance Scenarios**:

1. **Given** um lançamento pendente, **When** a pessoa o efetiva, **Then** o estado passa a "efetivado", a data
   de última alteração é atualizada e o lançamento passa a contar no saldo, no dashboard, nos orçamentos e na
   fatura.
2. **Given** uma transferência pendente, **When** a pessoa efetiva uma das pontas, **Then** as duas pontas ficam
   efetivadas juntas, na mesma operação, e nunca sobra uma ponta pendente e outra efetivada.
3. **Given** um lançamento que já está efetivado, **When** a pessoa o efetiva de novo, **Then** a operação
   responde com sucesso sem efeito (nenhuma alteração, nem da data de última alteração).
4. **Given** um lançamento pendente numa conta ou categoria que depois foi arquivada, **When** a pessoa o
   efetiva, **Then** a operação funciona: efetivar não é passar a usar a conta ou a categoria.
5. **Given** um lançamento excluído, de outro workspace, inexistente ou com identificador malformado, **When**
   a pessoa tenta efetivá-lo, **Then** recebe "não encontrado".
6. **Given** a efetivação, **When** ela termina, **Then** nada além do estado e da data de última alteração
   muda: valor, data do lançamento, conta, categoria e descrição ficam como estavam.

---

### User Story 3 - Só quem pode altera, e só no seu workspace (Priority: P1)

Somente OWNER e EDITOR criam lançamentos pendentes, efetivam e mudam o estado. VIEWER só consulta. Quem não é
membro do workspace recebe a mesma resposta de um workspace que não existe.

**Why this priority**: a constituição exige o teste de acesso cruzado e a separação de papéis em toda rota de
dados, e o estado de um lançamento muda o saldo da pessoa.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER, tentar efetivar,
criar pendente e mudar o estado e conferir as respostas.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta efetivar, criar pendente ou mudar o
   estado, **Then** recebe "não encontrado", igual à resposta de um workspace inexistente, e nada é alterado.
2. **Given** uma pessoa com o papel VIEWER, **When** ela consulta a listagem, **Then** vê o estado de cada
   lançamento normalmente.
3. **Given** uma pessoa com o papel VIEWER, **When** ela tenta efetivar, criar pendente ou mudar o estado,
   **Then** a ação é recusada por falta de permissão e nada muda.
4. **Given** uma pessoa com o papel EDITOR, **When** ela efetiva ou muda o estado, **Then** funciona, inclusive
   sobre lançamento criado por outra pessoa; o "criado por" nunca muda.
5. **Given** o identificador de um lançamento de outro workspace usado numa rota do meu workspace, **When** a
   pessoa tenta efetivá-lo, **Then** recebe "não encontrado" e o lançamento não muda.
6. **Given** alguém sem sessão, **When** tenta qualquer operação, **Then** é recusado por falta de
   autenticação.

---

### User Story 4 - Ver o previsto (Priority: P2)

A pessoa quer saber como ficam as contas se tudo o que está pendente acontecer. Cada conta e o total mostram o
saldo **previsto** (realizado mais pendente), a listagem filtra por estado, e o dashboard traz um bloco com o
que está pendente no período.

**Why this priority**: dá utilidade ao pendente (planejar), mas o essencial (não distorcer o realizado e poder
efetivar) já funciona sem isso.

**Independent Test**: numa conta com saldo de R$ 1.000,00, uma despesa pendente de R$ 800,00 e uma receita
pendente de R$ 300,00, conferir saldo R$ 1.000,00, pendente de -R$ 500,00 e previsto de R$ 500,00.

**Acceptance Scenarios**:

1. **Given** uma conta com lançamentos pendentes, **When** a pessoa consulta a conta, **Then** vê o saldo
   (só efetivados), o valor pendente (soma dos pendentes, com sinal) e o saldo previsto (saldo mais pendente).
2. **Given** a consulta de saldos do workspace, **When** há pendentes, **Then** cada conta traz o pendente e o
   previsto, e o total traz o saldo total e o previsto total das contas ativas.
3. **Given** uma conta sem pendentes, **When** a pessoa consulta, **Then** o pendente é zero e o previsto é
   igual ao saldo.
4. **Given** o filtro de estado na listagem, **When** a pessoa pede só os pendentes (ou só os efetivados),
   **Then** recebe só esses, combinados com os outros filtros por "e".
5. **Given** o dashboard de um período, **When** há pendentes nele, **Then** o bloco "pendente" traz a receita
   pendente, a despesa pendente (positiva) e a quantidade de lançamentos pendentes do período, sem transferências.
6. **Given** um estado inválido no filtro, **When** a pessoa consulta, **Then** a consulta é recusada indicando
   o campo.
7. **Given** contas arquivadas, **When** a pessoa consulta os saldos, **Then** o pendente e o previsto seguem a
   mesma regra do saldo: as arquivadas ficam fora do total e do dashboard.

---

### User Story 5 - Mudar o estado pela edição (Priority: P2)

Pela edição de um lançamento a pessoa pode informar o estado: efetivar com a data real do pagamento, ou voltar
um efetivado para pendente (por exemplo, quando um pagamento foi estornado ou lançado cedo demais).

**Why this priority**: cobre o ajuste fino, mas efetivar direto (história 2) já resolve o fluxo comum.

**Independent Test**: editar um lançamento pendente informando o estado "efetivado" e a data de hoje, e depois
editá-lo informando "pendente", conferindo o saldo nos dois momentos.

**Acceptance Scenarios**:

1. **Given** uma edição que informa o estado, **When** a pessoa a conclui, **Then** o lançamento fica no estado
   informado, junto com as demais mudanças da edição, tudo na mesma operação.
2. **Given** uma edição que não informa o estado, **When** a pessoa a conclui, **Then** o estado atual do
   lançamento é mantido (um pendente não vira efetivado por acaso).
3. **Given** uma transferência, **When** a pessoa muda o estado numa ponta pela edição, **Then** as duas pontas
   mudam juntas.
4. **Given** um lançamento efetivado que volta a pendente, **When** a edição termina, **Then** ele deixa de
   contar no saldo, no dashboard, nos orçamentos e na fatura, e passa a contar no previsto.
5. **Given** um estado inválido na edição, **When** a pessoa tenta, **Then** a operação é recusada indicando o
   campo e nada muda.

---

### Edge Cases

- Lançamento pendente com data de ontem (atrasado): continua pendente; o sistema não efetiva nada sozinho por
  causa da data.
- Efetivar muda só o estado; se a pessoa quer registrar que o pagamento aconteceu em outro dia, usa a edição
  (que muda a data e o estado juntos).
- Despesa pendente num cartão de crédito: não entra na fatura nem no devido até ser efetivada; uma transferência
  pendente que entra no cartão (pagamento) não conta como pago até ser efetivada.
- Orçamento do período com despesa pendente: o gasto e o estado do limite ignoram o pendente; efetivar o
  pendente move o gasto na hora (e pode mudar o estado do limite).
- Dois pedidos de efetivar a mesma transferência ao mesmo tempo: as duas pontas ficam efetivadas, uma só vez, sem
  erro.
- Efetivar e excluir o mesmo lançamento ao mesmo tempo: ou o lançamento é efetivado e depois excluído, ou a
  efetivação recebe "não encontrado"; nunca sobra estado inconsistente.
- Valores muito altos: os saldos, o pendente e o previsto continuam exatos, sem estouro.
- Campos fora da lista permitida (workspace, quem criou, grupo da transferência, datas de criação e exclusão)
  continuam recusados.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Todo lançamento MUST ter um estado, "efetivado" ou "pendente". Lançamentos que já existem MUST
  continuar efetivados, e a criação sem estado informado MUST criar um lançamento efetivado.
- **FR-002**: O sistema MUST permitir criar receita, despesa e transferência já no estado "pendente". Estado
  fora dos dois valores MUST ser recusado indicando o campo `status`.
- **FR-003**: O lançamento pendente MUST NÃO contar em nada que seja realizado: no saldo das contas, no total de
  saldos, nos totais, grupos, evolução diária, comparação e saldos do dashboard, no gasto e no fora dos limites
  dos orçamentos semanal e mensal, e no total, nas linhas, nos pagamentos e no devido da fatura do cartão.
- **FR-004**: O sistema MUST permitir efetivar um lançamento pendente. Numa transferência, as duas pontas MUST
  mudar de estado juntas, na mesma operação. Efetivar um lançamento já efetivado MUST responder com sucesso sem
  nenhum efeito (nem na data de última alteração).
- **FR-005**: Efetivar MUST alterar somente o estado e a data de última alteração, e MUST funcionar também em
  conta ou categoria que foi arquivada depois da criação do lançamento.
- **FR-006**: A edição de um lançamento MUST aceitar o estado (opcional). Sem estado informado, o estado atual
  MUST ser mantido. Numa transferência, as duas pontas MUST mudar juntas. Um efetivado MUST poder voltar a
  pendente pela edição.
- **FR-007**: Cada lançamento devolvido pela API MUST informar o seu estado. A listagem MUST aceitar o filtro
  `status` (pendente ou efetivado), combinado com os demais filtros por "e"; valor inválido MUST ser recusado
  indicando o campo.
- **FR-008**: O sistema MUST oferecer o saldo previsto: cada conta MUST trazer o saldo (só efetivados), o valor
  pendente (soma dos pendentes não excluídos, com sinal) e o saldo previsto (saldo mais pendente); a consulta de
  saldos MUST trazer o saldo total e o previsto total das contas ativas. O saldo e o total que já existiam MUST
  manter o mesmo significado (realizado), sem nenhum outro valor mudar para quem não tem pendentes.
- **FR-009**: O dashboard de um período MUST trazer um bloco "pendente" com a receita pendente, a despesa
  pendente (positiva) e a quantidade de lançamentos pendentes do período, sem transferências e sem excluídos, e
  MUST manter todos os demais números como realizados.
- **FR-010**: O sistema MUST NÃO efetivar, excluir nem alterar nenhum lançamento por causa da data: o estado só
  muda por ação explícita da pessoa.
- **FR-011**: Somente os papéis OWNER e EDITOR MUST poder criar pendente, efetivar e mudar o estado. O papel
  VIEWER MUST poder apenas consultar. A recusa por papel MUST usar resposta de "sem permissão", distinta de
  "não encontrado".
- **FR-012**: Toda operação MUST ficar sob o identificador do workspace e MUST conferir que a pessoa autenticada
  é membro dele. Para quem não é membro, ou para workspace inexistente, lançamento de outro workspace, excluído
  ou malformado, a resposta MUST ser "não encontrado", sem revelar nada.
- **FR-013**: O isolamento por workspace MUST existir nas duas camadas independentes exigidas pela constituição
  (camada de dados e camada de serviço), inclusive para a mudança de estado, e MUST ter teste automatizado de
  acesso cruzado para cada operação nova.
- **FR-014**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da lista
  permitida e indicando o campo inválido nas recusas.
- **FR-015**: Mudar o estado de uma transferência e efetivar duas vezes ao mesmo tempo MUST deixar sempre as duas
  pontas no mesmo estado, sem erro interno.
- **FR-016**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do projeto,
  sem vazar detalhes internos; as respostas autenticadas MUST instruir navegadores e intermediários a não
  guardá-las em cache.
- **FR-017**: Esta funcionalidade MUST NÃO mudar o resultado de nenhuma consulta para quem não tem lançamentos
  pendentes: todos os testes das specs anteriores continuam valendo sem alteração de resultado.

### Key Entities *(include if feature involves data)*

- **Lançamento** (spec 004), agora com **estado**: efetivado (já aconteceu, conta no realizado) ou pendente
  (previsto, não conta no realizado). Numa transferência as duas pontas têm sempre o mesmo estado.
- **Saldo previsto**: visão calculada, não guardada: saldo realizado da conta mais a soma dos pendentes.
- **Bloco pendente do dashboard**: visão calculada com receita, despesa e quantidade de pendentes do período.
- **Conta** (spec 002), **Orçamento** (specs 006 e 007), **Fatura** (spec 008): só leem o estado para ignorar o
  pendente; nada muda no cadastro deles.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes, um lançamento pendente não muda o saldo de nenhuma conta, o total de saldos,
  nenhum número realizado do dashboard, nenhum gasto de orçamento (semanal e mensal) e nenhum número da fatura,
  em dados aleatórios com semente fixa que misturam efetivados, pendentes, transferências e excluídos.
- **SC-002**: Em 100% dos testes, efetivar um pendente faz os números realizados passarem a incluí-lo em exatos
  centavos, e voltar a pendente os devolve ao valor anterior (ida e volta sem resíduo).
- **SC-003**: Em 100% dos testes de transferência, as duas pontas têm sempre o mesmo estado depois de criar,
  efetivar e editar, inclusive com operações simultâneas.
- **SC-004**: Em 100% dos testes, o saldo previsto de cada conta é igual ao saldo mais o pendente, e o previsto
  total é igual à soma dos previstos das contas ativas.
- **SC-005**: Em 100% dos testes, o bloco pendente do dashboard bate com uma soma independente feita no teste
  sobre os lançamentos pendentes não excluídos do período.
- **SC-006**: Em 100% dos testes, efetivar um lançamento já efetivado não altera nenhuma coluna (nem a data de
  última alteração).
- **SC-007**: Em 100% dos testes de isolamento, tentar efetivar ou mudar o estado de lançamento de um workspace
  do qual a pessoa não é membro recebe a mesma resposta de um workspace inexistente, e nada é alterado.
- **SC-008**: Em 100% dos testes de permissão, VIEWER consegue consultar e nenhuma operação de mudança de estado
  tem efeito; OWNER e EDITOR conseguem.
- **SC-009**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de mudar o estado de lançamento de outro workspace continuam recusadas pela outra camada.
- **SC-010**: Em 100% dos testes de validação, estados inválidos e campos extras são recusados indicando o
  campo, e nada é gravado.
- **SC-011**: Todos os testes das specs 001 a 008 continuam passando sem mudar o resultado esperado (FR-017).
- **SC-012**: Num workspace com 20.000 lançamentos, com uma parte pendente, a listagem, o saldo das contas, o
  dashboard e os acompanhamentos de orçamento e de fatura continuam respondendo em menos de 1 segundo cada.

## Assumptions

- Esta funcionalidade é só de backend; as telas fazem parte do roadmap de front-end. Ela cumpre o "estado
  pendente do lançamento" que as specs 004 e 005 deixaram para a fase 2.
- **Regra única**: o pendente nunca conta no realizado. Mostrar o pendente dentro dos orçamentos e das faturas
  (por exemplo, "comprometido") fica para uma funcionalidade futura; aqui eles só o ignoram.
- O estado é manual: a pessoa efetiva ou volta a pendente. Efetivar sozinho na data, lembretes e notificações de
  pendente atrasado ficam fora desta funcionalidade.
- Recorrências, parcelas e lançamentos agendados que se criam sozinhos são funcionalidades futuras, que dependem
  desta (um lançamento recorrente nasce pendente).
- O valor pendente de uma conta soma os lançamentos pendentes não excluídos com o sinal que eles já têm (despesa
  e saída negativas, receita e entrada positivas), como o saldo faz com os efetivados.
- Contas arquivadas ficam fora do total previsto e do dashboard, como já ficam fora do saldo total (spec 002).
- Onde as specs anteriores dizem "todo lançamento conta como efetivado", passa a valer "todo lançamento
  efetivado conta", e como todo lançamento existente é efetivado, nada muda para os dados de hoje.
- Moeda única (BRL).
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `002-contas` (saldo), da
  `004-lancamentos` (lançamentos, transferências e exclusão lógica), da `005-dashboard-mensal`, das specs `006` e
  `007` (orçamentos) e da `008-fatura-cartao`, além das regras da constituição (v1.0.0), em especial os
  princípios II, IV, V, VI e VIII.
