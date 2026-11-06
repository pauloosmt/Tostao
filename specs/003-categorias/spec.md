# Feature Specification: Categorias

**Feature Branch**: `003-categorias`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Categorias de receita e despesa por workspace, com subcategorias de dois níveis (categoria pai e filha), um conjunto padrão criado automaticamente no cadastro do workspace (ex.: Alimentação, Moradia, Transporte, Saúde, Lazer, Salário), criar/listar/editar/arquivar e restaurar (sem exclusão definitiva, como em contas), nome único entre irmãs ativas, tipo da categoria (receita ou despesa) herdado pela subcategoria, papéis OWNER/EDITOR escrevem e VIEWER só lê, isolamento por workspace nas duas camadas. Segue o padrão da spec 002-contas."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Já começar com categorias prontas (Priority: P1)

Quando a pessoa cria sua conta, o workspace pessoal dela já vem com um conjunto padrão de categorias
de despesa e de receita (por exemplo Alimentação, Moradia, Transporte, Saúde, Lazer e Salário), algumas
com subcategorias. Ela não precisa montar nada para começar a lançar.

**Why this priority**: sem categorias o lançamento (spec 004) e o dashboard (spec 005) não têm como
classificar nada, e partir do zero deixaria o primeiro uso trabalhoso. É o que torna o produto útil
desde o primeiro minuto.

**Independent Test**: cadastrar uma pessoa nova e consultar a lista de categorias do workspace dela:
as categorias padrão estão lá, com os tipos e as subcategorias definidos.

**Acceptance Scenarios**:

1. **Given** uma pessoa que acabou de se cadastrar, **When** ela consulta as categorias do workspace
   pessoal, **Then** vê o conjunto padrão completo, com categorias de despesa e de receita, e as
   subcategorias sob suas categorias.
2. **Given** duas pessoas recém-cadastradas, **When** cada uma consulta suas categorias, **Then** cada
   uma vê somente as suas, e alterar uma categoria de uma não afeta a da outra.
3. **Given** um workspace já existente antes desta funcionalidade e ainda sem nenhuma categoria,
   **When** a funcionalidade passa a valer, **Then** esse workspace também recebe o conjunto padrão; um
   workspace que já tenha alguma categoria não recebe nem duplica nada.
4. **Given** as categorias padrão de um workspace, **When** a pessoa as edita ou arquiva, **Then** elas
   se comportam como qualquer outra categoria: o padrão é só o ponto de partida, e nada é recriado
   depois.
5. **Given** o cadastro de uma pessoa, **When** a criação das categorias padrão falha, **Then** o
   cadastro inteiro é desfeito, e nunca fica um workspace sem o conjunto padrão.

---

### User Story 2 - Ver as categorias em árvore (Priority: P1)

A pessoa vê as categorias do workspace organizadas em dois níveis: categorias principais e, sob cada
uma, suas subcategorias. Pode pedir só as de despesa ou só as de receita. Por padrão vê apenas as
ativas.

**Why this priority**: a lista é o que o lançamento usa para escolher a categoria e o que o dashboard
usa para agrupar os gastos. Precisa existir junto com o conjunto padrão.

**Independent Test**: com o conjunto padrão, listar tudo, listar só despesas e só receitas, e conferir
a ordem e o agrupamento.

**Acceptance Scenarios**:

1. **Given** um workspace com categorias e subcategorias, **When** a pessoa consulta a lista, **Then**
   cada categoria principal vem com suas subcategorias, em ordem estável por nome (sem diferenciar
   maiúsculas e minúsculas).
2. **Given** categorias de despesa e de receita, **When** a pessoa filtra por um dos tipos, **Then**
   recebe apenas as desse tipo.
3. **Given** categorias arquivadas, **When** a pessoa consulta a lista padrão, **Then** elas não
   aparecem; ao pedir também as arquivadas, aparecem marcadas como tal.
4. **Given** uma categoria, **When** a pessoa a consulta pelo identificador, **Then** recebe seus dados,
   incluindo o tipo, a categoria principal (se for subcategoria) e se está arquivada.
5. **Given** um tipo desconhecido no filtro, **When** a pessoa consulta, **Then** a consulta é recusada
   indicando o campo inválido.

---

### User Story 3 - Só quem pode altera, e só no seu workspace (Priority: P1)

Somente OWNER e EDITOR criam, editam, arquivam e restauram categorias. VIEWER apenas consulta. Nenhuma
pessoa vê ou altera categorias de um workspace do qual não é membro: a tentativa recebe a mesma
resposta de um workspace que não existe.

