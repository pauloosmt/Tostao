# Feature Specification: Dashboard mensal

**Feature Branch**: `005-dashboard-mensal`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Painel de um workspace, somente leitura, por mês ou por semana (a pessoa escolhe um dos dois, para acompanhar o mês inteiro ou semana a semana). Para o período escolhido (padrão: o mês corrente) mostra o total de receitas, o total de despesas e o resultado (receitas menos despesas), em centavos inteiros; receitas e despesas agrupadas por categoria principal, com o total de cada subcategoria dentro dela e o percentual sobre o total do tipo, do maior para o menor; a evolução diária (receita e despesa por dia do mês); a comparação com o período anterior, o mês ou a semana anterior (variação em centavos e em percentual); e os saldos por conta e o total: o de hoje (cálculo da spec 002), o de abertura e o de fechamento do período, e o saldo ao fim de cada dia. Transferências não entram nos totais nem nos grupos por categoria; lançamentos excluídos não contam; contas e categorias arquivadas continuam contando nos lançamentos antigos; lançamento sem categoria aparece num grupo 'Sem categoria'. O mês e a semana vêm da data de ocorrência, sem fuso. A semana vai de segunda a domingo. Mês ou semana inválidos, fora do intervalo aceito (2000 a 2100) ou pedidos juntos são recusados. OWNER, EDITOR e VIEWER leem; isolamento por workspace nas duas camadas; não-membro recebe 'não encontrado'. Todos os números vêm de uma única leitura consistente. Com 20.000 lançamentos, responde em menos de 1 segundo. Sem novas gravações. Segue o padrão das specs 002-contas, 003-categorias e 004-lancamentos."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver o resumo do mês (Priority: P1)

A pessoa abre o painel e vê, para o mês escolhido, quanto entrou, quanto saiu e quanto sobrou: total de
receitas, total de despesas e o resultado (receitas menos despesas). Sem escolher mês, vê o mês corrente.
Transferências entre contas não contam: elas só movem dinheiro de uma conta para outra.

**Why this priority**: é a pergunta central do produto ("como foi meu mês?") e o critério de pronto da
fase 1: a pessoa lança despesas reais por um mês e enxerga o resultado dele.

**Independent Test**: com receitas de R$ 5.000,00 e R$ 200,00, despesas de R$ 1.200,50 e R$ 99,50 e uma
transferência de R$ 300,00 no mesmo mês, conferir receitas R$ 5.200,00, despesas R$ 1.300,00 e resultado
R$ 3.900,00, e que a transferência não alterou nenhum dos três.

**Acceptance Scenarios**:

1. **Given** lançamentos de receita e de despesa no mês, **When** a pessoa consulta o painel desse mês,
   **Then** vê o total de receitas, o total de despesas (ambos como valores positivos, em centavos) e o
   resultado igual às receitas menos as despesas (negativo quando gastou mais do que ganhou).
2. **Given** transferências no mês, **When** a pessoa consulta o painel, **Then** elas não entram em
   receitas, despesas nem resultado.
3. **Given** um lançamento excluído no mês, **When** a pessoa consulta o painel, **Then** ele não conta.
4. **Given** lançamentos em contas ou categorias arquivadas, **When** a pessoa consulta o painel, **Then**
   eles contam normalmente.
5. **Given** lançamentos no primeiro e no último dia do mês, e no último dia do mês anterior e no primeiro
   do seguinte, **When** a pessoa consulta o mês, **Then** só os do primeiro ao último dia dele contam, com
   os dois limites incluídos.
6. **Given** nenhum mês informado, **When** a pessoa consulta o painel, **Then** recebe o mês corrente.
7. **Given** um mês sem nenhum lançamento, **When** a pessoa consulta, **Then** recebe totais zerados (não um
   erro) e a evolução diária com todos os dias em zero.
8. **Given** a soma de valores como R$ 0,10 e R$ 0,20, **When** o painel os totaliza, **Then** o total é
   exatamente R$ 0,30.

---

### User Story 2 - Saber para onde o dinheiro foi, por categoria (Priority: P1)

