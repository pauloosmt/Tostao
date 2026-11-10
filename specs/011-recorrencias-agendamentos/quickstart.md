# Quickstart: validar Recorrências e efetivação automática

Roteiro para provar a feature de ponta a ponta. Contrato em [contracts/openapi.yaml](contracts/openapi.yaml);
modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e stack local reconstruída: `docker compose up -d --build` (as migrations `V10` e `V11`
  rodam na subida; confira `select version from flyway_schema_history order by installed_rank desc limit 1` → `11`).
- `BASE=http://127.0.0.1:8080/api/v1`. JSON com acento vai como arquivo UTF-8 no Windows (o `curl` do Git Bash recusa
  a descrição acentuada no corpo, então nos exemplos de linha de comando use "Aluguel", "Assinatura" etc.).
- `HOJE=$(TZ=America/Sao_Paulo date +%F)`: as datas abaixo são relativas a ele (a rotina usa o mesmo fuso).
- A execução automática roda **~10 s depois de a API subir** e depois a cada hora. Para provar o "efetiva sozinho"
  sem esperar uma hora, o roteiro reinicia a API (`docker compose restart api`) e espera a rotina.

## 1. Testes automatizados

```bash
cd backend && ./mvnw -B verify
```

Esperado: todos passam, inclusive os das specs 001 a 010 **sem alteração de resultado** (FR-026).

## 2. Preparo

1. Cadastre uma pessoa (`POST /auth/register`); guarde o cookie e o `id` do workspace. Pegue `Alimentação`
   (despesa) e `Salário` (receita).
2. Crie `Corrente` (`CHECKING`, saldo inicial 500000), `Poupança` (`SAVINGS`, 0) e `Recorrentes` (`CHECKING`, 0). A
   `Recorrentes` recebe as recorrências e a compra parcelada, para os números da `Corrente` na seção 3 ficarem simples.

## 3. Efetivar sozinho no dia (US1)

Crie quatro lançamentos pendentes na `Corrente`, em `Alimentação` (a transferência, na `Poupança`):

| Lançamento | Valor | Data | `autoClear` |
|---|---|---|---|
| `A` | 80000 | `HOJE` | `true` |
| `B` | 5000 | `HOJE` | ausente |
| `C` | 7000 | `HOJE` + 10 dias | `true` |
| `T` (transferência da `Corrente` para a `Poupança`) | 3000 | `HOJE` | `true` |

1. As respostas trazem `status` **PENDING** e `autoClear` **true** (A, C e T) e **false** (B). `GET .../accounts/{corrente}`:
   `balanceCents` **500000**, `pendingCents` **-95000**, `projectedBalanceCents` **405000**.
2. Recusas (crie antes uma receita efetivada `D` de 100 na `Poupança`): criar com `status` `CLEARED` e `autoClear` `true`,
   criar sem `status` (nasce efetivado) com `autoClear` `true`, e editar o efetivado `D` com `autoClear` `true` →
   todos **400**, campo `autoClear`.
3. Antes da rotina, as recorrências e a compra parcelada das seções 4 a 7 também já foram criadas; então **reinicie a
   API uma vez** (`docker compose restart api`) e espere a rotina rodar (~10 s depois de a API subir).
4. Depois da rotina: `A` **CLEARED** e `autoClear` **false**; `B` **PENDING** (sem o marcador a data não efetiva); `C`
   **PENDING** com `autoClear` **true** (a data é futura). `Corrente`: `balanceCents` **417000** (500000 − 80000 − 3000),
   `pendingCents` **-12000** (B e C), `projectedBalanceCents` **405000** (efetivar não muda o previsto).
5. Uso único (depois do primeiro reinício): edite `A` voltando-a a `PENDING` (`PUT` com `"status":"PENDING"`): sai com
   `autoClear` **false**. Reinicie a API de novo e espere a rotina: `A` continua **PENDING**.
6. Efetive `C` à mão (`POST .../clear`): `C` **CLEARED** e `autoClear` **false** (o marcador sai junto).
7. Transferência: depois da primeira rotina as **duas** pontas de `T` estão `CLEARED` e sem marcador.

## 4. Criar uma recorrência (US2)

Todas na conta `Recorrentes`, categoria `Alimentação`, `type` `EXPENSE`:

1. **Aluguel** (`R1`): `POST .../recurrences` com `amountCents` **150000**, `frequency` `MONTHLY`, `startOn` `HOJE`,
   `description` "Aluguel" → **201**, `Location` para o detalhe. Há **13** ocorrências (números 0 a 12), todas `PENDING`
   e `autoClear` **false**, de `-150000`, uma por mês no mesmo dia, a última em `HOJE` + 12 meses, com `recurrenceId` e
   `occurrenceNumber` preenchidos. `totalCents` **1950000**, `clearedCents` **0**, `pendingCents` **1950000**,
   `nextOccurrenceOn` = `HOJE`. As datas batem com um calendário independente (o roteiro usa um pequeno script de cálculo).
2. **Assinatura** (`R2`): `WEEKLY`, `amountCents` 4000, `startOn` `HOJE` → as ocorrências caem de 7 em 7 dias, até a
   última que não passa de `HOJE` + 12 meses.
3. **Dia 31** (`R3`): `MONTHLY`, `startOn` `2027-01-31`, `description` "Fim de mes" → **31/01, 28/02, 31/03, 30/04...**
   (sem acumular o ajuste), e nenhuma depois de `HOJE` + 12 meses.
