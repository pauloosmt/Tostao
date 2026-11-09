# Feature Specification: Fatura do cartão de crédito

**Feature Branch**: `008-fatura-cartao`

**Created**: 2026-11-09

**Status**: Draft

**Input**: User description: "Fatura do cartão de crédito calculada a partir dos dias de fechamento e de vencimento da conta (spec 002) e dos lançamentos do cartão (spec 004), sem guardar nada novo. A fatura é identificada pelo mês do seu vencimento (AAAA-MM). Para um cartão, a pessoa consulta uma fatura e vê a data de fechamento, a data de vencimento, o período, os lançamentos que a compõem (compras, saques por transferência e estornos) e o total. Vê também o resumo do cartão: limite, valor devido, limite disponível e a fatura aberta. Vê a situação de cada fatura (aberta, fechada, paga ou vencida), o quanto já foi pago e o quanto falta, e a lista das faturas de um intervalo de meses. Pagamentos são as transferências que entram no cartão (spec 004). O dia 31 em mês com menos dias vale o último dia do mês. Só leitura. Papéis: OWNER, EDITOR e VIEWER leem; isolamento por workspace em duas camadas (RLS + filtro). Rotas sob /api/v1/workspaces/{wid}/accounts/{accountId}. Segue o padrão das specs 002-contas, 004-lancamentos, 005-dashboard-mensal, 006 e 007."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver a fatura de um mês (Priority: P1)

A pessoa escolhe um cartão de crédito e um mês de vencimento e vê a fatura daquele mês: quando fechou, quando
vence, que período de compras ela cobre, cada lançamento que a compõe e o total a pagar.

**Why this priority**: é o motivo de existir da funcionalidade. Hoje o cartão só tem um saldo devido corrido; a
pessoa não sabe quanto vence e quando.

**Independent Test**: num cartão com fechamento no dia 20 e vencimento no dia 28, lançar compras antes e depois
do dia 20 de novembro e consultar a fatura de `2026-11` e a de `2026-12`; conferir que cada compra caiu na
fatura certa e que os totais batem.

**Acceptance Scenarios**:

1. **Given** um cartão com fechamento no dia 20 e vencimento no dia 28, **When** a pessoa consulta a fatura de
   `2026-11`, **Then** o fechamento é 20/11/2026, o vencimento é 28/11/2026 e o período vai de 21/10/2026 a
   20/11/2026 (do dia seguinte ao fechamento anterior até o fechamento, inclusive).
2. **Given** um cartão com fechamento no dia 25 e vencimento no dia 5, **When** a pessoa consulta a fatura de
   `2026-12`, **Then** o vencimento é 05/12/2026 e o fechamento é 25/11/2026 (o vencimento cai no mês seguinte
   ao fechamento quando o dia de vencimento é menor ou igual ao de fechamento).
3. **Given** compras no cartão, **When** a pessoa consulta uma fatura, **Then** o total é a soma das despesas
   do período, mais as saídas por transferência do cartão (saque ou adiantamento), menos os estornos (receitas
   lançadas no cartão), e cada lançamento aparece na lista com data, descrição, categoria e valor.
4. **Given** uma compra feita exatamente no dia do fechamento, **When** a pessoa consulta, **Then** ela entra
   nessa fatura; a compra do dia seguinte entra na próxima.
5. **Given** lançamentos excluídos e transferências que entram no cartão (pagamentos), **When** a pessoa
   consulta a fatura, **Then** nenhum deles entra no total nem na lista de compras da fatura.
6. **Given** um mês sem nenhum lançamento no período, **When** a pessoa consulta, **Then** recebe a fatura com
   as datas, a lista vazia e total zero, sem erro.
7. **Given** o saldo inicial do cartão (dívida anterior ao cadastro), **When** a pessoa consulta qualquer
   fatura, **Then** o saldo inicial não entra em nenhuma delas.
8. **Given** um mês mal escrito, inexistente ou fora de 2000-01 a 2100-12, **When** a pessoa consulta, **Then**
   a consulta é recusada indicando o campo.
9. **Given** um cartão arquivado, **When** a pessoa consulta uma fatura antiga, **Then** a fatura é devolvida
   normalmente (arquivar não apaga o histórico).

---

### User Story 2 - Ver o resumo do cartão (Priority: P1)

