# Quickstart: validar Categorias

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado.
- Stack local de pé: `docker compose up -d --build` (a `V4` é aplicada sobre o banco que já está na `V3`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 (`--data-binary @arquivo`) no
  Windows.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos os testes passam, inclusive os novos de `category/` e o `OpenApiContractTest` com o
contrato da `003`.

## 2. Conjunto padrão (US1)

1. Cadastre uma pessoa (`POST /auth/register`) e guarde o cookie e o `id` do workspace.
2. `GET /workspaces/$WID/categories` → 10 principais (7 de despesa, 3 de receita) e 10 subcategorias sob
   Alimentação, Moradia, Transporte, Saúde e Lazer.
3. Cadastre uma segunda pessoa e confira que ela tem o próprio conjunto, e que arquivar "Lazer" na primeira
   não muda nada na segunda.
4. No banco, confira que os workspaces criados antes da `V4` ganharam o conjunto uma vez:
   `select w.id, count(c.*) from workspaces w join categories c on c.workspace_id = w.id group by w.id`.

## 3. Árvore, filtros e leitura (US2)

1. `GET .../categories?kind=INCOME` → só Salário, Rendimentos e Outros.
2. `GET .../categories?kind=XYZ` → 400 com o campo `kind`.
3. Arquive uma categoria e confira que some da lista e aparece com `includeArchived=true`.
4. `GET .../categories/{id}` de uma subcategoria → traz `parentId` e o tipo do pai.

## 4. Permissão e isolamento (US3)

1. Pessoa B (de outro workspace) usa o workspace de A: 404, igual a um UUID inexistente.
2. Semeie B como `VIEWER` no workspace de A (`insert into workspace_members`): B lista e consulta (200);
   cria, edita, arquiva e restaura (403).
3. Troque B para `EDITOR`: cria (201).
4. Sem cookie: 401. Id de categoria de A na rota de B: 404. Id malformado: 404.
5. No banco, como `tostao_app`: `delete from categories` → `permission denied`;
   `select seed_default_categories('<uuid>')` → `permission denied`.

## 5. Criar e hierarquia (US4)

1. Crie uma principal `{"name":"Pets","kind":"EXPENSE"}` → 201.
2. Crie uma subcategoria `{"name":"Ração","parentId":"<Pets>"}` → 201, com `kind` igual ao da pai.
3. Subcategoria com `kind` diferente do pai → 400 (campo `kind`).
4. Categoria sob uma subcategoria → 400 (campo `parentId`).
5. Mesmo nome sob a mesma principal (`"ração "`) → 409; o mesmo nome sob outra principal → 201.
6. O conjunto padrão já tem "Outros" de despesa e "Outros" de receita (mesmo nome, tipos diferentes). Criar
   outra principal "outros" de despesa → 409; arquivar a de despesa e criar de novo → 201.
7. Corpo com `archived`, `workspaceId` ou cor `vermelho` → 400.

## 6. Editar (US5)

1. `PUT` renomeando e dando cor `#1A9E5C` → 200.
2. `PUT` com `kind` diferente do atual, ou `parentId` diferente → 400 no campo.
3. `PUT` numa arquivada → 409.

## 7. Arquivar e restaurar (US6)

1. Arquive "Pets" → ela e "Ração" saem da lista padrão; aparecem com `includeArchived=true`.
2. Restaure "Ração" com "Pets" arquivada → 409. Restaure "Pets" → 200; "Ração" continua arquivada.
3. Restaure "Ração" → 200.
4. Arquive duas vezes → 200 nas duas. `DELETE` → 405, e a categoria continua no banco.
5. Lançamento já feito com a categoria (inserido pelo banco) continua ligado a ela depois de arquivada.

## 8. Cadastro atômico

1. Repita o cadastro com o mesmo e-mail → 409, sem criar workspace nem categorias novas (contagem igual).
2. (Teste automatizado) Falha forçada na semente desfaz o usuário e o workspace.

## 9. Limpeza

Apague do banco, como dono, os dados de teste criados nesta sessão (transações, categorias, associações,
workspaces e usuários) e confira as contagens.
