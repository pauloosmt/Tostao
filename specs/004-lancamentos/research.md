# Research: Lançamentos

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Reaproveitar a tabela `transactions` da V1

- **Decisão**: não recriar a tabela. A migration `V5__transactions.sql` só acrescenta restrições, índice,
  políticas e privilégios.
- **Motivo**: a V1 já tem `type`, `amount_cents` com sinal (com `CHECK` de sinal por tipo e de valor
  diferente de zero), `transfer_group_id` (com `CHECK` de que só transferência tem grupo e não tem
  categoria), `created_by`, `deleted_at`, as chaves compostas `(account_id, workspace_id)` e
  `(category_id, workspace_id)` e os índices parciais por data, categoria, conta e grupo. Migration aplicada
  nunca é editada.
- **Colunas sem uso nesta spec**: `status` (fica no padrão `CLEARED`; "pendente" é da fase 2) e `notes`
  (usada como a observação opcional).

## 2. Restrições novas no banco (V5)

- **Decisão**: `CHECK` de descrição já sem espaços nas pontas e com 1 a 200 caracteres; `CHECK` de
  observação nula ou com 1 a 1.000 caracteres; `CHECK` de módulo do valor de no máximo `1_000_000_000_000`;
  `CHECK` de data entre `2000-01-01` e `2100-12-31`.
- **Motivo**: a API valida primeiro (mensagem por campo), mas o banco é a segunda linha de defesa contra
  qualquer escrita por outro caminho, e o limite de valor garante que a soma de saldo nunca estoura `BIGINT`
  (a `V3` já limitou o saldo inicial das contas da mesma forma).

## 3. Categoria obrigatória fica no serviço

- **Decisão**: a regra "toda receita e despesa tem categoria" é do serviço, não de um `CHECK`. O banco
  continua aceitando `category_id` nulo em receita e despesa.
- **Motivo**: as specs `001` a `003` têm dezenas de testes que semeiam lançamentos de receita e despesa sem
  categoria pelo dono do schema, e a importação da fase 3 pode trazer lançamentos ainda sem categoria. Um
  `CHECK` agora quebraria tudo isso sem ganho: o serviço é o único caminho de escrita (a role da aplicação
  só escreve por ele).
- **Alternativa descartada**: `CHECK (type = 'TRANSFER' OR category_id IS NOT NULL)` com reescrita das
  fixtures.

## 4. Tipo da categoria igual ao do lançamento, garantido pelo banco

- **Decisão**: segunda chave estrangeira `(category_id, workspace_id, type) → categories (id, workspace_id,
  kind)`, reaproveitando a chave única `(id, workspace_id, kind)` da `V4`. Como `type` de receita e despesa
  usa os mesmos textos de `kind` (`INCOME`, `EXPENSE`) e a categoria da transferência é nula, o banco recusa
  receita com categoria de despesa e vice-versa.
- **Motivo**: mesma ideia da `V4` (subcategoria com o tipo do pai): invariante financeira que vale nas duas
  camadas. A chave existente `(category_id, workspace_id)` continua.
- **Serviço**: confere antes e responde 400 no campo `categoryId`; a chave é rede de segurança.

## 5. Transferência: uma saída e uma entrada por grupo

- **Decisão**: índice único parcial `transactions_transfer_leg_key` em `(transfer_group_id, (amount_cents >
  0)) WHERE transfer_group_id IS NOT NULL`.
- **Motivo**: um grupo só pode ter uma ponta negativa e uma positiva, então nunca há três pontas nem duas
  saídas. A igualdade de valor, data e descrição entre as pontas é cruzada entre linhas e fica no serviço
  (que grava as duas na mesma transação) e nos testes; uma constraint diferida quebraria os testes que
  semeiam uma ponta por vez e não traria nada ao único caminho de escrita.
- **Alternativas**: tabela própria de transferências com duas chaves para lançamentos (muda o modelo da V1 e
  toda a soma de saldo); gatilho de coerência (complexidade sem caminho de escrita alternativo).

