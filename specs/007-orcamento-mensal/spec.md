# Feature Specification: Orçamento mensal

**Feature Branch**: `007-orcamento-mensal`

**Created**: 2026-11-08

**Status**: Draft

**Input**: User description: "Orçamento mensal por categoria dentro de um workspace, para acompanhar os gastos mês a mês. A pessoa define, para um mês (formato AAAA-MM, o mesmo da spec 005), um limite de gasto em centavos para uma categoria de despesa (principal ou subcategoria) ou um limite geral do mês. O sistema mostra, para o mês, cada limite com quanto já foi gasto, quanto resta, o percentual usado e o estado (dentro do limite, atenção ou estourado), mais o total gasto e o quanto foi gasto fora de qualquer limite. A pessoa pode ajustar o valor de um limite, remover um limite e copiar os limites de um mês para os meses seguintes (só onde ainda não há limite). O gasto vem dos lançamentos da spec 004: só despesas, sem transferências, sem excluídos, contas e categorias arquivadas contam nos lançamentos antigos. A regra de gasto, de percentual e de estado é a mesma da spec 006. Papéis: OWNER e EDITOR escrevem, VIEWER só lê; isolamento por workspace em duas camadas (RLS + filtro). Rotas sob /api/v1/workspaces/{wid}/monthly-budgets. Segue o padrão das specs 002-contas, 003-categorias, 004-lancamentos, 005-dashboard-mensal e 006-orcamento-semanal."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Definir quanto posso gastar no mês (Priority: P1)

A pessoa escolhe um mês e define um limite de gasto: para uma categoria de despesa (por exemplo,
"Alimentação" com R$ 1.600,00) ou um limite geral do mês (por exemplo, R$ 6.000,00 no total). Cada limite
vale para um único mês e uma única categoria (ou o geral).

**Why this priority**: sem limites não há o que acompanhar. É o ponto de partida do planejamento mensal, que
é o ritmo em que a maioria das pessoas pensa em dinheiro (salário, aluguel, contas fixas).

**Independent Test**: definir R$ 1.600,00 para Alimentação e R$ 6.000,00 de limite geral no mês 2026-11,
consultar o mês e conferir que os dois limites aparecem com os valores certos.

**Acceptance Scenarios**:

1. **Given** uma categoria de despesa ativa e um mês válido, **When** a pessoa define um limite de
   R$ 1.600,00, **Then** o limite é criado para essa categoria nesse mês e aparece na consulta do mês.
2. **Given** nenhuma categoria informada, **When** a pessoa define um limite, **Then** ele é o limite geral
   do mês, que vale para todas as despesas dele.
3. **Given** um limite já existente para aquela categoria e mês, **When** a pessoa define outro valor,
   **Then** o valor é substituído (nunca nascem dois limites para o mesmo escopo e mês).
4. **Given** uma categoria principal, **When** a pessoa define um limite, **Then** ele vale para os gastos
   lançados direto nela e para os de todas as suas subcategorias; um limite de subcategoria vale só para os
   gastos dela. Os dois podem existir ao mesmo tempo, independentes.
5. **Given** uma categoria de receita, **When** a pessoa tenta definir um limite, **Then** a operação é
   recusada indicando o campo da categoria: só categorias de despesa têm limite.
6. **Given** uma categoria arquivada, **When** a pessoa tenta definir um limite novo ou alterar o valor de
   um existente nela, **Then** a operação é recusada e nada muda.
7. **Given** uma categoria de outro workspace ou inexistente, **When** a pessoa tenta usá-la, **Then**
   recebe "não encontrado" e nada é criado.
8. **Given** valor negativo, com casas decimais além dos centavos, acima do limite ou ausente, ou um mês
   mal escrito, inexistente ou fora de 2000-01 a 2100-12, **When** a pessoa tenta definir o limite,
   **Then** a operação é recusada indicando o campo e nada é criado.
9. **Given** um valor zero, **When** a pessoa o define, **Then** é aceito: significa "não quero gastar nada
   nessa categoria neste mês".
