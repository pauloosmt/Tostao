# Feature Specification: Importação de extratos

**Feature Branch**: `013-importacao-extratos`

**Created**: 2026-11-10

**Status**: Draft

**Input**: User description: "Importação de extratos: a pessoa envia o arquivo de extrato do banco (OFX ou CSV) para uma conta, vê uma prévia do que será lançado, confirma, e os movimentos entram como lançamentos efetivados daquela conta. Importar o mesmo extrato de novo (ou um extrato que se sobrepõe ao anterior) nunca duplica lançamentos. A categoria pode ser sugerida a partir dos lançamentos anteriores e pode ficar em branco para ser definida depois."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enviar um extrato e ver a prévia (Priority: P1)

A dona baixa o extrato do mês no aplicativo do banco (um arquivo OFX ou CSV) e o envia para a conta `Corrente`. Antes
de gravar qualquer coisa, o sistema mostra o que encontrou no arquivo: cada movimento com data, descrição, valor e
tipo, quais já existem na conta e quais têm algum problema.

**Why this priority**: sem a prévia a pessoa importaria às cegas. Ela é também a parte que mais depende do formato
do arquivo, então é onde o risco está.

**Independent Test**: com uma conta e um arquivo OFX de três movimentos, o envio devolve as três linhas com data,
descrição, valor com sinal e tipo, e nada é gravado.

**Acceptance Scenarios**:

1. **Given** uma conta ativa e um arquivo OFX (versão 1 ou 2) com movimentos, **When** a pessoa o envia para a
   prévia, **Then** o sistema devolve uma linha por movimento com a data, a descrição, o valor em centavos com sinal
   (entrada positiva, saída negativa), o tipo (receita ou despesa) e a chave de importação, **sem gravar nada**.
2. **Given** um arquivo CSV com cabeçalho que tem colunas de data, descrição e valor, **When** é enviado, **Then** as
   linhas são lidas do mesmo jeito, aceitando separador vírgula ou ponto e vírgula, datas `dd/MM/yyyy` ou
   `yyyy-MM-dd` e valores como `1.234,56`, `-1234.56` ou `R$ 12,50`, sempre em centavos exatos.
3. **Given** movimentos que já foram importados antes para a mesma conta, **When** o arquivo é enviado de novo,
   **Then** essas linhas vêm marcadas como já existentes.
4. **Given** um movimento do arquivo com a mesma data e o mesmo valor de um lançamento que a pessoa digitou à mão
   naquela conta, **When** a prévia é gerada, **Then** a linha vem marcada como **possível duplicata** (só um aviso: a
   pessoa decide).
5. **Given** linhas com problema (data inválida ou fora de 2000-01-01 a 2100-12-31, valor zero ou ilegível,
   descrição vazia), **When** a prévia é gerada, **Then** cada uma vem com o motivo e o número da linha no arquivo,
   e as boas continuam normais.
6. **Given** um arquivo que não é OFX nem CSV legível, vazio, sem movimentos, maior que 2 MB ou com mais de 1.000
   movimentos, **When** é enviado, **Then** é recusado inteiro, com o motivo, sem prévia parcial.
7. **Given** uma conta arquivada, de outro workspace ou inexistente, **When** a prévia é pedida, **Then** conta de
   outro workspace ou inexistente responde "não encontrado" e arquivada é recusada como conflito.
8. **Given** uma descrição com mais de 200 caracteres ou com espaços repetidos, **When** a prévia é gerada, **Then**
   a descrição vem sem espaços nas pontas, com os espaços repetidos reduzidos a um e cortada em 200 caracteres.

---

### User Story 2 - Confirmar a importação sem duplicar (Priority: P1)

Satisfeita com a prévia, a pessoa confirma. Os movimentos escolhidos viram lançamentos efetivados da conta. Se ela
importar o mesmo arquivo outra vez, ou um extrato do mês seguinte que repete alguns dias do anterior, só entra o que
ainda não existe.

**Why this priority**: é o motivo de existir da funcionalidade, e a ausência de duplicidade é o que a torna segura de
usar todo mês.

**Independent Test**: confirmar as três linhas de um arquivo cria três lançamentos; confirmar as mesmas três de novo
cria zero e informa três já existentes.

**Acceptance Scenarios**:

