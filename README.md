# Tostao
App Open Source de finanças pessoais para desktop e celular (PWA). Back-end em Spring Boot e PostgreSQL, pronto para vários usuários por workspace

## Rodando localmente

```bash
docker compose up -d --build          # Postgres (127.0.0.1:5442) + API (127.0.0.1:8080)
curl http://127.0.0.1:8080/api/v1/health

cd frontend && npm install && npm run dev   # http://localhost:5173, /api vai para a API
```

Testes do backend (usam Testcontainers, precisam do Docker ligado): `cd backend && ./mvnw verify`.

### Variáveis de ambiente

Copie `.env.example` para `.env` (ignorado pelo git) para mudar os valores. Os padrões servem só para o ambiente local.

| Variável | Para quê |
|---|---|
| `DB_MIGRATION_PASSWORD` | Senha do dono do schema (Flyway e container do Postgres) |
| `DB_APP_PASSWORD` | Senha da role `tostao_app`, usada pela API e criada pela migration V2. Essa role não ignora RLS |
| `JWT_SECRET` | Assinatura do JWT de sessão, com ao menos 32 bytes. Obrigatório fora do perfil `local` |
| `COOKIE_SECURE` | `true` em produção (HTTPS). `false` só no ambiente local |
| `CORS_ORIGIN` | Única origem aceita no CORS |

Em produção, a API recusa subir se `COOKIE_SECURE=true` e o `JWT_SECRET` ainda for o valor de desenvolvimento.

### Testando o login com `curl`

O cookie de sessão é `HttpOnly` e `Path=/api`; use um arquivo de cookies para guardá-lo.

```bash
BASE=http://127.0.0.1:8080/api/v1
curl -c jar.txt -H 'Content-Type: application/json' \
  -d '{"name":"Paulo","email":"paulo@example.com","password":"Senha-Forte-123"}' $BASE/auth/register
curl -b jar.txt $BASE/me                      # seus dados e seus workspaces
curl -b jar.txt -X POST $BASE/auth/logout     # sai deste aparelho
curl -b jar.txt -X POST $BASE/auth/logout-all # sai de todos os aparelhos
```

Contas (substitua `$WID` pelo `id` do workspace que veio em `/me`):

```bash
curl -b jar.txt -H 'Content-Type: application/json' \
  -d '{"name":"Nubank","type":"CHECKING","initialBalanceCents":100000}' $BASE/workspaces/$WID/accounts
curl -b jar.txt $BASE/workspaces/$WID/accounts                 # contas ativas com o saldo de cada uma
curl -b jar.txt $BASE/workspaces/$WID/reports/balance          # saldo por conta e total
curl -b jar.txt -X POST $BASE/workspaces/$WID/accounts/<id>/archive   # contas não se apagam, só se arquivam
```

Valores monetários são sempre inteiros em centavos (`100000` = R$ 1.000,00).

Lançamentos (o valor vai sempre positivo, em centavos; o tipo dá o sinal):

```bash
curl -b jar.txt -H 'Content-Type: application/json' -d '{"type":"EXPENSE","accountId":"<conta>","amountCents":10000,
  "occurredOn":"2026-11-05","description":"Mercado","categoryId":"<categoria de despesa>"}' $BASE/workspaces/$WID/transactions
curl -b jar.txt -H 'Content-Type: application/json' -d '{"type":"TRANSFER","accountId":"<origem>","destinationAccountId":"<destino>",
  "amountCents":30000,"occurredOn":"2026-11-06","description":"Reserva"}' $BASE/workspaces/$WID/transactions   # duas pontas, atômica
curl -b jar.txt "$BASE/workspaces/$WID/transactions?size=20&page=1&from=2026-11-01&to=2026-11-30&type=EXPENSE&q=mercado"
curl -b jar.txt -X DELETE $BASE/workspaces/$WID/transactions/<id>    # exclusão lógica; numa transferência exclui as duas pontas
```