## 6. RLS de escrita por papel, `created_by` preso e sem DELETE

- **Decisão**: remover `transactions_tenant` (FOR ALL) e criar `transactions_read` (SELECT),
  `transactions_insert` e `transactions_update` (exigindo `app_can_write(workspace_id)`). A política de
  `INSERT` exige também `created_by = app_user_id()`. `REVOKE DELETE ON transactions FROM tostao_app` e
  `REVOKE UPDATE` seguido de `GRANT UPDATE (account_id, category_id, amount_cents, occurred_on, description,
  notes, updated_at, deleted_at)`.
- **Motivo**: mesma proteção das contas e categorias; "ninguém apaga lançamento" (FR-013) e "ninguém muda
  quem criou, o tipo, o grupo ou o workspace" (FR-007 e FR-015) viram garantia do banco, mesmo se um serviço
  futuro errar.
- **Efeito colateral**: o teste da `001` que apagava `transactions` com `tostao_app` passa a esperar
  "permission denied" (ajuste na implementação).

## 7. Valor com sinal: o cliente manda positivo

- **Decisão**: o corpo traz `amountCents` positivo (1 a 10^12) e `type`; o serviço grava `+valor` para
  receita e entrada e `-valor` para despesa e saída. A resposta traz o valor com sinal.
- **Motivo**: evita o cliente errar o sinal e dispensa uma regra "sinal bate com o tipo" na entrada (a `V1`
  ainda confere no banco). Valor zero, negativo ou decimal é recusado (`accept-float-as-int=false` da `002`).

## 8. Uma rota, três tipos

- **Decisão**: `POST /transactions` com `type`. Para `TRANSFER`: `accountId` é a origem,
  `destinationAccountId` o destino, e `categoryId` deve ser ausente. Para `INCOME`/`EXPENSE`:
  `destinationAccountId` deve ser ausente e `categoryId` é obrigatório. A resposta da transferência é a
  ponta de saída (com `transferGroupId` e `counterpartAccountId`), `Location` apontando para ela.
- **Edição**: `PUT /transactions/{id}` tem o mesmo corpo, com `type` opcional só para o cliente devolver o
  objeto inteiro; se vier diferente do atual, 400 no campo `type`. Em transferência, `accountId` e
  `destinationAccountId` são sempre origem e destino, qualquer que seja a ponta endereçada; o `id` da
  resposta é o da ponta endereçada.
- **Alternativas**: rota `/transfers` à parte (duplica listagem, edição e exclusão para o mesmo dado);
  derivar o tipo do sinal (ambíguo para transferência).

## 9. Contas e categorias: conferir e travar antes de gravar

- **Decisão**: o serviço carrega as contas (e a categoria) com `SELECT ... FOR SHARE`, em ordem de id para
  evitar deadlock entre duas transferências opostas, e confere: existe no workspace (404 senão), está ativa
  (409 senão), tipo da categoria igual (400). Só então grava.
- **Corrida com arquivamento**: arquivar conta ou categoria faz `UPDATE` na linha (na categoria principal,
  também nas filhas). `FOR SHARE` bloqueia esse `UPDATE` até o lançamento confirmar, e o arquivamento que
  ganhar a disputa é visto pelo lançamento, que então é recusado. Mesmo mecanismo da `003`.
- **Edição**: só confere e trava as referências que **mudaram**. Manter conta ou categoria arquivada é
  aceito (FR-012); passar a usar uma arquivada é recusado.
- **Alternativas**: `SERIALIZABLE` (aborta com erro e exigiria `catch`/retry, contra o princípio VI).

## 10. Editar e excluir a unidade da transferência