1. **Given** linhas vindas da prévia, **When** a pessoa confirma, **Then** cada uma vira um lançamento **efetivado**
   da conta, receita se o valor é positivo e despesa se é negativo, com a data, a descrição e o valor da linha,
   autoria da pessoa, e entra no saldo da conta, no dashboard e nos orçamentos como qualquer lançamento efetivado.
2. **Given** uma linha cuja chave já existe na conta, **When** a pessoa confirma, **Then** a linha é ignorada, sem
   erro, e a resposta informa quantas foram criadas e quantas já existiam.
3. **Given** a mesma confirmação enviada duas vezes (inclusive ao mesmo tempo), **When** as duas terminam, **Then**
   cada linha existe uma única vez.
4. **Given** um lançamento importado que a pessoa excluiu, **When** o mesmo extrato é importado de novo, **Then** ele
   **não** volta: excluir é uma decisão que a importação respeita.
5. **Given** duas compras idênticas no mesmo dia no mesmo arquivo (mesma data, descrição e valor), **When** o arquivo
   é importado, **Then** as duas viram dois lançamentos, e importar o arquivo de novo não cria um terceiro.
6. **Given** uma linha inválida entre as confirmadas (valor zero, data fora do intervalo, categoria inexistente ou
   do tipo errado, mais de 1.000 linhas), **When** a pessoa confirma, **Then** a confirmação inteira é recusada
   indicando a linha e o campo, e **nada** é gravado.
7. **Given** a importação concluída, **When** os lançamentos são listados, **Then** eles aparecem como lançamentos
   comuns (spec 004): podem ser editados, excluídos e filtrados, sem nenhuma restrição extra.
8. **Given** uma conta arquivada entre a prévia e a confirmação, **When** a pessoa confirma, **Then** é recusado
   como conflito e nada é gravado.

---

### User Story 3 - Categorizar na importação ou depois (Priority: P2)

O extrato do banco não traz categoria. Na prévia, cada linha vem com uma **sugestão** tirada do que a pessoa já
lançou com a mesma descrição. Ela aceita, troca ou deixa em branco; o que ficar em branco entra sem categoria e
pode ser encontrado depois.

**Why this priority**: sem isso a pessoa teria de categorizar tudo à mão, mas a importação já é útil sem a
sugestão.

**Independent Test**: depois de lançar "Padaria do Zé" como Alimentação, um extrato com "PADARIA DO ZE" vem com
Alimentação sugerida; confirmar sem categoria cria o lançamento sem categoria, que aparece no filtro de "sem
categoria".

**Acceptance Scenarios**:

1. **Given** lançamentos anteriores com categoria, **When** a prévia é gerada, **Then** cada linha traz, se houver,
   a categoria do lançamento mais recente não excluído da mesma conta ou de outra do workspace com a mesma descrição
   (sem diferença de maiúsculas, acentos nem espaços) e do mesmo tipo, desde que a categoria esteja ativa; sem
   correspondência, a linha vem sem sugestão.
2. **Given** a confirmação com uma categoria em uma linha, **When** a categoria existe, está ativa e é do tipo da
   linha (receita ou despesa), **Then** o lançamento nasce com ela; se não, a confirmação inteira é recusada
   indicando a linha.
3. **Given** a confirmação sem categoria numa linha, **When** ela é gravada, **Then** o lançamento nasce **sem
   categoria**, entra no saldo e no dashboard (no grupo "Sem categoria") e conta só no limite geral dos orçamentos.
4. **Given** lançamentos sem categoria, **When** a pessoa lista lançamentos com `uncategorized=true`, **Then** vê só
   receitas e despesas sem categoria, combinável com conta, datas e busca; combinar com `categoryId` é recusado
   indicando o campo.
5. **Given** um lançamento importado sem categoria, **When** a pessoa o edita escolhendo uma categoria (spec 004),
   **Then** ele passa a tê-la e a chave de importação continua a mesma.

---

### User Story 4 - Só quem pode importa, e o arquivo é só dado (Priority: P1)

Cada workspace importa só para as suas contas; leitores consultam mas não importam; e um arquivo malicioso ou
gigante não derruba nem vaza nada.

**Why this priority**: a importação aceita conteúdo de fora e grava em volume; o isolamento e os limites precisam
valer desde o primeiro dia.

**Independent Test**: com dois workspaces, enviar o extrato para a conta do outro responde "não encontrado" e não
grava; um arquivo com entidade externa XML não busca nada nem trava a resposta.

