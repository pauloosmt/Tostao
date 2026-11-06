# Research: Categorias

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Reaproveitar a tabela `categories` da V1

- **Decisão**: não recriar a tabela. A migration `V4__categories.sql` só acrescenta restrições, índices,
  gatilho e políticas.
- **Motivo**: a V1 já tem `parent_id`, `kind`, `color`, `icon`, `archived`, `created_at`, a chave
  `(id, workspace_id)` usada pela chave composta de `transactions` e a chave composta do pai
  `(parent_id, workspace_id)`. Migration aplicada nunca é editada.
- **Sem `updated_at`**: a spec pede só a data de criação (FR-020). Não se acrescenta coluna sem uso.

## 2. Restrições novas no banco (V4)

- **Decisão**: `CHECK` de nome já sem espaços nas pontas e com 1 a 60 caracteres; `CHECK` de cor no
  formato `#RRGGBB` quando existir; `CHECK` de ícone com 1 a 40 caracteres quando existir.
- **Motivo**: a API valida primeiro (mensagem por campo), mas o banco é a segunda linha de defesa contra
  qualquer escrita por outro caminho.

## 3. Hierarquia garantida pelo banco

- **Decisão**: (a) `UNIQUE (id, workspace_id, kind)` e uma segunda chave estrangeira
  `(parent_id, workspace_id, kind) → categories (id, workspace_id, kind)`: a subcategoria só pode apontar
  para um pai do mesmo workspace **e do mesmo tipo**, e o tipo de um pai com filhas não pode mudar; (b)
  gatilho `BEFORE INSERT OR UPDATE OF parent_id` que recusa um pai que já é subcategoria e uma categoria
  com filhas que ganhe um pai (impede o terceiro nível).
- **Motivo**: a V1 deixou "dois níveis" só para o serviço. Tipo herdado e profundidade são invariantes
  que, se quebrados, estragam o dashboard; por isso valem nas duas camadas, como o isolamento. A chave
  composta cobre o tipo sem código; o gatilho é o menor meio de cobrir a profundidade (um `CHECK` não
  consulta outra linha).
- **Princípio VI**: o serviço checa tudo antes (pai existe, ativo, é principal); o gatilho e a FK são rede
  de segurança, nunca fluxo normal, então não há `catch` dentro de transação.
- **Alternativas**: coluna `depth` gerada com `CHECK (depth <= 1)` (mais uma coluna e ainda precisa ler o
  pai); só validar no serviço (uma camada).

## 4. Nome único entre irmãs ativas

- **Decisão**: dois índices únicos parciais:
  - `categories_active_root_name_key` em `(workspace_id, kind, lower(name)) WHERE NOT archived AND
    parent_id IS NULL` (principais do mesmo tipo);
  - `categories_active_child_name_key` em `(workspace_id, parent_id, lower(name)) WHERE NOT archived AND
    parent_id IS NOT NULL` (subcategorias do mesmo pai).
- **Criação**: `INSERT ... ON CONFLICT DO NOTHING RETURNING id`. Sem alvo de conflito, cobre os dois
  índices com uma só instrução; resultado vazio vira 409. Não há outra restrição única que pudesse ser
  confundida (a de `(id, workspace_id)` usa `id` novo).
- **Edição e restauração**: checagem prévia por `exists` (mesma regra de irmãs) e, na corrida residual, o
  índice barra a segunda gravação; a violação sobe, a transação sofre rollback e um `@ExceptionHandler`
  de `DuplicateKeyException` na borda web traduz em 409 para os dois nomes de índice.
- **Alternativas**: um índice só com `coalesce(parent_id, kind-uuid)` (obscuro); unicidade só na
  aplicação (corrida deixa passar duplicata).

## 5. Permissão por papel e sem exclusão

- **Decisão**: igual a `accounts` (V3): remove a política `categories_tenant` (FOR ALL) e cria
  `categories_read` (SELECT), `categories_insert` e `categories_update`, as duas últimas exigindo
  `app_can_write(workspace_id)` (função da V3, reaproveitada). `REVOKE DELETE ON categories FROM
  tostao_app`.
- **Motivo**: o princípio II e o FR-015 ("não existe apagar") viram garantia do banco, e VIEWER não
  escreve mesmo se um serviço esquecer a anotação.

## 6. Conjunto padrão no cadastro

- **Decisão**: função `seed_default_categories(p_workspace UUID)` (SQL/plpgsql) com os nomes escritos
  nela, chamada por `register_user` logo depois de criar o workspace. `register_user` é recriada na
  `V4` com `CREATE OR REPLACE` (mesma assinatura e mesmo retorno; os privilégios são mantidos), repetindo
  o corpo da `V2` mais a chamada à semente. A `V2` não é editada.
- **Atomicidade (FR-002)**: usuário, workspace, associação e categorias saem da mesma chamada, portanto da
  mesma transação. Se a semente falhar, o cadastro volta inteiro. Nenhum código da aplicação é
  necessário, e o `AuthService` não muda.
- **Privilégio**: `REVOKE ALL ... FROM PUBLIC` na semente e nenhum `GRANT` à `tostao_app`. Só roda dentro
  de `register_user` (que é `SECURITY DEFINER`) e da própria migration. A role da aplicação não consegue
  semear nem duplicar categorias por conta própria.