**Why this priority**: é a garantia de privacidade e de permissão do produto, e a constituição exige
teste de acesso cruzado em toda rota de dados. Precisa estar pronta junto com as categorias.

**Independent Test**: com duas pessoas em workspaces diferentes e uma terceira como VIEWER no
workspace da primeira, tentar ler, criar, editar e arquivar categorias e conferir as respostas.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** ela tenta listar, consultar, criar,
   editar, arquivar ou restaurar categorias, **Then** recebe "não encontrado", igual à resposta de um
   workspace inexistente, e nada é revelado nem alterado.
2. **Given** uma pessoa com o papel VIEWER, **When** ela lista e consulta categorias, **Then** vê tudo
   normalmente.
3. **Given** uma pessoa com o papel VIEWER, **When** ela tenta criar, editar, arquivar ou restaurar uma
   categoria, **Then** a ação é recusada por falta de permissão e nada muda.
4. **Given** uma pessoa com o papel EDITOR, **When** ela cria, edita, arquiva e restaura categorias,
   **Then** as ações funcionam.
5. **Given** o identificador de uma categoria de outro workspace usado numa rota do meu workspace,
   **When** a pessoa tenta consultá-la, editá-la ou arquivá-la, **Then** recebe "não encontrado" e a
   categoria não muda.
6. **Given** alguém sem sessão, **When** tenta qualquer operação de categorias, **Then** é recusado por
   falta de autenticação.

---

### User Story 4 - Criar minhas categorias e subcategorias (Priority: P2)

A pessoa cria categorias além das padrão: uma categoria principal informando nome e tipo (despesa ou
receita), ou uma subcategoria informando nome e a categoria principal à qual pertence. A subcategoria
herda o tipo da categoria principal e não aceita filhas.

**Why this priority**: o conjunto padrão cobre o começo, mas cada pessoa tem hábitos próprios. O
produto já é útil sem isto, por isso vem depois.

**Independent Test**: criar uma categoria principal e uma subcategoria sob ela e conferir tipo
herdado, lista e recusas das regras de hierarquia.

**Acceptance Scenarios**:

1. **Given** um workspace do qual a pessoa é OWNER ou EDITOR, **When** ela cria uma categoria principal
   com nome e tipo, **Then** a categoria aparece na lista com esse tipo.
2. **Given** uma categoria principal ativa, **When** a pessoa cria uma subcategoria sob ela, **Then** a
   subcategoria aparece sob a categoria principal e tem o mesmo tipo dela.
3. **Given** um cadastro de subcategoria que informa um tipo diferente do da categoria principal,
   **When** a pessoa salva, **Then** o cadastro é recusado (o tipo é herdado, nunca escolhido).
4. **Given** uma subcategoria existente, **When** a pessoa tenta criar uma categoria sob ela, **Then**
   o cadastro é recusado: a hierarquia tem somente dois níveis.
5. **Given** uma categoria principal arquivada, **When** a pessoa tenta criar uma subcategoria sob ela,
   **Then** o cadastro é recusado.
6. **Given** uma categoria principal de outro workspace informada como pai, **When** a pessoa tenta
   criar a subcategoria, **Then** recebe "não encontrado" e nada é criado.
7. **Given** uma categoria ativa "Mercado" sob "Alimentação", **When** a pessoa cria outra ativa
   "mercado " sob a mesma "Alimentação", **Then** o cadastro é recusado por nome repetido; porém o
   mesmo nome sob outra categoria principal é aceito.
8. **Given** uma categoria principal de despesa "Outros", **When** a pessoa cria uma categoria principal
   de receita "Outros", **Then** é aceito, porque a repetição de nomes é verificada dentro do mesmo tipo.
9. **Given** nome vazio, nome longo demais, tipo desconhecido ou cor em formato inválido, **When** a
   pessoa tenta cadastrar, **Then** o cadastro é recusado indicando o campo inválido e nada é criado.

---

### User Story 5 - Corrigir uma categoria (Priority: P2)

A pessoa renomeia uma categoria ou muda sua cor e seu ícone, para corrigir um erro ou adaptar ao seu
jeito. O tipo e a categoria principal de uma categoria não mudam depois de criada.

**Why this priority**: erros de nome acontecem e as padrão podem não agradar, mas o produto funciona
sem isto nos primeiros dias.

**Independent Test**: renomear uma categoria padrão e conferir que a lista mostra o novo nome, e que
tentar mudar o tipo ou a categoria principal é recusado.

**Acceptance Scenarios**:

1. **Given** uma categoria ativa, **When** a pessoa altera o nome, a cor ou o ícone, **Then** a lista
   mostra os novos valores.
2. **Given** uma categoria ativa, **When** a pessoa tenta mudar o tipo ou a categoria principal,
   **Then** a alteração é recusada indicando que esses campos não podem ser mudados.
3. **Given** uma edição com valores inválidos ou um nome já usado por outra categoria ativa irmã,
   **When** a pessoa salva, **Then** a edição é recusada indicando o motivo e a categoria não muda.
4. **Given** uma categoria arquivada, **When** a pessoa tenta editá-la, **Then** a edição é recusada
   (só é possível restaurá-la).

---

### User Story 6 - Arquivar e restaurar categorias (Priority: P2)

Quando uma categoria deixa de ser útil, a pessoa a arquiva em vez de apagá-la. Ela some da lista
padrão, mas os lançamentos que já a usam continuam classificados por ela, e o histórico é preservado.
A pessoa pode ver as arquivadas e restaurar uma delas.

**Why this priority**: mantém a lista limpa sem quebrar o histórico, mas o produto já é útil antes
disso.

**Independent Test**: arquivar uma categoria com subcategorias, conferir que tudo some da lista padrão,
aparece ao pedir as arquivadas e volta a valer depois de restaurada.

**Acceptance Scenarios**:

1. **Given** uma categoria principal ativa com subcategorias ativas, **When** a pessoa a arquiva,
   **Then** ela e todas as suas subcategorias saem da lista padrão, e continuam guardadas com os
   lançamentos que as usam.
2. **Given** uma subcategoria ativa, **When** a pessoa a arquiva, **Then** só ela sai da lista; a
   categoria principal e as irmãs continuam ativas.
3. **Given** uma categoria arquivada, **When** a pessoa a restaura, **Then** ela volta à lista padrão;
   restaurar uma categoria principal não restaura as subcategorias, que são restauradas uma a uma.
4. **Given** uma subcategoria arquivada cuja categoria principal está arquivada, **When** a pessoa tenta
   restaurá-la, **Then** a restauração é recusada até a categoria principal ser restaurada.
5. **Given** uma categoria arquivada cujo nome já foi tomado por uma categoria ativa irmã, **When** a
   pessoa tenta restaurá-la, **Then** a restauração é recusada por nome repetido.
6. **Given** qualquer categoria, **When** a pessoa procura uma forma de apagá-la, **Then** não existe: o
   sistema só arquiva.
7. **Given** uma categoria já arquivada, **When** a pessoa a arquiva de novo (ou restaura uma ativa),
   **Then** a resposta é de sucesso e nada muda.

---

### Edge Cases

- Cor e ícone são opcionais e apenas visuais; a cor, quando informada, segue o formato hexadecimal de
  seis dígitos (por exemplo `#1A9E5C`); o ícone é um texto curto de identificação.
- Nome é aparado nas pontas e mede de 1 a 60 caracteres; "Mercado" e "mercado " são o mesmo nome.
- Categoria principal e subcategoria de mesmo nome (por exemplo "Saúde" e "Saúde" sob ela) são
  aceitas, pois a repetição só é verificada entre irmãs.
- Arquivar a categoria principal arquiva as subcategorias ativas no mesmo ato, de forma atômica: nunca
  fica só parte arquivada.
- Criar uma subcategoria sob uma categoria que foi arquivada no meio do caminho (corrida): recusado, nunca
  cria subcategoria ativa sob pai arquivado.
- Dois cadastros simultâneos do mesmo nome na mesma posição: no máximo um é aceito; o outro recebe a
  recusa de nome repetido, nunca um erro interno.
- Corpo da requisição com campos extras, como informar o workspace, o estado de arquivada ou a data de
  criação: campos não permitidos são recusados, nunca aceitos.
- Identificador de categoria malformado ou inexistente: resposta de "não encontrado", sem erro interno.
- Lançamentos que já usam uma categoria arquivada (spec 004) mantêm a categoria; a restrição de uso é
  só para novos lançamentos.
- Workspace criado concorrentemente a outro cadastro: cada um recebe apenas o seu conjunto padrão.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Todo workspace MUST ter um conjunto padrão de categorias criado automaticamente. O conjunto
  inclui, no mínimo: despesas Alimentação, Moradia, Transporte, Saúde, Lazer, Educação e Outros;
  receitas Salário, Rendimentos e Outros; com subcategorias nas principais de despesa (por exemplo
  Mercado e Restaurantes sob Alimentação; Aluguel e Contas de casa sob Moradia).
