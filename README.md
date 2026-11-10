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

Com `SPRING_PROFILES_ACTIVE=local` (o padrão do Compose), a documentação da API fica em
`http://127.0.0.1:8080/swagger-ui.html`.

## Estrutura

- `backend/`: API Spring Boot (Java 21, Maven, Flyway)
- `frontend/`: React + TypeScript + Vite, como PWA
- `.specify/`: constituição e templates do Spec Kit; specs em `specs/`