Lançamento pendente: um lançamento pode ser registrado como **pendente** (previsto, ainda não efetivado) e depois
efetivado. Sem `status` na criação, o lançamento é efetivado, como sempre. **O pendente nunca conta no realizado**:
saldo das contas, dashboard, gasto dos orçamentos e fatura do cartão só contam efetivados. Ele aparece na listagem, no
saldo previsto e no bloco `pending` do dashboard. Numa transferência as duas pontas têm sempre o mesmo estado.

```bash
curl -b jar.txt -H 'Content-Type: application/json' -d '{"type":"EXPENSE","accountId":"<conta>","amountCents":80000,
  "occurredOn":"2026-11-20","description":"Aluguel","categoryId":"<categoria de despesa>","status":"PENDING"}' $BASE/workspaces/$WID/transactions
curl -b jar.txt -X POST $BASE/workspaces/$WID/transactions/<id>/clear     # efetiva (as duas pontas, se for transferência); repetir não tem efeito
curl -b jar.txt "$BASE/workspaces/$WID/transactions?status=PENDING"       # só os pendentes (ou CLEARED); combina com os outros filtros
curl -b jar.txt $BASE/workspaces/$WID/accounts                            # balanceCents (só efetivados), pendingCents e projectedBalanceCents (previsto)
curl -b jar.txt $BASE/workspaces/$WID/reports/balance                     # totalBalanceCents e totalProjectedCents
```

Para efetivar com outra data, ou voltar um efetivado a pendente, use a edição (`PUT .../transactions/<id>`) com `status`;
sem `status` na edição, o estado atual é mantido. Só `CLEARED` e `PENDING` são aceitos.

Compra parcelada: uma despesa dividida em **2 a 48 parcelas mensais**, registrada de uma vez (todas as parcelas ou
nenhuma). O total é dividido em centavos exatos: o resto vai, um centavo por parcela, para as primeiras, e a soma é
sempre o total (R$ 100,00 em 3 = 33,34 + 33,33 + 33,33). A data de cada parcela é a da primeira mais N-1 meses, sempre
contada a partir da primeira (31/01, 28/02, 31/03). A **primeira parcela nasce efetivada** (ou pendente, com
`firstInstallmentStatus`) e **as demais nascem pendentes**, então só a primeira conta no realizado até ser efetivada;
nada efetiva sozinho com o passar do tempo. Cada parcela é um lançamento comum (despesa): aparece na listagem, e pode
ser editada, efetivada e excluída; a descrição ganha o sufixo ` (N/T)` e o grupo, o número e o total aparecem em todo
lançamento (`installmentGroupId`, `installmentNumber`, `installmentCount`; nulos nos que não são parcela). O plano não
é um registro próprio: é o conjunto das parcelas do grupo. Cancelar exclui só as parcelas ainda pendentes e mantém as
efetivadas; repetir o cancelamento não tem efeito. Papéis: OWNER e EDITOR registram e cancelam, VIEWER só consulta.

```bash
curl -b jar.txt -H 'Content-Type: application/json' -d '{"accountId":"<conta>","categoryId":"<categoria de despesa>",
  "totalCents":300000,"installmentCount":3,"firstOccurredOn":"2026-11-10","description":"Notebook"}' \
  $BASE/workspaces/$WID/installment-purchases               # 201: o plano, com as 3 parcelas e o groupId
curl -b jar.txt $BASE/workspaces/$WID/installment-purchases/<groupId>          # o plano: totalCents, clearedCents, pendingCents e as parcelas em ordem
curl -b jar.txt -X POST $BASE/workspaces/$WID/installment-purchases/<groupId>/cancel   # {"cancelled":N}: exclui só as pendentes
```

