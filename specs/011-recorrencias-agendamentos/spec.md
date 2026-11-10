# Feature Specification: Recorrências e efetivação automática

**Feature Branch**: `011-recorrencias-agendamentos`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "Recorrências e efetivação automática: a pessoa marca um lançamento pendente para ser efetivado sozinho quando a data chega, e cria recorrências (aluguel, salário, assinaturas) que o sistema repete toda semana, mês ou ano. A efetivação automática é opcional e vem desligada: sem ela, a data nunca efetiva nada sozinha (spec 009). Com ela ligada, o sistema efetiva o lançamento pendente no dia da data, igual à efetivação manual (spec 009), e desliga o marcador. A recorrência define conta, categoria, tipo (receita ou despesa), valor, descrição, frequência (semanal, mensal ou anual), data de início, data de fim opcional e se as ocorrências são efetivadas automaticamente. O sistema gera as ocorrências adiante, como lançamentos pendentes comuns da spec 004, até 12 meses depois de hoje, e continua gerando com o passar do tempo, sem nunca duplicar nem recriar uma ocorrência que a pessoa excluiu. A pessoa vê as recorrências e as suas ocorrências, e pode encerrar uma recorrência: o sistema para de gerar e exclui as ocorrências futuras que ainda estão pendentes, sem tocar no que já foi efetivado. A compra parcelada (spec 010) ganha a opção de efetivar as parcelas automaticamente. Uma execução automática do sistema, ao iniciar e a cada hora, gera as ocorrências que faltam e efetiva o que venceu; ela não depende de uma pessoa logada. Papéis: OWNER e EDITOR escrevem, VIEWER só lê; isolamento por workspace em duas camadas (RLS + filtro). Rotas sob /api/v1/workspaces/{wid}/recurrences. Segue o padrão das specs 004, 009 e 010."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Efetivar sozinho no dia da data (Priority: P1)

A pessoa lança o aluguel que vence dia 5 como pendente e liga a efetivação automática. No dia 5, sem ela fazer
nada, o lançamento passa a efetivado e entra no saldo, no dashboard, nos orçamentos e na fatura, como se ela o
tivesse efetivado à mão.

**Why this priority**: é o que fecha o ciclo da spec 009. Hoje todo lançamento previsto exige que a pessoa lembre de
efetivá-lo, e o saldo realizado fica desatualizado até ela fazer isso.

**Independent Test**: criar uma despesa pendente com a data de hoje e a efetivação automática ligada, rodar a
execução automática e conferir que ela ficou efetivada, com o saldo atualizado e o marcador desligado.

**Acceptance Scenarios**:

1. **Given** um lançamento pendente com efetivação automática e a data de hoje (ou de um dia que já passou),
   **When** a execução automática roda, **Then** ele fica efetivado, só o estado e a data de última alteração mudam,
   e o marcador de efetivação automática fica desligado.
2. **Given** um lançamento pendente com efetivação automática e a data no futuro, **When** a execução automática
   roda, **Then** nada muda; ele é efetivado na execução que acontece a partir do dia da data.
3. **Given** um lançamento pendente **sem** efetivação automática, mesmo com a data no passado, **When** a execução
   automática roda, **Then** ele continua pendente (a regra da spec 009 vale: a data nunca efetiva sozinha).
4. **Given** uma transferência pendente com efetivação automática, **When** ela vence, **Then** as duas pontas são
   efetivadas juntas.
5. **Given** um lançamento efetivado pela execução automática e depois devolvido a pendente pela pessoa (spec 009),
   **When** a execução automática roda de novo, **Then** ele continua pendente: a efetivação automática é de uso
   único.
6. **Given** um lançamento criado ou editado como efetivado com a efetivação automática ligada, **When** a pessoa
   envia, **Then** a operação é recusada indicando o campo, porque a efetivação automática só vale para pendentes.
7. **Given** a data de um lançamento com efetivação automática editada para o futuro antes de vencer, **When** a
   execução automática roda, **Then** ele só é efetivado quando a nova data chegar.
