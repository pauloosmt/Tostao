# Implementation Plan: Importação de extratos

**Branch**: `013-importacao-extratos` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/013-importacao-extratos/spec.md`

## Summary

A pessoa envia o extrato do banco (OFX ou CSV) para uma conta, vê uma prévia que não grava nada e confirma as linhas
escolhidas, que viram lançamentos efetivados daquela conta. Importar o mesmo extrato (ou um que se sobrepõe) nunca duplica
nem recria o que foi excluído. A categoria é opcional e pode ser sugerida; o que ficar sem categoria é achado depois por
um filtro novo da listagem.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Migration `V14` (schema)**: coluna `transactions.import_key` com restrições de formato e de coerência (só receita e
  despesa avulsas) e índice único parcial `(account_id, import_key)` que **inclui as linhas excluídas**. Sem tabela nova e
  sem `GRANT UPDATE` na coluna: a chave nunca muda.
- **Duas rotas sem estado**: `POST .../accounts/{accountId}/import/preview` (multipart, lê e compara, não grava) e
  `POST .../accounts/{accountId}/import` (JSON, grava até 1.000 linhas). A prévia não é guardada.
- **Leitores próprios, sem dependência nova**: `OfxReader` (marcas, serve ao OFX 1 em SGML e ao 2 em XML, sem parser de
  XML, então sem entidade externa por construção) e `CsvReader` (RFC 4180, separador `;` ou `,` achado pelo cabeçalho,
  colunas por nome). `StatementParser` reconhece o formato pelo conteúdo, decodifica (UTF-8 ou Windows-1252) e produz
  linhas normalizadas com problemas por linha.
- **A chave de importação**: o identificador do banco (`FITID` ou coluna `id`) ou, sem ele, o SHA-256 de data, valor,
  descrição normalizada e ordem entre linhas idênticas do arquivo.
- **Gravação**: trava de conta (`pg_advisory_xact_lock`) e `INSERT ... ON CONFLICT DO NOTHING RETURNING id` em blocos de 100
  linhas; sem exceção capturada dentro da transação. Tudo conferido antes (conta, categorias distintas, campos).
- **Sugestão e possível duplicata**: duas consultas por prévia, comparadas em memória (descrição normalizada em Java, sem
  extensão `unaccent`).
- **Listagem**: filtro `uncategorized`; resposta de lançamento ganha `imported`.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC (multipart), Spring JDBC (`JdbcClient`), Bean Validation, Spring Security (contexto e
aspecto da `001`), springdoc-openapi, Flyway. Nenhuma dependência nova (`MessageDigest`, `Charset` e `CharsetDecoder` são do JDK).

**Storage**: PostgreSQL 17. `V14` (coluna, restrições, índice único parcial).

**Testing**: JUnit 5, MockMvc (`MockMultipartFile`), Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para
semear e inspecionar, threads reais para a corrida da confirmação, `OutputCaptureExtension` para provar que nada do arquivo
vai a registro, arquivos de teste em `src/test/resources/statements/`.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: prévia de 1.000 movimentos em menos de 2 s e confirmação de 1.000 linhas em menos de 3 s, com 20.000
lançamentos no workspace (SC-014).

**Constraints**: arquivo de até 2 MB e 1.000 movimentos; nada do conteúdo em log nem em mensagem de erro; toda consulta
filtra por `workspace_id`; nenhuma exceção capturada dentro de transação; dinheiro em centavos inteiros, sem ponto
flutuante; entrada validada antes de qualquer gravação.

**Scale/Scope**: até 1.000 lançamentos por confirmação; até 20.000 por workspace.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | Papel e participação vêm do banco no aspecto; `created_by` vem da sessão; conta e categoria são conferidas no servidor; a chave vem do arquivo mas só identifica o movimento naquela conta; nenhum segredo novo | OK |
| II. Isolamento em duas camadas | Conta e categoria só do workspace da rota (filtro explícito) e RLS de `transactions` no banco; a chave é única por conta; teste de acesso cruzado por rota e com cada camada removida | OK |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (todo `/api/v1/**` exige sessão); o upload multipart não abre exceção; cookie `SameSite=Lax`; limite de tamanho do envio | OK |
| IV. Entrada validada, saída controlada | O arquivo é dado não confiável: formato pelo conteúdo, limites de tamanho e de linhas, leitor sem resolução de entidades, linhas normalizadas e validadas; DTO de confirmação fechado com Bean Validation; consultas parametrizadas; a chave não sai em resposta de lançamento | OK |
| V. Dados financeiros confiáveis | Centavos inteiros sem arredondar em silêncio; efetivado só o que o banco informou; confirmação atômica; excluir respeitado (a chave segue ocupada); schema só por migration | OK |
| VI. Sem tentativa e erro em transação | Tudo conferido antes de gravar; unicidade por índice e `ON CONFLICT DO NOTHING`; trava por conta contra impasse; nenhum `catch` dentro de transação | OK |
| VII. Simplicidade | Uma coluna, um índice, dois leitores pequenos, duas rotas, sem tabela de rascunho, sem dependência nova, sem extensão do banco | OK |
| VIII. Prova, não promessa | Formatos provados com arquivos reais de teste; idempotência em série e em paralelo; atomicidade; isolamento por camada; segurança do arquivo (entidade externa, log); não regressão; [quickstart.md](quickstart.md) real | OK |

**Ponto de atenção (alcance)**: acrescenta um campo a `TransactionResponse` e um parâmetro à listagem de lançamentos
(spec 004). Mitigação: ambos são aditivos (`imported` no fim do registro, `uncategorized` opcional), a suíte inteira das
specs 001 a 012 roda sem alteração depois de cada passo (FR-025, SC-013).

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/013-importacao-extratos/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Coluna, índice, consultas, pedidos e respostas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/
│   ├── application.properties                            # AJUSTE: limites do multipart (2 MB / 3 MB)
│   └── db/migration/V14__statement_import.sql            # import_key, restrições e índice único parcial
├── src/main/java/com/tostao/tostao_api/
│   ├── imports/
│   │   ├── StatementParser.java                          # NOVO: formato pelo conteúdo, decodificação, limites
│   │   ├── OfxReader.java                                # NOVO: leitor de marcas (OFX 1 e 2), sem parser de XML
│   │   ├── CsvReader.java                                # NOVO: RFC 4180, separador e colunas por nome
│   │   ├── StatementLine.java                            # NOVO: linha lida (valores ou problemas)
│   │   ├── Money.java                                    # NOVO: texto para centavos exatos (regras do FR-004)
│   │   ├── ImportKeys.java                               # NOVO: normalização e chave de importação
│   │   ├── ImportService.java                            # NOVO: prévia e confirmação
│   │   ├── ImportController.java                         # NOVO: 2 rotas
│   │   └── dto/                                          # NOVO: pedidos e respostas
│   ├── transaction/
│   │   ├── TransactionFilter.java                        # AJUSTE: campo uncategorized
│   │   ├── TransactionController.java                    # AJUSTE: parâmetro uncategorized
│   │   ├── TransactionService.java                       # AJUSTE: filtro e coluna imported
│   │   └── dto/TransactionResponse.java                  # AJUSTE: campo imported
│   └── common/error/                                     # AJUSTE: tratador do estouro de tamanho (413)
└── src/test/
    ├── resources/statements/                             # arquivos de teste (OFX 1, OFX 2, CSV)
    └── java/com/tostao/tostao_api/imports/
        ├── ImportSchemaTest.java                         # V14: coluna, restrições, índice, grants
        ├── MoneyTest.java                                # unitário: centavos exatos
        ├── ImportKeysTest.java                           # unitário: normalização e chaves
        ├── OfxReaderTest.java                            # unitário: OFX 1 e 2, entidades
        ├── CsvReaderTest.java                            # unitário: separadores, aspas, cabeçalho
        ├── StatementParserTest.java                      # formato, codificação, limites
        ├── ImportScenario.java                           # apoio dos testes de rota
        ├── PreviewTest.java                              # prévia
        ├── ConfirmImportTest.java                        # confirmação, atomicidade, validação
        ├── ImportIdempotencyTest.java                    # repetição, sobreposição, exclusão, concorrência
        ├── CategorizeImportedTest.java                   # sugestão, filtro uncategorized, edição
        ├── ImportEffectsTest.java                        # saldo, dashboard, orçamentos, fatura
        ├── ImportAccessTest.java                         # papéis e isolamento
        ├── ImportLayersTest.java                         # cada camada
        └── ImportFileSafetyTest.java                     # entidades, log, tamanho, desempenho
```

**Structure Decision**: continua o monólito em camadas por módulo. A importação forma um módulo novo (`imports`; o nome
`import` é palavra reservada do Java), porque tem leitores, regras e rotas próprias; o lançamento continua em `transaction`
e a importação o grava pelo mesmo caminho de inserção e conferência de conta e categoria (`UsableRefs`).

## Complexity Tracking

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| Dois leitores escritos à mão (OFX e CSV) em vez de bibliotecas | O OFX 1 não é XML (um parser de XML não o lê) e, sem parser de XML, entidade externa e expansão de entidade não existem por construção; o CSV é pequeno, mas precisa de regras próprias (separador, cabeçalho precedido de resumo, milhar brasileiro) que uma biblioteca genérica não dá | Biblioteca de OFX e de CSV de terceiros: duas dependências novas para auditar e atualizar, e o comportamento de segurança deixaria de ser nosso |
| Índice único inclui as linhas excluídas | Respeitar a exclusão da pessoa: importar de novo não pode recriá-la (mesmo critério da spec 011) | Índice só nas linhas ativas: reimportar um extrato traria de volta o que a pessoa excluiu de propósito |
| Trava consultiva por conta na confirmação | Duas confirmações simultâneas das mesmas linhas em ordens diferentes entrariam em impasse esperando as chaves uma da outra; o índice único sozinho garante não duplicar, mas não evita o impasse | Ordenar as linhas pela chave antes de inserir (resolve entre importações do mesmo tipo, mas depende de todo inserção seguir a ordem) |