Efetivação automática e recorrências: por padrão a data **nunca** efetiva nada sozinha (o lançamento pendente espera a
pessoa). Quem quiser, liga o marcador `autoClear` num lançamento pendente (na criação ou na edição; também na compra
parcelada, que o liga nas parcelas pendentes): no dia da data o sistema o efetiva, com o mesmo efeito da efetivação
manual (as duas pontas de uma transferência juntas). O marcador é **de uso único**: some quando o lançamento é efetivado
por qualquer caminho, e devolvido a pendente não volta a ser efetivado sozinho; só vale para pendente (efetivado com
`autoClear` ligado é 400). Uma **recorrência** repete uma receita, despesa ou transferência (abaixo) toda semana, mês ou ano (`WEEKLY`,
`MONTHLY`, `YEARLY`): o sistema mantém geradas, como lançamentos pendentes comuns, as ocorrências até 12 meses à frente.
As datas são sempre contadas a partir do início (31/01, 28/02, 31/03, sem acumular o ajuste), o início pode ficar até
366 dias no passado, e uma ocorrência excluída à mão não é recriada. A regra da recorrência não se edita: encerra-se e
cria-se outra. Encerrar exclui só as ocorrências pendentes **futuras**; as efetivadas e as pendentes vencidas ficam.
Cada ocorrência é um lançamento comum (`recurrenceId` e `occurrenceNumber` aparecem em todo lançamento, nulos nos
demais). OWNER e EDITOR criam e encerram; VIEWER só consulta.

```bash
curl -b jar.txt -H 'Content-Type: application/json' -d '{"accountId":"<conta>","categoryId":"<categoria de despesa>",
  "type":"EXPENSE","amountCents":150000,"description":"Aluguel","frequency":"MONTHLY","startOn":"2026-11-05",
  "autoClear":true}' $BASE/workspaces/$WID/recurrences          # 201: a recorrência e as ocorrências geradas
curl -b jar.txt $BASE/workspaces/$WID/recurrences               # lista, com a próxima ocorrência pendente
curl -b jar.txt $BASE/workspaces/$WID/recurrences/<id>          # detalhe: ocorrências em ordem, totais efetivado e pendente
curl -b jar.txt -X POST $BASE/workspaces/$WID/recurrences/<id>/end   # {"cancelled":N}: exclui só as pendentes futuras
```

Transferência recorrente: a mesma recorrência, com `"type":"TRANSFER"`, repete uma transferência entre duas contas do
workspace (por exemplo, todo dia 5, R$ 500 da corrente para a poupança). Informe `accountId` (origem) e
`destinationAccountId` (destino, diferente da origem) e **não** informe `categoryId` (transferência não tem categoria; em
receita e despesa é o contrário: categoria obrigatória e destino recusado). Cada ocorrência é uma transferência comum de
**duas pontas** (saída na origem, entrada no destino, mesmo grupo), que nasce pendente e, com `autoClear`, é efetivada
sozinha no dia, as duas pontas juntas; nunca existe uma ponta só. As duas contas precisam estar ativas (arquivar uma pausa a
geração; restaurar a retoma). O destino pode ser um cartão de crédito: a ocorrência efetivada conta como pagamento da
fatura. No detalhe, a lista traz as duas pontas e os totais contam cada ocorrência uma vez; encerrar exclui as duas pontas das
pendentes futuras e `cancelled` conta **ocorrências**, não pontas.

```bash
curl -b jar.txt -H 'Content-Type: application/json' -d '{"type":"TRANSFER","accountId":"<corrente>",
  "destinationAccountId":"<poupança>","amountCents":50000,"description":"Reserva","frequency":"MONTHLY",
  "startOn":"2026-11-05","autoClear":true}' $BASE/workspaces/$WID/recurrences    # 201: 13 pares de pontas pendentes
```

A **execução automática** (gerar as ocorrências que faltam e efetivar o que venceu) roda sem pessoa logada, 10 segundos
depois de a API subir e a cada hora, no fuso `America/Sao_Paulo`; então um lançamento que vence no dia D é efetivado em
até uma hora depois do início do dia D. Duas execuções simultâneas nunca duplicam nem efetivam duas vezes. Fica ligada
por padrão; `TOSTAO_AUTOMATION_ENABLED=false` (a propriedade `tostao.automation.enabled`) a desliga, e é o que os testes
fazem, chamando `AutomationService.runOnce(data)` com a data que querem.