**Acceptance Scenarios**:

1. **Given** uma pessoa que não é membro do workspace, **When** pede a prévia ou confirma, **Then** recebe a mesma
   resposta de um workspace inexistente e nada é gravado.
2. **Given** um membro com papel VIEWER, **When** tenta a prévia ou a confirmação, **Then** é recusado por falta de
   permissão e nada é gravado; OWNER e EDITOR conseguem.
3. **Given** uma conta ou uma categoria de **outro** workspace, **When** aparece na prévia ou na confirmação,
   **Then** responde "não encontrado" e nada é gravado.
4. **Given** um OFX que declara entidades externas ou expansão de entidades, **When** é enviado, **Then** nada é
   buscado de endereço externo nem de arquivo local, a expansão não consome memória sem limite e a resposta é a
   recusa do arquivo ou as linhas lidas sem a expansão.
5. **Given** qualquer envio, **When** o sistema registra o que aconteceu, **Then** o conteúdo do arquivo, as
   descrições e os valores não aparecem em nenhum registro do sistema.
6. **Given** uma pessoa sem sessão, **When** tenta qualquer rota, **Then** recebe 401.

---

### Edge Cases

- OFX de cartão de crédito: lido do mesmo jeito; compras (valores negativos) viram despesas da conta cartão. Um
  pagamento da fatura (positivo) entra como receita; convertê-lo em transferência não existe nesta versão (o tipo de
  um lançamento nunca muda, spec 004): a pessoa exclui a linha e cria a transferência.
- Data do OFX com horário e fuso (`20261105120000[-3:BRT]`): vale a data como escrita, sem converter fuso.
- Valor do OFX com vírgula decimal ou sinal explícito (`+`): aceito, sempre em centavos exatos, sem ponto flutuante.
- Valor com mais de duas casas decimais (`10.505` no OFX, `10,5050` no CSV): linha com problema (não se arredonda
  dinheiro em silêncio). No CSV, `10.505` é dez mil quinhentos e cinco (milhar).
- Valor acima do limite de 1.000.000.000.000 centavos: linha com problema.
- OFX sem `FITID`, ou CSV sem coluna de identificador: a chave é derivada da data, do valor, da descrição normalizada
  e da ordem entre linhas idênticas do arquivo; o mesmo arquivo gera sempre as mesmas chaves.
- Dois `FITID` iguais no mesmo arquivo: a segunda linha vem com problema ("identificador repetido").
- Arquivo em UTF-8 (com ou sem BOM) ou em Windows-1252/ISO-8859-1: os acentos saem certos nos dois.
- CSV com linhas de resumo, saldo ou total antes do cabeçalho: o cabeçalho é a primeira linha que tem as colunas de
  data, descrição e valor; o que vem antes é ignorado. Linhas totalmente vazias são ignoradas.
- CSV com aspas, separador dentro de aspas e quebras de linha `\r\n` ou `\n`: lido corretamente.
- Fórmula de planilha na descrição (`=HYPERLINK(...)`, `+cmd`, `@SUM`): é só texto, gravado como veio; nada é
  interpretado.
- Extrato de uma conta enviado para outra conta: a chave vale por conta, então importa nas duas sem conflito; a
  pessoa é quem escolhe a conta certa.
- Prévia e confirmação são independentes: o sistema não guarda a prévia. A confirmação confere tudo de novo, e as
  chaves ignoram o que entrou entre as duas.
- Extrato com período já fechado em orçamento ou fatura: nada é bloqueado; os lançamentos entram e os cálculos
  (saldo, dashboard, orçamentos, fatura) refletem a data deles.
- Importar uma linha cuja data é futura: entra efetivada como veio; o banco informa o que já aconteceu.
- Campos fora da lista permitida na confirmação (workspace, autoria, estado, tipo) são recusados.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: O sistema MUST permitir enviar um arquivo para uma conta ativa do workspace e devolver uma **prévia**
  das linhas encontradas, sem gravar nada.
- **FR-002**: O sistema MUST reconhecer o formato pelo **conteúdo**, não pelo nome nem pelo tipo declarado: OFX
  (versões 1 e 2, extrato bancário e de cartão) ou CSV; qualquer outro MUST ser recusado como formato não
  reconhecido.
