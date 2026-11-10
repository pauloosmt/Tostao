# Quickstart: validar a Importação de extratos

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml); modelo em
[data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e stack local reconstruída: `docker compose up -d --build` (a migration `V14` roda na subida;
  confira `select version from flyway_schema_history order by installed_rank desc limit 1` → `14`).
- `BASE=http://127.0.0.1:8080/api/v1`. Duas pessoas, cada uma com o seu cookie: **Dona** (`jar.txt`, workspace `$WID`) e
  **Intrusa** (`jar2.txt`), cadastradas por `POST /auth/register`. Um **Leitor** (`jar3.txt`) entra no workspace da Dona
  como VIEWER (spec 012, `invitations`).
- Três arquivos de teste, criados no passo 2: `extrato.ofx` (OFX 1, SGML), `extrato2.ofx` (OFX 2, XML) e `extrato.csv`.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos passam, inclusive os das specs 001 a 012 **sem alteração de resultado** (FR-025).

## 2. Preparo

1. Cadastre as pessoas e guarde o `$WID` da Dona. Crie a conta `Corrente` (`$CONTA`), a conta `Poupança` (`$POUP`) e
   pegue a categoria `Alimentação` (`$ALIM`, despesa) e `Salário` (`$SAL`, receita).
2. Crie `extrato.ofx` com quatro movimentos (`FITID` 1 a 4): salário `+5000.00` em 05/11, `PADARIA DO ZE` `-23.50`,
   `MERCADO CENTRAL` `-180.90` e `PADARIA DO ZE` `-23.50` (mesma data, mesmo valor e descrição do segundo).
   `extrato2.ofx` é o mesmo conteúdo em XML; `extrato.csv`, em `;` com vírgula decimal, sem coluna de identificador.
3. Lance à mão, na `Corrente`, `Padaria do Zé` de `-23.50` (categoria `Alimentação`) em 06/11, para a sugestão de categoria
   e outra de `-180.90` em 07/11 (para a possível duplicata).

## 3. Prévia (US1)

1. Dona: `POST .../accounts/$CONTA/import/preview` com `extrato.ofx` → **200**, `format` **OFX**, **4** linhas na ordem do
   arquivo, com data, descrição, `amountCents` com sinal (`500000`, `-2350`, `-18090`, `-2350`), `type` pelo sinal e `key`
   `id:1` a `id:4`. Nada gravado: `GET .../transactions` mantém só os dois lançamentos do preparo.
2. As linhas `PADARIA DO ZE` vêm com `suggestedCategoryId` = `$ALIM`; o salário vem sem sugestão.
3. O mesmo extrato em `extrato2.ofx` (XML) e em `extrato.csv` → **200**, mesmas descrições e valores (no CSV, `key` começa
   com `h:`, e as duas padarias têm chaves **diferentes**).
4. Arquivo inválido: texto qualquer → **400** `file`; arquivo vazio → **400**; arquivo de 2,5 MB → **413**; CSV com 1.001
   linhas → **400**; OFX com uma data `20261301` → **200** com essa linha em `problems` e as outras normais.
5. Conta da Intrusa na rota da Dona e `$POUP` arquivada → **404** e **409**.

## 4. Confirmar (US2)

1. Dona confirma as quatro linhas da prévia (sem categoria) → **201**, `created` com **4**, `skipped` **0**. Cada lançamento
   é `CLEARED`, `imported=true`, receita para `+5000.00` e despesa para as outras, sem categoria, `createdBy` = Dona.
2. `GET .../accounts/$CONTA` → o saldo mudou em exatos centavos (+5000,00 −23,50 −180,90 −23,50).
3. Repetir a **mesma** confirmação → **200**, `created` vazio, `skipped` **4**; `GET .../transactions` não mudou.
4. Duas confirmações **ao mesmo tempo** das mesmas linhas (`curl` em paralelo) → no total **uma** linha por movimento.
5. Excluir um dos lançamentos importados e confirmar de novo → **skipped 4**; ele **não** volta.
6. Linha inválida: `amountCents` `0`, `occurredOn` `1999-12-31`, `categoryId` de outro workspace (404), categoria de
   receita numa despesa (400, `lines[N].categoryId`), 1.001 linhas, chave repetida → recusa **inteira** e nada gravado.

## 5. Categorizar (US3)

1. Confirmar de novo (em outra conta, `$POUP` ativa) uma linha com `categoryId` = `$ALIM` → lançamento nasce com ela.
2. `GET .../transactions?uncategorized=true` → só as receitas e despesas sem categoria; com `categoryId` junto → **400**
   no campo `uncategorized`; `uncategorized=talvez` → **400**.
3. `PUT .../transactions/{id}` escolhendo `$ALIM` num importado sem categoria → **200**, `imported` continua `true`; a
   listagem `uncategorized=true` perdeu essa linha.
4. `GET .../dashboard` (mês de novembro) mostra o grupo "Sem categoria" com os valores das linhas sem categoria.

## 6. Só quem pode, e o arquivo é só dado (US4)

1. Leitor (VIEWER): prévia e confirmação → **403**, nada gravado. Sem cookie → **401**. Intrusa, na rota do `$WID` → **404**,
   igual a um workspace inexistente.
2. Um OFX 2 com `<!DOCTYPE ... <!ENTITY xxe SYSTEM "file:///etc/passwd">` e `&xxe;` na descrição → **200** (ou 400), sem
   o conteúdo do arquivo local e sem demora.
3. `docker logs` da API não contém nenhuma descrição, valor ou chave dos arquivos de teste.

## 7. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão e confira `users = workspaces = workspace_members = accounts =
categories = transactions = invitations = 0`.