- **FR-002**: O conjunto padrão MUST ser criado no mesmo ato do cadastro do workspace pessoal, de forma
  atômica: se a criação das categorias falhar, o cadastro inteiro MUST ser desfeito.
- **FR-003**: Workspaces que já existiam antes desta funcionalidade e não têm nenhuma categoria MUST
  receber o conjunto padrão uma única vez; os que já têm categorias MUST NOT receber nem duplicar nada.
  As categorias padrão MUST NOT ser recriadas depois que a pessoa editar ou arquivar.
- **FR-004**: As categorias padrão MUST ser categorias comuns do workspace: podem ser editadas e
  arquivadas como as demais, e não há distinção entre elas e as criadas pela pessoa.
- **FR-005**: O sistema MUST permitir criar uma categoria principal informando nome e tipo (despesa ou
  receita), e uma subcategoria informando nome e a categoria principal. Cor e ícone são opcionais.
- **FR-006**: O nome MUST ter de 1 a 60 caracteres após remover espaços das pontas. Não pode haver duas
  categorias ativas irmãs com o mesmo nome, sem diferenciar maiúsculas e minúsculas. São irmãs as
  subcategorias da mesma categoria principal, e as categorias principais do mesmo tipo no mesmo
  workspace.
- **FR-007**: A hierarquia MUST ter exatamente dois níveis: uma subcategoria MUST NOT ter filhas. A
  categoria principal informada MUST pertencer ao mesmo workspace e MUST estar ativa.
- **FR-008**: A subcategoria MUST herdar o tipo da categoria principal; informar um tipo diferente MUST
  ser recusado.
- **FR-009**: A cor, quando informada, MUST ser um texto hexadecimal de seis dígitos precedido de `#`;
  o ícone, quando informado, MUST ter de 1 a 40 caracteres.
- **FR-010**: O sistema MUST listar as categorias do workspace organizadas em dois níveis, por padrão só
  as ativas, em ordem estável por nome (sem diferenciar maiúsculas) dentro de cada nível. A pessoa MUST
  poder filtrar por tipo e pedir também as arquivadas.
- **FR-011**: O sistema MUST permitir consultar uma categoria pelo identificador, dentro do seu workspace.
- **FR-012**: O sistema MUST permitir editar nome, cor e ícone de uma categoria ativa. O tipo e a
  categoria principal MUST NUNCA mudar depois de criada. As mesmas regras de validação do cadastro
  valem na edição.
- **FR-013**: O sistema MUST permitir arquivar e restaurar uma categoria, preservando todos os seus dados
  e os lançamentos que a usam. Arquivar uma categoria principal MUST arquivar também, no mesmo ato, as
  suas subcategorias ativas. Restaurar uma categoria principal MUST NOT restaurar suas subcategorias.
- **FR-014**: Restaurar MUST ser recusado se o nome já estiver em uso por uma categoria ativa irmã, ou se
  for subcategoria cuja categoria principal esteja arquivada. Arquivar uma já arquivada, ou restaurar uma
  ativa, MUST responder com sucesso sem efeito. Uma categoria arquivada MUST NUNCA ser editada além da
  restauração.
- **FR-015**: O sistema MUST NÃO oferecer nenhuma forma de apagar uma categoria.
- **FR-016**: Somente os papéis OWNER e EDITOR MUST poder criar, editar, arquivar e restaurar
  categorias. O papel VIEWER MUST poder apenas consultar. A recusa por papel MUST usar resposta de "sem
  permissão", distinta de "não encontrado".
- **FR-017**: Toda operação de categorias MUST ficar sob o identificador do workspace e MUST conferir que
  a pessoa autenticada é membro dele. Para quem não é membro, ou para workspace inexistente, a resposta
  MUST ser a mesma de "não encontrado", sem revelar nada.
- **FR-018**: Uma categoria MUST pertencer a exatamente um workspace e MUST NUNCA ser acessada por
  identificador a partir de outro workspace. Uma subcategoria MUST NUNCA apontar para uma categoria
  principal de outro workspace.
- **FR-019**: O isolamento por workspace das categorias MUST existir nas duas camadas independentes
  exigidas pela constituição (camada de dados e camada de serviço) e MUST ter teste automatizado de
  acesso cruzado para cada operação.
- **FR-020**: As consultas MUST informar a data de criação de cada categoria.
- **FR-021**: O sistema MUST validar toda entrada antes de qualquer gravação, recusando campos fora da
  lista permitida (workspace, estado de arquivada, data de criação) e indicando o campo inválido nas
  recusas.
