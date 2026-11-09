# Research: Fatura do cartão de crédito

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Calcular, nunca guardar

- **Decisão**: nenhuma tabela e nenhuma migration. A fatura é uma visão sobre `accounts` e `transactions`.
- **Motivo**: princípio V ("a fatura do cartão é calculada a partir dos dias de fechamento e de vencimento") e a
  mesma lógica do saldo ("calculado, não gravado, até que a medição prove necessidade"). Guardar o total criaria
  um segundo valor que pode divergir do saldo quando um lançamento é editado ou excluído.
- **Alternativas descartadas**: tabela `invoices` materializada (precisaria de recálculo em cada escrita de
  lançamento e traria o problema do histórico de dias de fechamento).

## 2. Fatura identificada pelo mês do vencimento

- **Decisão**: o identificador é `AAAA-MM` do **vencimento**. Vencimento = dia de vencimento do mês pedido.
  Fechamento = dia de fechamento do mesmo mês se `dueDay > closingDay`, senão do mês anterior (`dueDay <=
  closingDay`). A comparação usa os dias cadastrados, não os ajustados ao tamanho do mês.
- **Motivo**: é como a pessoa chama a fatura ("a de novembro"). Dado o mês, as datas saem de uma fórmula sem
  busca nem iteração. Comparar os dias cadastrados mantém a regra estável: o ajuste de fim de mês só muda o dia,
  nunca o mês em que a fatura cai.
- **Alternativas descartadas**: identificar pelo mês do fechamento (menos natural; "a fatura de novembro" fecha
  em outubro quando o vencimento é no dia 5).

## 3. Partição dos dias

- **Decisão**: período = (fechamento da fatura do mês anterior + 1 dia) até o fechamento desta, inclusive. O
  fechamento é `min(closingDay, tamanho do mês)`.
- **Motivo**: como o mês de fechamento avança de um em um junto com o mês de vencimento, os fechamentos formam
  uma sequência estritamente crescente, então os períodos se encaixam sem buracos nem sobreposição, inclusive com
  o ajuste de fim de mês (por exemplo, fechamento 31: 31/01, 28/02, 31/03 → 01/02 a 28/02 e 01/03 a 31/03). O
  teste de partição (SC-002) prova isso para todas as combinações de 1 a 31.
- **Alternativas descartadas**: período "30 dias antes do fechamento" (não bate com o calendário do banco).

## 4. Total e pagamentos por tipo de lançamento

- **Decisão**: valor da linha = `-amount_cents` do lançamento (despesa e saída por transferência viram positivos;
  receita/estorno vira negativo). O total soma as linhas do período, excluindo os lançamentos excluídos e as
  transferências que **entram** no cartão. Pagamento = transferência que entra no cartão (`type = 'TRANSFER' and
  amount_cents > 0`) com data em `(fechamento, fechamento da próxima]`.
- **Motivo**: segue o sinal que a spec 004 já grava, sem tabela nova. O pagamento da fatura anterior cai dentro do
  período desta (porque o vencimento vem depois do fechamento), então só o recorte por data separa um do outro.
- **Alternativas descartadas**: casar pagamento a fatura por vínculo explícito (exigiria campo novo e tela de
  conciliação, fora do escopo; a simplificação está declarada na spec).

## 5. Situação

- **Decisão**: função pura `status(hoje, fechamento, vencimento, restante)`: `hoje <= fechamento` → `OPEN`;
  senão `restante == 0` → `PAID`; senão `hoje > vencimento` → `OVERDUE`; senão `CLOSED`. "Hoje" é a data no fuso
  de Brasília (`Period.today`). Em fatura `OPEN` o pago é zero e o restante é o total até agora (nunca negativo).
- **Motivo**: ordem de precedência simples e testável; a fatura aberta nunca é "paga", mesmo com transferência
  futura.

## 6. Fatura aberta de uma data

- **Decisão**: dentre os meses de vencimento do mês de hoje menos 1 até o de hoje mais 2, a fatura aberta é a
  primeira cujo período contém hoje.
- **Motivo**: o fechamento mais próximo está sempre a menos de dois meses; uma busca de 4 candidatos é exata e
  sem laço aberto.

## 7. Rotas e erros

- **Decisão**: `GET /accounts/{accountId}/invoices/{month}`, `GET /accounts/{accountId}/invoices?from=&to=` e
  `GET /accounts/{accountId}/card-summary`. Conta inexistente ou de outro workspace: `AccountNotFoundException`
  (404). Conta que não é cartão e cartão sem dias (nas duas rotas de fatura): `InvoiceNotAvailableException` (409
  "Conflito de estado") com a mensagem dizendo o motivo e os campos que faltam. Mês e intervalo inválidos: 400
  com o campo (`month`, `from`, `to`).
- **Motivo**: o 409 é o código que o projeto já usa para "o estado do recurso impede a operação" (conta
  arquivada, categoria arquivada); a conta existe, só não serve para fatura.
- **Alternativas descartadas**: 400 (o corpo da requisição está correto); 404 (diria que a conta não existe).

## 8. Uma consulta e leitura consistente

- **Decisão**: `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`. Fatura: uma consulta das linhas do
  período e uma soma de pagamentos. Lista: uma única consulta agregada por dia, tipo e sentido (entrada ou saída)
  do cartão entre o início da primeira fatura e o fechamento seguinte à última; a distribuição nas faturas é feita
  em Java, com `addExact`.
- **Motivo**: FR-015 e desempenho (36 faturas sem 72 consultas).
- **Alternativas descartadas**: uma consulta por fatura na lista.

## 9. Resumo do cartão

- **Decisão**: `AccountService.get` para o saldo e os dados do cartão (limite, dias, arquivado); devido =
  `max(-saldo, 0)`; disponível = `limite - devido` (nulo sem limite); a fatura aberta é calculada como na
  consulta de fatura, só se o cartão tem os dois dias.
- **Motivo**: FR-016: o devido é o mesmo cálculo da spec 002, não uma cópia. Chamar o serviço de contas dentro da
  mesma transação reaproveita o aspecto e o RLS.

## 10. Desempenho

- **Decisão**: sem índice novo. A medição com 20.000 lançamentos decide; se passar de 1 s, o índice entra numa
  migration com a medição registrada.
- **Motivo**: princípio VII; as consultas filtram por `account_id` e data, e a `V1` já indexa lançamentos por
  conta e data.