8. **Given** a efetivação automática ligada ou desligada pela edição de um lançamento pendente, **When** a pessoa
   edita sem informar o marcador, **Then** o valor atual do marcador é mantido.
9. **Given** o resultado da listagem e da consulta de um lançamento, **When** a pessoa consulta, **Then** cada
   lançamento informa se a efetivação automática está ligada (desligada nos que não usam).
10. **Given** a conta ou a categoria do lançamento arquivada depois da criação, **When** ele vence, **Then** é
    efetivado normalmente (efetivar não é passar a usar a conta ou a categoria).

---

### User Story 2 - Criar uma recorrência e ver as ocorrências adiante (Priority: P1)

A pessoa cadastra o aluguel de R$ 1.500,00 que vence todo dia 5. O sistema passa a manter as próximas ocorrências
como lançamentos pendentes, e a pessoa as vê no saldo previsto dos próximos meses sem lançar nada à mão.

**Why this priority**: é o motivo de existir da funcionalidade. Despesas e receitas fixas são a maior parte do
previsto, e lançá-las uma a uma é o que faz o previsto ficar abandonado.

**Independent Test**: criar uma recorrência mensal de R$ 1.500,00 a partir de hoje e conferir as ocorrências geradas
até 12 meses à frente, todas pendentes, uma por mês, no mesmo dia.

**Acceptance Scenarios**:

1. **Given** uma conta ativa, uma categoria de despesa ativa, um valor de R$ 1.500,00, frequência mensal e início em
   2026-11-05, **When** a pessoa cria a recorrência em 2026-11-10, **Then** a recorrência é criada e as ocorrências de
   2026-11-05 até 2027-11-05 (a última que não passa de 12 meses depois de hoje) existem como despesas pendentes de
   R$ 1.500,00, com a descrição da recorrência, a conta e a categoria dela.
2. **Given** a frequência semanal, **When** a recorrência é criada, **Then** as ocorrências caem de 7 em 7 dias a
   partir do início; na frequência anual, no mesmo dia e mês de cada ano.
3. **Given** o início no dia 31, **When** a recorrência mensal é criada, **Then** cada ocorrência cai no mesmo dia do
   mês, ou no último dia do mês quando ele não tem esse dia (31/01, 28/02, 31/03), sempre contado a partir do início,
   sem acumular o ajuste; um início em 29/02 na frequência anual cai em 28/02 nos anos comuns.
4. **Given** uma data de fim, **When** a recorrência é criada, **Then** nenhuma ocorrência passa dessa data, e a
   data de fim, se informada, não pode ser anterior ao início.
5. **Given** um início já passado (até 366 dias atrás), **When** a recorrência é criada, **Then** as ocorrências desde
   o início também são geradas como pendentes (e, com a efetivação automática ligada, as que já venceram são
   efetivadas na mesma execução).
6. **Given** a recorrência criada, **When** o tempo passa e a execução automática roda, **Then** as ocorrências que
   passam a caber nos 12 meses à frente são geradas, sem duplicar nenhuma das que já existem.
7. **Given** uma ocorrência que a pessoa excluiu (spec 004), **When** a execução automática roda, **Then** ela não é
   recriada.
8. **Given** a efetivação automática ligada na recorrência, **When** as ocorrências são geradas, **Then** todas nascem
   pendentes com o marcador ligado, e cada uma é efetivada no seu dia.
9. **Given** os campos inválidos (tipo, frequência, valor fora de 1 centavo a R$ 10 bilhões, descrição vazia ou acima
   de 200 caracteres, início fora de "366 dias atrás" a 2100-12-31, fim anterior ao início ou depois de 2100-12-31),
   **When** a pessoa cria, **Then** a operação é recusada indicando o campo e nada é criado.
10. **Given** uma conta ou categoria arquivada, de outro workspace, inexistente, ou uma categoria que não é do tipo
    da recorrência, **When** a pessoa cria, **Then** a operação é recusada como na criação de lançamentos (arquivada:
    conflito; outro workspace ou inexistente: "não encontrado"; tipo errado: campo inválido) e nada é criado.
