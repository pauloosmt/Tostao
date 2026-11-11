# Quickstart: validar a Transferência recorrente

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml); modelo em
[data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e stack local reconstruída: `docker compose up -d --build` (a migration `V15` roda na subida; confira
  `select version from flyway_schema_history order by installed_rank desc limit 1` → `15`).
- `BASE=http://127.0.0.1:8080/api/v1`. Três pessoas: **Dona** (`jar.txt`, workspace `$WID`), **Intrusa** (`jar2.txt`) e **Leitor**
  (`jar3.txt`, VIEWER no workspace da Dona, semeado pelo banco). A Dona tem `Corrente`, `Poupança`, `Antiga` (arquivada) e um cartão de
  crédito `Cartão`; a Intrusa tem uma conta própria.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos passam, inclusive os das specs 001 a 013 **sem alteração de resultado**, exceto os três que registravam a recusa do tipo
transferência na recorrência (FR-015).

## 2. Criar (US1)

1. Dona: `POST .../recurrences` `{"type":"TRANSFER","accountId":Corrente,"destinationAccountId":Poupança,"amountCents":50000,
   "description":"Reserva","frequency":"MONTHLY","startOn":<primeiro dia 5 até 30 dias atrás>}` → **201** com `type` TRANSFER,
   `destinationAccountId`, `categoryId` **nulo**, ocorrências até 12 meses à frente.
2. Cada ocorrência: duas pontas (`amountCents` −50000 na Corrente e +50000 na Poupança), mesma data, mesmo `transferGroupId`, `PENDING`,
   sem categoria, `recurrenceId` e `occurrenceNumber` iguais nas duas. `totalCents`/`pendingCents` contam uma vez por ocorrência.
3. `GET .../accounts` → o saldo das contas **não mudou**; `GET .../dashboard` e orçamentos idem (pendente não conta).
4. Recusas (**400**, campo indicado): sem `destinationAccountId`; destino igual à origem; `categoryId` numa transferência; `destinationAccountId` numa
   despesa; valor 0; início muito antigo. `Antiga` (arquivada) como origem ou destino → **409**; conta da Intrusa → **404**.
5. Cartão como destino → **201**.

## 3. Efetivar sozinha (US2)

1. Dona cria uma recorrência de transferência semanal com `autoClear: true` e início há 10 dias (as ocorrências vencidas nascem pendentes).
2. Reinicie a API (`docker compose restart api`) e espere a rotina: as ocorrências vencidas ficam **efetivadas nas duas pontas**, o marcador
   some, os saldos mudam em exatos centavos e a **soma** dos saldos não muda.
3. Sem `autoClear`, nada é efetivado pela data.
4. Execuções repetidas (reiniciar de novo) não duplicam nem deixam ponta solta: `select count(*) filter (where amount_cents < 0) , count(*)
   filter (where amount_cents > 0) from transactions where recurrence_id = ...` → iguais.
5. Arquivar a `Poupança`: nenhuma ocorrência nova é gerada (as existentes ficam); restaurar volta a gerar.

## 4. Ver e encerrar (US3)

1. `GET .../recurrences` → todas as recorrências; as de transferência com `destinationAccountId`, as demais com `categoryId`.
2. Dona exclui uma ocorrência (pelo lançamento): as duas pontas somem; depois de nova geração ela **não** volta.
3. `POST .../recurrences/{id}/end` → **200** com `cancelled` = número de **ocorrências** pendentes futuras (não pontas); as efetivadas e as
   vencidas ficam; encerrar de novo → `cancelled` 0.

## 5. Papéis e isolamento (US4)

1. Leitor: lista e consulta **200**; criar e encerrar **403**. Sem cookie **401**.
2. Intrusa nas rotas da Dona → **404**, igual a um workspace inexistente; a conta da Intrusa numa criação da Dona → **404**.
3. Receita e despesa recorrentes (spec 011) continuam iguais: criar, listar, encerrar.

## 6. Limpeza

Apague as pessoas, workspaces e dados criados e confira `users = workspaces = workspace_members = accounts = categories = transactions =
recurrences = 0`.
