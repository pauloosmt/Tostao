# Research: Compra parcelada

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. O plano é o conjunto de parcelas, não uma tabela

- **Decisão**: não criar tabela de compras parceladas. Cada parcela é uma linha de `transactions` (tipo `EXPENSE`)
  e o plano é o conjunto das linhas com o mesmo `installment_group_id`.
- **Motivo**: tudo que o plano mostra (descrição base, conta, categoria, número de parcelas, totais) já está nas
  parcelas. Uma tabela própria exigiria RLS, privilégios, chave estrangeira e uma regra de sincronização para
  quando uma parcela é editada ou excluída, e guardaria um "total original" que diverge da realidade assim que a
  pessoa edita uma parcela. Sem tabela, a spec 004 (editar, excluir) e a 009 (efetivar) valem para as parcelas sem
  nenhum código novo, e o saldo, o dashboard, os orçamentos e a fatura (que já leem `transactions`) enxergam as
  parcelas sozinhos.
- **Alternativas descartadas**: tabela `installment_purchases` (segundo valor que diverge, mais uma tabela sob
  RLS); guardar as parcelas futuras em outra tabela e "materializar" com o tempo (exige agendador, que é da 011).

## 2. Migration `V9`: três colunas, uma restrição e um índice

- **Decisão**: `V9__installments.sql`:
  - `installment_group_id UUID`, `installment_number SMALLINT`, `installment_count SMALLINT`, todas nulas;
  - `CHECK installments_coherent`: as três nulas, ou as três preenchidas com `type = 'EXPENSE'`,
    `installment_count BETWEEN 2 AND 48` e `installment_number BETWEEN 1 AND installment_count`;
  - `CREATE UNIQUE INDEX transactions_installment_key ON transactions (workspace_id, installment_group_id,
    installment_number) WHERE installment_group_id IS NOT NULL`.
- **Motivo**: o banco garante o que o serviço promete: nunca um lançamento com só parte das colunas, nunca um
  número fora de 1..total, nunca duas parcelas com o mesmo número no mesmo grupo (mesmo sob duas requisições
  simultâneas, sem `catch`), e só despesa pode ser parcela (isso também impede transferência parcelada). O índice
  único tem `workspace_id` na frente, então também serve a busca "todas as parcelas do grupo no workspace" sem um
  segundo índice. É parcial, então os lançamentos comuns (a esmagadora maioria) não pagam nada por ele.
- **Sem `GRANT` novo**: o `INSERT` vale para a tabela inteira (`V2`), então as colunas novas nascem na criação.
  O `UPDATE` continua restrito às colunas da `V5` mais `status` da `V8`, então grupo, número e total **nunca
  mudam** depois de criados (FR-010) por privilégio, não por convenção. Um teste de schema prova os dois lados.
- **Alternativas descartadas**: uma coluna JSON com os dados do plano (sem restrição nem índice); `installment_count`
  fora da linha, derivado por `count(*)` (quebraria quando uma parcela é excluída); chave estrangeira do grupo para
  uma tabela (decisão 1).

## 3. Regras puras em `InstallmentRules`

- **Decisão**: uma classe final, sem Spring nem banco, com:
  - `split(total, count)`: devolve `long[count]`; `base = total / count`, `rest = total % count`; as `rest`
    primeiras parcelas valem `base + 1` e as demais `base`. A soma é exatamente `total` (conferida por
    `Math.addExact` no teste, não no código de produção, onde é uma identidade aritmética).
  - `dates(first, count)`: `first.plusMonths(i)` para `i` de 0 a `count - 1`, sempre a partir de `first` (o
    `LocalDate.plusMonths` já limita o dia ao fim do mês, e partir sempre de `first` evita o ajuste acumulado:
    31/01, 28/02, 31/03, 30/04).
  - `requireDatesInRange(first, count)`: a primeira e a última datas ficam entre 2000-01-01 e 2100-12-31; erro no
    campo `firstOccurredOn`.
  - `requireTotal(total, count)`: `total >= count` (pelo menos 1 centavo por parcela); erro no campo `totalCents`.
  - `describe(base, number, count)`: `base + " (" + number + "/" + count + ")"`.
- **Motivo**: princípio VIII. A parte que mais erra em dinheiro (divisão, resto, calendário) fica provada por teste
  unitário rápido e sem Docker, com dados aleatórios e semente fixa. A faixa de datas reaproveita as constantes de
  `TransactionRules` (mesma regra da `V5`).
- **Alternativas descartadas**: dividir com `BigDecimal` e arredondar (cria o risco de a soma não fechar); jogar o
  resto na última parcela (o texto da spec e o costume do cartão no Brasil põem o centavo nas primeiras); somar um
  mês por vez à data anterior (acumula o recorte de fim de mês: 31/01 → 28/02 → 28/03).

## 4. Serviço próprio e extração mínima