A pessoa vê as despesas do mês agrupadas por categoria principal, da maior para a menor, com o total de cada
subcategoria dentro da principal e o percentual de cada grupo sobre o total de despesas. Vê o mesmo para as
receitas.

**Why this priority**: o total sozinho não diz o que mudar. O agrupamento por categoria é o que transforma
o extrato em decisão (por exemplo, "Alimentação levou 32% do mês").

**Independent Test**: lançar despesas em Alimentação (e nas subcategorias dela), Transporte e Lazer,
consultar o painel e conferir os totais de cada grupo e subgrupo, a ordem e os percentuais.

**Acceptance Scenarios**:

1. **Given** despesas em categorias diferentes, **When** a pessoa consulta o painel, **Then** vê um grupo por
   categoria principal usada no mês, com o total do grupo, ordenados do maior para o menor total (empate:
   por nome).
2. **Given** uma categoria principal com subcategorias, **When** a pessoa consulta, **Then** o total do grupo
   é a soma do que foi lançado direto na principal com o de todas as subcategorias, e cada subcategoria usada
   aparece dentro do grupo com o próprio total, também do maior para o menor.
3. **Given** os grupos de um tipo, **When** a pessoa consulta, **Then** cada grupo e cada subcategoria trazem
   o percentual sobre o total daquele tipo (despesas sobre o total de despesas, receitas sobre o total de
   receitas), com duas casas decimais.
4. **Given** receitas e despesas no mês, **When** a pessoa consulta, **Then** os grupos de receita e os de
   despesa vêm separados, cada um com o seu total.
5. **Given** uma categoria sem lançamentos no mês, **When** a pessoa consulta, **Then** ela não aparece.
6. **Given** uma categoria arquivada com lançamentos no mês, **When** a pessoa consulta, **Then** ela aparece
   normalmente, no grupo da sua principal.
7. **Given** um lançamento de receita ou despesa sem categoria, **When** a pessoa consulta, **Then** ele
   aparece num grupo chamado "Sem categoria" do tipo dele, e entra no total do tipo e nos percentuais como
   qualquer outro grupo.
8. **Given** um mês sem despesas (ou sem receitas), **When** a pessoa consulta, **Then** a lista de grupos
   daquele tipo vem vazia e os percentuais não dividem por zero.
9. **Given** a soma dos totais de todos os grupos de um tipo, **When** comparada ao total do tipo, **Then**
   é exatamente igual, em centavos.

---

### User Story 3 - Acompanhar a evolução e comparar com o mês anterior (Priority: P2)

A pessoa vê dia a dia do mês quanto entrou e quanto saiu, e compara os totais do mês com os do mês anterior:
quanto aumentou ou diminuiu, em centavos e em percentual.

**Why this priority**: mostra o ritmo do mês (gastos concentrados no início, por exemplo) e se o mês está
melhor ou pior que o anterior. Agrega bastante, mas os totais e as categorias já respondem o essencial.

**Independent Test**: com lançamentos em dias espalhados de dois meses seguidos, conferir os valores de cada
dia, a presença dos dias sem movimento e a variação de cada total em relação ao mês anterior.

**Acceptance Scenarios**:

1. **Given** um mês, **When** a pessoa consulta, **Then** a evolução traz um item para cada dia do mês (28,
   29, 30 ou 31), na ordem do calendário, com a receita e a despesa do dia, e zero nos dias sem movimento.
2. **Given** a evolução diária, **When** somada, **Then** a soma das receitas dos dias é igual ao total de
   receitas do mês, e a das despesas, ao total de despesas.
3. **Given** o mês anterior, **When** a pessoa consulta, **Then** recebe, para receitas, despesas e
   resultado: o valor do mês anterior, a variação em centavos (mês escolhido menos o anterior) e a variação
   em percentual sobre o valor absoluto do mês anterior, com duas casas decimais.
4. **Given** um total do mês anterior igual a zero, **When** a pessoa consulta, **Then** a variação em
   centavos é calculada normalmente e a variação em percentual vem como "não aplicável" (vazia), sem erro.
5. **Given** o primeiro mês aceito (janeiro de 2000), **When** a pessoa consulta, **Then** a comparação vem
   zerada, como mês anterior sem lançamentos, sem erro.