- **FR-003**: Para OFX, cada movimento MUST dar a data lançada (como escrita), o valor, o identificador do banco
  (`FITID`) e a descrição formada pelo nome e/ou memorando do movimento. Para CSV, o cabeçalho MUST ser reconhecido
  pelos nomes das colunas (sem diferença de maiúsculas e acentos): data (`data`, `date`), descrição (`descrição`,
  `histórico`, `lançamento`, `memo`, `description`) e valor (`valor`, `amount`); uma coluna opcional `id` ou
  `identificador` vale como identificador.
- **FR-004**: Valores MUST ser lidos para centavos inteiros exatos, sem ponto flutuante, aceitando vírgula ou ponto
  decimal, sinal `+` ou `-` e o prefixo `R$`. No OFX, que não usa separador de milhar, o último ponto ou vírgula é o
  decimal. No CSV, com os dois separadores o último é o decimal; com um só tipo, seguido de exatamente três dígitos
  vale como milhar (`1.234` = 1.234,00) e seguido de um ou dois como decimal (`12,5` = 12,50). Mais de duas casas
  decimais, valor zero ou fora de 1 a 1.000.000.000.000 centavos em módulo MUST ser uma linha com problema.
- **FR-005**: Datas MUST estar entre 2000-01-01 e 2100-12-31; no CSV, em `dd/MM/yyyy` ou `yyyy-MM-dd`; no OFX, no
  formato `AAAAMMDD` com horário e fuso opcionais, que são ignorados. Fora disso MUST ser uma linha com problema.
- **FR-006**: A descrição MUST sair sem espaços nas pontas, com sequências de espaços reduzidas a um e cortada em
  200 caracteres; vazia MUST ser uma linha com problema.
- **FR-007**: O arquivo MUST ter no máximo 2 MB e 1.000 movimentos; acima disso, vazio, sem nenhum movimento ou
  ilegível como um todo, MUST ser recusado inteiro. Uma linha com problema MUST NOT impedir as outras.
- **FR-008**: Cada linha da prévia MUST trazer: o número dela no arquivo, a data, a descrição, o valor com sinal, o
  tipo (receita se positivo, despesa se negativo), a chave de importação, se **já existe** na conta, se é **possível
  duplicata**, a categoria sugerida (se houver) e os problemas (se houver).
- **FR-009**: A **chave de importação** MUST ser estável: o mesmo movimento do mesmo arquivo gera sempre a mesma
  chave. Com identificador do banco, a chave é derivada dele; sem identificador, da data, do valor, da descrição
  normalizada e da ordem entre linhas idênticas do arquivo. Duas linhas idênticas no mesmo arquivo MUST ter chaves
  diferentes.
- **FR-010**: "Já existe" MUST significar que existe na **mesma conta** um lançamento, **inclusive excluído**, com a
  mesma chave. "Possível duplicata" MUST significar que existe na mesma conta um lançamento não excluído **sem chave
  de importação**, com a mesma data e o mesmo valor com sinal; é só informativo e MUST NOT impedir a importação.
- **FR-011**: A categoria sugerida MUST ser a do lançamento mais recente, não excluído e com categoria ativa, do
  workspace, com o mesmo tipo e a mesma descrição (sem diferença de maiúsculas, acentos e espaços repetidos);
  desempate pela data e, depois, pelo momento de criação.
- **FR-012**: O sistema MUST permitir confirmar a importação de até 1.000 linhas, cada uma com a chave, a data, a
  descrição, o valor com sinal e, opcionalmente, a categoria. Cada linha confirmada MUST virar um lançamento
  **efetivado** da conta, receita se positivo e despesa se negativo, com autoria da pessoa logada, sem observação,
  sem efetivação automática e sem vínculo com parcela, recorrência ou transferência.
- **FR-013**: A confirmação MUST conferir cada linha com as mesmas regras de um lançamento comum (data entre 2000 e
  2100, valor de 1 a 1.000.000.000.000 centavos em módulo, descrição de 1 a 200 caracteres sem espaços nas pontas) e
  a chave (de 1 a 200 caracteres, sem espaços nas pontas). Qualquer linha inválida MUST recusar a confirmação
  inteira indicando a posição da linha e o campo, sem gravar nada.