- **Decisão**: `InstallmentService` (registrar, consultar o plano, cancelar) e `InstallmentController`, no módulo
  `transaction`. A conferência de conta e de categoria hoje é privada em `TransactionService`
  (`requireUsableAccount`, `requireUsableCategory`); passa para um componente pequeno `UsableRefs`, usado pelos dois
  serviços. Comportamento, mensagens e travas (`FOR SHARE`) ficam **idênticos**.
- **Motivo**: o registro precisa exatamente das mesmas conferências e travas da criação de lançamentos (FR-009);
  copiá-las criaria duas versões de uma regra de integridade. É o primeiro segundo consumidor real, então a
  extração se justifica (princípio VII). A suíte de lançamentos inteira roda antes e depois, sem alteração.
- **Inserção**: o `InstallmentService` tem o seu próprio `insert` (colunas de parcela incluídas) em vez de abrir o
  `insertRow` da criação comum com mais parâmetros: são 48 `insert` de uma transação, simples de ler.
- **Alternativas descartadas**: pôr tudo dentro de `TransactionService` (já tem ~490 linhas e outra
  responsabilidade); chamar `TransactionService.create` 48 vezes (cada chamada passaria pelo aspecto e pela
  conferência de novo, e não daria para fazer a conta uma vez só); reimplementar as conferências.

## 5. Rotas

- **Decisão**: sob `/api/v1/workspaces/{wid}/installment-purchases`:
  - `POST` → 201, corpo = o plano, `Location` para o `GET` do plano;
  - `GET /{groupId}` → 200, o plano;
  - `POST /{groupId}/cancel` → 200, `{ "cancelled": N }`.
- **Motivo**: `POST .../cancel` segue o `POST .../clear` da 009 (ação que muda estado, sem corpo, idempotente) e
  evita `DELETE` com corpo de resposta. `groupId` malformado vira 404 pelo tratador de `PathVariable` que já existe.
- **Alternativas descartadas**: `DELETE /{groupId}` (a spec exclui só as pendentes e informa quantas; `DELETE`
  sugere apagar o plano inteiro e devolve sem corpo); `PATCH` com um estado "cancelado" (o plano não tem estado).

## 6. O que a resposta de cada lançamento ganha

- **Decisão**: `TransactionResponse` ganha, ao fim, `installmentGroupId` (UUID), `installmentNumber` (inteiro) e
  `installmentCount` (inteiro), nulos nos que não são parcela. O `SELECT` de `TransactionService` lê as três
  colunas. Nenhum campo existente muda de nome nem de significado (FR-011, FR-020).
- **Motivo**: a pessoa precisa ver na listagem que um lançamento é a parcela 2 de 12, e o front monta o link para
  o plano pelo `installmentGroupId`.

## 7. Descrição base, conta e categoria do plano

- **Decisão**: o plano devolve `description` (base), `accountId` e `categoryId` lidos da **parcela de menor
  número, mesmo excluída**, que é a que mais perto está do que foi registrado. A descrição base é essa descrição
  sem o sufixo ` (N/T)` quando ela termina exatamente com o sufixo esperado da própria parcela; senão (editada à
  mão) devolve-se a descrição como está.
- **Motivo**: o plano não tem registro próprio (decisão 1) e a spec pede a descrição base. Ler também as excluídas
  mantém o cabeçalho do plano estável depois de cancelar. Evita uma quarta coluna só para guardar o texto original.
- **Alternativas descartadas**: coluna `installment_description` (redundante com `description`, mais um valor para
  manter coerente); exigir sempre a primeira parcela viva (o cabeçalho sumiria ao excluir a parcela 1).

## 8. Existência do plano, totais e parcelas excluídas

- **Decisão**: o plano existe se há **qualquer** linha do grupo no workspace, excluída ou não. Sem nenhuma linha
  (inexistente, outro workspace ou malformado) é 404. `GET` de um plano com todas as parcelas excluídas é 200 com
  lista vazia e totais zero; `installmentCount` mostra o número original.
- **Totais**: `totalCents` = soma dos valores (positivos) das parcelas **não excluídas**; `clearedCents` = só as
  efetivadas; `pendingCents` = só as pendentes. Somados em Java (`Math.addExact`) **a partir da própria lista**
  lida, então a lista e os totais nunca discordam, nem dentro da transação de escrita do registro. A leitura
  pública do plano roda em `REPEATABLE READ` somente leitura, para o cabeçalho e a lista saírem do mesmo instante.
- **Chamada interna**: `create` devolve o plano por um método **privado** (`loadPlan`) que o `plan` público também
  usa; chamar o método público por dentro do mesmo bean não passaria pelo proxy (nem pelo aspecto nem pela
  transação).
- **Motivo**: cancelar um plano já cancelado precisa continuar respondendo com sucesso (FR-013), então o plano
  não pode "sumir" quando as linhas são excluídas.