6. **Given** fevereiro de ano bissexto e de ano comum, **When** a pessoa consulta, **Then** a evolução traz
   29 e 28 dias, respectivamente.

---

### User Story 4 - Acompanhar semana a semana (Priority: P2)

Em vez do mês inteiro, a pessoa escolhe uma semana (de segunda a domingo) e vê tudo o que o painel mensal
mostra, só que para aqueles sete dias: receitas, despesas e resultado, grupos por categoria, evolução dia a
dia e comparação com a semana anterior. Assim acompanha o ritmo de gastos semana a semana, sem esperar o mês
fechar. Uma semana pode atravessar dois meses, e o painel dela conta só os sete dias, onde quer que caiam.

**Why this priority**: o mês inteiro chega tarde para corrigir o rumo; a semana mostra cedo se os gastos
estão fugindo do ritmo. Reaproveita todo o cálculo do painel mensal, mudando só o intervalo de datas.

**Independent Test**: com lançamentos espalhados por duas semanas seguidas (uma delas atravessando a virada
do mês), consultar cada semana e conferir totais, grupos, os sete dias e a variação em relação à semana
anterior.

**Acceptance Scenarios**:

1. **Given** lançamentos em várias semanas, **When** a pessoa consulta uma semana, **Then** os totais de
   receitas, despesas e resultado, os grupos por categoria e as subcategorias contam só os lançamentos de
   segunda a domingo daquela semana, ambos os dias incluídos, pelas mesmas regras do painel mensal
   (transferências fora, excluídos fora, arquivadas contam, "Sem categoria", percentuais).
2. **Given** uma semana, **When** a pessoa consulta, **Then** a evolução traz sete itens, de segunda a
   domingo, com a receita e a despesa de cada dia, e a soma dos dias é igual aos totais da semana.
3. **Given** uma semana que atravessa a virada do mês (por exemplo, de 26/10 a 01/11), **When** a pessoa
   consulta, **Then** o painel conta os sete dias, dos dois meses, e mostra o período com as datas de início
   e de fim.
4. **Given** a semana anterior, **When** a pessoa consulta, **Then** recebe a comparação com ela (valor
   anterior, variação em centavos e em percentual, vazio quando o anterior é zero), com as mesmas regras da
   comparação mensal.
5. **Given** uma semana sem lançamentos, **When** a pessoa consulta, **Then** recebe totais zerados e os sete
   dias em zero, sem erro.
6. **Given** a consulta por semana, **When** a pessoa a faz, **Then** os saldos das contas e o total são os
   mesmos da consulta mensal: o saldo de hoje, não o do fim da semana.
7. **Given** uma semana mal escrita, inexistente (por exemplo, a semana 53 de um ano que só tem 52) ou fora do
   intervalo aceito, **When** a pessoa consulta, **Then** a consulta é recusada indicando o campo da semana.
8. **Given** mês e semana informados ao mesmo tempo, **When** a pessoa consulta, **Then** a consulta é
   recusada indicando que só um dos dois pode ser pedido.
9. **Given** nenhuma semana informada, **When** a pessoa consulta, **Then** o painel é o do mês, como antes:
   a visão semanal só existe quando é pedida.

---

### User Story 5 - Ver os saldos no painel: o de hoje e o do período (Priority: P2)

No mesmo painel a pessoa vê o saldo atual de cada conta ativa e o saldo total do workspace, e também como
o dinheiro estava no começo e no fim do período escolhido, e dia a dia: saldo de abertura (antes do primeiro
dia), saldo de fechamento (ao fim do último dia) e o saldo total ao fim de cada dia. Assim, no
acompanhamento semana a semana, ela vê com quanto entrou e com quanto saiu de cada semana.

**Why this priority**: junta a foto do período com a foto do patrimônio. O saldo atual já existe em
consulta própria (spec 002) e aqui só é reunido; o saldo de abertura, o de fechamento e o diário são
leituras novas, calculadas pela mesma regra, mas numa data de corte.