- **FR-014**: A categoria de uma linha, quando informada, MUST existir no workspace, estar ativa e ser do tipo da
  linha. De outro workspace ou inexistente MUST responder "não encontrado"; arquivada MUST ser recusada como
  conflito; de outro tipo MUST ser recusada indicando a linha e o campo. Sem categoria, o lançamento MUST nascer sem
  categoria. A conta MUST ser conferida e travada antes de gravar, como na criação de lançamentos.
- **FR-015**: Linhas cuja chave já existe na conta (inclusive excluída) MUST ser ignoradas sem erro. A resposta MUST
  trazer os lançamentos criados e a quantidade de linhas ignoradas. Chaves repetidas dentro de uma mesma confirmação
  MUST ser recusadas indicando a linha.
- **FR-016**: A confirmação MUST ser atômica e idempotente: uma chave existe no máximo uma vez por conta, inclusive
  com confirmações simultâneas, e uma falha no meio MUST NOT deixar lançamento algum gravado.
- **FR-017**: Um lançamento importado MUST ser um lançamento comum: pode ser editado, excluído e filtrado, e a
  edição MUST manter a chave. Excluí-lo MUST manter a chave ocupada, para que a importação não o recrie.
- **FR-018**: A listagem de lançamentos MUST aceitar o filtro `uncategorized=true`, que devolve só receitas e
  despesas sem categoria, combinável com os demais filtros, exceto `categoryId` (recusado indicando o campo).
  Valores diferentes de `true` e `false` MUST ser recusados.
- **FR-019**: O sistema MUST informar em toda resposta de lançamento se ele foi importado (a chave não é exposta
  em respostas de lançamento), sem mudar nenhum campo existente.
- **FR-020**: Prévia e confirmação MUST exigir papel OWNER ou EDITOR. VIEWER MUST receber "sem permissão". Quem não é
  membro do workspace MUST receber a mesma resposta de um workspace inexistente. Sem sessão, 401.
- **FR-021**: Conta e categoria MUST ser do workspace da rota; as duas camadas de isolamento (políticas do banco e
  filtro explícito por workspace no serviço) MUST valer cada uma sozinha.
- **FR-022**: O arquivo MUST ser tratado só como dado: nenhuma entidade externa, instrução de processamento ou
  expansão de entidades é resolvida, nada é buscado de fora e o consumo de memória e de tempo fica limitado pelos
  limites do FR-007.
- **FR-023**: O conteúdo do arquivo, as descrições e os valores MUST NOT ser registrados nos logs do sistema.
- **FR-024**: Valores e datas MUST seguir as regras de dinheiro da constituição (centavos inteiros, sem arredondar
  em silêncio) e nenhuma exceção MUST ser capturada dentro da transação de gravação.
- **FR-025**: Os testes de todas as specs anteriores (001 a 012) MUST continuar passando sem mudar o resultado
  esperado.

### Key Entities *(include if feature involves data)*

- **Extrato**: o arquivo enviado (OFX ou CSV). Não é guardado: existe só durante a prévia.
- **Linha do extrato**: um movimento lido do arquivo, com número, data, descrição, valor com sinal, chave, estado
  (já existe, possível duplicata) e problemas. Existe só na resposta da prévia.
- **Chave de importação**: o que identifica um movimento do banco naquela conta. Guardada no lançamento importado,
  única por conta (inclusive para excluídos). Nunca muda.
- **Lançamento importado**: um lançamento comum (spec 004), efetivado, de receita ou despesa, com chave de
  importação e, talvez, sem categoria.
- **Conta** (spec 002) e **Categoria** (spec 003): onde os lançamentos entram e como são classificados.
- **Papel**: o que a pessoa pode fazer no workspace (OWNER, EDITOR ou VIEWER), definido na spec 001.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Em 100% dos testes de formato, arquivos OFX (versões 1 e 2, banco e cartão, com e sem `FITID`) e CSV
  (vírgula e ponto e vírgula, aspas, cabeçalho precedido de resumo, UTF-8 e Windows-1252) dão as mesmas linhas
  esperadas, com data, descrição e valor exatos em centavos.
- **SC-002**: Em 100% dos testes, a prévia não grava nada, e um arquivo recusado (formato, tamanho, vazio) não deixa
  prévia parcial nem gravação.
- **SC-003**: Em 100% dos testes de idempotência, importar o mesmo arquivo duas vezes (em série e em paralelo) deixa
  exatamente um lançamento por movimento, e um extrato que se sobrepõe ao anterior cria só os movimentos novos.
