# Research: Importação de extratos

Decisões técnicas e as alternativas descartadas. Spec em [spec.md](spec.md).

## 1. Duas rotas sem estado: prévia e confirmação

**Decisão**: `POST .../accounts/{accountId}/import/preview` (multipart, não grava) e `POST .../accounts/{accountId}/import`
(JSON, grava). O servidor não guarda a prévia: a confirmação recebe as linhas, confere tudo de novo e grava.

**Por quê**: sem tabela de rascunhos, sem expiração, sem limpeza. A segurança vem da chave de importação (item 4): repetir
a confirmação é inofensivo. A pessoa pode editar a categoria de cada linha entre as duas etapas sem nenhum estado novo.

**Descartado**: um envio só que já grava (a pessoa importaria às cegas) e uma prévia guardada em tabela com identificador
(estado a mais, para ganhar só a validação de que as linhas não foram adulteradas, o que não importa: quem confirma é um
membro com permissão de escrita, que poderia criar os mesmos lançamentos um a um).

## 2. O OFX é lido por um leitor próprio de marcas, sem biblioteca de XML

**Decisão**: um leitor pequeno percorre o texto procurando `<MARCA>`, e dentro de cada bloco `STMTTRN` coleta as folhas
`TRNTYPE`, `DTPOSTED`, `TRNAMT`, `FITID`, `NAME` e `MEMO`. O mesmo leitor serve ao OFX 1 (SGML, em que as folhas não têm
marca de fechamento) e ao OFX 2 (XML): o valor de uma folha é o texto até a próxima `<`. Declarações `<!...>` e instruções
`<?...?>` são puladas como texto; nenhuma entidade além das cinco predefinidas (`&amp; &lt; &gt; &quot; &apos;`) e das
numéricas é expandida.

**Por quê**: o OFX 1 não é XML válido, então um parser de XML não lê metade dos extratos brasileiros. E, sem parser de XML,
não há resolução de entidade externa nem expansão de entidade: o ataque (XXE, "bilhão de risadas") **não existe por
construção** (FR-022). O arquivo tem no máximo 2 MB, o que limita tempo e memória.

**Descartado**: `javax.xml` com `FEATURE_SECURE_PROCESSING` (não lê OFX 1; exigiria pré-processar o texto); biblioteca de
OFX de terceiros (dependência nova para ler um formato pequeno, e a segurança dela passaria a ser nossa a auditar).

**Blocos lidos**: `STMTTRN` dentro de extrato bancário (`STMTRS`) e de cartão (`CCSTMTRS`) são iguais, então basta o
bloco. Blocos de investimento (`INVTRAN`) não têm `STMTTRN` e são ignorados.

## 3. O CSV é lido por um leitor de RFC 4180 com detecção do separador pelo cabeçalho

**Decisão**: o leitor trata aspas, separador e quebra de linha dentro de aspas, `\r\n` e `\n`. O separador é o primeiro de
`;` e `,` com o qual se encontra uma linha de cabeçalho (primeira linha com as três colunas reconhecidas); linhas antes
dela (resumos) são ignoradas, linhas totalmente vazias também. Os nomes das colunas são comparados sem maiúsculas e sem
acentos. Os valores seguem a regra do FR-004: um só tipo de separador seguido de exatamente três dígitos é milhar.

**Por quê**: os bancos brasileiros usam `;` com vírgula decimal; os demais, `,` com ponto decimal. Tentar `;` primeiro é
seguro: um arquivo com `,` lido por `;` fica com uma coluna só, cujo nome é a linha inteira e nunca casa com os nomes.

## 4. A chave de importação

**Decisão**: coluna nova `transactions.import_key TEXT` e índice único **parcial** `(account_id, import_key) WHERE
import_key IS NOT NULL`, **inclusive para linhas excluídas**. Formato da chave:

- com identificador do banco (`FITID` do OFX, ou coluna `id` do CSV): `id:` + o identificador, ou `id:h:` + SHA-256
  hexadecimal dele quando passa de 150 caracteres;
- sem identificador: `h:` + SHA-256 hexadecimal de `data | valor em centavos com sinal | descrição normalizada | ordem`,
  em que a descrição normalizada é minúscula, sem acentos e com espaços reduzidos, e a ordem (0, 1, ...) conta as linhas
  idênticas do arquivo na ordem em que aparecem.

**Por quê**: a chave diz "este movimento do banco já entrou nesta conta". Incluir as excluídas é o mesmo critério da spec
011 (ocorrência excluída não volta): importar de novo nunca desfaz uma exclusão. O parcial mantém todas as linhas antigas
(`NULL`) fora do índice. O hash curto e sem segredo basta: não é credencial.

**Limite conhecido** (registrado nas premissas): sem identificador, extratos que reordenam movimentos idênticos do mesmo
dia podem gerar chaves diferentes. Com `FITID`, que é o caso comum, não há esse limite.

## 5. Concorrência: uma trava por conta e `ON CONFLICT DO NOTHING`

**Decisão**: a confirmação toma `pg_advisory_xact_lock(hashtextextended(accountId::text, 1))` antes de gravar e insere com
`INSERT ... ON CONFLICT (account_id, import_key) WHERE import_key IS NOT NULL DO NOTHING RETURNING id`.

**Por quê**: duas confirmações simultâneas das mesmas linhas, em ordens diferentes, podem entrar em impasse (cada uma
espera a chave que a outra inseriu). A trava por conta as serializa. O `ON CONFLICT` é a forma sem exceção (princípio VI)
de ignorar o que já existe, e a trava só serve ao impasse: o índice único continua sendo a garantia de não duplicar. É o
mesmo padrão da geração de ocorrências da spec 011.