**Independent Test**: conferir que o saldo atual do painel é idêntico, conta a conta e no total, ao da
consulta de saldos da spec 002; e que, com lançamentos conhecidos, a abertura, o fechamento e o saldo de
cada dia batem com a conta feita à mão.

**Acceptance Scenarios**:

1. **Given** contas com lançamentos, **When** a pessoa consulta o painel, **Then** vê o saldo atual de cada
   conta ativa e o total, calculados pela mesma regra da spec 002 (saldo inicial mais lançamentos não
   excluídos, de qualquer data).
2. **Given** um mês ou uma semana no passado, **When** a pessoa consulta, **Then** o saldo atual mostra a
   situação de hoje, e o saldo de abertura e o de fechamento mostram a situação nas datas do período.
3. **Given** um período, **When** a pessoa consulta, **Then** o saldo de abertura de cada conta ativa é o
   saldo inicial somado aos lançamentos não excluídos com data anterior ao primeiro dia do período, e o de
   fechamento é o saldo inicial somado aos lançamentos não excluídos com data até o último dia, inclusive.
   O total de cada um segue a mesma regra do saldo total da spec 002.
4. **Given** um período, **When** a pessoa consulta, **Then** cada dia da evolução traz o saldo total ao fim
   daquele dia, e o saldo do último dia é igual ao saldo de fechamento; o do dia anterior ao primeiro é o
   de abertura.
5. **Given** uma semana ou um mês que termina no futuro, **When** a pessoa consulta, **Then** o fechamento e
   os saldos diários contam também lançamentos com data futura dentro do período (todo lançamento conta como
   efetivado), e a resposta não distingue o que já passou.
6. **Given** uma transferência entre duas contas ativas dentro do período, **When** a pessoa consulta,
   **Then** o saldo de cada conta muda no dia dela e o saldo total do dia não muda.
7. **Given** uma conta arquivada, **When** a pessoa consulta, **Then** ela segue a regra da spec 002 para
   contas arquivadas (fica fora do saldo total e da lista de saldos), também nos saldos de abertura, de
   fechamento e diários.
8. **Given** um lançamento excluído, **When** a pessoa consulta, **Then** ele não conta em nenhum saldo,
   inclusive nos de datas passadas.
9. **Given** uma data de corte antes de qualquer lançamento, **When** a pessoa consulta, **Then** o saldo é o
   saldo inicial das contas.

---

### User Story 6 - Só quem é do workspace vê, e só o dele (Priority: P1)

OWNER, EDITOR e VIEWER consultam o painel. Quem não é membro do workspace recebe a mesma resposta de um
workspace que não existe. O painel nunca mostra números de outro workspace.

**Why this priority**: o painel concentra o resumo financeiro inteiro; um vazamento aqui é o mais grave. A
constituição exige o teste de acesso cruzado em toda rota de dados.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER no workspace da
primeira, consultar o painel de cada um e conferir respostas e números.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela consulta o painel dele, **Then** recebe
   "não encontrado", igual à resposta de um workspace inexistente, sem revelar nada.
2. **Given** uma pessoa com o papel VIEWER, EDITOR ou OWNER, **When** consulta o painel, **Then** vê o painel
   completo.
3. **Given** dois workspaces com lançamentos, **When** cada um consulta o seu painel, **Then** os números de
   um nunca incluem lançamentos, contas ou categorias do outro.
4. **Given** alguém sem sessão, **When** tenta consultar o painel, **Then** é recusado por falta de
   autenticação.
5. **Given** uma tentativa de alterar dados pelo painel (qualquer método que não seja de leitura), **When**
   enviada, **Then** é recusada e nada muda.

---

### User Story 7 - Pedir um mês inválido (Priority: P3)

A pessoa informa um mês mal escrito ou fora do intervalo aceito e recebe um erro claro, em vez de um painel
errado ou de uma falha interna. O mesmo vale para a semana (ver a história da visão semanal).

**Why this priority**: protege contra erro de digitação e de integração, mas não é o caminho principal.

**Independent Test**: pedir meses malformados, inexistentes e fora do intervalo e conferir a recusa com o
campo indicado.

**Acceptance Scenarios**:

1. **Given** um mês que não seja no formato ano-mês (por exemplo "2026-13", "2026-1", "novembro", "2026-11-05"
   ou vazio), **When** a pessoa consulta, **Then** a consulta é recusada indicando o campo do mês.
2. **Given** um mês anterior a 2000-01 ou posterior a 2100-12, **When** a pessoa consulta, **Then** a consulta
   é recusada indicando o campo do mês.
3. **Given** um mês válido sem nenhum lançamento, **When** a pessoa consulta, **Then** recebe o painel zerado
   (não é erro).

---

### Edge Cases

- Mês definido pela data de ocorrência do lançamento (só a data, sem horário nem fuso): um lançamento do
  dia 30 às 23h59 continua sendo do dia 30.
- Mês corrente, quando não informado, é o da data de hoje no fuso de Brasília; perto da meia-noite, o
  servidor nunca devolve um mês diferente do que a pessoa vê no calendário dela no Brasil.
- Lançamentos com data futura dentro do mês contam normalmente nos totais, nos grupos e na evolução, e nos
  saldos (a spec 004 considera todo lançamento efetivado).
- Transferência com uma ponta em conta arquivada e a outra em conta ativa: continua ignorada nos totais.
- Subcategoria e principal de nomes iguais a "Sem categoria": o grupo "Sem categoria" é identificado como
  grupo sem categoria (sem identificador de categoria), e não se confunde com uma categoria real.
- Receita ou despesa com valor máximo (R$ 10 bilhões) em volume grande: os totais continuam exatos, sem
  estouro.
- Empate de totais entre grupos: ordem estável, por nome e depois por identificador, para a resposta ser
  igual em consultas repetidas.
- Parâmetro de mês ou de semana repetido, ou campos extras na consulta: recusados ou ignorados de forma
  previsível, sem erro interno.
- Semana que atravessa a virada de mês ou de ano (por exemplo, de 29/12 a 04/01): conta os sete dias, sem
  separar por mês; a numeração da semana segue o padrão ISO 8601 (semana 1 é a que contém a primeira
  quinta-feira do ano), então o ano da semana pode diferir do ano das datas.
- Semana cujos dias caem em parte fora do intervalo de datas aceito nos lançamentos (2000-01-01 a
  2100-12-31): é aceita e os dias sem lançamento possível contam zero.
- Pedir mês e semana juntos: recusado, para não haver dois períodos concorrentes na mesma resposta.
- Workspace sem contas ou sem lançamentos: painel zerado, sem erro.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST oferecer uma consulta de painel, somente leitura, sob o workspace, para um
  período que pode ser um mês (formato ano-mês) ou uma semana (formato de semana ISO, ano e número da
  semana). A pessoa MUST poder pedir um dos dois, nunca os dois juntos. Sem nenhum dos dois, MUST usar o mês
  corrente na data de hoje no fuso de Brasília.
- **FR-002**: O sistema MUST aceitar meses de 2000-01 a 2100-12 e semanas ISO cujo ano esteja entre 2000 e
  2100, e MUST recusar, indicando o campo do mês ou da semana, o que estiver em outro formato, for um mês ou
  uma semana inexistente (como a semana 53 de um ano com 52) ou estiver fora desse intervalo. Pedir mês e
  semana ao mesmo tempo MUST ser recusado indicando os campos.
- **FR-003**: O painel MUST trazer o total de receitas, o total de despesas e o resultado do período, todos
  em centavos inteiros. Receitas e despesas MUST sair como valores positivos; o resultado MUST ser receitas
  menos despesas e pode ser negativo.
- **FR-004**: Um lançamento MUST pertencer ao período pela sua data de ocorrência (só a data, sem fuso). O
  mês vai do primeiro ao último dia do mês; a semana vai da segunda-feira ao domingo, numerada pelo padrão
  ISO 8601. Os dois limites MUST ser incluídos. A resposta MUST informar o tipo do período (mês ou semana) e
  as datas de início e de fim dele.
