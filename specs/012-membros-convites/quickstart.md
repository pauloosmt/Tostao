# Quickstart: validar Membros e convites

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e stack local reconstruída: `docker compose up -d --build` (as migrations `V12` e `V13` rodam na
  subida; confira `select version from flyway_schema_history order by installed_rank desc limit 1` → `13`).
- `BASE=http://127.0.0.1:8080/api/v1`. Três pessoas, cada uma com o seu cookie: **Dona** (`jar.txt`), **Marido** (`jar2.txt`)
  e **Intrusa** (`jar3.txt`), cadastradas por `POST /auth/register`, cada uma com o seu workspace pessoal. `$WID` é o
  workspace da Dona.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos passam, inclusive os das specs 001 a 011 **sem alteração de resultado** (FR-023).

## 2. Preparo

1. Cadastre as três pessoas e guarde o `id` de cada uma e o `$WID` da Dona.
2. Na Dona: crie a conta `Corrente` e pegue a categoria `Alimentação` (para o Marido lançar depois).

## 3. Convidar (US1)

1. Dona: `POST .../workspaces/$WID/invitations` com `{"email":"  Marido@Exemplo.com ","role":"EDITOR"}` → **201** com `id`,
   `email` **marido@exemplo.com** (normalizado), `role` **EDITOR**, `createdBy`, `expiresAt` (7 dias) e `code` de **43**
   caracteres. Guarde o `code` (`CODE1`); a resposta traz `Cache-Control: no-store`.
2. `GET .../invitations` (Dona) → 1 pendente, **sem** o campo `code`.
3. Recusas: `role` `OWNER`, `"OUTRO"`, ausente → **400**, campo `role`; `email` vazio, `abc`, com 255 caracteres → **400**,
   campo `email`; campo extra (`createdBy`, `workspaceId`) → **400**.
4. Reconvidar: `POST` de novo para o mesmo e-mail, agora `VIEWER` → **201** com outro `code` (`CODE2`); `GET .../invitations`
   → **1** pendente (o anterior foi invalidado); `CODE1` deixa de funcionar (seção 4).

## 4. Aceitar (US1)

1. Intrusa: `POST /invitations/accept` com `{"code":"$CODE2"}` → **404** "Convite inválido ou expirado".
2. Marido com um código aleatório e com `CODE1` (invalidado pelo reconvite) → **404**, o **mesmo** `title` e `detail` do
   passo anterior.
3. Marido com `CODE2` → **200** `{workspaceId=$WID, name, role=VIEWER}`. Repetir → **404** (já usado).
4. `GET /me` do Marido → **2** workspaces (o pessoal, `OWNER`, e o da Dona, `VIEWER`).
5. Marido tenta lançar uma despesa no workspace da Dona → **403** (VIEWER); `GET .../transactions` → **200**.
6. Convite expirado: a Dona convida o e-mail da Intrusa (`CODE3`); pelo banco do dono, `update invitations set expires_at =
   now() - interval '1 minute'`; a Intrusa aceita → **404**; `GET .../invitations` (Dona) já não o lista.

## 5. Membros (US2)

1. Dona, Marido (VIEWER) e qualquer outro membro: `GET .../members` → **2** membros (a Dona primeiro, `OWNER`; o Marido,
   `VIEWER`), cada um com `userId`, `name`, `email`, `role`, `joinedAt`; nada de senha, hash ou versão de sessão.
2. Intrusa (não membro): `GET .../members` → **404**, igual a um `$WID` aleatório.

## 6. Gerenciar (US3)

1. Dona muda o Marido para EDITOR: `PUT .../members/{marido}` `{"role":"EDITOR"}` → **200**. **Na requisição seguinte** o
   Marido lança uma despesa de 5000 → **201**, com `createdBy` = Marido.
2. Dona volta o Marido a VIEWER → o lançamento dele seguinte é **403** (efeito imediato, sem novo login).
3. Recusas à Dona: mudar o próprio papel e o papel do OWNER → **409**; `{"role":"OWNER"}` → **400**; remover a si mesma →
   **409**. Ao Marido (VIEWER ou EDITOR): convidar, listar convites, revogar, mudar papel e remover a Dona → **403**.
4. Marido sai: `DELETE .../members/{marido}` → **204**. Depois: `GET .../workspaces/$WID` e `.../members` → **404**; `GET
   /me` do Marido → **1** workspace (o pessoal). A despesa que ele lançou continua no workspace da Dona, com `createdBy`
   dele.
5. Dona reconvida o Marido (`CODE4`) e o Marido aceita (EDITOR); a Dona remove o Marido: `DELETE .../members/{marido}` →
   **204**, e o Marido passa a receber **404** no workspace.
6. Revogar: Dona convida o Marido (`CODE5`), `DELETE .../invitations/{id}` → **204**; o aceite com `CODE5` → **404**; revogar
   de novo → **404**; `GET .../invitations` → vazio.

## 7. Só o dono gerencia e cada workspace é isolado (US4)

1. Intrusa (não membro) tenta listar membros, convidar, listar e revogar convites, mudar papel e remover no `$WID` → **404**
   igual ao de um workspace inexistente (mesmo `title` e `detail`). Sem cookie → **401**.
2. O `id` de um convite do `$WID` usado no workspace pessoal da Intrusa → **404**.
3. O código do `$WID` não dá acesso a nenhum outro workspace (só o convite que o gerou).
4. O banco guarda só a impressão: `select code_hash from invitations` mostra 64 hexadecimais e **nenhum** valor de `CODE*`;
   o log da API (`docker logs`) não contém nenhum dos códigos.

## 8. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão (inclusive as `invitations`) e confira `users = workspaces =
workspace_members = accounts = categories = transactions = invitations = 0`.