4. **Com fim** (`R4`): `MONTHLY`, `startOn` `HOJE`, `endOn` `HOJE` + 2 meses → **3** ocorrências.
5. **Início no passado com efetivação automática** (`R5`): `MONTHLY`, `startOn` `HOJE` − 40 dias, `autoClear` `true`,
   `amountCents` 2500 → ocorrências desde `HOJE` − 40 dias, todas `PENDING` na resposta. Depois da primeira rotina as que
   já venceram estão **CLEARED** e sem marcador; as futuras continuam `PENDING` com `autoClear` **true**.
6. `Recorrentes`: depois da rotina, o `balanceCents` só tem o que foi efetivado: as vencidas de `R5` (2500 cada), as duas
   primeiras parcelas da compra da seção 7 (3000 cada) e a ocorrência 0 de `R1` efetivada à mão na seção 6 (150000).
7. A geração é **idempotente**: depois dos reinícios, `R1` continua com as suas ocorrências e nenhuma repete número.
8. Uma ocorrência excluída não volta: exclua a de número 3 de `R2` (`DELETE .../transactions/{id}`) **antes** do reinício;
   depois dele o detalhe de `R2` tem uma ocorrência a menos e nenhuma de número 3.

## 5. Validação (nada é criado)

Anote `totalItems` da listagem de lançamentos e o tamanho da lista de recorrências antes e confira que **não mudam**:

| Entrada | Esperado |
|---|---|
| `type` `TRANSFER`, `"OUTRO"`, ausente | 400, campo `type` |
| `frequency` `"DAILY"`, ausente | 400, campo `frequency` |
| `amountCents` 0, -1, 10.5, 1000000000001, ausente | 400, campo `amountCents` |
| `startOn` `HOJE` − 367 dias, `2101-01-01`, texto inválido, ausente | 400, campo `startOn` |
| `endOn` anterior ao `startOn`, `2101-01-01` | 400, campo `endOn` |
| `description` vazia, só espaços, 201 caracteres | 400, campo `description` |
| campo extra (`createdBy`, `workspaceId`, `recurrenceId`, `status`) | 400 |
| categoria de receita com `type` `EXPENSE` | 400, campo `categoryId` |
| conta ou categoria arquivada | 409 |
| conta ou categoria inexistente (UUID aleatório) | 404 |

## 6. Ver, encerrar e o que não muda (US3)

Antes do primeiro reinício da API:

1. `GET .../recurrences` → lista com `R1` a `R5`, cada uma com `ended` **false** e `nextOccurrenceOn`.
2. `GET .../recurrences/{R1}` → detalhe com as ocorrências em ordem de número.
3. Efetive a ocorrência 0 de `R1` à mão. `POST .../recurrences/{R1}/end` → **200** `{"cancelled": 12}` (as ocorrências
   `PENDING` com data depois de `HOJE`, de 1 a 12). Repita → `{"cancelled":0}`. A ocorrência 0 (efetivada) continua; o
   detalhe mostra `ended` **true**, `endedAt`, 1 ocorrência e `pendingCents` **0**; a lista passa a ter 1 recorrência
   encerrada.
4. Depois do reinício: `R1` não ganha ocorrência nenhuma (encerrada). O saldo, o dashboard e a fatura não mudam com o
   encerramento; só o previsto (`pendingCents`) cai.
5. Depois do segundo reinício, `POST .../recurrences/{R5}/end` → exclui só as pendentes **futuras**; as efetivadas pela
   rotina ficam (o detalhe tem só as vencidas).
6. UUID aleatório, recorrência de outro workspace e `abc` → **404**, nada muda.

## 7. Parcelas que se efetivam sozinhas (US4)

1. `POST .../installment-purchases` (antes do primeiro reinício) com `Recorrentes`, `Alimentação`, `totalCents` **9000**, `installmentCount` 3,
   `firstOccurredOn` `HOJE` − 40 dias, `autoClear` `true`, `description` "Fone" → a parcela 1 `CLEARED` (sem o
   marcador), as parcelas 2 e 3 `PENDING` com `autoClear` **true**.
2. Depois do primeiro reinício e da rotina: a parcela 2 (data já vencida) fica **CLEARED**; a 3 (data futura) continua
   **PENDING**. O plano reflete: `clearedCents` **6000**, `pendingCents` **3000**.
3. Sem `autoClear` no registro, nenhuma parcela é efetivada sozinha.

## 8. Papéis e isolamento (US5)

1. Semeie (via banco do dono) uma segunda pessoa como `VIEWER`: `GET .../recurrences` e `GET .../recurrences/{id}` →
   200; `POST .../recurrences`, `POST .../end` e criar lançamento com `autoClear` → **403** e nada muda. Como `EDITOR`:
   cria e encerra, inclusive recorrência de outra pessoa, e o `createdBy` das ocorrências segue a pessoa que criou a
   recorrência.
2. Pessoa de outro workspace tenta listar, consultar, criar e encerrar no seu `$WID` → **404** igual ao de um
   workspace inexistente; um `id` de recorrência seu usado no workspace dela → 404. Sem cookie → **401**.
3. Com dados nos dois workspaces, depois da rotina cada um só tem as suas ocorrências e os seus efetivados (provado
   nos testes automatizados com dois workspaces; no roteiro, a segunda pessoa só tem o próprio workspace vazio).

## 9. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão (inclusive as `recurrences`) e confira `users =
workspaces = accounts = categories = transactions = recurrences = weekly_budgets = monthly_budgets = 0`.
