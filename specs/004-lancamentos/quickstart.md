# Quickstart: validar Lançamentos

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado.
- Stack local de pé: `docker compose up -d --build` (a `V5` é aplicada sobre o banco que já está na `V4`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 (`--data-binary @arquivo`) no
  Windows.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos os testes passam, inclusive os novos de `transaction/`, o `RlsPolicyTest` ajustado e o
`OpenApiContractTest` com o contrato da `004`.

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace. Ela já tem as
   categorias padrão (`GET .../categories`): guarde o `id` de "Alimentação" (despesa) e de "Salário"
   (receita).
2. Crie duas contas: `Corrente` (`CHECKING`, saldo inicial 100000) e `Poupança` (`SAVINGS`, saldo inicial 0).

## 3. Receita e despesa (US1)

1. `POST .../transactions` `{"type":"EXPENSE","accountId":"<Corrente>","amountCents":10000,"occurredOn":
   "2026-11-05","description":"Mercado","categoryId":"<Alimentação>"}` → 201, `amountCents` `-10000`.
2. `POST` de uma `INCOME` de 25000 com a categoria "Salário" → 201, `amountCents` `25000`.
3. `GET .../accounts/<Corrente>` → `balanceCents` `115000` (100000 − 10000 + 25000).
4. Recusas (todas 400, com o campo): valor `0`, `-5`, `10.5`, `1000000000001`; descrição vazia; data
   `1999-12-31`; despesa sem `categoryId` (campo `categoryId`); despesa com a categoria "Salário" (campo
   `categoryId`); `"type":"OUTRO"`; corpo com `createdBy`, `workspaceId` ou `deletedAt`.
5. Conta arquivada (`POST .../accounts/<id>/archive`) → o cadastro nela dá 409; categoria arquivada idem.
6. Conta ou categoria de outro workspace → 404.

## 4. Transferência (US2)

1. `POST` `{"type":"TRANSFER","accountId":"<Corrente>","destinationAccountId":"<Poupança>","amountCents":30000,
   "occurredOn":"2026-11-06","description":"Reserva"}` → 201 (ponta de saída, com `transferGroupId` e
   `counterpartAccountId`).
2. `GET .../accounts` → Corrente `85000`, Poupança `30000`; `GET .../reports/balance` → total igual ao de antes.
3. Recusas: origem igual ao destino (campo `destinationAccountId`); com `categoryId` (campo `categoryId`);
   sem `destinationAccountId`; destino arquivado (409) e destino de outro workspace (404), sem nenhuma ponta
   criada (contagem no banco igual).

## 5. Consultar e filtrar (US3)

1. `GET .../transactions` → página 1, ordem do mais recente para o mais antigo, com `totalItems` e
   `totalPages`; a transferência aparece duas vezes (uma por conta).
2. `?size=2&page=1`, `page=2`: cobre tudo sem repetir. `size=0`, `size=101`, `page=0` → 400 com o campo.
3. `?accountId=<Poupança>` → só a entrada da transferência. `?type=INCOME`. `?categoryId=<Alimentação>`
   (inclui as subcategorias). `?from=2026-11-06&to=2026-11-06`. `?from=2026-11-07&to=2026-11-06` → 400.
4. `?q=merc` e `?q=MERC` → o "Mercado". `?q=%25` (o texto `%`) não casa com tudo.
5. `?type=XYZ`, `?accountId=abc`, `?from=ontem` → 400 com o campo.

## 6. Permissão e isolamento (US4)

1. Pessoa B (outro workspace) usa o workspace de A: 404, igual a um UUID inexistente.
2. Semeie B como `VIEWER` no workspace de A (`insert into workspace_members`): B lista e consulta (200);
   cria, edita e exclui (403).
3. Troque B para `EDITOR`: cria (201), edita e exclui o lançamento que A criou (`createdBy` segue sendo o de
   A).
4. Sem cookie: 401. Id de lançamento de A na rota de B: 404. Id malformado: 404.
5. No banco, como `tostao_app`: `delete from transactions` → `permission denied`;
   `update transactions set created_by = ...` → `permission denied`.

## 7. Editar (US5)

1. `PUT` mudando valor e categoria de uma despesa → 200 e o saldo da conta muda pelo delta.
2. `PUT` mudando a conta → o saldo das duas contas muda.
3. `PUT` com `type` diferente → 400 no campo `type`.
4. `PUT` na ponta de entrada de uma transferência mudando valor e data → as duas pontas mudam (conferir pela
   lista); mudando o destino para a mesma conta da origem → 400.
5. `PUT` passando a usar uma conta arquivada → 409; manter a conta arquivada que o lançamento já tinha →
   200.

## 8. Excluir (US6)

1. `DELETE` de uma despesa → 204; some da lista e do saldo; consultar, editar e excluir de novo → 404; a
   linha segue no banco com `deleted_at` preenchido.
2. `DELETE` de uma ponta de transferência → as duas somem e os saldos voltam ao valor anterior.
3. `DELETE` de um lançamento numa conta arquivada → 204.

## 9. Limpeza

Apague do banco, como dono, os dados de teste criados nesta sessão (transações, contas, categorias,
associações, workspaces e usuários) e confira as contagens.