- **Conjunto** (FR-001):
  - Despesas: Alimentação (Mercado, Restaurantes), Moradia (Aluguel, Contas de casa), Transporte
    (Combustível, Transporte público), Saúde (Farmácia, Consultas), Lazer (Viagens, Assinaturas),
    Educação, Outros.
  - Receitas: Salário, Rendimentos, Outros.
  - Total: 10 principais e 10 subcategorias.
- **Alternativas**: semear no `AuthService` por `JdbcClient` (a role não tem INSERT em `categories` sem
  contexto de workspace, e seria uma segunda transação, quebrando a atomicidade); tabela de modelos
  editável (flexibilidade sem necessidade, princípio VII).

## 7. Workspaces que já existem

- **Decisão**: no fim da `V4`, `SELECT seed_default_categories(id)` para cada workspace sem nenhuma
  categoria. A migration roda uma única vez, então o conjunto nunca é recriado depois que a pessoa edita
  ou arquiva (FR-003, FR-004). A migration roda como dono do schema, que não é submetido ao RLS.
- **Motivo**: nada de lógica "semear se faltar" em tempo de execução, que ressuscitaria categorias que a
  pessoa arquivou.
- **Alternativas**: semear na primeira consulta (efeito colateral em GET e corrida).

## 8. Arquivar em cascata e restaurar

- **Decisão**: `POST .../categories/{id}/archive` e `.../restore`, idempotentes (200 com o estado final).
  - Arquivar uma principal: na mesma transação, `UPDATE` da principal e depois `UPDATE` das subcategorias
    ativas dela. Só cascateia quando a principal realmente mudou de estado.
  - Restaurar uma principal: só ela volta. Restaurar uma subcategoria exige o pai ativo (409 se não) e
    nome livre entre irmãs ativas (409 se não).
  - Editar uma arquivada: 409.
- **Concorrência pai x filha (edge case da spec)**: criar ou restaurar uma subcategoria faz
  `SELECT ... FOR SHARE` na linha do pai e confere que está ativo; arquivar a principal começa por um
  `UPDATE` da própria linha, que pede bloqueio exclusivo. Se o arquivamento vence, a criação enxerga o pai
  arquivado e recusa (409). Se a criação vence, o arquivamento espera e a segunda instrução da cascata
  (nova consulta, novo snapshot) já enxerga a filha nova e a arquiva. Nunca sobra filha ativa sob pai
  arquivado.
- **Alternativas**: restaurar a principal restaurando as filhas (não dá para saber quais foram arquivadas
  junto e quais antes); `PATCH` com `archived` (reabriria um campo que a spec manda recusar na entrada).

## 9. Leitura em árvore

- **Decisão**: `GET /categories` devolve só as principais, cada uma com `subcategories` aninhadas. Uma
  consulta traz todas as linhas do workspace (filtro opcional por tipo; por padrão só ativas, ordenadas por
  `lower(name)`), e a árvore é montada em memória. Com a invariante "filha ativa só sob pai ativo", a
  árvore ativa nunca perde uma filha. `GET /categories/{id}` devolve a categoria plana, com `parentId`.
- **Filtro de tipo**: parâmetro de consulta `kind` lido como texto e validado no serviço; valor
  desconhecido vira 400 com o campo `kind` (um `enum` direto cairia no tratamento de tipo errado da
  `002`, pensado para identificadores de rota).
- **Alternativas**: lista plana (obriga cada cliente a montar a árvore); dois endpoints (um por nível).

## 10. Entrada na criação e na edição

- **Decisão**: criação aceita `name`, `kind`, `parentId`, `color` e `icon`. Sem `parentId`, `kind` é
  obrigatório (principal). Com `parentId`, `kind` é opcional e, se vier, tem de ser igual ao do pai (senão
  400 no campo `kind`); pai de outro workspace ou inexistente é 404; pai arquivado é 409; pai que já é
  subcategoria é 400 no campo `parentId`.
- **Edição**: aceita `name`, `color`, `icon` e, opcionalmente, `kind` e `parentId` só para o cliente poder
  devolver o objeto inteiro; se vierem diferentes do atual, 400 no campo. `color` e `icon` ausentes
  limpam o valor (a edição é completa, como a `002`). Limitação conhecida: `parentId` nulo não se
  distingue de ausente, então "mover para o topo" não é detectado como tentativa, apenas ignorado; o
  parent continua inalterado, o que atende a spec (nada muda).
- **Campos fora da lista** (`workspaceId`, `archived`, `createdAt`): recusados como desconhecidos, pela
  configuração de Jackson da `002`.

## 11. Testes

- **Decisão**: o `WorkspaceFixtures` ganha `insertCategory`. Testes de papel semeiam `VIEWER` e `EDITOR`
  pelo banco. Teste específico para a `V4` sobre banco já na `V3` com workspace sem categorias (semente da
  migração), teste de concorrência com threads para o mesmo nome (SC-009) e para arquivar x criar filha, e
  teste direto no banco das invariantes (terceiro nível, tipo diferente, pai de outro workspace) com a
  role `tostao_app` e com o dono.
- **Motivo**: prova as regras com Postgres real, sem depender de rotas que não existem.

## Resultado

Nenhum item ficou em aberto: todas as dúvidas do Technical Context foram resolvidas acima.