A pessoa abre o cartão e vê, num lugar só, o limite, quanto deve hoje, quanto ainda pode gastar e a fatura
aberta (a que está recebendo compras agora).

**Why this priority**: é a pergunta do dia a dia ("posso passar o cartão?") e a porta de entrada para as
faturas.

**Independent Test**: num cartão com limite de R$ 5.000,00 e R$ 1.200,00 devidos, conferir limite disponível de
R$ 3.800,00 e que a fatura aberta é a do mês certo.

**Acceptance Scenarios**:

1. **Given** um cartão com limite de R$ 5.000,00 e saldo devido de R$ 1.200,00, **When** a pessoa consulta o
   resumo, **Then** vê limite 500000, devido 120000 e disponível 380000, em centavos.
2. **Given** um cartão sem limite cadastrado, **When** a pessoa consulta o resumo, **Then** limite e disponível
   vêm vazios ("não informado") e o devido continua calculado.
3. **Given** um cartão com crédito a favor (saldo positivo, por exemplo, por pagamento a mais), **When** a
   pessoa consulta, **Then** o devido é zero e o disponível é o limite mais o crédito.
4. **Given** um cartão cujo devido passa do limite, **When** a pessoa consulta, **Then** o disponível vem
   negativo, sem erro.
5. **Given** a data de hoje, **When** a pessoa consulta o resumo, **Then** a fatura aberta é a que contém hoje
   no seu período, com fechamento, vencimento e o total até agora.
6. **Given** o resumo, **When** a pessoa o consulta, **Then** o devido é o mesmo valor que a spec 002 já
   calcula como saldo negativo do cartão, nunca um segundo cálculo diferente.

---

### User Story 3 - Saber se a fatura foi paga (Priority: P2)

Para cada fatura a pessoa vê a situação (aberta, fechada, paga ou vencida), quanto já foi pago e quanto ainda
falta pagar. Pagar a fatura é transferir dinheiro de uma conta para o cartão (já existe na spec 004).

**Why this priority**: valor grande, mas a fatura já entrega o essencial só com datas e total.

**Independent Test**: fechar uma fatura de R$ 1.000,00, transferir R$ 600,00 de uma conta corrente para o cartão
antes do vencimento e conferir pago 60000, restante 40000 e situação "fechada"; transferir mais R$ 400,00 e
conferir "paga".

**Acceptance Scenarios**:

1. **Given** uma fatura cujo fechamento ainda não passou, **When** a pessoa consulta, **Then** a situação é
   "aberta", pago é zero e o restante é o total até agora.
2. **Given** uma fatura já fechada, ainda no prazo e com pagamentos menores que o total, **When** a pessoa
   consulta, **Then** a situação é "fechada" e o restante é o total menos o pago.
3. **Given** uma fatura fechada com pagamentos que cobrem o total, **When** a pessoa consulta, **Then** a
   situação é "paga" e o restante é zero, mesmo antes do vencimento.
4. **Given** uma fatura fechada cujo vencimento já passou com pagamentos que não cobrem o total, **When** a
   pessoa consulta, **Then** a situação é "vencida" e o restante é o que falta.
5. **Given** pagamentos, **When** a pessoa consulta, **Then** os pagamentos de uma fatura são as transferências
   que entram no cartão com data depois do fechamento dela, até o fechamento da fatura seguinte (inclusive). Um
   pagamento da fatura anterior, feito dentro do período desta, não conta como pagamento desta.
6. **Given** uma fatura fechada com total zero ou negativo (só estornos), **When** a pessoa consulta, **Then**
   o restante é zero e a situação é "paga".
7. **Given** pagamento maior que o total, **When** a pessoa consulta, **Then** o restante é zero (nunca
   negativo) e o pago mostra o valor real.

---

### User Story 4 - Ver as faturas de um intervalo de meses (Priority: P2)

A pessoa vê a lista das faturas de um cartão em vários meses, cada uma com datas, total, pago, restante e
situação, para acompanhar o histórico e achar a que está em aberto.

**Why this priority**: útil, mas cada fatura já pode ser consultada uma a uma.

**Independent Test**: pedir as faturas de `2026-06` a `2026-12` e conferir sete itens em ordem de vencimento,
cada um igual à consulta individual do mesmo mês.

**Acceptance Scenarios**:

1. **Given** um intervalo de meses, **When** a pessoa consulta a lista, **Then** recebe uma fatura por mês de
   vencimento, em ordem do mais recente para o mais antigo, cada uma com os mesmos números da consulta
   individual, sem a lista de lançamentos.