- **FR-005**: Transferências MUST NÃO entrar nos totais, nos grupos por categoria nem na evolução diária.
  Lançamentos excluídos logicamente MUST NÃO contar em nenhum número do painel. Lançamentos em contas ou
  categorias arquivadas MUST contar normalmente.
- **FR-006**: O painel MUST trazer, separadamente para receitas e para despesas, os grupos por categoria
  principal usados no período. Cada grupo MUST trazer a categoria, o total (a soma do lançado direto na principal
  com o de todas as suas subcategorias) e o percentual sobre o total do tipo; e as subcategorias usadas no
  período dentro dele, cada uma com categoria, total e percentual sobre o total do tipo.
- **FR-007**: Grupos e subcategorias MUST vir ordenados do maior para o menor total, com desempate estável
  por nome e depois por identificador. Categorias sem lançamento no período MUST NÃO aparecer.
- **FR-008**: Lançamentos de receita ou despesa sem categoria MUST aparecer num grupo "Sem categoria" do
  tipo, sem identificador de categoria, contando no total do tipo e nos percentuais. O grupo MUST aparecer
  só se existirem tais lançamentos no período.
- **FR-009**: Os percentuais MUST ser calculados sobre o total do tipo, com duas casas decimais, e MUST NÃO
  falhar quando o total do tipo for zero (nesse caso não há grupos). A soma dos totais dos grupos de um tipo
  MUST ser exatamente igual ao total do tipo, em centavos.
- **FR-010**: A evolução diária MUST trazer um item para cada dia do período (os dias do mês, ou os sete da
  semana), em ordem de calendário, com a receita e a despesa do dia (zero nos dias sem movimento) e o saldo
  total ao fim do dia (FR-012). A soma das receitas e a das despesas dos dias MUST ser igual aos totais do
  período.