10. **Given** um limite criado, **When** a pessoa o consulta, **Then** vê quem o criou e as datas de criação
    e de última alteração.
11. **Given** limites semanais já definidos na spec 006 para semanas desse mesmo mês, **When** a pessoa
    define um limite mensal, **Then** os dois coexistem sem se alterar: o limite mensal não depende dos
    semanais nem os modifica.

---

### User Story 2 - Acompanhar o mês contra os limites (Priority: P1)

A pessoa abre um mês e vê, para cada limite, quanto já gastou, quanto resta, que percentual do limite foi
usado e em que estado está: dentro do limite, em atenção (80% ou mais) ou estourado. Vê também o total gasto
no mês e o que foi gasto fora de qualquer limite.

**Why this priority**: é o motivo de existir do orçamento: saber, durante o mês, se está no ritmo.

**Independent Test**: com limites definidos e despesas lançadas no mês, conferir gasto, restante,
percentual e estado de cada limite, e o gasto fora de limite.

**Acceptance Scenarios**:

1. **Given** um limite de R$ 1.600,00 para Alimentação e despesas de R$ 400,00 e R$ 200,00 nela, **When** a
   pessoa consulta o mês, **Then** o limite mostra gasto R$ 600,00, restante R$ 1.000,00, 37,50% usado e o
   estado "dentro do limite".
2. **Given** um gasto entre 80% e 100% do limite (inclusive os dois extremos), **When** a pessoa consulta,
   **Then** o estado é "atenção". **Given** um gasto acima de 100%, **Then** o estado é "estourado", o
   restante vem negativo e o percentual passa de 100%.
3. **Given** um limite de categoria principal, **When** há gastos nas subcategorias dela e direto nela,
   **Then** o gasto do limite é a soma de todos. **Given** um limite de subcategoria, **Then** só os gastos
   dela contam.
4. **Given** um limite geral, **When** a pessoa consulta, **Then** o gasto dele é o total de todas as
   despesas do mês, de qualquer categoria, incluindo despesa sem categoria.
5. **Given** o mesmo gasto coberto por mais de um limite (subcategoria, principal e geral), **When** a pessoa
   consulta, **Then** ele conta em cada um deles, e os limites não são somados entre si em nenhum total.
6. **Given** receitas, transferências e lançamentos excluídos no mês, **When** a pessoa consulta,
   **Then** nenhum deles conta como gasto.
7. **Given** despesas em contas ou categorias arquivadas, **When** a pessoa consulta, **Then** elas contam
   normalmente; um limite que já existia numa categoria arquivada continua aparecendo e sendo acompanhado.
8. **Given** despesas no mês em categorias sem nenhum limite de categoria que as cubra, **When** a pessoa
   consulta, **Then** o total dessas despesas aparece como "gasto fora dos limites de categoria".
9. **Given** um limite de valor zero, **When** não há gasto, **Then** o estado é "dentro do limite" e o
   percentual vem vazio ("não aplicável"); **When** há qualquer gasto, **Then** o estado é "estourado".
10. **Given** um mês sem nenhum limite, **When** a pessoa consulta, **Then** recebe a lista de limites
    vazia, sem limite geral, e o total gasto no mês, sem erro.
11. **Given** os limites do mês, **When** a pessoa consulta, **Then** eles vêm ordenados por estado
    (estourados primeiro, depois atenção, depois dentro do limite), depois por maior gasto e depois por
    nome, numa ordem estável.
12. **Given** o mês, **When** a pessoa consulta, **Then** o mês é o mesmo da spec 005 (do dia 1 ao último dia
    do mês civil) e os gastos usam as mesmas regras de lançamentos dela: a data de ocorrência, sem fuso,
    ambos os dias-limite incluídos.
13. **Given** nenhum mês informado, **When** a pessoa consulta, **Then** recebe o mês corrente, como no
    painel da spec 005.

---

### User Story 3 - Copiar os limites para os próximos meses (Priority: P2)

Para não redigitar tudo todo mês, a pessoa copia os limites de um mês para os meses seguintes, de um até
muitos. A cópia só preenche o que ainda não tem limite: nunca sobrescreve um limite que a pessoa já definiu ou
ajustou.