- **SC-004**: Em 100% dos testes, um lançamento importado e depois excluído não é recriado por nova importação, e
  duas linhas idênticas do mesmo arquivo viram dois lançamentos que não se duplicam em nova importação.
- **SC-005**: Em 100% dos testes de atomicidade, uma linha inválida ou uma falha no meio da confirmação não deixa
  lançamento algum gravado.
- **SC-006**: Em 100% dos testes de integração, os lançamentos importados entram em exatos centavos no saldo da
  conta, no dashboard (receitas e despesas sem categoria no grupo "Sem categoria"), nos orçamentos (só no limite
  geral, quando sem categoria) e na fatura do cartão, e ficam disponíveis para editar, categorizar e excluir.
- **SC-007**: Em 100% dos testes, a sugestão de categoria segue a regra do FR-011 e nunca sugere categoria
  arquivada, de outro tipo ou de outro workspace, e o filtro `uncategorized` devolve exatamente as receitas e
  despesas sem categoria.
- **SC-008**: Em 100% dos testes de isolamento, prévia e confirmação em workspace do qual a pessoa não é membro, ou
  com conta ou categoria de outro workspace, recebem a mesma resposta de um recurso inexistente e nada é gravado.
- **SC-009**: Em 100% dos testes de permissão, VIEWER não consegue prévia nem confirmação e nada é gravado; OWNER e
  EDITOR conseguem.
- **SC-010**: Com a proteção de uma das duas camadas de isolamento removida de propósito em teste, 100% das
  tentativas de importar para conta de outro workspace continuam recusadas pela outra camada.
- **SC-011**: Em 100% dos testes de segurança do arquivo, entidades externas e expansão de entidades não buscam
  nada nem consomem memória sem limite, e nenhum conteúdo do arquivo aparece nos logs.
- **SC-012**: Em 100% dos testes de validação, valores, datas, descrições, chaves, categorias, quantidade de linhas e
  campos extras inválidos são recusados indicando o campo e a linha, e nada é gravado.
- **SC-013**: Todos os testes das specs 001 a 012 continuam passando sem mudar o resultado esperado (FR-025).
- **SC-014**: Com 20.000 lançamentos no workspace, a prévia de um arquivo de 1.000 movimentos responde em menos de 2
  segundos e a confirmação de 1.000 linhas em menos de 3 segundos.

## Assumptions

- Esta funcionalidade é só de backend; as telas (escolher o arquivo, revisar a prévia, ajustar categorias)
  fazem parte do roadmap de front-end.
- Importação é **sempre para uma conta escolhida pela pessoa**: o sistema não tenta descobrir a conta pelo arquivo.
- O sinal do valor segue o padrão dos bancos: entrada positiva, saída negativa. Extratos de cartão que usem a convenção
  inversa não são tratados nesta versão.
- Todo movimento importado é **efetivado**: o extrato do banco só traz o que já aconteceu. Não existe importação
  de lançamentos pendentes.
- Só receita e despesa são importadas. Transferências entre contas do próprio workspace e a conciliação do saldo do
  extrato com o saldo da conta ficam para uma funcionalidade futura.
- Não existe desfazer a importação em lote: os lançamentos são excluídos um a um (spec 004).
- A prévia não é guardada. Confirmar é uma operação independente, que confere tudo de novo.
- A chave sem identificador do banco protege contra reimportar o **mesmo arquivo** e contra sobreposição de extratos
  em que as linhas idênticas mantêm a mesma ordem; não é garantia contra extratos que reordenam movimentos
  idênticos do mesmo dia.
- Formatos suportados: OFX e CSV genérico por nome de coluna. Layouts específicos de um banco que fujam disso
  (colunas de crédito e débito separadas, por exemplo) ficam para depois.
- A descrição vem do banco e pode ser pouco amigável; renomear é a edição comum (spec 004).
- Moeda única (BRL).
- Depende da `001-autenticacao-workspace` (sessão, papéis e isolamento), da `002-contas`, da `003-categorias`, da
  `004-lancamentos` (lançamentos, edição e exclusão lógica) e das `005` a `008` (que já tratam lançamento sem
  categoria), além das regras da constituição (v1.0.0), em especial os princípios II, IV, V, VI e VIII.
