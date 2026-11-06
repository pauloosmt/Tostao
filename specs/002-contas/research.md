# Research: Contas

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Reaproveitar a tabela `accounts` da V1

- **Decisão**: não recriar a tabela. A migration `V3__accounts.sql` só acrescenta restrições e índices,
  e ajusta políticas e privilégios.
- **Motivo**: a V1 já tem os campos da spec (`type`, `currency`, `initial_balance_cents`,
  `credit_limit_cents`, `closing_day`, `due_day`, `archived`, datas) e a chave `(id, workspace_id)`
  usada pelas chaves estrangeiras compostas dos lançamentos. Migration aplicada nunca é editada.
- **Alternativas**: recriar a tabela (quebraria as chaves compostas de `transactions` e violaria o
  princípio V).

## 2. Restrições novas no banco (V3)

- **Decisão**: `CHECK` de que `credit_limit_cents`, `closing_day` e `due_day` só existem quando
  `type = 'CREDIT_CARD'`; `CHECK` de módulo máximo de `1_000_000_000_000` centavos em
  `initial_balance_cents` e `credit_limit_cents`; `CHECK` de nome já sem espaços nas pontas e com 1 a 100
  caracteres.
- **Motivo**: a API valida tudo primeiro (mensagens por campo), mas o banco é a segunda linha de defesa
  e a garantia contra qualquer escrita por outro caminho. Também impede somas que estourem `BIGINT`.
- **Observação**: a V1 já limita `closing_day` e `due_day` a 1 a 31 e `credit_limit_cents` a valores não
  negativos.

## 3. Nome único entre contas ativas

- **Decisão**: índice único parcial `accounts_active_name_key` em `(workspace_id, lower(name))` onde
  `NOT archived`. A criação usa `INSERT ... ON CONFLICT (workspace_id, lower(name)) WHERE NOT archived
  DO NOTHING RETURNING id`; resultado vazio vira 409.
- **Motivo**: decide o conflito na própria inserção, sem exceção dentro da transação (princípio VI), e é
  seguro sob concorrência. `lower()` cobre a comparação sem diferenciar maiúsculas; os espaços das pontas
  já foram removidos pela validação.
- **Edição e restauração**: não existe `ON CONFLICT` para `UPDATE`. Fazem uma checagem prévia (`exists`)
  e, na corrida residual de duas gravações simultâneas, o índice único barra a segunda. A violação sobe,
  a transação sofre rollback, e só então um `@ExceptionHandler` de `DuplicateKeyException` na borda web a
  traduz em 409. Nada é capturado dentro da transação.
- **Alternativas**: unicidade só na aplicação (corrida deixa passar duplicata); `SELECT ... FOR UPDATE`
  de toda a tabela de contas (bloqueio desproporcional).

## 4. Permissão por papel: duas camadas

- **Decisão**: `@WorkspaceScoped` ganha o atributo `write` (padrão `false`). O aspecto da `001`, depois de
  confirmar a participação (404 se não for membro), lê o papel da associação e, com `write = true`, lança
  `InsufficientRoleException` (403) para `VIEWER`. No banco, a política de RLS de `accounts` deixa de ser
  uma só (`FOR ALL`) e vira políticas separadas: leitura por workspace, e inserção e atualização exigindo
  também que o usuário seja `OWNER` ou `EDITOR` do workspace (função `app_can_write(workspace_id)`).
  Não existe política de `DELETE`, e o privilégio `DELETE` em `accounts` é revogado de `tostao_app`.
- **Motivo**: o princípio II pede duas camadas para o isolamento; estender a mesma ideia à escrita
  mantém a regra "VIEWER não escreve" mesmo se um serviço novo esquecer a anotação, e "não existe apagar
  conta" (FR-011) vira garantia do banco.
- **Ordem das respostas**: não membro recebe 404, membro `VIEWER` em escrita recebe 403. Quem é membro já
  sabe que o workspace existe, então 403 não revela nada.
- **Alternativas**: papel em coluna da sessão/JWT (autorização decidida no cliente, proibido pelo princípio
  I); só checagem na aplicação (uma camada).

## 5. Cálculo do saldo