## 9. Cancelar sem perder uma efetivação simultânea

- **Decisão**: o cancelamento, numa transação:
  1. conferir que o plano existe (404 se não);
  2. travar `FOR UPDATE`, em ordem de `installment_number`, as parcelas **pendentes e vivas** do grupo;
  3. `UPDATE transactions SET deleted_at = now(), updated_at = now()` nessas linhas travadas, com
     `status = 'PENDING' AND deleted_at IS NULL` repetido na condição;
  4. devolver quantas linhas o `UPDATE` alterou.
- **Motivo**: uma efetivação concorrente da parcela k (`POST .../clear`, que trava a linha e altera o estado) ou a
  espera ou chega antes. Se chega antes, o cancelamento relê a linha ao ganhar a trava (READ COMMITTED), vê
  `CLEARED` e a deixa de fora: **uma parcela efetivada nunca é desfeita pelo cancelamento**. Se o cancelamento
  chega antes, a efetivação encontra a linha excluída e responde 404, como já faz com qualquer lançamento
  excluído. Travar em ordem de número evita impasse entre dois cancelamentos simultâneos do mesmo plano. Repetir
  o cancelamento encontra zero linhas e responde `cancelled: 0`.
- **Alternativas descartadas**: `UPDATE` único sem trava prévia (o Postgres já relê a linha, mas a ordem de
  aquisição das travas de 47 linhas ficaria a cargo do plano de execução, abrindo caminho para impasse entre dois
  cancelamentos); `SERIALIZABLE` com nova tentativa (nova tentativa dentro de transação é proibida pela
  constituição, princípio VI).

## 10. Criação atômica sem tentativa e erro

- **Decisão**: uma transação: validar a entrada (campos, valor, datas, estado) → `UsableRefs` (conta e categoria,
  `FOR SHARE`) → calcular parcelas e datas → `INSERT` das N linhas → devolver o plano pela leitura. Nenhuma exceção
  é capturada dentro da transação. O `groupId` é gerado no servidor (`UUID.randomUUID()`), nunca vem do cliente.
- **Motivo**: o princípio VI. Toda causa previsível de falha (campo, conta arquivada, categoria de receita) é
  conferida antes do primeiro `INSERT`; o que sobra (falha de infraestrutura) desfaz tudo pelo `rollback` normal
  da transação, o que é a atomicidade pedida (SC-004). O teste de atomicidade força uma falha na 3ª de 5 parcelas
  com um gatilho temporário no banco, criado e removido pela conexão do dono, que levanta exceção ao inserir a
  parcela "(3/5)" (o grupo é aleatório, então não dá para colidir com ele, e não se acrescenta gancho de teste à
  produção), e confere que nenhuma das cinco ficou.

## 11. Validação de entrada

- **Decisão**: `CreateInstallmentPurchaseRequest` (record, campos fechados): `accountId` e `categoryId`
  obrigatórios; `totalCents` (`Long`, 1 a 10¹²); `installmentCount` (`Integer`, 2 a 48); `firstOccurredOn`
  (`LocalDate`); `description` (obrigatória, sem espaços nas pontas, até 190); `notes` (opcional, até 1.000, copiada
  para todas as parcelas); `firstInstallmentStatus` (texto opcional, validado por `TransactionRules.parseStatus`,
  porque o Jackson aceita texto vazio como `null` num enum). Campos extras (`workspaceId`, `createdBy`,
  `installmentGroupId`, estado das seguintes) são recusados pela configuração que já existe para o projeto.
  `installmentCount` com casas decimais (`3.5`) deve ser recusado como os valores em centavos já são na spec 004;
  o teste prova o comportamento real e, se o Jackson truncasse, a validação acrescenta a recusa explícita.
- **Regras cruzadas no serviço, antes de gravar**: `totalCents >= installmentCount` (campo `totalCents`) e a faixa
  de datas (campo `firstOccurredOn`).

## 12. Efeito em specs anteriores

- **Decisão**: nenhuma consulta existente muda, exceto o `SELECT` de lançamentos, que lê três colunas a mais. Como
  a parcela é uma despesa comum com `status`, todas as regras da 009 (pendente fora do realizado) e a fatura da 008
  valem sem código novo. Um teste prova que o pendente de uma parcela não entra no saldo, no dashboard, no
  orçamento nem na fatura, e que efetivá-lo passa a incluí-lo.
- **Risco verificado**: `TransactionResponse` é um `record`, mas só o `ROW_MAPPER` de `TransactionService` chama o
  construtor canônico (nenhum teste das specs anteriores o faz), então acrescentar três campos ao fim não quebra
  compilação alguma.
- **Decimais**: números com casas decimais (`10.5`, `10.0`, `1e3`) já são recusados pelos DTOs de valor inteiro do
  projeto (testes das specs 002, 004, 006 e 007), então `installmentCount` e `totalCents` seguem a mesma regra.