Dashboard (só leitura): o resumo de um mês ou de uma semana, com totais, grupos por categoria, evolução
dia a dia, comparação com o período anterior e saldos. Sem parâmetro, vale o mês corrente.

```bash
curl -b jar.txt "$BASE/workspaces/$WID/dashboard"                   # mês corrente
curl -b jar.txt "$BASE/workspaces/$WID/dashboard?month=2026-11"      # um mês (AAAA-MM)
curl -b jar.txt "$BASE/workspaces/$WID/dashboard?week=2026-W45"      # uma semana ISO, de segunda a domingo
```

Despesas saem positivas e o resultado é receitas menos despesas. Transferências e lançamentos excluídos não
contam. Mês e semana não podem ser pedidos juntos. Os saldos de abertura, de fechamento e por dia valem para
as contas ativas.

Orçamento semanal: limites de gasto por semana (de segunda a domingo, a mesma do dashboard), para uma categoria
de despesa ou gerais da semana. O acompanhamento mostra, por limite, o gasto, o que resta, o percentual usado e
o estado (`WITHIN`, `WARNING` a partir de 80% e `EXCEEDED` acima de 100%), mais o total gasto e o gasto fora dos
limites de categoria. Só OWNER e EDITOR escrevem.

```bash
curl -b jar.txt -X PUT -H 'Content-Type: application/json' \
  -d '{"week":"2026-W45","categoryId":"<categoria de despesa>","amountCents":40000}' $BASE/workspaces/$WID/budgets   # define ou substitui
curl -b jar.txt -X PUT -H 'Content-Type: application/json' \
  -d '{"week":"2026-W45","amountCents":150000}' $BASE/workspaces/$WID/budgets                  # limite geral da semana (sem categoryId)
curl -b jar.txt "$BASE/workspaces/$WID/budgets?week=2026-W45"                                   # acompanhamento (sem week: a semana corrente)
curl -b jar.txt -X PUT -H 'Content-Type: application/json' \
  -d '{"amountCents":45000}' $BASE/workspaces/$WID/budgets/<id>                                 # ajusta o valor
curl -b jar.txt -X DELETE $BASE/workspaces/$WID/budgets/<id>                                    # remove (de verdade, só daquela semana)
curl -b jar.txt -X POST -H 'Content-Type: application/json' \
  -d '{"fromWeek":"2026-W45","weeks":3}' $BASE/workspaces/$WID/budgets/copy                    # copia para as 3 semanas seguintes, só onde falta
```

Um limite de categoria principal cobre os gastos dela e os das subcategorias; o limite geral cobre todas as
despesas da semana. Os limites são planos, não lançamentos: ao contrário dos lançamentos, são removidos de
verdade. Receitas, transferências e lançamentos excluídos nunca contam como gasto.

Orçamento mensal: o mesmo orçamento por mês (`AAAA-MM`, do dia 1 ao último dia), com a mesma regra de gasto,
percentual e estado, em rotas próprias. É independente do semanal: o mês não é a soma das semanas e um tipo de
limite nunca mexe no outro. A cópia vai até 24 meses.

```bash
curl -b jar.txt -X PUT -H 'Content-Type: application/json' \
  -d '{"month":"2026-11","categoryId":"<categoria de despesa>","amountCents":160000}' $BASE/workspaces/$WID/monthly-budgets   # define ou substitui
curl -b jar.txt -X PUT -H 'Content-Type: application/json' \
  -d '{"month":"2026-11","amountCents":600000}' $BASE/workspaces/$WID/monthly-budgets                  # limite geral do mês (sem categoryId)
curl -b jar.txt "$BASE/workspaces/$WID/monthly-budgets?month=2026-11"                                   # acompanhamento (sem month: o mês corrente)
curl -b jar.txt -X PUT -H 'Content-Type: application/json' \
  -d '{"amountCents":170000}' $BASE/workspaces/$WID/monthly-budgets/<id>                                # ajusta o valor
curl -b jar.txt -X DELETE $BASE/workspaces/$WID/monthly-budgets/<id>                                    # remove (de verdade, só daquele mês)
curl -b jar.txt -X POST -H 'Content-Type: application/json' \
  -d '{"fromMonth":"2026-11","months":3}' $BASE/workspaces/$WID/monthly-budgets/copy                   # copia para os 3 meses seguintes, só onde falta
```