2. **Given** nenhum intervalo informado, **When** a pessoa consulta, **Then** recebe as seis faturas até a
   fatura aberta, inclusive.
3. **Given** um intervalo de mais de 36 meses, com início depois do fim, ou com meses malformados ou fora de
   2000-01 a 2100-12, **When** a pessoa consulta, **Then** é recusado indicando o campo.
4. **Given** um cartão sem nenhum lançamento, **When** a pessoa consulta a lista, **Then** recebe as faturas do
   intervalo todas com total zero.

---

### User Story 5 - Só cartões, só no seu workspace, todos leem (Priority: P1)

A fatura existe só para contas de cartão de crédito que tenham dia de fechamento e de vencimento. OWNER, EDITOR
e VIEWER consultam; quem não é membro do workspace recebe a mesma resposta de um workspace que não existe.

**Why this priority**: a constituição exige o teste de acesso cruzado em toda rota de dados, e a fatura revela
hábitos de gasto.

**Independent Test**: com uma conta corrente, um cartão sem dias, um cartão completo, um VIEWER e uma pessoa de
outro workspace, tentar cada consulta e conferir as respostas.

**Acceptance Scenarios**:

1. **Given** uma conta que não é cartão de crédito, **When** a pessoa pede fatura, resumo ou lista, **Then** a
   consulta é recusada dizendo que a conta não é um cartão de crédito.
2. **Given** um cartão sem dia de fechamento ou sem dia de vencimento, **When** a pessoa pede uma fatura ou a
   lista, **Then** a consulta é recusada indicando quais dias faltam cadastrar; o resumo do cartão continua
   funcionando, com a fatura aberta vazia.
3. **Given** uma pessoa que não é membro do workspace, **When** ela tenta qualquer consulta, **Then** recebe
   "não encontrado", igual à resposta de um workspace inexistente, e nada é revelado.
4. **Given** uma pessoa com qualquer papel (OWNER, EDITOR ou VIEWER), **When** consulta, **Then** vê tudo
   normalmente.
5. **Given** o identificador de uma conta de outro workspace, ou inexistente, ou malformado, **When** a pessoa
   o usa, **Then** recebe "não encontrado".
6. **Given** alguém sem sessão, **When** tenta qualquer consulta, **Then** é recusado por falta de
   autenticação.

---

### Edge Cases

- Dia de fechamento ou de vencimento 29, 30 ou 31 em mês com menos dias: vale o último dia daquele mês (por
  exemplo, dia 31 em fevereiro de 2027 é 28/02; em fevereiro de 2028, 29/02). A decisão de o vencimento cair no
  mês do fechamento ou no seguinte usa os dias **cadastrados**, não os ajustados.
- Fechamento e vencimento no mesmo dia: o vencimento é no mês seguinte ao fechamento.
- Dia de vencimento maior que o de fechamento: os dois caem no mesmo mês (fechamento 20, vencimento 28).
- Faturas consecutivas nunca se sobrepõem nem deixam dia de fora: cada dia pertence a exatamente uma fatura,
  mesmo com ajuste de fim de mês.
- Cartão que tinha dias diferentes antes de uma edição: as faturas são sempre calculadas com os dias de
  **hoje** (o sistema não guarda histórico de dias); isso é dito na documentação.
- Lançamento com data fora de 2000 a 2100: não existe (a spec 004 já recusa).
- Saque ou adiantamento em dinheiro pelo cartão (transferência que sai do cartão): conta como compra na fatura
  do período.
- Compra parcelada: ainda não existe; cada parcela é um lançamento comum na sua data.
- Fatura com milhares de lançamentos: todos são devolvidos, sem paginação (um usuário, dados pequenos).
- Valores muito altos: continuam exatos, sem estouro.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST calcular a fatura de um cartão de crédito a partir do dia de fechamento, do dia de
  vencimento e dos lançamentos da conta, sem guardar a fatura nem o total em nenhum lugar.
- **FR-002**: A fatura MUST ser identificada pelo mês do vencimento, no formato `AAAA-MM`, de 2000-01 a 2100-12.
  Mês mal escrito, inexistente ou fora do intervalo MUST ser recusado indicando o campo.
