# Tostão

App open source de finanças pessoais para desktop e celular (PWA), pensado para várias pessoas por workspace.
Back-end em Spring Boot e PostgreSQL; front-end em React e TypeScript.

> Este README é provisório. A documentação completa do projeto entra quando a implementação terminar.

## Rodando

```bash
docker compose up -d --build        # PostgreSQL (127.0.0.1:5442) e API (127.0.0.1:8080)
cd frontend && npm ci && npm run dev   # http://localhost:5173
```

A API responde em `http://127.0.0.1:8080/api/v1/health`. Com o perfil `local` (padrão do Compose), a documentação
interativa fica em `http://127.0.0.1:8080/swagger-ui.html`.

## Testes

```bash
cd backend && ./mvnw verify          # precisa do Docker ligado
cd frontend && npm run verify        # lint, tipos, testes, build e tamanho
```

## Configuração

Copie `.env.example` para `.env` (ignorado pelo git) para mudar os valores. Os padrões servem só para o ambiente local.

| Variável | Para quê |
|---|---|
| `DB_MIGRATION_PASSWORD` | Senha do dono do schema (Flyway e container do Postgres) |
| `DB_APP_PASSWORD` | Senha da role `tostao_app`, usada pela API |
| `JWT_SECRET` | Assinatura da sessão, com ao menos 32 bytes (obrigatório fora do perfil `local`) |
| `COOKIE_SECURE` | `true` em produção (HTTPS) |
| `CORS_ORIGIN` | Única origem aceita no CORS |

## Estrutura

- `backend/`: API Spring Boot (Java 21, Maven, Flyway)
- `frontend/`: React, TypeScript e Vite, como PWA ([detalhes](frontend/README.md))
- `specs/`: especificações, planos e tarefas de cada funcionalidade
- `.specify/`: constituição do projeto e modelos do Spec Kit