11. **Given** uma falha em qualquer ponto da criação, **When** ela não termina, **Then** nem a recorrência nem
    nenhuma ocorrência fica gravada.
12. **Given** a conta ou a categoria arquivada depois da criação da recorrência, **When** a execução automática roda,
    **Then** nenhuma ocorrência nova é gerada para ela enquanto estiver arquivada; as ocorrências que já existem
    ficam como estão.

---

### User Story 3 - Ver as recorrências e encerrar uma (Priority: P2)

A pessoa vê as recorrências que tem, o que cada uma gera e o que já foi efetivado. Quando cancela a assinatura, ela
encerra a recorrência: o sistema para de gerar e remove o que ainda estava previsto para o futuro.

**Why this priority**: sem isso a pessoa teria de excluir as ocorrências futuras uma a uma, e a recorrência nunca
acabaria.

**Independent Test**: com uma recorrência mensal com ocorrências futuras pendentes e uma já efetivada, encerrar e
conferir que as futuras pendentes somem e a efetivada continua.

**Acceptance Scenarios**:

1. **Given** as recorrências do workspace, **When** a pessoa lista, **Then** vê cada uma com os seus dados, se está
   encerrada e a data da próxima ocorrência pendente.
2. **Given** uma recorrência, **When** a pessoa a consulta, **Then** vê os dados e as suas ocorrências não excluídas,
   em ordem, com o estado de cada uma, o total efetivado e o total pendente.
3. **Given** uma recorrência com ocorrências efetivadas, pendentes do passado e pendentes futuras, **When** a pessoa
   a encerra, **Then** a recorrência fica encerrada, as ocorrências pendentes com data depois de hoje são excluídas
   (exclusão lógica), as efetivadas e as pendentes que já venceram continuam, e a resposta informa quantas foram
   excluídas.
4. **Given** uma recorrência encerrada, **When** a execução automática roda, **Then** nenhuma ocorrência nova é
   gerada para ela.
5. **Given** uma recorrência já encerrada, **When** a pessoa a encerra de novo, **Then** a operação responde com
   sucesso sem efeito (zero excluídas).
6. **Given** o encerramento, **When** ele termina, **Then** o saldo, o dashboard, os orçamentos e a fatura (que só
   contam efetivados) não mudam, e o previsto deixa de incluir as ocorrências excluídas.
7. **Given** um identificador de recorrência de outro workspace, inexistente ou malformado, **When** a pessoa o
   consulta ou encerra, **Then** recebe "não encontrado" e nada muda.
8. **Given** cada ocorrência, **When** a pessoa a edita, efetiva ou exclui, **Then** funciona como num lançamento
   comum (specs 004 e 009) e não altera a recorrência nem as outras ocorrências.

---

### User Story 4 - Parcelas que se efetivam sozinhas (Priority: P2)

A pessoa registra uma compra em 6 vezes no cartão e quer que cada parcela entre na fatura no mês dela, sem ter de
efetivá-la à mão.