- **FR-003**: A data de vencimento MUST ser o dia de vencimento do mês pedido; a data de fechamento MUST ser o
  dia de fechamento do mesmo mês quando o dia de vencimento cadastrado é maior que o de fechamento, e do mês
  anterior quando é menor ou igual. Dia maior que o tamanho do mês MUST valer o último dia dele.
- **FR-004**: O período da fatura MUST ir do dia seguinte ao fechamento da fatura anterior até o fechamento
  desta, ambos incluídos, de modo que cada dia pertença a exatamente uma fatura.
- **FR-005**: O total da fatura MUST ser, em centavos inteiros, a soma das despesas do período, mais as saídas
  por transferência do cartão, menos os estornos (receitas lançadas no cartão), considerando só lançamentos
  não excluídos da conta. Transferências que entram no cartão (pagamentos), lançamentos excluídos e o saldo
  inicial MUST NÃO entrar no total.
- **FR-006**: A consulta de uma fatura MUST trazer as datas (fechamento, vencimento, início do período), o
  total, o pago, o restante, a situação e a lista de lançamentos que a compõem, cada um com identificador,
  data, descrição, categoria (ou vazia) e valor, em ordem de data e depois de identificador.
- **FR-007**: Os pagamentos de uma fatura MUST ser as transferências não excluídas que entram no cartão com
  data depois do fechamento dela e até o fechamento da fatura seguinte, inclusive. O restante MUST ser o total
  menos o pago, nunca negativo.
- **FR-008**: A situação MUST ser calculada com a data de hoje no fuso de Brasília: "aberta" quando hoje é até o
  dia do fechamento; "paga" quando já fechou e o restante é zero; "vencida" quando já fechou, o vencimento
  passou e o restante é maior que zero; "fechada" nos demais casos.
- **FR-009**: O sistema MUST oferecer o resumo do cartão: limite (ou vazio se não cadastrado), valor devido (o
  saldo negativo calculado na spec 002, e zero se o saldo for zero ou positivo), limite disponível (limite
  menos o devido, podendo ser negativo, ou vazio sem limite) e a fatura aberta com fechamento, vencimento e
  total até agora.
- **FR-010**: O sistema MUST oferecer a lista das faturas de um intervalo de meses (padrão: as seis até a
  fatura aberta; no máximo 36 meses), do vencimento mais recente para o mais antigo, cada uma com os mesmos
  números da consulta individual e sem os lançamentos. Intervalo inválido MUST ser recusado indicando o campo.
- **FR-011**: A consulta MUST ser recusada, indicando o motivo, quando a conta não é de cartão de crédito e,
  para fatura e lista, quando o cartão não tem dia de fechamento ou de vencimento, indicando quais faltam. Um
  cartão arquivado MUST continuar consultável.
- **FR-012**: O sistema MUST NÃO alterar nenhum dado: nenhuma consulta de fatura grava, move ou exclui
  lançamentos, contas ou categorias.
- **FR-013**: Os papéis OWNER, EDITOR e VIEWER MUST poder consultar. Toda operação MUST ficar sob o
  identificador do workspace e da conta e MUST conferir que a pessoa autenticada é membro dele; para quem não é
  membro, conta de outro workspace ou inexistente, a resposta MUST ser "não encontrado", sem revelar nada.
- **FR-014**: O isolamento por workspace da fatura MUST existir nas duas camadas independentes exigidas pela
  constituição (camada de dados e camada de serviço) e MUST ter teste automatizado de acesso cruzado para cada
  consulta. Nenhum lançamento de outro workspace MUST entrar em qualquer soma.
- **FR-015**: Todos os números de uma consulta (total, pago, lançamentos, resumo) MUST vir de uma única leitura
  consistente, sem misturar dados de antes e de depois de uma gravação feita ao mesmo tempo.
- **FR-016**: O devido do resumo MUST ser o mesmo valor que a consulta de saldos da spec 002 devolve para o
  cartão, nunca um cálculo separado que possa divergir.
- **FR-017**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do
  projeto, sem vazar detalhes internos; as respostas autenticadas MUST instruir navegadores e intermediários a
  não guardá-las em cache.

### Key Entities *(include if feature involves data)*

- **Fatura**: visão calculada, não guardada, de um cartão para um mês de vencimento: datas de fechamento e de
  vencimento, período, total, pago, restante, situação e lançamentos. Pertence a exatamente um cartão e um
  workspace.
