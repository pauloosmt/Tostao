# Data Model: Categorias

A tabela `categories` já existe na `V1`. Esta feature acrescenta a migration **`V4__categories.sql`** (a `V1`,
a `V2` e a `V3` não são editadas) com restrições, a chave composta de tipo, o gatilho de profundidade, os
índices únicos parciais, as políticas de RLS de escrita por papel, a revogação do `DELETE`, a função de
semente e a recriação de `register_user`.

## Entidades

### Categoria (`categories`, existente)

| Campo | Regra |
|---|---|
| `id` | UUID gerado pelo banco; junto com `workspace_id` (e `kind`) forma a chave usada pelas chaves compostas |
| `workspace_id` | obrigatório; nunca vem do cliente (vem da rota, depois de confirmada a participação) |
| `parent_id` | nulo na categoria principal; na subcategoria aponta para uma principal do mesmo workspace e do mesmo tipo; imutável depois de criada |
| `name` | 1 a 60 caracteres, sem espaços nas pontas; único entre irmãs ativas, sem diferenciar maiúsculas |
| `kind` | `INCOME` ou `EXPENSE`; herdado do pai na subcategoria; imutável depois de criada |
| `color` | nulo ou `#RRGGBB` (hexadecimal de seis dígitos) |
| `icon` | nulo ou texto de 1 a 40 caracteres |
| `archived` | booleano, `false` ao criar; só muda pelas ações de arquivar e restaurar |
| `created_at` | preenchido pelo banco na criação |

"Irmãs" são: as subcategorias de um mesmo pai; e as principais do mesmo workspace e do mesmo tipo.

### Conjunto padrão (não é tabela)

Função `seed_default_categories(workspace)` com os nomes escritos nela:

| Tipo | Principal | Subcategorias |
|---|---|---|
| EXPENSE | Alimentação | Mercado, Restaurantes |
| EXPENSE | Moradia | Aluguel, Contas de casa |
| EXPENSE | Transporte | Combustível, Transporte público |
| EXPENSE | Saúde | Farmácia, Consultas |
| EXPENSE | Lazer | Viagens, Assinaturas |
| EXPENSE | Educação | |
| EXPENSE | Outros | |
| INCOME | Salário | |
| INCOME | Rendimentos | |
| INCOME | Outros | |

Depois de copiado, o conjunto não tem vínculo com o workspace: as categorias são comuns.

### Relações

- Categoria → Workspace: muitas para uma (`workspace_id`).
- Subcategoria → Categoria principal: muitas para uma, pela chave composta
  `(parent_id, workspace_id, kind)` (mesmo workspace, mesmo tipo).
- Lançamento → Categoria: muitas para uma, pela chave composta `(category_id, workspace_id)` da V1.

## Transições de estado

```text
ativa --arquivar--> arquivada --restaurar--> ativa
principal ativa --arquivar--> principal e suas subcategorias ativas ficam arquivadas (mesma transação)
arquivada --restaurar principal--> só a principal volta; as subcategorias seguem arquivadas
arquivada --restaurar subcategoria (pai arquivado)--> recusado (409)
arquivada --restaurar (nome em uso por irmã ativa)--> recusado (409)
ativa --arquivar (já arquivada)--> sem efeito (sucesso)
arquivada --editar--> recusado (409)
```

Invariante: nunca existe subcategoria ativa sob uma principal arquivada. Não existe transição de exclusão.

## Migration V4 (resumo)

1. `CHECK categories_name_trimmed`: `name = btrim(name)` e `char_length(name)` de 1 a 60.
2. `CHECK categories_color_format`: `color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$'`.
3. `CHECK categories_icon_length`: `icon IS NULL OR char_length(icon) BETWEEN 1 AND 40`.
4. `UNIQUE (id, workspace_id, kind)` e `FOREIGN KEY (parent_id, workspace_id, kind) REFERENCES categories
   (id, workspace_id, kind)`: tipo e workspace do pai.
5. Gatilho `categories_two_levels` (`BEFORE INSERT OR UPDATE OF parent_id`): recusa um pai que já tem pai e
   uma categoria com filhas que ganhe um pai.
6. Índices únicos parciais `categories_active_root_name_key` e `categories_active_child_name_key`
   (definições na [pesquisa](research.md), item 4).
7. Remove a política `categories_tenant` (FOR ALL) e cria `categories_read` (SELECT), `categories_insert` e
   `categories_update`, as duas últimas exigindo `workspace_id = app_workspace_id()` e
   `app_can_write(workspace_id)` em `USING` e `WITH CHECK`. Sem política de `DELETE`.
8. `REVOKE DELETE ON categories FROM tostao_app`.
9. `seed_default_categories(p_workspace UUID)`, com `REVOKE ALL ... FROM PUBLIC`.
10. `CREATE OR REPLACE FUNCTION register_user(...)`: corpo da `V2` mais `PERFORM
    seed_default_categories(v_workspace)`.
11. Semente única para os workspaces existentes sem nenhuma categoria.

## Políticas de RLS resultantes

| Política | Operação | Condição |
|---|---|---|
| `categories_read` | SELECT | `workspace_id = app_workspace_id()` |
| `categories_insert` | INSERT | `workspace_id = app_workspace_id()` e `app_can_write(workspace_id)` |
| `categories_update` | UPDATE | mesma condição em `USING` e em `WITH CHECK` |

## Validações de entrada (aplicadas na API, antes do banco)

| Campo | Regra |
|---|---|
| `name` | obrigatório, 1 a 60 caracteres após `strip` |
| `kind` | na criação, obrigatório sem `parentId`; com `parentId`, opcional e igual ao do pai; na edição, opcional e igual ao atual |
| `parentId` | opcional na criação (subcategoria); principal ativa do mesmo workspace; na edição, opcional e igual ao atual |
| `color` | opcional, `#RRGGBB` |
| `icon` | opcional, 1 a 40 caracteres |
| `kind` (filtro da lista) | `INCOME` ou `EXPENSE`; outro valor é 400 no campo `kind` |
| campos extras | rejeitados (`workspaceId`, `archived`, `createdAt` e qualquer outro) |

## Índices relevantes

- Existentes: `categories_workspace_idx (workspace_id, parent_id)` e `UNIQUE (id, workspace_id)` (V1).
- Novos: `UNIQUE (id, workspace_id, kind)`, `categories_active_root_name_key` e
  `categories_active_child_name_key`.
