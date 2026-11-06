# Tostao
App Open Source de finanças pessoais para desktop e celular (PWA). Back-end em Spring Boot e PostgreSQL, pronto para vários usuários por workspace

## Rodando localmente

```bash
docker compose up -d --build          # Postgres (127.0.0.1:5442) + API (127.0.0.1:8080)
curl http://127.0.0.1:8080/api/v1/health

cd frontend && npm install && npm run dev   # http://localhost:5173, /api vai para a API
```

Testes do backend (usam Testcontainers, precisam do Docker ligado): `cd backend && ./mvnw verify`.

## Estrutura

- `backend/`: API Spring Boot (Java 21, Maven, Flyway)
- `frontend/`: React + TypeScript + Vite, como PWA
- `.specify/`: constituição e templates do Spec Kit; specs em `specs/`