**Why this priority**: sem isso, planejar mês a mês vira trabalho repetitivo e a pessoa desiste. Mas o
orçamento já entrega valor definindo limites um a um.

**Independent Test**: com limites no mês 2026-11, copiar para os 3 meses seguintes (um deles já com um limite
diferente) e conferir que 2026-12 a 2027-02 ganham os limites que faltavam e que o limite diferente fica
intacto.

**Acceptance Scenarios**:

1. **Given** um mês de origem com limites, **When** a pessoa pede para copiá-los para os próximos N meses,
   **Then** cada um dos N meses seguintes recebe uma cópia de cada limite da origem que ainda não existia
   nele (mesma categoria, mesmo valor).
2. **Given** um mês de destino que já tem limite para uma categoria, **When** a cópia acontece, **Then**
   esse limite não é alterado e a cópia dele é ignorada.
3. **Given** limites de origem em categorias que hoje estão arquivadas, **When** a cópia acontece, **Then**
   esses limites não são copiados.
4. **Given** a cópia concluída, **When** a resposta é devolvida, **Then** informa quantos limites foram
   criados, quantos ignorados por já existirem e quantos ignorados por categoria arquivada.
5. **Given** uma origem sem limites, **When** a pessoa pede a cópia, **Then** nada é criado e a resposta
   informa zero em tudo, sem erro.
6. **Given** N menor que 1, maior que 24, não inteiro, ou meses de destino que passam de 2100-12, **When** a
   pessoa pede a cópia, **Then** é recusada indicando o campo e nada é criado.
7. **Given** uma falha em qualquer ponto da cópia, **When** ela não termina, **Then** nenhum limite da cópia
   fica gravado: a cópia acontece inteira ou não acontece.
8. **Given** um mês de origem mal escrito ou fora do intervalo, **When** a pessoa pede a cópia, **Then**
   é recusada indicando o campo.
9. **Given** a virada de ano (por exemplo, origem em 2026-11 e N igual a 3), **When** a cópia acontece,
   **Then** os destinos são 2026-12, 2027-01 e 2027-02, sem pular nem repetir mês.

---

### User Story 4 - Ajustar ou remover um limite (Priority: P2)

A pessoa corrige o valor de um limite ou o remove do mês, sem afetar nenhum outro mês.

**Why this priority**: planos mudam durante o mês e erros de digitação acontecem, mas o fluxo principal
(definir e acompanhar) já funciona sem isso.

**Independent Test**: definir um limite, mudar o valor, conferir o novo acompanhamento; remover e conferir
que ele some só daquele mês.

**Acceptance Scenarios**:

1. **Given** um limite existente, **When** a pessoa muda o valor, **Then** o acompanhamento passa a usar o
   novo valor e a data de última alteração é atualizada; o gasto não muda.
2. **Given** um limite existente, **When** a pessoa o remove, **Then** ele deixa de aparecer no mês, o
   gasto antes coberto por ele passa a contar como fora dos limites (se nenhum outro o cobrir) e nenhum
   outro mês é afetado.
3. **Given** um limite já removido ou inexistente, de outro workspace ou com identificador malformado,
   **When** a pessoa tenta ajustá-lo ou removê-lo, **Then** recebe "não encontrado".
4. **Given** um limite numa categoria arquivada, **When** a pessoa tenta alterar o valor, **Then** é
   recusado; **When** a pessoa o remove, **Then** funciona (arquivar não trava a limpeza do plano).
5. **Given** uma remoção, **When** concluída, **Then** os lançamentos, as categorias, as contas e os limites
   semanais não mudam.

---

### User Story 5 - Só quem pode altera, e só no seu workspace (Priority: P1)

Somente OWNER e EDITOR definem, ajustam, removem e copiam limites. VIEWER apenas consulta. Quem não é membro
do workspace recebe a mesma resposta de um workspace que não existe.