- **FR-011**: A comparação com o período anterior (o mês anterior, ou a semana anterior) MUST trazer, para
  receitas, despesas e resultado: o valor do período anterior, a variação em centavos (período escolhido
  menos período anterior) e a variação em percentual sobre o valor absoluto do período anterior, com duas
  casas decimais. Quando o valor do período anterior for zero, o percentual MUST vir vazio ("não
  aplicável"). O período anterior MUST ser calculado do mesmo jeito, mesmo quando o que o precede está fora
  do intervalo de datas aceito nos lançamentos (nesse caso ele só não tem lançamentos).
- **FR-012**: O painel MUST trazer o saldo atual de cada conta ativa e o saldo total do workspace, pela mesma
  regra da spec 002 (saldo inicial mais os lançamentos não excluídos, de qualquer data), independentemente
  do mês ou da semana escolhidos, e esses números MUST ser idênticos aos da consulta de saldos da spec 002.
  O painel MUST trazer também, pela mesma regra e só para contas ativas: o saldo de abertura (saldo inicial
  mais lançamentos não excluídos com data anterior ao primeiro dia do período) e o saldo de fechamento
  (saldo inicial mais lançamentos não excluídos com data até o último dia, inclusive), de cada conta e no
  total; e o saldo total ao fim de cada dia do período. O saldo do último dia MUST ser igual ao de
  fechamento. Lançamentos com data futura dentro do período contam, e o saldo MUST NUNCA ser guardado: é
  calculado a cada consulta.
- **FR-013**: Todos os números do painel (totais, grupos, evolução, comparação e saldos) MUST vir de uma
  única leitura consistente: uma consulta nunca mistura dados de antes e de depois de uma gravação feita por
  outra pessoa ao mesmo tempo.
- **FR-014**: Todas as operações do painel MUST ser somente de leitura: o sistema MUST NÃO criar, alterar nem
  apagar nenhum dado ao consultá-lo, e MUST NÃO oferecer outro método além da consulta.
- **FR-015**: Os papéis OWNER, EDITOR e VIEWER MUST poder consultar o painel.
- **FR-016**: A consulta MUST ficar sob o identificador do workspace e MUST conferir que a pessoa autenticada
  é membro dele. Para quem não é membro, ou para workspace inexistente, a resposta MUST ser a mesma de "não
  encontrado", sem revelar nada.
- **FR-017**: Cada número do painel MUST considerar somente dados do workspace consultado; contas e
  categorias de outro workspace MUST NUNCA entrar em nenhuma soma.
- **FR-018**: O isolamento por workspace do painel MUST existir nas duas camadas independentes exigidas pela
  constituição (camada de dados e camada de serviço) e MUST ter teste automatizado de acesso cruzado.
- **FR-019**: As respostas MUST usar o formato de saída próprio do painel e, em erro, o formato padronizado
  do projeto, sem vazar detalhes internos.
- **FR-020**: As respostas autenticadas MUST instruir navegadores e intermediários a não guardá-las em cache.

### Key Entities *(include if feature involves data)*

- **Painel**: visão calculada de um workspace para um período, mês ou semana; não é guardada. Reúne totais,
  grupos por categoria, evolução diária, comparação com o período anterior e saldos.
- **Período**: um mês (do dia 1 ao último) ou uma semana ISO (de segunda a domingo), com datas de início e de
  fim.
- **Totais do período**: receitas, despesas e resultado, em centavos.
- **Grupo de categoria**: categoria principal (ou "Sem categoria") com total, percentual e subcategorias
  usadas, de um dos tipos (receita ou despesa).
- **Dia da evolução**: data, receita do dia, despesa do dia e saldo total ao fim do dia.
- **Saldos do período**: por conta ativa e no total, o saldo atual (de hoje), o de abertura e o de
  fechamento do período; sempre calculados, nunca guardados.
- **Comparação**: para cada total, valor do período anterior, variação em centavos e variação em percentual.
- **Lançamento, Conta e Categoria** (specs 004, 002 e 003): as fontes dos números; o painel só os lê.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes, receitas, despesas e resultado do mês e da semana batem, em centavos
  exatos, com a soma dos lançamentos não excluídos de receita e despesa daquele período, depois de qualquer
  sequência de
  criações, edições e exclusões (inclusive somas como R$ 0,10 + R$ 0,20 = R$ 0,30).
- **SC-002**: Em 100% dos testes, transferências (criadas, editadas ou excluídas) não alteram nenhum total,
  grupo ou dia do painel, mas alteram os saldos das contas envolvidas e mantêm o saldo total.
- **SC-003**: Em 100% dos testes, a soma dos totais dos grupos de cada tipo é igual ao total do tipo e a soma
  dos dias da evolução é igual aos totais do período (mês ou semana), em centavos exatos.
- **SC-004**: Em 100% dos testes, o painel e uma contagem independente feita no teste (por categoria, por dia
  e por período anterior) coincidem, para mês e para semana, em dados gerados aleatoriamente com semente
  fixa, incluindo lançamentos nos limites do período, em categorias arquivadas e sem categoria.
- **SC-005**: Em 100% dos testes, os saldos atuais do painel são idênticos aos da consulta de saldos da
  spec 002.
- **SC-013**: Em 100% dos testes, o saldo de abertura, o de fechamento e o saldo ao fim de cada dia batem,
  em centavos exatos, com uma conta independente feita no teste (saldo inicial mais lançamentos não
  excluídos até a data de corte), para mês e para semana, em dados aleatórios com semente fixa; o saldo do
  último dia é igual ao de fechamento; e uma transferência entre contas ativas nunca muda o saldo total de
  nenhum dia.
- **SC-006**: Em 100% dos testes de isolamento, tentativas de consultar o painel de um workspace do qual a
  pessoa não é membro recebem a mesma resposta de um workspace inexistente, e os números de um workspace
  nunca incluem dados de outro.
- **SC-007**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de ler dados de outro workspace pelo painel continuam recusadas ou vazias pela outra camada.
- **SC-008**: O painel de um workspace com 20.000 lançamentos responde em menos de 1 segundo, para o mês
  corrente, para um mês qualquer e para uma semana qualquer.
- **SC-009**: Em 100% dos testes de consistência, uma consulta feita enquanto outra pessoa grava lançamentos
  nunca devolve números que contradigam entre si (por exemplo, soma dos grupos diferente do total).
- **SC-010**: Em 100% dos testes, consultar o painel não altera nenhum dado do workspace.
- **SC-011**: Em 100% dos testes de validação, meses malformados, inexistentes ou fora de 2000-01..2100-12
  são recusados indicando o campo, e um mês válido sem lançamentos devolve o painel zerado.
- **SC-012**: Em 100% dos testes da visão semanal, semanas mal escritas, inexistentes ou fora do intervalo
  são recusadas indicando o campo da semana; mês e semana pedidos juntos são recusados; semanas que
  atravessam a virada de mês e de ano (como de 29/12 a 04/01) contam exatamente os sete dias; e uma semana
  válida sem lançamentos devolve o painel zerado com sete dias.

## Assumptions

- Esta funcionalidade é só de backend (API de leitura); a tela do dashboard faz parte do roadmap de
  front-end. Nenhuma tabela nova é gravada: o painel só lê lançamentos, contas e categorias.
- O painel mostra o workspace inteiro, sem filtro por conta nem por categoria nesta funcionalidade.
- Despesas saem como valor positivo no painel (é o "quanto saiu"); o sinal negativo guardado na spec 004
  aparece só nos lançamentos individuais. O resultado é receitas menos despesas.
- Mês corrente usa o fuso de Brasília porque o produto é só em BRL e para o Brasil; o workspace ainda não
  tem fuso próprio.
- Percentual com duas casas decimais, arredondado de forma comum (metade para cima); por isso a soma dos
  percentuais de um tipo pode diferir de 100% em centésimos. Os totais em centavos nunca são arredondados.
- A semana vai de segunda a domingo e é numerada pelo padrão ISO 8601 (semana 1 contém a primeira quinta-feira
  do ano), pedida no formato ano-W-número (por exemplo, 2026-W45). Foi escolhida a segunda-feira como início
  por ser o padrão internacional; se a pessoa preferir domingo, é uma mudança de regra a decidir antes do
  plano. Quem quer a semana corrente envia o identificador dela; só o mês tem padrão no servidor.
- A visão semanal mostra os mesmos blocos da mensal (totais, grupos, evolução diária de sete dias, comparação
  com a semana anterior e saldos); não há visão por quinzena, trimestre ou intervalo livre nesta
  funcionalidade.
- A variação percentual usa o valor absoluto do período anterior como base, para o sinal da variação sempre
  indicar o sentido da mudança (subiu ou desceu), inclusive no resultado, que pode ser negativo.
- O saldo atual do painel é o de hoje, de qualquer data de lançamento, como na spec 002. Os saldos de
  abertura, de fechamento e diário são o mesmo cálculo com data de corte; não exigem tabela nem coluna nova,
  e não há histórico guardado.
- A conta não tem data de abertura: o saldo inicial vale em qualquer data de corte, mesmo antes de a conta
  ter sido cadastrada. Contas arquivadas ficam fora dos saldos do painel (como no saldo total da spec 002),
  considerando o estado de hoje, não o da época.
- O saldo do dia não distingue passado de futuro: um lançamento com data futura dentro do período já entra,
  porque o produto trata todo lançamento como efetivado até existir o estado "pendente" (fase 2).
- Orçamento e meta por semana (ou por mês) não fazem parte desta funcionalidade, porque exigem cadastro e
  gravação de dados e esta funcionalidade só lê. Eles são da fase 2 (orçamento e metas); registrei que a
  periodicidade semanal deve ser prevista no desenho deles, e a visão semanal e os totais por categoria daqui
  são a base para comparar o realizado com o planejado.
- O grupo "Sem categoria" existe porque o banco aceita receita ou despesa sem categoria (importação da fase
  3); pelo serviço da spec 004 toda receita e despesa tem categoria, então hoje o grupo costuma não aparecer.
- Moeda única (BRL). Gráficos, exportação, orçamento e metas (fases 2 a 4) e o estado "pendente" do
  lançamento ficam fora desta funcionalidade; todo lançamento conta como efetivado.
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `002-contas` (saldo calculado),
  da `003-categorias` (hierarquia de categorias) e da `004-lancamentos` (lançamentos, transferência como
  duas pontas, exclusão lógica), além das regras da constituição (v1.0.0), em especial os princípios II, IV
  e V.
