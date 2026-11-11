# Research: Transferência recorrente

Decisões técnicas e as alternativas descartadas. Spec em [spec.md](spec.md). A base é a recorrência da spec 011
([research](../011-recorrencias-agendamentos/research.md)); aqui só entra o que a transferência muda.

## 1. Uma extensão da recorrência, não uma tabela nova

**Decisão**: o tipo `TRANSFER` entra em `recurrences` com a coluna `destination_account_id`, e `category_id` passa a ser opcional.
A forma é garantida por um `CHECK`: transferência tem destino diferente da origem e não tem categoria; receita e despesa têm
categoria e não têm destino. Mesma rota (`/recurrences`), mesmo calendário, mesmo encerramento.

**Por quê**: a regra de repetição (frequência, início, fim, marcador, estado) é a mesma; só os dados da ocorrência mudam. Uma tabela
nova duplicaria o calendário, a função de geração, a rota e as provas. As chaves estrangeiras já existentes seguem valendo: a da
categoria `(category_id, workspace_id, type)` é ignorada quando a categoria é nula (`MATCH SIMPLE`), e a do destino é nova, composta
com o workspace como a da origem.

**Descartado**: `transfer_recurrences` separada (duas definições de calendário e de execução automática).

## 2. A ocorrência é um par, e o índice único passa a contar a ponta

**Decisão**: o índice único `(recurrence_id, occurrence_number)` da V10 é trocado por `(recurrence_id, occurrence_number,
(amount_cents > 0))`, no mesmo desenho do índice das pontas de transferência (V5). Para receita e despesa o terceiro termo é
constante dentro da recorrência, então a garantia de "uma ocorrência por número" não muda. Para transferência, uma saída e uma
entrada por número. O `CHECK` `transactions_occurrence_coherent` deixa de exigir tipo receita ou despesa (continua exigindo que a
linha não seja parcela e que recorrência e número andem juntos).

**Por quê**: a ocorrência de transferência tem duas linhas com o mesmo `occurrence_number`. Sem mudar o índice, a segunda ponta seria
rejeitada. Incluir a ponta no índice mantém a idempotência por par sem coluna nova.

## 3. Geração idempotente por par: grupo de transferência determinístico

**Decisão**: a função `generate_recurrence_occurrences` (substituída na V15 com `CREATE OR REPLACE`, mesma assinatura, mesmos
privilégios) insere as duas pontas na **mesma instrução** e usa como `transfer_group_id` um UUID derivado da recorrência e do número:
`md5(recorrência || ':' || número)::uuid`. O `ON CONFLICT` passa a inferir o novo índice. As linhas entram ordenadas por
(recorrência, número, ponta).

**Por quê**: com um grupo aleatório, duas execuções simultâneas poderiam deixar a saída de uma e a entrada de outra, com grupos
diferentes. Com o grupo determinístico, as duas execuções produzem as mesmas linhas, o índice único de pontas (`transfer_group_id,
amount_cents > 0`, V5) é uma segunda barreira, e o resultado é sempre um par. A ordem fixa evita que duas execuções esperem uma pela
outra em ordens diferentes.

**Descartado**: gerar a saída e depois a entrada em instruções separadas (janela para uma ponta só); `pg_advisory_lock` por recorrência
(trava a mais para o que o índice já garante).

## 4. Ativa só com as duas contas desarquivadas

**Decisão**: a função junta a conta de origem e, para transferência, a de destino (`LEFT JOIN`); a recorrência só gera se as contas
estão ativas (e, para receita e despesa, a categoria também). Nada de erro: a recorrência é só pulada, como na 011.

**Por quê**: uma conta arquivada não recebe lançamento novo (spec 002). Restaurar a conta volta a gerar sozinho.

## 5. Conferir e travar as duas contas na criação

**Decisão**: `requireUsableAccounts` (hoje privado em `TransactionService`) vira público em `UsableRefs` e `TransactionService` passa a
usá-lo de lá. A criação da recorrência de transferência o chama com origem e destino: `SELECT ... ORDER BY id FOR SHARE`, o que evita
impasse entre criações opostas e conserva a ordem que a transferência comum já usa.

**Por quê**: a criação tem de ser tudo ou nada e sem exceção dentro da transação; conferir antes de gravar é a regra (princípio VI).

## 6. Totais e encerramento falam em ocorrências

**Decisão**: no detalhe, a lista traz as duas pontas (como a listagem de lançamentos da spec 004), mas os totais somam só a **saída** de
cada ocorrência de transferência, para o valor não dobrar. O encerramento exclui as duas pontas e conta **ocorrências distintas** (por
`occurrence_number` das linhas excluídas, via `RETURNING`). As linhas do encerramento passam a ser travadas por id, a mesma ordem da
execução automática (`auto_clear_due`) e das operações de uma transferência, o que remove qualquer ciclo de espera entre elas.

**Por quê**: o número que a pessoa vê ("3 transferências excluídas") tem de ser o das ocorrências, não o das linhas.

## 7. Respostas

**Decisão**: `RecurrenceResponse`, `RecurrenceDetailResponse` e o pedido de criação ganham `destinationAccountId`; `categoryId` passa
a poder ser nulo na resposta e ausente no pedido de transferência. Os construtores antigos do pedido continuam existindo (sem
destino), para o código e os testes que os usam. Nenhum campo existente muda de nome ou de tipo para receita e despesa.

## 8. O que muda nos testes da 011

Três testes registravam a recusa do tipo transferência e passam a registrar o novo comportamento: a lista de tipos inválidos em
`CreateRecurrenceTest`, a regra de tipo em `RecurrenceRulesTest` e, no banco, o caso de `RecurrenceSchemaTest` que recusava uma ponta de
transferência como ocorrência (agora aceita; continua recusada a parcela que é ocorrência). O caso de `RecurrenceSchemaTest` que recusa
uma recorrência de transferência com categoria segue como está (recusada, agora pela forma). Mais nenhum teste anterior muda.

## 9. Efeito em outras specs

- Execução automática (011): a mesma função de geração e a mesma `auto_clear_due`; as duas pontas têm sempre a mesma data e o mesmo
  marcador, então já são efetivadas juntas. Nenhuma mudança no agendador.
- Saldo, dashboard, orçamentos, fatura (005 a 009): já ignoram o pendente e já tratam a transferência; nenhuma mudança.
- Cartão como destino (008): a transferência efetivada para o cartão já conta como pagamento; nenhuma mudança.