**Why this priority**: o orçamento revela hábitos de gasto; a constituição exige o teste de acesso cruzado em
toda rota de dados e a separação de papéis.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER no workspace da
primeira, tentar consultar, definir, ajustar, remover e copiar e conferir as respostas.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta consultar, definir, ajustar,
   remover ou copiar limites, **Then** recebe "não encontrado", igual à resposta de um workspace
   inexistente, e nada é revelado nem alterado.
2. **Given** uma pessoa com o papel VIEWER, **When** ela consulta o mês, **Then** vê tudo normalmente.
3. **Given** uma pessoa com o papel VIEWER, **When** ela tenta definir, ajustar, remover ou copiar,
   **Then** a ação é recusada por falta de permissão e nada muda.
4. **Given** uma pessoa com o papel EDITOR, **When** ela define, ajusta, remove e copia limites, **Then** as
   ações funcionam, inclusive sobre limites criados por outra pessoa do workspace; o "criado por" nunca muda.
5. **Given** o identificador de um limite de outro workspace usado numa rota do meu workspace, **When** a
   pessoa tenta ajustá-lo ou removê-lo, **Then** recebe "não encontrado" e o limite não muda.
6. **Given** o identificador de um limite semanal (spec 006) usado numa rota de limite mensal, **When** a
   pessoa tenta ajustá-lo ou removê-lo, **Then** recebe "não encontrado": os dois tipos de limite não se
   misturam.
7. **Given** alguém sem sessão, **When** tenta qualquer operação de orçamento, **Then** é recusado por falta
   de autenticação.

---

### Edge Cases

- Um limite pertence a exatamente um mês, um workspace e um escopo (uma categoria de despesa ou o geral).
  Dois limites para o mesmo escopo e mês nunca existem; definir de novo substitui o valor.
- Meses de tamanhos diferentes (fevereiro com 28 ou 29 dias, meses de 30 e 31): conta do dia 1 ao último dia
  do mês, sem prorrogar nem cortar; 29 de fevereiro de ano bissexto conta no mês.
- Despesa com data futura dentro do mês conta como gasto (todo lançamento conta como efetivado).
- Despesa sem categoria (importação da fase 3) conta só no limite geral e no "gasto fora dos limites de
  categoria".
- Categoria principal e subcategoria com limite no mesmo mês: o mesmo gasto conta nos dois; os valores dos
  limites não precisam fechar entre si.
- Categoria arquivada no meio da definição de um limite (corrida): nunca nasce limite novo em categoria
  arquivada; a tentativa é recusada.
- Duas pessoas definindo o limite do mesmo escopo e mês ao mesmo tempo: sobra um único limite, com o valor da
  última gravação, nunca dois nem um erro interno.
- Corpo da requisição com campos extras, como informar o workspace, quem criou ou as datas de criação e de
  alteração: campos não permitidos são recusados, nunca aceitos.
- Valor máximo (R$ 10 bilhões) e gastos muito altos: continuam exatos, sem estouro.
- Percentual sempre com duas casas decimais; com limite zero, vem vazio.
- Workspace sem categorias de despesa ativas: não há onde definir limite por categoria, mas o limite geral
  continua possível.
- Mês com limite semanal e mensal sobrepostos: cada um é calculado sozinho, pelo seu próprio período; o
  sistema não compara nem concilia os dois.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir definir um limite mensal de gasto informando o mês, o valor e,
  opcionalmente, uma categoria de despesa. Sem categoria, o limite é o geral do mês.
- **FR-002**: O mês MUST seguir exatamente a definição da spec 005 (formato AAAA-MM, do primeiro ao último
  dia do mês civil, aceito de 2000-01 a 2100-12) e MUST ser recusado, indicando o campo, quando estiver mal
  escrito, inexistente ou fora do intervalo.
- **FR-003**: O valor do limite MUST ser um inteiro em centavos, de 0 a 1.000.000.000.000 (R$ 10 bilhões),
  nunca com casas decimais nem em ponto flutuante. Zero é aceito. Valor fora disso MUST ser recusado
  indicando o campo.