Fatura do cartão de crédito: calculada a cada consulta (nada é guardado) a partir do dia de fechamento e do de
vencimento do cartão, e só de leitura (OWNER, EDITOR e VIEWER consultam). A fatura é identificada pelo mês do
**vencimento**. O período vai do dia seguinte ao fechamento anterior até o dia do fechamento, inclusive; dia 29,
30 ou 31 em mês curto vale o último dia do mês. O total soma compras e saques e subtrai estornos; transferências
que entram no cartão são pagamentos (depois do fechamento, até o fechamento da seguinte). A situação é `OPEN`,
`CLOSED`, `PAID` ou `OVERDUE`. As faturas usam os dias de fechamento e vencimento de **hoje**: mudar os dias
recalcula as passadas.

```bash
curl -b jar.txt $BASE/workspaces/$WID/accounts/<id do cartão>/invoices/2026-11          # fatura de novembro, com os lançamentos
curl -b jar.txt "$BASE/workspaces/$WID/accounts/<id do cartão>/invoices?from=2026-06&to=2026-12"   # lista (sem from/to: as seis até a aberta)
curl -b jar.txt $BASE/workspaces/$WID/accounts/<id do cartão>/card-summary               # limite, devido, disponível e fatura aberta
```

Conta que não é cartão, ou cartão sem `closingDay` e `dueDay`, responde 409 dizendo o que falta.

Categorias: o cadastro já traz o conjunto padrão (Alimentação, Moradia, Transporte, Saúde, Lazer, Educação,
Salário e outras), com subcategorias.

```bash
curl -b jar.txt $BASE/workspaces/$WID/categories                     # árvore: principais com subcategorias
curl -b jar.txt "$BASE/workspaces/$WID/categories?kind=INCOME"       # só receitas (ou EXPENSE)
curl -b jar.txt -H 'Content-Type: application/json' \
  -d '{"name":"Pets","kind":"EXPENSE"}' $BASE/workspaces/$WID/categories           # categoria principal
curl -b jar.txt -H 'Content-Type: application/json' \
  -d '{"name":"Ração","parentId":"<id da Pets>"}' $BASE/workspaces/$WID/categories  # subcategoria (herda o tipo)
curl -b jar.txt -X POST $BASE/workspaces/$WID/categories/<id>/archive   # arquivar uma principal arquiva as subcategorias
```

Membros e convites: só o dono (OWNER) convida, muda papéis e remove; qualquer membro vê a lista de membros. O vínculo
nasce só de um convite aceito. O convite é para um e-mail e um papel (`EDITOR` ou `VIEWER`) e vale 7 dias. O
**código** do convite aparece uma única vez, na resposta que o cria: o banco guarda só a impressão dele (SHA-256),
nenhuma listagem o repete e ele não vai para o log. Quem aceita precisa estar logado com a conta do e-mail
convidado; código errado, vencido, já usado, revogado ou de outro e-mail respondem todos o mesmo 404. Cada
workspace tem um único dono, no máximo 20 membros e 20 convites pendentes; convidar de novo o mesmo e-mail revoga o
convite anterior.