- **FR-022**: As respostas MUST usar os formatos de saída próprios e, em erro, o formato padronizado do
  projeto, sem vazar detalhes internos.
- **FR-023**: As respostas autenticadas MUST instruir navegadores e intermediários a não guardá-las em
  cache.

### Key Entities *(include if feature involves data)*

- **Categoria**: classificação de um lançamento. Tem nome, tipo (despesa ou receita), cor e ícone
  opcionais, estado (ativa ou arquivada), data de criação e, se for subcategoria, a categoria principal.
  Pertence a exatamente um workspace.
- **Categoria principal**: categoria sem pai; define o tipo de suas subcategorias.
- **Subcategoria**: categoria com pai; herda o tipo do pai e não tem filhas.
- **Conjunto padrão**: lista fixa de categorias e subcategorias copiada para cada workspace, uma única
  vez; depois disso, é só um ponto de partida sem vínculo.
- **Lançamento** (definido na spec 004): usa no máximo uma categoria; aqui só importa que categorias
  arquivadas preservam os lançamentos que já as usam.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% dos workspaces recém-criados têm o conjunto padrão completo logo após o cadastro, sem
  nenhuma ação da pessoa; e 0 workspaces ficam sem ele quando a criação falha.
- **SC-002**: Uma pessoa recém-cadastrada consegue escolher uma categoria de despesa ou de receita para um
  lançamento sem criar nada antes: o conjunto padrão cobre os dois tipos.
- **SC-003**: Em 100% dos testes de hierarquia, nenhuma categoria fica com mais de dois níveis, com tipo
  diferente do da categoria principal ou com categoria principal de outro workspace.
- **SC-004**: Em 100% dos testes de isolamento, tentativas de listar, consultar, criar, editar, arquivar ou
  restaurar categorias de um workspace do qual a pessoa não é membro recebem a mesma resposta de um
  workspace inexistente, e nenhum dado é revelado ou alterado.
- **SC-005**: Em 100% dos testes de permissão, VIEWER consegue consultar e nenhuma operação de alteração
  tem efeito; OWNER e EDITOR conseguem alterar.
- **SC-006**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100%
  das tentativas de acesso a categorias de outro workspace continuam recusadas pela outra camada.
- **SC-007**: Listar as categorias de um workspace com 200 categorias e subcategorias responde em menos de
  1 segundo.
- **SC-008**: Nenhuma operação de categorias oferece apagar uma categoria, e 100% das categorias
  arquivadas mantêm todos os seus dados e os lançamentos que as usam.
- **SC-009**: Em 100% dos testes de concorrência para o mesmo nome na mesma posição, no máximo um cadastro
  é aceito e o outro recebe a recusa de nome repetido, sem erro interno.

## Assumptions

- Esta funcionalidade é só de backend; as telas de categorias fazem parte do roadmap de front-end.
- Os lançamentos (spec 004) ainda não têm rotas. A tabela de lançamentos já existe no banco, então a
  preservação de lançamentos ao arquivar é exercitada nos testes inserindo lançamentos diretamente.
- O conjunto padrão está em português e é igual para todos os workspaces; não há escolha de conjunto no
  cadastro. A lista exata de nomes é detalhada no planejamento, respeitando o mínimo do FR-001.
- Cor e ícone são só visuais; o que o ícone significa fica a cargo do front-end.
- O tipo e a categoria principal não mudam depois de criados, porque lançamentos e relatórios dependem
  do tipo. Para mover uma categoria, a pessoa cria outra no lugar certo e arquiva a antiga.
- Repetição de nome só é verificada entre irmãs ativas; uma arquivada pode ter o nome de uma ativa, e a
  restauração é que checa o conflito.
- Restaurar uma categoria principal não restaura as subcategorias, porque não há como saber quais foram
  arquivadas junto e quais antes; a pessoa restaura as que quiser.
- Categoria arquivada não recebe novos lançamentos (regra a ser aplicada na spec 004); os existentes
  continuam classificados por ela.
- Não há limite de quantidade de categorias por workspace; o uso esperado é de poucas dezenas.
- A lista de categorias não é paginada, dado o volume pequeno esperado.
- Transferências não têm categoria (decisão já tomada no modelo de dados).
- Depende da `001-autenticacao-workspace` (sessão, papéis, isolamento e cadastro do workspace pessoal) e
  da `002-contas` (padrão de arquivar em vez de apagar e de permissão por papel), além das regras da
  constituição (v1.0.0), em especial os princípios II, IV e V.