**Why this priority**: era a pendência explícita da spec 010 ("efetivar automaticamente na data fica para a
funcionalidade de recorrências e agendamentos").

**Independent Test**: registrar uma compra parcelada com a efetivação automática ligada, rodar a execução automática
numa data em que a 2ª parcela já venceu e conferir que ela está efetivada e a 3ª continua pendente.

**Acceptance Scenarios**:

1. **Given** o registro de uma compra parcelada com a efetivação automática ligada, **When** a compra é registrada,
   **Then** todas as parcelas pendentes nascem com o marcador ligado (a primeira, se for efetivada, não precisa dele).
2. **Given** a compra registrada sem informar a efetivação automática, **When** a execução automática roda, **Then**
   nenhuma parcela é efetivada sozinha (como na spec 010).
3. **Given** uma parcela efetivada automaticamente, **When** a pessoa consulta o plano, **Then** o efetivado e o
   pendente refletem a mudança.

---

### User Story 5 - Só quem pode altera, e só no seu workspace (Priority: P1)

Somente OWNER e EDITOR criam e encerram recorrências e ligam a efetivação automática. VIEWER apenas consulta. Quem
não é membro do workspace recebe a mesma resposta de um workspace que não existe. A execução automática não cria
brecha entre workspaces.

**Why this priority**: a constituição exige o teste de acesso cruzado em toda rota de dados e a separação de
papéis, e a execução automática roda sem pessoa logada, então precisa de prova própria de que não vaza entre
workspaces.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER, tentar criar, consultar,
listar e encerrar e conferir as respostas; rodar a execução automática com dados nos dois workspaces e conferir que
cada um só recebe o que é seu.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta criar, consultar, listar ou encerrar,
   **Then** recebe "não encontrado", igual à resposta de um workspace inexistente, e nada é revelado nem alterado.
2. **Given** uma pessoa com o papel VIEWER, **When** ela lista ou consulta, **Then** vê tudo normalmente.
3. **Given** uma pessoa com o papel VIEWER, **When** ela tenta criar ou encerrar, ou ligar a efetivação automática
   num lançamento, **Then** a ação é recusada por falta de permissão e nada muda.
4. **Given** uma pessoa com o papel EDITOR, **When** ela cria ou encerra, inclusive uma recorrência de outra pessoa,
   **Then** funciona; as ocorrências geradas ficam com a pessoa que criou a recorrência como autora e o "criado por"
   nunca muda.
5. **Given** alguém sem sessão, **When** tenta qualquer operação, **Then** é recusado por falta de autenticação.
6. **Given** recorrências e lançamentos com efetivação automática em dois workspaces, **When** a execução automática
   roda, **Then** ela gera e efetiva em cada workspace só o que pertence a ele, e nada de um aparece no outro.

---

### Edge Cases

- Efetivação automática ligada num lançamento com a data no passado: é efetivado na próxima execução automática
  (no máximo uma hora depois do início da execução seguinte), e não na criação.
- Lançamento com efetivação automática efetivado à mão antes de vencer: o marcador é desligado junto; devolvido a
  pendente, não volta a ser efetivado sozinho.
- Ocorrência de recorrência editada à mão (valor, data ou descrição): continua sendo um lançamento comum; a
  recorrência não a recalcula, e a execução automática não a recria nem a duplica.
- Ocorrência com a data editada para depois do limite de 12 meses: continua existindo e conta como a ocorrência de
  número dela; nenhuma outra a substitui.
- Recorrência com início em data futura distante (por exemplo, daqui a 2 anos): é criada sem nenhuma ocorrência e
  as ocorrências passam a ser geradas quando entram nos 12 meses à frente.
- Recorrência com fim antes do horizonte de 12 meses: gera só até o fim; depois de gerar a última ocorrência, a
  execução automática não tem mais nada a fazer por ela.
- Execuções automáticas simultâneas (mais de uma instância, ou uma execução que demora): nunca duplicam uma
  ocorrência nem efetivam duas vezes o mesmo lançamento.
- Uma recorrência com problema (por exemplo, conta arquivada) não impede as outras de serem processadas.
- Exclusão do marcador por edição: a pessoa pode desligar a efetivação automática de um pendente a qualquer momento.
- Valores muito altos: continuam exatos, sem estouro.
- Campos fora da lista permitida (workspace, quem criou, recorrência de origem, número da ocorrência) são recusados.
- Mudar o valor ou a frequência de uma recorrência não existe nesta versão: a pessoa encerra a recorrência e cria
  outra.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir ligar a efetivação automática em um lançamento pendente, na criação e na
  edição (campo opcional, desligado por padrão), em receita, despesa e transferência (nas duas pontas).
- **FR-002**: A efetivação automática MUST valer só para lançamentos pendentes: criar ou editar um lançamento
  efetivado com ela ligada MUST ser recusado indicando o campo. Quando um lançamento passa a efetivado, por
  qualquer caminho (a execução automática, a efetivação manual ou a edição da spec 009), o marcador MUST ficar
  desligado.
- **FR-003**: Na edição, o marcador ausente MUST manter o valor atual; presente, MUST mudar junto com os demais
  campos.
- **FR-004**: A execução automática MUST efetivar todo lançamento pendente, não excluído, com a efetivação
  automática ligada e a data igual ou anterior ao dia de hoje (no fuso `America/Sao_Paulo`), com o mesmo efeito da
  efetivação manual da spec 009: só o estado e a data de última alteração mudam, as duas pontas de uma transferência
  mudam juntas, e o marcador fica desligado. Lançamento sem o marcador MUST NOT ser efetivado pela data.
- **FR-005**: O sistema MUST informar em toda resposta de lançamento se a efetivação automática está ligada,
  sem mudar nenhum campo existente.
- **FR-006**: O sistema MUST permitir criar uma recorrência com conta, categoria, tipo (receita ou despesa), valor
  em centavos, descrição, observação opcional, frequência (semanal, mensal ou anual), data de início, data de fim
  opcional e efetivação automática opcional (desligada por padrão).
- **FR-007**: O valor MUST ser um inteiro de 1 a 1.000.000.000.000 centavos; a descrição MUST ter de 1 a 200
  caracteres, sem espaços nas pontas; a observação até 1.000. O início MUST ficar entre 366 dias antes de hoje e
  2100-12-31; o fim, se informado, entre o início e 2100-12-31. Fora disso MUST ser recusado indicando o campo.
- **FR-008**: A conta MUST existir no workspace e estar ativa; a categoria MUST existir no workspace, estar ativa e
  ser do tipo da recorrência. Conta ou categoria de outro workspace ou inexistente MUST responder "não encontrado";
  arquivada MUST ser recusada como conflito; categoria de outro tipo MUST ser recusada indicando o campo. A criação
  MUST conferir e travar conta e categoria antes de gravar, como na criação de lançamentos (spec 004).
- **FR-009**: A data da ocorrência de número N (a partir de 0) MUST ser o início somado a N semanas (7 dias), N meses
  ou N anos, conforme a frequência, sempre contado a partir do início (sem acumular o ajuste), com o dia limitado ao
  último dia do mês quando necessário. Nenhuma ocorrência MUST passar do fim, quando houver, nem de 2100-12-31.
- **FR-010**: O sistema MUST manter geradas, como lançamentos pendentes, todas as ocorrências de uma recorrência
  ativa com data até 12 meses depois de hoje, a partir do início. As ocorrências MUST ser despesas ou receitas
  comuns (spec 004), com a conta, a categoria, o valor, a descrição e a observação da recorrência, o marcador de
  efetivação automática da recorrência e como autora a pessoa que criou a recorrência.
- **FR-011**: A geração MUST ser idempotente: uma ocorrência (recorrência e número) MUST existir no máximo uma vez,
  mesmo sob execuções simultâneas, e uma ocorrência excluída MUST NOT ser recriada.
- **FR-012**: Enquanto a conta ou a categoria de uma recorrência estiver arquivada, o sistema MUST NOT gerar novas
  ocorrências para ela; as já geradas ficam como estão.
- **FR-013**: A criação da recorrência MUST ser atômica: a recorrência e as ocorrências iniciais ficam gravadas
  juntas ou nada fica gravado.
- **FR-014**: O sistema MUST oferecer a lista de recorrências do workspace e a consulta de uma: dados, se está
  encerrada, a data da próxima ocorrência pendente, as ocorrências não excluídas em ordem de número, o total
  efetivado e o total pendente.
- **FR-015**: O sistema MUST permitir encerrar uma recorrência: marcá-la como encerrada, excluir logicamente as
  ocorrências pendentes com data depois de hoje, manter as efetivadas e as pendentes que já venceram e informar
  quantas foram excluídas. Encerrar uma recorrência já encerrada MUST responder com sucesso sem efeito.
- **FR-016**: Cada ocorrência MUST ser um lançamento comum: MUST aparecer na listagem, poder ser editada,
  efetivada e excluída. Cada lançamento MUST informar a recorrência de origem e o número da ocorrência (vazios nos
  que não são ocorrência), e esses campos MUST NUNCA mudar depois de criados.
- **FR-017**: O registro de uma compra parcelada MUST aceitar a efetivação automática (opcional, desligada por
  padrão): ligada, todas as parcelas pendentes nascem com o marcador ligado.
- **FR-018**: A execução automática MUST rodar ao iniciar o sistema e a cada hora. Cada execução MUST gerar as
  ocorrências que faltam e depois efetivar o que venceu, de modo que uma ocorrência gerada já vencida, com o
  marcador ligado, seja efetivada na mesma execução. A execução MUST NOT depender de uma pessoa logada.
- **FR-019**: A execução automática MUST NOT ler nem alterar nada além do que as regras acima descrevem, MUST
  respeitar o isolamento por workspace (cada recorrência e cada lançamento só afeta o seu próprio workspace) e uma
  recorrência ou lançamento com problema MUST NOT impedir o processamento dos demais.
- **FR-020**: O sistema MUST registrar quem criou cada recorrência e cada ocorrência, a partir da pessoa
  autenticada na criação da recorrência, e esses campos MUST NUNCA ser informados pelo cliente nem alterados.
- **FR-021**: Somente os papéis OWNER e EDITOR MUST poder criar e encerrar recorrências e ligar a efetivação
  automática. O papel VIEWER MUST poder apenas consultar. A recusa por papel MUST usar resposta de "sem
  permissão", distinta de "não encontrado".
- **FR-022**: Toda operação MUST ficar sob o identificador do workspace e MUST conferir que a pessoa autenticada é
  membro dele. Para quem não é membro, workspace inexistente, recorrência de outro workspace ou malformada, a
  resposta MUST ser "não encontrado", sem revelar nada.
- **FR-023**: O isolamento por workspace MUST existir nas duas camadas independentes exigidas pela constituição
  (camada de dados e camada de serviço) e MUST ter teste automatizado de acesso cruzado para cada operação nova e
  para a execução automática.
- **FR-024**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da lista
  permitida e indicando o campo inválido nas recusas.
- **FR-025**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do projeto,
  sem vazar detalhes internos; as respostas autenticadas MUST instruir navegadores e intermediários a não
  guardá-las em cache.
- **FR-026**: A funcionalidade MUST NÃO mudar o resultado de nenhuma consulta existente: com a efetivação automática
  desligada e sem recorrências, o sistema se comporta exatamente como antes, e os testes das specs anteriores
  continuam valendo.

### Key Entities *(include if feature involves data)*

- **Recorrência**: a regra que repete um lançamento. Tem conta, categoria, tipo, valor, descrição, observação,
  frequência, início, fim opcional, efetivação automática, estado (ativa ou encerrada) e quem a criou. É um registro
  próprio, ao contrário do plano de parcelas.
- **Ocorrência**: um lançamento de receita ou despesa (spec 004) gerado por uma recorrência, com a recorrência de
  origem e o seu número. Nasce pendente (spec 009).
- **Efetivação automática**: o marcador de um lançamento pendente que o faz ser efetivado quando a data chega. De
  uso único: some quando o lançamento é efetivado.
- **Execução automática**: a rotina do sistema, sem pessoa logada, que gera as ocorrências que faltam e efetiva o
  que venceu.
- **Conta** (spec 002) e **Categoria** (spec 003): onde as ocorrências são lançadas; precisam estar ativas ao criar
  a recorrência e ao gerar cada ocorrência.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes, um lançamento pendente com efetivação automática e data até hoje é efetivado pela
  execução automática, com o mesmo efeito da efetivação manual (só o estado e a data de alteração mudam, nenhum
  outro campo), e um sem o marcador, ou com a data no futuro, nunca é efetivado por ela.
- **SC-002**: Em 100% dos testes, o marcador é de uso único: depois de efetivado por qualquer caminho, fica
  desligado, e devolvido a pendente não é efetivado de novo sozinho.
- **SC-003**: Em 100% dos testes de calendário, as datas das ocorrências seguem o início somado a semanas, meses ou
  anos, sem acumular ajuste de fim de mês (31/01, 28/02, 31/03, 30/04; 29/02 anual), inclusive em ano bissexto e na
  virada de ano, e nunca passam do fim nem de 2100-12-31.
- **SC-004**: Em 100% dos testes, depois de qualquer número de execuções automáticas (inclusive simultâneas), cada
  ocorrência existe no máximo uma vez, uma ocorrência excluída nunca volta, e as ocorrências geradas cobrem
  exatamente as datas até 12 meses depois de hoje.
- **SC-005**: Em 100% dos testes de atomicidade, uma falha no meio da criação da recorrência não deixa nem a
  recorrência nem ocorrência alguma gravada.
- **SC-006**: Em 100% dos testes, encerrar uma recorrência exclui só as ocorrências pendentes com data depois de
  hoje, mantém as efetivadas e as pendentes vencidas, é idempotente e não muda nenhum número realizado.
- **SC-007**: Em 100% dos testes de integração, as ocorrências pendentes nunca entram no saldo, no dashboard, nos
  orçamentos nem na fatura, e passam a entrar em exatos centavos quando efetivadas.
- **SC-008**: Em 100% dos testes de isolamento, criar, consultar, listar ou encerrar num workspace do qual a pessoa
  não é membro recebe a mesma resposta de um workspace inexistente, e a execução automática com dados em dois
  workspaces nunca gera nem efetiva nada no workspace errado.
- **SC-009**: Em 100% dos testes de permissão, VIEWER consegue consultar e nenhuma operação de escrita tem efeito;
  OWNER e EDITOR conseguem.
- **SC-010**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de acessar ou alterar recorrência ou ocorrência de outro workspace continuam recusadas pela outra
  camada.
- **SC-011**: Em 100% dos testes de validação, tipo, frequência, valor, datas, descrição, conta, categoria e campos
  extras inválidos são recusados indicando o campo, e nada é gravado.
- **SC-012**: Todos os testes das specs 001 a 010 continuam passando sem mudar o resultado esperado (FR-026).
- **SC-013**: Uma execução automática sobre um workspace com 20.000 lançamentos, 200 recorrências e 2.000
  lançamentos vencidos com efetivação automática termina em menos de 5 segundos, e criar uma recorrência, consultá-la
  e listar lançamentos respondem em menos de 1 segundo cada.

## Assumptions

- Esta funcionalidade é só de backend; as telas fazem parte do roadmap de front-end.
- A efetivação automática é opcional e vem desligada: a regra da spec 009 ("a data nunca efetiva sozinha") continua
  valendo para tudo que não ligar o marcador.
- "Hoje" é o dia no fuso `America/Sao_Paulo`, o mesmo usado pelo dashboard e pela fatura. A execução automática roda
  ao iniciar e a cada hora, então um lançamento que vence no dia D é efetivado em até uma hora depois do início do dia D.
- A recorrência não é editável nesta versão: para mudar valor, frequência ou datas, a pessoa encerra a recorrência
  e cria outra. Cada ocorrência, sim, é um lançamento comum e pode ser editada, efetivada ou excluída.
- As ocorrências geradas nascem sempre pendentes; só a efetivação automática (ou a pessoa) as efetiva.
- A recorrência só cobre receita e despesa; recorrência de transferência fica para uma funcionalidade futura. A
  efetivação automática, porém, vale também para transferências pendentes comuns.
- O horizonte de geração é de 12 meses depois de hoje; o início de uma recorrência pode ser até 366 dias no passado,
  para limitar quantas ocorrências nascem de uma vez.
- Nenhuma notificação (por e-mail ou push) é enviada pela execução automática.
- O sistema roda em uma única instância hoje; mesmo assim, execuções simultâneas não podem duplicar nada.
- Moeda única (BRL).
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `002-contas`, da `003-categorias`, da
  `004-lancamentos` (lançamentos e exclusão lógica), da `009-lancamento-pendente` (estado pendente e efetivação) e
  da `010-compra-parcelada` (efetivação automática das parcelas), além das regras da constituição (v1.0.0), em
  especial os princípios II, IV, V, VI e VIII.