```bash
curl -b jar.txt $BASE/workspaces/$WID/members                                                   # membros (qualquer papel)
curl -b jar.txt -X PUT -H 'Content-Type: application/json' \
  -d '{"role":"VIEWER"}' $BASE/workspaces/$WID/members/<id do usuário>                          # muda o papel (dono)
curl -b jar.txt -X DELETE $BASE/workspaces/$WID/members/<id do usuário>                         # remove (dono; o membro pode sair sozinho)
curl -b jar.txt -H 'Content-Type: application/json' \
  -d '{"email":"marido@exemplo.com","role":"EDITOR"}' $BASE/workspaces/$WID/invitations         # convida: o "code" vem só aqui
curl -b jar.txt $BASE/workspaces/$WID/invitations                                               # convites pendentes (sem o código)
curl -b jar.txt -X DELETE $BASE/workspaces/$WID/invitations/<id>                                # revoga
curl -b jar2.txt -H 'Content-Type: application/json' \
  -d '{"code":"<código recebido>"}' $BASE/invitations/accept                                    # a pessoa convidada aceita, logada
```

O dono não sai nem é rebaixado: o workspace sempre tem exatamente um.

Importação de extratos: a pessoa (OWNER ou EDITOR) envia o extrato do banco para uma conta, vê uma **prévia que não
grava nada** e confirma; cada movimento vira um lançamento **efetivado** (receita se o valor é positivo, despesa se é
negativo). Formatos, reconhecidos pelo conteúdo: **OFX** (versões 1 e 2, banco e cartão, o melhor: traz identificador
do banco), **CSV** (cabeçalho com colunas de data, descrição e valor; `;` ou `,`; `1.234,56` ou `1234.56`) e **PDF com
texto selecionável** (cada linha que começa com uma data e termina com um valor; saldos são ignorados; PDF digitalizado
ou com senha é recusado, e como o PDF é lido pela aparência do texto, confira a prévia). Até 2 MB e 1.000 movimentos
(PDF: até 20 páginas). Cada movimento tem uma **chave de importação** por conta: importar o mesmo extrato de novo, ou
um que repete alguns dias do anterior, só cria o que falta, e um lançamento que você excluiu **não volta**. A prévia
marca o que já existe, as **possíveis duplicatas** (mesma data e valor de algo que você digitou à mão), os problemas de
cada linha e uma categoria sugerida (a do último lançamento com a mesma descrição). A categoria é opcional; o que ficar
sem categoria se acha com `uncategorized=true` e se categoriza pela edição normal. O arquivo é só dado: nada dele é
executado nem buscado, e o conteúdo não vai para o log.

```bash
curl -b jar.txt -F 'file=@extrato.ofx' $BASE/workspaces/$WID/accounts/<id da conta>/import/preview   # prévia (OFX, CSV ou PDF)
# a prévia traz, por linha: key, occurredOn, description, amountCents (com sinal), type, existing, possibleDuplicate,
# suggestedCategoryId e problems. Confirma as linhas escolhidas (categoryId é opcional):
curl -b jar.txt -H 'Content-Type: application/json' -d '{"lines":[
  {"key":"id:1","occurredOn":"2026-11-05","description":"SALARIO EMPRESA","amountCents":500000},
  {"key":"id:2","occurredOn":"2026-11-06","description":"PADARIA DO ZE","amountCents":-2350,"categoryId":"<id da categoria>"}
]}' $BASE/workspaces/$WID/accounts/<id da conta>/import                    # 201 {created:[...], skipped:N}; repetir dá 200
curl -b jar.txt "$BASE/workspaces/$WID/transactions?uncategorized=true"    # receitas e despesas ainda sem categoria
```

Não faz nesta versão: importar transferências entre contas (entram como receita ou despesa), desfazer uma importação
em lote (exclua os lançamentos um a um), conciliar o saldo do extrato e ler PDF digitalizado (sem reconhecimento de
imagem).

Com `SPRING_PROFILES_ACTIVE=local` (o padrão do Compose), a documentação da API fica em
`http://127.0.0.1:8080/swagger-ui.html`.

## Estrutura

- `backend/`: API Spring Boot (Java 21, Maven, Flyway)
- `frontend/`: React + TypeScript + Vite, como PWA
- `.specify/`: constituição e templates do Spec Kit; specs em `specs/`