- **FR-004**: A categoria de um limite MUST ser de despesa, do mesmo workspace e ativa; pode ser principal
  ou subcategoria. Categoria de receita MUST ser recusada indicando o campo; categoria arquivada MUST ser
  recusada; categoria de outro workspace ou inexistente MUST responder "não encontrado".
- **FR-005**: Para cada workspace, escopo (uma categoria, ou o geral) e mês MUST existir no máximo um
  limite mensal. Definir um limite que já existe MUST substituir o valor dele, mantendo o "criado por" e a
  data de criação, e atualizando a data de alteração.
- **FR-006**: Um limite de categoria principal MUST cobrir os gastos lançados direto nela e os de todas as
  suas subcategorias; um limite de subcategoria MUST cobrir só os dela; o limite geral MUST cobrir todas as
  despesas do mês, inclusive as sem categoria. Limites de um mesmo mês MUST ser independentes entre si, sem
  exigir que os valores fechem.
- **FR-007**: O gasto de um limite MUST ser a soma, em centavos, dos lançamentos de despesa não excluídos
  cuja data de ocorrência cai no mês (do dia 1 ao último dia, ambos incluídos) e cuja categoria o limite
  cobre, em valor positivo. Receitas, transferências e lançamentos excluídos MUST NÃO contar. Lançamentos
  em contas ou categorias arquivadas MUST contar normalmente.
- **FR-008**: A consulta de um mês MUST trazer, para cada limite, a categoria (ou a indicação de geral), o
  valor do limite, o gasto, o restante (limite menos gasto, negativo quando estourado), o percentual usado
  (gasto sobre limite, com duas casas decimais, vazio quando o limite é zero) e o estado. Sem mês informado,
  MUST usar o mês corrente.
- **FR-009**: O estado de um limite MUST ser "dentro do limite" quando o gasto está abaixo de 80% do limite,
  "atenção" quando está de 80% a 100% do limite (inclusive) e "estourado" quando passa de 100%. Com limite
  zero, MUST ser "dentro do limite" sem gasto e "estourado" com qualquer gasto. As regras de gasto,
  percentual e estado MUST ser idênticas às da spec 006, para o mesmo gasto e o mesmo limite darem o mesmo
  resultado nos dois orçamentos.
- **FR-010**: A consulta do mês MUST trazer também o total gasto no mês (todas as despesas) e o gasto fora
  dos limites de categoria (despesas do mês que nenhum limite de categoria cobre), e MUST NÃO somar os valores
  dos limites entre si em nenhum total.
- **FR-011**: Os limites da consulta MUST vir ordenados por estado (estourado, atenção, dentro do limite),
  depois por maior gasto, depois por nome da categoria (o geral primeiro entre iguais) e depois por
  identificador, para a resposta ser igual em consultas repetidas.
- **FR-012**: O sistema MUST permitir alterar o valor de um limite existente e removê-lo. Alterar o valor de
  um limite de categoria arquivada MUST ser recusado; remover MUST funcionar sempre. Remover um limite MUST
  NÃO afetar lançamentos, categorias, contas, limites semanais nem outros meses.
- **FR-013**: O sistema MUST permitir copiar os limites de um mês de origem para os N meses seguintes (N de 1
  a 24). A cópia MUST criar apenas os limites que ainda não existem no destino, MUST NUNCA alterar um limite
  existente, MUST ignorar limites de categorias hoje arquivadas e MUST informar quantos limites foram
  criados, quantos foram ignorados por já existirem e quantos por categoria arquivada. Os meses de destino
  MUST ficar dentro do intervalo aceito; senão a cópia MUST ser recusada inteira.
- **FR-014**: A cópia MUST ser atômica: ou todos os limites novos ficam gravados ou nenhum.
- **FR-015**: O sistema MUST registrar quem criou cada limite, a partir da pessoa autenticada, e esse campo
  MUST NUNCA ser informado pelo cliente nem alterado depois.
- **FR-016**: Somente os papéis OWNER e EDITOR MUST poder definir, ajustar, remover e copiar limites. O
  papel VIEWER MUST poder apenas consultar. A recusa por papel MUST usar resposta de "sem permissão",
  distinta de "não encontrado".