- **Resumo do cartão**: visão calculada com limite, valor devido, limite disponível e a fatura aberta.
- **Cartão de crédito** (spec 002): a conta com limite, dia de fechamento e dia de vencimento.
- **Lançamento** (spec 004): a fonte de compras, estornos e pagamentos; a fatura só o lê.
- **Papel**: o que a pessoa pode fazer no workspace, definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes de calendário, as datas de fechamento e de vencimento e o período estão certos
  para combinações de dias (20/28, 25/5, 10/10, 31/31, 29/30, 1/31) em meses de 28, 29, 30 e 31 dias e na
  virada de ano.
- **SC-002**: Em 100% dos testes, cada lançamento do cartão pertence a exatamente uma fatura: a soma dos totais
  de faturas consecutivas é igual à soma dos lançamentos do cartão que contam, em dados aleatórios com semente
  fixa e com os dias de fim de mês.
- **SC-003**: Em 100% dos testes, o total de cada fatura bate, em centavos exatos, com uma soma independente
  feita no teste (inclusive R$ 0,10 + R$ 0,20 = R$ 0,30), e pagamentos, excluídos e saldo inicial nunca entram
  no total.
- **SC-004**: Em 100% dos testes de situação, uma fatura é "aberta", "fechada", "paga" ou "vencida" de acordo
  com a data de hoje, o fechamento, o vencimento e os pagamentos, inclusive no dia exato do fechamento, no dia
  exato do vencimento e com pagamento maior que o total.
- **SC-005**: Em 100% dos testes, o devido do resumo é igual ao saldo negativo do cartão na consulta de saldos
  da spec 002, e o limite disponível é o limite menos o devido.
- **SC-006**: Em 100% dos testes de isolamento, consultar fatura, resumo e lista de uma conta de outro workspace
  recebe a mesma resposta de um workspace inexistente, e nenhum dado é revelado; os lançamentos de um workspace
  nunca entram em soma de outro.
- **SC-007**: Em 100% dos testes de permissão, OWNER, EDITOR e VIEWER conseguem consultar.
- **SC-008**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de acesso a dados de outro workspace continuam recusadas pela outra camada.
- **SC-009**: A consulta de uma fatura, do resumo e de uma lista de 36 meses, num workspace com 20.000
  lançamentos, responde em menos de 1 segundo cada.
- **SC-010**: Em 100% dos testes de validação, meses, intervalos, contas que não são cartão e cartões sem dias
  são recusados indicando o campo ou o motivo, e nada é alterado.
- **SC-011**: Em 100% dos testes, nenhuma consulta altera lançamentos, contas ou categorias.

## Assumptions

- Esta funcionalidade é só de backend e só de leitura; as telas de fatura fazem parte do roadmap de front-end.
  Fecha, no backend, a parte "fatura do cartão" que a constituição (princípio V) e a spec 002 deixaram
  reservada.
- A fatura é identificada pelo mês do vencimento (a "fatura de novembro" é a que vence em novembro), porque é
  assim que a pessoa a chama.
- A compra feita no dia do fechamento entra na fatura que fecha naquele dia. Alguns bancos tratam esse dia como
  da fatura seguinte; aqui vale a regra mais simples e previsível. Se o cartão real se comportar diferente, a
  pessoa ajusta o dia de fechamento cadastrado.
- Os pagamentos são casados com a fatura apenas por data (depois do fechamento dela, até o fechamento da
  seguinte). Um pagamento feito antes do fechamento (adiantado) ou depois do fechamento da fatura seguinte não
  conta para ela: é uma simplificação, e o devido do resumo continua sempre correto porque vem do saldo.
- Estorno é uma receita lançada na conta do cartão; reduz o total da fatura do período em que cai.
- O sistema não guarda o histórico dos dias de fechamento e de vencimento: mudar os dias recalcula todas as
  faturas, inclusive as passadas.
- Compras parceladas, juros, multa, IOF, pagamento mínimo, limite por fatura e fatura "em aberto de
  verdade" (com saldo transportado) ficam fora desta funcionalidade.
- Moeda única (BRL); todo lançamento conta como efetivado.
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `002-contas` (cartão, dias de
  fechamento e vencimento, saldo) e da `004-lancamentos` (despesas, receitas, transferências e exclusão
  lógica), das definições de mês da `005-dashboard-mensal`, além das regras da constituição (v1.0.0), em
  especial os princípios II, IV, V e VI.