- **Decisão**: o serviço lê a linha endereçada com `FOR UPDATE`; se houver `transfer_group_id`, trava as
  duas pontas com `FOR UPDATE` em ordem de id. Linha excluída (`deleted_at` preenchido) vira 404, inclusive
  depois de esperar o bloqueio (o `READ COMMITTED` reavalia a linha). Edição atualiza as duas pontas
  (saída `-valor` na origem, entrada `+valor` no destino); exclusão põe `deleted_at` nas duas.
- **Motivo**: uma transferência nunca fica com uma ponta viva e outra excluída, nem com valores diferentes,
  mesmo com edições e exclusões simultâneas (SC-002).

## 11. Listagem, filtros e paginação

- **Decisão**: `GET /transactions` com `page` (a partir de 1, padrão 1), `size` (1 a 100, padrão 20), `from`,
  `to`, `accountId`, `categoryId`, `type` e `q`. Ordem fixa `occurred_on desc, created_at desc, id desc`.
  Resposta `{ items, page, size, totalItems, totalPages }`.
  - Período: `occurred_on >= from` e `<= to` (inclusivos); `from > to` é 400.
  - Categoria: `category_id = :c` ou `category_id` em `(select id from categories where parent_id = :c and
    workspace_id = :w)`; inclui subcategorias arquivadas, pois os lançamentos antigos continuam valendo.
  - Texto: `lower(description) like lower(:padrão) escape '\'`, com `%`, `_` e `\` escapados no Java e no
    máximo 100 caracteres.
  - Conta: `account_id = :a` (cada ponta de transferência pertence à própria conta).
- **Consistência**: a consulta da página e a contagem rodam na mesma transação `READ ONLY` com isolamento
  `REPEATABLE READ`, para o total e a página nunca discordarem sob escrita simultânea.
- **Parâmetros inválidos**: `page`/`size` fora da faixa, `type` desconhecido, datas ou UUIDs malformados e
  período invertido viram 400 com o campo (`errors[].field`). O tratador de erros ganha o nome do parâmetro
  nos erros de tipo de parâmetro de consulta (hoje devolve 400 sem campo).
- **Paginação por deslocamento**: suficiente na escala do projeto (princípio VII); paginação por cursor só
  se a medição pedir.
- **Índice**: o índice parcial `(workspace_id, occurred_on) WHERE deleted_at IS NULL` da V1 atende filtro de
  período e ordenação; os de conta e categoria atendem os outros filtros. Um índice novo só entra se o teste
  de desempenho com 20.000 lançamentos (SC-008) falhar.

## 12. Exclusão lógica sem restauração

- **Decisão**: `DELETE /transactions/{id}` responde 204 e põe `deleted_at` (e `updated_at`) na linha, ou nas
  duas pontas. Excluir de novo, consultar ou editar um excluído é 404. Não há rota de restauração.
- **Saldo**: a consulta de saldo da `002` já ignora `deleted_at`; nada muda lá. O teste de consistência
  (SC-001) prova a soma depois de sequências aleatórias de criações, edições e exclusões.

## 13. Quem criou

- **Decisão**: `created_by` vem do usuário autenticado (o mesmo `sub` que o aspecto já lê do JWT, exposto ao
  serviço por um método do `TenantSession` ou pelo `SecurityContext`), nunca do corpo. Não há coluna nem
  rota para alterá-lo (privilégio de `UPDATE` não inclui a coluna).

## 14. Testes

- **Decisão**: o `WorkspaceFixtures` já cria usuários, papéis, contas, categorias e lançamentos pelo
  `OwnerJdbc`. Acrescenta-se só o que faltar (por exemplo, lançamento com categoria). Os testes de
  concorrência usam threads e conferem invariantes no banco: transferência coerente, sem lançamento em conta
  arquivada, soma de saldo igual à esperada. O teste de desempenho semeia 20.000 lançamentos com
  `generate_series`.
- **Motivo**: prova as regras com Postgres real, sem depender de rotas que não existem.

## Resultado

Nenhum item ficou em aberto: todas as dúvidas do Technical Context foram resolvidas acima.
