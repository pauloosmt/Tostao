---

description: "Tarefas da feature 013-importacao-extratos"
---

# Tasks: Importação de extratos

**Input**: documentos em `/specs/013-importacao-extratos/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi.yaml](contracts/openapi.yaml), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição II, IV, V e VIII; FR-021 pede as duas camadas de isolamento; FR-022 e FR-023 pedem prova de segurança do arquivo; SC-003 pede idempotência em série e em paralelo; FR-025 pede prova de não regressão).

**Organization**: só backend; uma migration (`V14`). Ordem: schema e leitores primeiro (puros, provados sem rota), depois a prévia, a confirmação, a categorização e as provas de integração, isolamento e segurança do arquivo, e por fim contrato, README e quickstart real.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação de caminhos: `MAIN` = `backend/src/main/java/com/tostao/tostao_api`, `TEST` = `backend/src/test/java/com/tostao/tostao_api`, `MIG` = `backend/src/main/resources/db/migration`, `FILES` = `backend/src/test/resources/statements`
- **Commits**: pequenos, Conventional Commits, mensagem curta em inglês, **sem co-author**; **código junto com os testes que o provam**; sem commit só para marcar tarefa (a marca `[X]` vai no commit que fecha a tarefa)
- **Rodar Maven neste computador**: `JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`, Docker Desktop aberto e no PATH. Mandar o log para um arquivo, com `timeout`, a partir de `backend/`
- **Privacidade**: nenhum teste, log ou mensagem de erro repete descrição, valor ou chave de um arquivo de extrato

---

## Phase 1: Base

- [X] T001 Criar `MIG/V14__statement_import.sql` como no [data-model.md](data-model.md): coluna `import_key`, as duas restrições (`transactions_import_key_format`, `transactions_import_key_coherent`) e o índice único parcial `transactions_import_key_key`. Criar `TEST/imports/ImportSchemaTest.java`: coluna e restrições existem; chave vazia, com espaços nas pontas, com 201 caracteres, em transferência, em parcela e em ocorrência de recorrência são recusadas pelo banco; a mesma chave na mesma conta é recusada (inclusive se a primeira está excluída) e em contas diferentes é aceita; linhas sem chave não colidem; `tostao_app` insere com chave mas **não** consegue atualizar `import_key`; o RLS segue valendo (a chave de outro workspace não é visível)
- [X] T002 [P] Criar `MAIN/imports/Money.java` (classe final, `parseOfx(String)` e `parseCsv(String)` para centavos `long`, ou resultado de problema): sem ponto flutuante, `+`/`-`, `R$`, regras de separador do FR-004 (OFX: o último é o decimal; CSV: dois tipos, o último decide; um tipo seguido de três dígitos é milhar, de um ou dois é decimal), mais de duas casas, zero e acima de 1.000.000.000.000 como problema. `TEST/imports/MoneyTest.java` com a tabela de casos (`1.234,56`, `-1234.56`, `R$ 12,50`, `1.234`, `12,5`, `10.505` no OFX, `10,5050`, `0,00`, vazio, texto)
- [X] T003 [P] Criar `MAIN/imports/ImportKeys.java`: `normalize(String)` (minúscula, sem acentos, espaços reduzidos), `fromIdentifier(String)` (`id:` + valor, ou `id:h:` + SHA-256 acima de 150 caracteres) e `derived(date, cents, description, ordinal)` (`h:` + SHA-256 hexadecimal). `TEST/imports/ImportKeysTest.java`: estabilidade, acentos e caixa, ordem de linhas idênticas, identificador longo, tamanho máximo 200
- [X] T004 [P] Criar `MAIN/imports/StatementLine.java` (número, data, descrição, valor, identificador, problemas) e `MAIN/imports/OfxReader.java` (leitor de marcas para OFX 1 e 2; folhas `DTPOSTED`, `TRNAMT`, `FITID`, `NAME`, `MEMO` dentro de `STMTTRN`; data como escrita, descrição de `NAME` e/ou `MEMO`, `FITID` repetido como problema; só as cinco entidades predefinidas e as numéricas; `<!...>` e `<?...?>` pulados). Criar `FILES/` com `banco.ofx` (OFX 1), `banco2.ofx` (OFX 2), `cartao.ofx`, `entidade.ofx` (com `DOCTYPE` e `ENTITY` externa) e `TEST/imports/OfxReaderTest.java`
- [X] T005 [P] Criar `MAIN/imports/CsvReader.java` (RFC 4180; separador `;` ou `,` achado pelo cabeçalho; cabeçalho por nome de coluna sem caixa e sem acentos; linhas antes do cabeçalho e linhas vazias ignoradas; datas `dd/MM/yyyy` e `yyyy-MM-dd`; coluna opcional `id`/`identificador`). Criar `FILES/banco.csv` (`;`), `banco-virgula.csv` (`,`), `resumo.csv` (resumo antes do cabeçalho), `latin1.csv` (Windows-1252) e `TEST/imports/CsvReaderTest.java`: aspas, separador e quebra de linha dentro de aspas, `\r\n`, fórmula na descrição lida como texto, sem cabeçalho reconhecido
- [X] T006 Criar `MAIN/imports/StatementParser.java`: recebe os bytes e devolve `{formato, linhas}` ou recusa o arquivo inteiro (vazio, mais de 2 MB, formato não reconhecido pelo conteúdo, sem movimento, mais de 1.000 movimentos) com `FieldValidationException("file", ...)`; decodifica UTF-8 estrito (com ou sem BOM) e, se inválido, Windows-1252; normaliza a descrição (espaços, 200 caracteres), confere a data (2000-01-01 a 2100-12-31) e a descrição vazia, e calcula as chaves (`ImportKeys`, ordem entre linhas idênticas). `TEST/imports/StatementParserTest.java` com os arquivos de `FILES/`

**Checkpoint**: V14 aplicada e provada; os leitores dão as linhas exatas dos arquivos de teste; nada das specs anteriores mudou

---

## Phase 2: US1 - Prévia (P1)

- [X] T007 [US1] Em `MAIN/transaction/dto/TransactionResponse.java` acrescentar `boolean imported` (último campo), em `MAIN/transaction/TransactionService.java` ler `t.import_key is not null` no `SELECT` e no `ROW_MAPPER`, e atualizar todo construtor de `TransactionResponse` no código e nos testes (valor `false`). Provar em `TEST/transaction/` que todo lançamento existente responde `imported=false` e que os testes das specs 004 a 011 seguem verdes
- [X] T008 [US1] Em `application.properties` definir `spring.servlet.multipart.max-file-size=2MB` e `max-request-size=3MB`; em `MAIN/common/error/` tratar o estouro (`MaxUploadSizeExceededException`) como 413 Problem Details sem citar o arquivo. Criar `MAIN/imports/dto/` (`ImportPreview`, `ImportLinePreview`), `MAIN/imports/ImportService.java` (`preview`: `@WorkspaceScoped(write = true)`, conta ativa via `UsableRefs`, `StatementParser`, chaves existentes, possíveis duplicatas e sugestão de categoria, só leitura) e `MAIN/imports/ImportController.java` (`POST .../accounts/{accountId}/import/preview`). Criar `TEST/imports/ImportScenario.java` (apoio) e `TEST/imports/PreviewTest.java`: OFX 1, OFX 2, CSV, cartão; linhas com problema com o motivo; já existente; possível duplicata; sugestão; recusas (formato, vazio, 413, mais de 1.000 linhas, conta arquivada, conta de outro workspace); a prévia **não grava** (impressão digital da tabela antes e depois)

**Checkpoint**: a prévia funciona e não grava; `./mvnw verify` verde para o módulo

---

## Phase 3: US2 - Confirmar sem duplicar (P1)

- [ ] T009 [US2] Em `MAIN/imports/dto/` criar `ImportConfirmRequest`, `ImportLineRequest` (campos fechados, Bean Validation: chave, data, descrição, valor sem zero e com módulo até 1.000.000.000.000, categoria opcional) e `ImportConfirmResponse`; em `ImportService` acrescentar `confirm` (`@WorkspaceScoped(write = true)` + `@Transactional`): conta ativa travada, linhas validadas, chaves repetidas na mesma confirmação recusadas, categorias distintas conferidas (existe, ativa, do tipo da linha), `pg_advisory_xact_lock` por conta e `INSERT ... ON CONFLICT DO NOTHING RETURNING id` em blocos de 100, devolvendo os criados (carregados com o `SELECT` de `TransactionService`) e `skipped`; em `ImportController` a rota `POST .../accounts/{accountId}/import` (201 quando cria, 200 quando tudo já existia). Criar `TEST/imports/ConfirmImportTest.java`: cada linha vira lançamento efetivado, receita pelo sinal positivo e despesa pelo negativo, autoria da sessão; saldo da conta em exatos centavos; linha inválida (cada campo) recusa **tudo** com `lines[N].campo` e não grava; categoria de outro workspace (404), arquivada (409), do tipo errado (400); campos extras recusados
- [ ] T010 [US2] Criar `TEST/imports/ImportIdempotencyTest.java`: confirmar duas vezes não duplica (skipped certo e status 200); extrato sobreposto cria só os novos; lançamento importado excluído não volta; duas linhas idênticas viram dois lançamentos e não se duplicam na repetição; a edição mantém a chave; N confirmações simultâneas das mesmas linhas (threads reais, ordens diferentes) deixam um lançamento por movimento e não entram em impasse; falha no meio (conta arquivada entre as etapas) não deixa nada

**Checkpoint**: o fluxo completo (prévia, confirmação, repetição) funciona; é o MVP

---

## Phase 4: US3 - Categorizar (P2)

- [ ] T011 [US3] Em `MAIN/transaction/TransactionFilter.java`, `TransactionController.java` e `TransactionService.java` acrescentar o filtro `uncategorized` (`true`/`false` estritos; junto com `categoryId` é 400 no campo `uncategorized`; `true` soma `category_id is null and type <> 'TRANSFER'`). Criar `TEST/imports/CategorizeImportedTest.java`: sugestão pela regra do FR-011 (caixa, acentos, espaços, tipo, categoria arquivada ignorada, desempate pela data e pela criação, sem sugestão de outro workspace); confirmar com e sem categoria; o filtro devolve exatamente as receitas e despesas sem categoria e combina com conta, datas e busca; categorizar pela edição da spec 004 mantém `imported` e tira a linha do filtro

**Checkpoint**: importar, categorizar e achar o que falta funciona de ponta a ponta

---

## Phase 5: Provas (US4 e integração)

- [ ] T012 [P] [US2] Criar `TEST/imports/ImportEffectsTest.java`: lançamentos importados entram em exatos centavos no saldo da conta, no dashboard (grupo "Sem categoria" para os sem categoria, categorias para os categorizados), nos orçamentos semanal e mensal (sem categoria só no limite geral), na fatura de um cartão (compras do extrato) e na listagem; excluir um importado o tira de tudo; nada pendente é criado
- [ ] T013 [P] [US4] Criar `TEST/imports/ImportAccessTest.java` (VIEWER em prévia e confirmação é 403 sem efeito; não membro tem o mesmo 404 de um workspace inexistente; sem sessão é 401; conta e categoria de outro workspace; id malformado) e `TEST/imports/ImportLayersTest.java` (camada de dados: o SQL da inserção como `tostao_app` sem filtro de workspace é barrado pelo RLS; camada de serviço: o serviço com a conexão do dono isola sozinho pelo filtro explícito)
- [ ] T014 [P] [US4] Criar `TEST/imports/ImportFileSafetyTest.java`: `entidade.ofx` não busca nada nem demora (arquivo local e endereço externo, tempo limitado) e a descrição sai como texto; expansão de entidades aninhadas não consome memória; descrição, valor e chave de um arquivo de teste não aparecem na saída do processo (`OutputCaptureExtension`) nem em mensagens de erro; arquivo de 2 MB no limite e de 2 MB + 1; desempenho do SC-014 (prévia de 1.000 movimentos em menos de 2 s e confirmação de 1.000 linhas em menos de 3 s, com 20.000 lançamentos no workspace)

**Checkpoint**: integração, isolamento, permissões e segurança do arquivo provados

---

## Phase 6: Polish

- [ ] T015 Em `TEST/OpenApiContractTest.java` acrescentar `IMPORT_CONTRACT` (`specs/013-importacao-extratos/contracts/openapi.yaml`) e conferir que o contrato declara exatamente as duas rotas (`POST` em `/import/preview` e em `/import`), que `/v3/api-docs` expõe as duas sem outros verbos, que `ImportConfirmRequest` não tem campos de workspace, autoria, estado nem tipo, que a prévia aceita `multipart/form-data` com a parte `file`, que `TransactionResponse` tem `imported` e que a listagem tem o parâmetro `uncategorized`; incluir o contrato no teste de "todas as operações do contrato existem"
- [ ] T016 Rodar `./mvnw -B verify` completo; corrigir qualquer falha **nova** sem enfraquecer testes e registrar a contagem. **Nenhum teste das specs 001 a 012 pode ter sido alterado além de acrescentar** (e dos construtores de `TransactionResponse` da T007), conferido com `git diff`
- [ ] T017 [P] Atualizar `README.md`: seção de importação de extratos (formatos OFX e CSV, prévia e confirmação com `curl`, a chave que evita duplicar e respeita exclusões, o limite de 2 MB e 1.000 movimentos, a categoria opcional e o filtro `uncategorized`, só OWNER e EDITOR, o que não faz: transferências, desfazer em lote, conciliação)
- [ ] T018 Executar o [quickstart.md](quickstart.md) contra o Docker Compose reconstruído (`docker compose up -d --build`), com `Flyway` na versão 14 e limpeza ao fim; marcar as tarefas restantes como `[X]` junto do último commit que as fecha; não fazer push

---

## Dependencies & Execution Order

- **Phase 1**: T001 primeiro; T002, T003, T004 e T005 independentes entre si; T006 depois de T002 a T005
- **Phase 2**: T007 e T008 depois de T001 e T006 (T007 antes de T009, que devolve lançamentos)
- **Phase 3**: T009 depois de T007 e T008; T010 depois de T009
- **Phase 4**: T011 depois de T008 (usa a sugestão) e de T009
- **Phase 5**: T012 a T014 em paralelo depois de T011
- **Phase 6**: T015 depois de T011; T016 depois de tudo; T018 por último

## Commits previstos (código sempre com os testes que o provam)

1. `feat(db): add the import key to transactions` — T001
2. `feat(import): read OFX and CSV statements` — T002 a T006
3. `feat(transaction): tell whether a transaction was imported` — T007
4. `feat(import): preview a statement` — T008
5. `feat(import): confirm an import without duplicating` — T009, T010
6. `feat(import): suggest categories and find the uncategorized` — T011
7. `test(import): prove the effects on the existing features` — T012
8. `test(import): prove access, isolation and file safety` — T013, T014
9. `test(api): check the import contract` — T015
10. `docs: describe the statement import in the README` — T017, com as marcas de T016 e T018 no último commit

## Implementation Strategy

1. Schema e leitores primeiro (T001 a T006): são pequenos, puros e provados com arquivos reais antes de qualquer rota.
2. Prévia (T007, T008), depois confirmação com idempotência (T009, T010): o fluxo completo é o MVP.
3. Categorização (T011) torna o resultado útil no dia a dia; efeitos (T012), acesso e camadas (T013) e segurança do arquivo (T014) como as provas de integração, isolamento e segurança.
4. Contrato, README e quickstart real fecham a prova.
5. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
