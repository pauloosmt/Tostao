# Quickstart: validar Autenticação e Workspace

Roteiro para provar, com chamadas reais, que a feature funciona. Contratos em
[contracts/openapi.yaml](contracts/openapi.yaml); tabelas e políticas em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e `.env` baseado em `.env.example` (inclui `DB_APP_PASSWORD`, `JWT_SECRET` e
  `COOKIE_SECURE=false` para o ambiente local).
- API e banco no ar: `docker compose up -d --build`.
- Os exemplos usam `curl` e um arquivo de cookies (`-c` grava, `-b` envia). `BASE=http://127.0.0.1:8080/api/v1`.

## 1. Testes automatizados

```bash
cd backend && ./mvnw verify
```

Esperado: todos os testes passam, incluindo os de acesso cruzado entre workspaces e o que confere RLS
ligado em todas as tabelas.

## 2. Cadastro e workspace pessoal (US1)

1. `POST $BASE/auth/register` com nome, e-mail e senha válida (`-c jar.txt`).
   Esperado: **201**, `Set-Cookie: tostao_session=...; HttpOnly; SameSite=Lax`, corpo com `workspaces`
   contendo um item com `role: OWNER`, sem campo de senha.
2. Repetir o mesmo e-mail com maiúsculas diferentes. Esperado: **409**.
3. Enviar senha `abc` ou e-mail inválido. Esperado: **400** com `errors[].field`.
4. No banco: `select count(*) from users`, `workspaces` e `workspace_members` têm uma linha cada.

## 3. Entrar, usar e sair (US2)

1. `POST $BASE/auth/login` com credenciais corretas (`-c jar.txt`). Esperado: **200** e cookie.
2. `GET $BASE/me` com `-b jar.txt`. Esperado: **200**; a resposta traz novo `Set-Cookie` (renovação).
3. Senha errada e e-mail inexistente. Esperado: **401** com o mesmo corpo nos dois casos.
4. `POST $BASE/auth/logout` seguido de `GET $BASE/me` sem o cookie. Esperado: **204** e depois **401**.

5. Sair de todos os aparelhos: entrar duas vezes (`-c jarA.txt` e `-c jarB.txt`), `POST $BASE/auth/logout`
   com `-b jarA.txt` e confirmar que `GET $BASE/me` com `jarB.txt` ainda dá **200**. Depois
   `POST $BASE/auth/logout-all` com `-b jarA.txt` (**204**) e `GET $BASE/me` com `jarB.txt`
   deve dar **401**. Um novo login volta a funcionar.

## 4. Isolamento (US3)

1. Cadastrar uma segunda pessoa (`-c jar2.txt`) e anotar o `id` do workspace dela.
2. Com o cookie da primeira (`-b jar.txt`): `GET $BASE/workspaces/<id-da-segunda>`. Esperado: **404**.
3. Com um UUID aleatório. Esperado: **404**, idêntico ao anterior.
4. Com o id do próprio workspace. Esperado: **200** com `role: OWNER`.
5. Prova de RLS no banco, conectado como `tostao_app`, sem `WHERE`:
   `select count(*) from accounts;` retorna 0 sem contexto definido, e
   `select rolbypassrls from pg_roles where rolname = 'tostao_app';` retorna `f`.

## 5. Limite de tentativas (US5)

1. Cinco logins errados seguidos para o mesmo e-mail.
2. Sexta tentativa, mesmo com a senha certa. Esperado: **429** com `Retry-After`.
3. Reiniciar a API (`docker compose restart api`) e tentar de novo. Esperado: continua **429**.
4. Após o prazo (ou ajustando `locked_until` no banco para o passado), login correto. Esperado: **200**.

## 6. Guard-rails

- Subir com `COOKIE_SECURE=true` e o `JWT_SECRET` de desenvolvimento: a API deve recusar iniciar com
  mensagem clara.
- Resposta autenticada contém `Cache-Control: no-store`.
- `GET $BASE/me` com header `Authorization: Bearer <jwt>` e sem cookie. Esperado: **401** (o header é ignorado).