- **FR-017**: Toda operação de orçamento mensal MUST ficar sob o identificador do workspace e MUST conferir
  que a pessoa autenticada é membro dele. Para quem não é membro, ou para workspace inexistente, a resposta
  MUST ser a mesma de "não encontrado", sem revelar nada.
- **FR-018**: Um limite MUST pertencer a exatamente um workspace, e sua categoria MUST ser desse mesmo
  workspace. Um limite MUST NUNCA ser acessado por identificador a partir de outro workspace, e nenhum gasto
  de outro workspace MUST entrar em qualquer soma.
- **FR-019**: O isolamento por workspace do orçamento mensal MUST existir nas duas camadas independentes
  exigidas pela constituição (camada de dados e camada de serviço) e MUST ter teste automatizado de acesso
  cruzado para cada operação.
- **FR-020**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da lista
  permitida (workspace, quem criou, datas de criação e de alteração) e indicando o campo inválido nas
  recusas.
- **FR-021**: Todos os números de uma consulta de mês (limites, gastos, totais) MUST vir de uma única
  leitura consistente, sem misturar dados de antes e de depois de uma gravação feita ao mesmo tempo.
- **FR-022**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do
  projeto, sem vazar detalhes internos; as respostas autenticadas MUST instruir navegadores e intermediários
  a não guardá-las em cache.
- **FR-023**: O orçamento mensal MUST ser independente do semanal: definir, ajustar, remover ou copiar um
  limite mensal MUST NÃO alterar nenhum limite semanal, e o contrário também. Cada tipo de limite MUST ser
  acessado só pelas suas próprias rotas.

### Key Entities *(include if feature involves data)*

- **Limite mensal**: valor máximo de gasto, em centavos, para um mês e um escopo (uma categoria de despesa,
  ou o geral). Tem quem o criou e as datas de criação e de alteração. Pertence a exatamente um workspace. É
  um plano, não um lançamento financeiro: não altera nenhum saldo. Distinto do limite semanal da spec 006.
- **Acompanhamento do mês**: visão calculada, não guardada, que une os limites do mês com o gasto real de
  cada um (gasto, restante, percentual, estado), o total gasto e o gasto fora dos limites.
- **Mês**: período do primeiro ao último dia do mês civil, no formato AAAA-MM, definido na spec 005.
- **Lançamento** (spec 004): a fonte do gasto; o orçamento só o lê.
- **Categoria** (spec 003): onde o limite se aplica; só despesa; principal cobre as subcategorias.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes, o gasto de cada limite bate, em centavos exatos, com uma soma independente
  feita no teste sobre os lançamentos de despesa não excluídos do mês (inclusive somas como R$ 0,10 +
  R$ 0,20 = R$ 0,30), em dados aleatórios com semente fixa, com gastos em categorias principais,
  subcategorias, arquivadas e sem categoria.
- **SC-002**: Em 100% dos testes, receitas, transferências e lançamentos excluídos nunca contam como gasto,
  e criar, editar ou excluir esses lançamentos não altera nenhum gasto de limite.
- **SC-003**: Em 100% dos testes de estado, os limites de gasto exato em 79,99%, 80%, 100% e 100,01% do
  limite recebem, respectivamente, "dentro do limite", "atenção", "atenção" e "estourado", e o limite zero
  se comporta como descrito; os mesmos valores de gasto e limite dão o mesmo estado e o mesmo percentual no
  orçamento semanal e no mensal.
- **SC-004**: Em 100% dos testes, o mês usa os mesmos dias da spec 005: lançamentos no primeiro e no último
  dia do mês contam, os do último dia do mês anterior e do primeiro do mês seguinte não contam, inclusive em
  fevereiro de ano comum e bissexto e na virada de ano.
- **SC-005**: Em 100% dos testes, nunca existem dois limites para o mesmo workspace, escopo e mês, nem sob
  gravações simultâneas do mesmo escopo e mês.