- **Decisão**: uma consulta por requisição, no formato
  `a.initial_balance_cents + coalesce(sum(t.amount_cents), 0)` com `left join transactions t on
  t.account_id = a.id and t.workspace_id = a.workspace_id and t.deleted_at is null`, agrupada por conta e
  filtrada por `a.workspace_id`. A soma de `bigint` no Postgres devolve `numeric`; o resultado é
  convertido para `bigint` na própria consulta.
- **Motivo**: cumpre FR-012 (calculado a cada consulta, nunca gravado) e usa o índice parcial
  `(workspace_id, account_id, occurred_on) where deleted_at is null` da V1. Com módulo máximo de 10^12 por
  valor monetário de conta, e lançamentos validados na spec 004, a soma cabe em `bigint` com folga
  enorme.
- **Total**: somado em memória a partir das linhas de contas ativas da mesma consulta, de modo que o
  total e as contas mostradas sempre concordam (FR-014), sem depender de duas leituras em instantes
  diferentes.
- **Alternativas**: coluna de saldo materializada com gatilho (estado duplicado e risco de divergência,
  que a constituição só admite se a consulta ficar lenta); duas consultas (total e lista) que poderiam
  divergir entre si.

## 6. Dinheiro em JSON: recusar não inteiros

- **Decisão**: `spring.jackson.deserialization.accept-float-as-int=false`. Campos monetários são `Long`
  com `@Min`/`@Max` de ±10^12. Um valor como `10.5` ou `1e3` em campo inteiro resulta em 400.
- **Motivo**: o padrão do Jackson aceita `10.5` num campo inteiro e trunca em silêncio, o que perderia
  centavos sem aviso (princípio V). Um teste prova o comportamento.

## 7. Validação por campo e regra entre campos

- **Decisão**: Bean Validation nos DTOs para o que é por campo (nome 1 a 100, valores dentro do limite,
  dias de 1 a 31, limite não negativo). A regra "campos de cartão só em cartão" depende do tipo, então
  fica no serviço e lança `FieldValidationException(campo, mensagem)`, que o manipulador de erros
  converte no mesmo formato `errors[]` das demais validações (400).
- **Motivo**: mensagens com o nome certo do campo, sem validador de classe complexo.
- **Edição e tipo**: o DTO de edição aceita `type` opcional só para o cliente poder devolver o objeto
  inteiro. Se vier diferente do tipo atual, o serviço lança `FieldValidationException("type", "não pode
  ser mudado")`. Campos fora da lista (por exemplo `archived`, `balanceCents`, `workspaceId`) seguem
  recusados como desconhecidos.

## 8. Arquivar e restaurar

- **Decisão**: `POST .../accounts/{id}/archive` e `POST .../accounts/{id}/restore`, ambos idempotentes
  (200 com a conta no estado final). Conta arquivada só aceita restauração; editar uma arquivada devolve
  409 com a mensagem de que a conta está arquivada.
- **Motivo**: ações com nome claro e sem corpo, sem permitir que `archived` entre como campo de edição.
- **Alternativas**: `PATCH` com `archived` (reabriria um campo que a spec manda recusar na entrada).

## 9. Saldo consolidado

- **Decisão**: `GET /api/v1/workspaces/{wid}/reports/balance`, que devolve contas ativas com saldo e o
  total. Fica no pacote `report`, com a lógica em `AccountService`. A lista de contas também traz o saldo
  de cada uma.
- **Motivo**: o escopo (roadmap) já prevê essa rota, e a spec 005 acrescenta o relatório mensal no mesmo
  pacote.

## 10. Testes

- **Decisão**: o `WorkspaceFixtures` (suporte de teste) cria usuários, associações com qualquer papel,
  tokens de sessão e lançamentos pelo `OwnerJdbc`. Os testes de papel semeiam `VIEWER` e `EDITOR` pelo
  banco, porque nenhuma rota atribui papéis antes da fase 3. O teste de desempenho insere 20.000
  lançamentos com `generate_series` e mede a consulta de saldos.
- **Motivo**: prova as regras com Postgres real, sem depender de rotas que ainda não existem.

## Resultado

Nenhum item ficou em aberto: todas as dúvidas do Technical Context foram resolvidas acima.
