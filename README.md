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