- **SC-006**: Em 100% dos testes de cópia, só os limites que faltavam são criados, nenhum existente muda, os
  de categorias arquivadas são ignorados, as contagens da resposta batem com o que ficou gravado, a virada
  de ano não pula nem repete mês, e uma falha no meio não deixa nenhum limite gravado.
- **SC-007**: Em 100% dos testes, nenhum limite novo nasce em categoria arquivada, de receita ou de outro
  workspace, inclusive quando a categoria é arquivada ao mesmo tempo da definição.
- **SC-008**: Em 100% dos testes de isolamento, tentativas de consultar, definir, ajustar, remover ou copiar
  limites de um workspace do qual a pessoa não é membro recebem a mesma resposta de um workspace
  inexistente, e nenhum dado é revelado ou alterado; os gastos de um workspace nunca incluem lançamentos de
  outro.
- **SC-009**: Em 100% dos testes de permissão, VIEWER consegue consultar e nenhuma operação de alteração tem
  efeito; OWNER e EDITOR conseguem alterar.
- **SC-010**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de acesso a limites de outro workspace continuam recusadas pela outra camada.
- **SC-011**: A consulta de um mês de um workspace com 20.000 lançamentos e dezenas de limites responde em
  menos de 1 segundo.
- **SC-012**: Em 100% dos testes de validação, meses e valores inválidos, campos extras e categorias
  inválidas são recusados indicando o campo, e nada é gravado.
- **SC-013**: Em 100% dos testes, nenhuma operação de orçamento mensal altera lançamentos, contas,
  categorias, saldos nem limites semanais, e nenhuma operação de orçamento semanal altera limites mensais.

## Assumptions

- Esta funcionalidade é só de backend; as telas do orçamento fazem parte do roadmap de front-end. Ela vem
  depois da `006-orcamento-semanal`, de quem herda a regra de gasto, percentual e estado, e da
  `005-dashboard-mensal`, de quem herda a definição de mês. Fecha o orçamento por categoria da fase 2.
- O limite é explícito por mês: cada mês tem os seus limites, e um mês sem limite fica sem orçamento. Não
  existe limite "padrão" que vale para todos os meses, pelo mesmo motivo da 006: mudá-lo reescreveria o
  passado. A cópia para os próximos meses só preenche o que falta.
- Orçamento mensal e semanal são independentes: o mês não é a soma das semanas e o sistema não valida nem
  concilia um contra o outro. A pessoa usa um, o outro ou os dois.
- A cópia vai até 24 meses (dois anos) de uma vez, em vez dos 52 da semanal, porque 24 meses cobre o
  planejamento anual com folga e a proporção de limites criados por chamada fica parecida.
- O limite geral cobre todas as despesas do mês; não se exige que a soma dos limites das categorias caiba
  nele. Os limites são metas independentes, e o sistema nunca soma limites entre si.
- O limite de 80% para "atenção" é fixo, o mesmo da 006; não é configurável.
- Remover um limite apaga o limite de verdade: ele é um plano editável, não um registro financeiro (a regra
  de exclusão lógica da constituição vale para lançamentos). Remover não toca em nenhum outro dado.
- Percentual com duas casas decimais, arredondado de forma comum (metade para cima); o estado é decidido com
  os centavos exatos, nunca com o percentual arredondado.
- Alterar o valor de um limite de categoria arquivada é recusado, mas o limite continua aparecendo e sendo
  acompanhado, e pode ser removido.
- Despesas saem como valor positivo (é o "quanto saiu"), como no painel da spec 005 e no orçamento da 006.
- Moeda única (BRL). Notificações de limite estourado, orçamento por conta, rollover de saldo não gasto,
  previsão, relatórios históricos de orçamento e metas de economia ficam fora desta funcionalidade; todo
  lançamento conta como efetivado.
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `003-categorias` (hierarquia,
  tipo e arquivamento), da `004-lancamentos` (despesas, exclusão lógica, transferências), da definição de
  mês da `005-dashboard-mensal` e das regras da `006-orcamento-semanal`, além das regras da constituição
  (v1.0.0), em especial os princípios II, IV, V e VI.
