# Quickstart: validar Contas

Roteiro para provar, com chamadas reais, que a feature funciona. Contratos em
[contracts/openapi.yaml](contracts/openapi.yaml); tabela, restrições e políticas em
[data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e a stack no ar: `docker compose up -d --build` (a migration `V3` roda sozinha).
- `BASE=http://127.0.0.1:8080/api/v1` e uma conta cadastrada, com o cookie guardado:
  `curl -c jar.txt -H 'Content-Type: application/json' -d '{"name":"Ana","email":"ana@example.com","password":"Senha-Forte-123"}' $BASE/auth/register`.
- `WID` é o `id` do workspace devolvido em `workspaces[0].id`.
- O papel `VIEWER` e o `EDITOR` não têm rota para serem atribuídos; os passos de papel usam `psql` como
  dono do banco para semear a associação.

## 1. Testes automatizados

```bash
cd backend && ./mvnw verify
```

Esperado: tudo verde, incluindo os de acesso cruzado, de papel, de saldo com lançamentos e de desempenho.

## 2. Cadastro e lista (US1)

1. `POST $BASE/workspaces/$WID/accounts` com `{"name":"Nubank","type":"CHECKING","initialBalanceCents":100000}`
   (`-b jar.txt`). Esperado: **201**, `balanceCents: 100000`, `currency: "BRL"`, `archived: false`.
2. Cartão: `{"name":"Cartão","type":"CREDIT_CARD","initialBalanceCents":-25050,"creditLimitCents":500000,"closingDay":10,"dueDay":17}`.
   Esperado: **201** com limite e os dois dias.
3. Campos de cartão em conta corrente. Esperado: **400** com `errors[].field` apontando o campo.
4. Nome repetido (`"nubank "`). Esperado: **409**.
5. Valor decimal `{"initialBalanceCents":10.5}`. Esperado: **400**.
6. `GET $BASE/workspaces/$WID/accounts`. Esperado: **200** com as duas contas, ordenadas por nome.

## 3. Saldo (US2)

1. Inserir lançamentos pelo banco para a conta corrente (+25000 e -10000) e um excluído logicamente.
2. `GET .../accounts/{id}`. Esperado: `balanceCents` = 100000 + 25000 - 10000 = **115000**; o excluído não conta.
3. `GET $BASE/workspaces/$WID/reports/balance`. Esperado: `totalBalanceCents` = soma das contas listadas
   (115000 + (-25050) = **89950**).
4. Arquivar a conta corrente e repetir. Esperado: o total cai para **-25050** e a conta some do relatório.

## 4. Permissão e isolamento (US3)

1. Cadastrar uma segunda pessoa (`jar2.txt`) e usar o `WID` da primeira com o cookie dela em qualquer rota
   de contas. Esperado: **404**, idêntico a um UUID inexistente.
2. Semear `VIEWER` do workspace da primeira para a segunda pessoa (`psql`, inserção em `workspace_members`).
   Com o cookie dela: `GET .../accounts` dá **200**; `POST`, `PUT` e `archive` dão **403**.
3. Semear `EDITOR` no lugar: `POST` dá **201**.
4. Sem cookie: **401**.
5. Conta de outro workspace pela rota do meu: `PUT $BASE/workspaces/$WID/accounts/<id-de-outro-workspace>`
   dá **404**.
6. No banco, como `tostao_app`: `delete from accounts` dá `permission denied`.

## 5. Editar, arquivar e restaurar (US4 e US5)

1. `PUT .../accounts/{id}` mudando nome e saldo inicial. Esperado: **200**; o saldo muda pelo mesmo delta.
2. `PUT` com `type` diferente. Esperado: **400** com `field: "type"`.
3. `POST .../archive`. Esperado: **200**, `archived: true`; a conta sai da lista padrão.
4. `GET .../accounts?includeArchived=true`. Esperado: a conta aparece marcada como arquivada.
5. `PUT` na conta arquivada. Esperado: **409**.
6. Criar outra conta ativa com o nome da arquivada e tentar `POST .../restore`. Esperado: **409**.
7. Sem o conflito, `POST .../restore`. Esperado: **200**, `archived: false`, de volta ao total.
8. Não existe `DELETE`: `DELETE .../accounts/{id}`. Esperado: **405** ou **404**, nunca sucesso.