## 6. Gravação em blocos de linhas

**Decisão**: `INSERT ... VALUES (...), (...) ...` em blocos de 100 linhas, com parâmetros nomeados, na mesma transação.

**Por quê**: até 1.000 linhas em 10 instruções em vez de 1.000 idas ao banco (cada ida custa milissegundos num banco
remoto), sem depender de passagem de arrays pelo driver. Os `RETURNING id` de todos os blocos dão os lançamentos criados;
o número de ignoradas é a diferença.

## 7. Verificações antes de gravar

**Decisão**: na confirmação, nesta ordem: papel (aspecto), conta (existe, não arquivada; travada com `FOR SHARE` como na
criação de lançamentos), validação de cada linha (campos), categorias distintas (existe, ativa, do tipo da linha, com
`FOR SHARE`), trava da conta, inserção. Nada é capturado em `try/catch` dentro da transação.

**Por quê**: é a ordem da criação de lançamentos (spec 004), reaproveitando `UsableRefs`. Como o tipo vem do sinal, a
categoria de uma linha é conferida contra receita ou despesa conforme o valor.

## 8. Sugestão de categoria sem extensão nova

**Decisão**: uma consulta traz `(descrição, tipo, categoria)` dos lançamentos não excluídos, com categoria ativa, do
workspace, do mais recente para o mais antigo; o serviço normaliza a descrição em Java (a mesma normalização da chave) e
guarda a primeira categoria de cada par (descrição normalizada, tipo) que aparece no arquivo.

**Por quê**: normalizar sem acentos no banco exigiria a extensão `unaccent` (nova migration de extensão, nem sempre
disponível no banco de produção). Para até 20.000 lançamentos por workspace (escala do projeto) a leitura é de uma
passada e cabe no orçamento de 2 s do SC-014.

**Descartado**: coluna gerada com a descrição normalizada e índice (mais schema para um ganho que só aparece com
dezenas de milhares de lançamentos); sugerir pela categoria mais frequente (mais caro e menos previsível que "a última").

## 9. Possível duplicata

**Decisão**: uma consulta traz, da mesma conta, os lançamentos não excluídos e **sem chave** entre a menor e a maior data
do arquivo; o serviço compara (data, valor com sinal) em memória. É só um aviso na linha.

**Por quê**: o caso real é a pessoa ter digitado a compra à mão e depois importar o extrato. Os lançamentos que já têm
chave são cobertos por "já existe" e ficam de fora da consulta.

## 10. Rotas, upload e respostas

**Decisão**:

- Prévia: `POST /api/v1/workspaces/{wid}/accounts/{accountId}/import/preview`, `multipart/form-data` com a parte `file`.
  Resposta 200 com `{format, lines[], totals}`.
- Confirmação: `POST /api/v1/workspaces/{wid}/accounts/{accountId}/import`, JSON `{lines[]}`. Resposta 201 com `{created[],
  skipped}` quando cria ao menos uma e 200 quando tudo já existia.
- Limite do upload por configuração do Spring (`spring.servlet.multipart.max-file-size=2MB`, `max-request-size=3MB`);
  o estouro vira 413 em Problem Details por um tratador novo. Os demais erros do arquivo são 400 com o campo `file`.
- A ordem das respostas é: sessão (401), participação e papel (404, 403), conta (404, 409), arquivo (400). O 413 vem antes
  porque o corpo é recusado ao ser recebido, o que não revela nada sobre o workspace.
- O arquivo é lido como bytes; a decodificação tenta UTF-8 estrito (com ou sem BOM) e, se o texto não for UTF-8 válido,
  Windows-1252. Os acentos saem certos nos dois.
- CSRF continua desligado: o cookie é `SameSite=Lax`, que não vai em POST entre sites, e o envio multipart não ganha
  nenhuma exceção.

## 11. Filtro `uncategorized` e campo `imported`

**Decisão**: `GET .../transactions?uncategorized=true` acrescenta `t.category_id IS NULL AND t.type <> 'TRANSFER'`
(o tipo é só para deixar a intenção explícita: transferência nunca tem categoria). `uncategorized` junto com `categoryId` é
400 no campo `uncategorized`; valor que não é `true` nem `false` é 400. `TransactionResponse` ganha `imported`
(`import_key IS NOT NULL`), o último campo; a chave não sai.

**Por quê**: sem o filtro não há como achar o que falta categorizar. A edição (spec 004) já aceita escolher a categoria de
um lançamento sem categoria; ela não toca `import_key` porque a coluna não está entre as que a aplicação pode atualizar.

## 12. Nada do arquivo vai para o registro

**Decisão**: nenhuma mensagem de exceção, log ou resposta de erro cita o conteúdo do arquivo; os erros do arquivo falam
do que faltou ("nenhum movimento encontrado", "linha 12: data inválida"), nunca do texto lido. Um teste captura a saída do
processo e prova que descrição, valor e chave de um arquivo de teste não aparecem.

## 13. Efeito em specs anteriores

- `TransactionResponse` (spec 004): campo novo `imported`; os campos existentes não mudam. Todos os construtores no
  código de produção e nos testes recebem o valor.
- `TransactionFilter` e a listagem: parâmetro novo opcional; sem ele a listagem é idêntica.
- Dashboard, orçamentos, fatura e saldo (specs 005 a 008): já tratam lançamento sem categoria e já somam só efetivados;
  nenhuma mudança, só provas novas com lançamentos importados.
- Aspecto de workspace (spec 001): sem mudança; as duas rotas novas usam `@WorkspaceScoped(write = true)`.
