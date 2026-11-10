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
2. Crie `Corrente` (`CHECKING`, saldo inicial 500000).

## 3. Efetivar sozinho no dia (US1)

Crie três despesas pendentes na `Corrente`, em `Alimentação`:

| Lançamento | Valor | Data | `autoClear` |
|---|---|---|---|
| `A` | 80000 | `HOJE` | `true` |
| `B` | 5000 | `HOJE` | ausente |
| `C` | 7000 | `HOJE` + 10 dias | `true` |

1. As respostas trazem `status` **PENDING** e `autoClear` **true** (A e C) e **false** (B). `GET .../accounts/{corrente}`:
   `balanceCents` **500000**, `pendingCents` **-92000**.
2. Recusas: `status` `CLEARED` com `autoClear` `true` → **400**, campo `autoClear`; editar um efetivado com
   `autoClear` `true` → **400**, campo `autoClear`.
3. Reinicie a API e espere a rotina (consulte `A` até `CLEARED`, no máximo ~90 s).
4. Depois da rotina: `A` **CLEARED** e `autoClear` **false**; `B` **PENDING** (sem o marcador a data não efetiva); `C`
   **PENDING** com `autoClear` **true** (a data é futura). `Corrente`: `balanceCents` **420000**, `pendingCents`
   **-12000**; o `updatedAt` de `A` mudou e nenhum outro campo (valor, data, descrição).
5. Uso único: edite `A` voltando-a a `PENDING` (`PUT` com `"status":"PENDING"`), reinicie a API e espere a rotina:
   `A` continua **PENDING** e `autoClear` **false**.
6. Efetive `C` à mão (`POST .../clear`): `C` **CLEARED** e `autoClear` **false** (o marcador sai junto).
7. Transferência: `TRANSFER` 3000 de `Corrente` para uma `Poupança` (crie-a com saldo 0), data `HOJE`, `PENDING`,
   `autoClear` `true` → as duas pontas com o marcador; depois da rotina as **duas** `CLEARED`.

## 4. Criar uma recorrência (US2)

1. **Aluguel** (`R1`): `POST .../recurrences` com `Corrente`, `Alimentação`, `type` `EXPENSE`, `amountCents` **150000**,
   `frequency` `MONTHLY`, `startOn` `HOJE`, `description` "Aluguel" → **201**, `Location` para o detalhe. Há **13**
   ocorrências (números 0 a 12), todas `PENDING` e `autoClear` **false**, de `-150000`, uma por mês no mesmo dia, a
   última em `HOJE` + 12 meses, com `recurrenceId` e `occurrenceNumber` preenchidos. `totalCents` **1950000**,
   `clearedCents` **0**, `pendingCents` **1950000**, `nextOccurrenceOn` = `HOJE`.
2. **Assinatura** (`R2`): `WEEKLY`, `amountCents` 4000, `startOn` `HOJE`, `description` "Assinatura" → as ocorrências
   caem de 7 em 7 dias; o número delas é `piso((HOJE + 12 meses − HOJE) / 7) + 1`.
3. **Dia 31** (`R3`): `MONTHLY`, `startOn` `2027-01-31` (ou o próximo dia 31 que ainda caiba), `description`
   "Fim de mes" → as quatro primeiras datas **31/01, 28/02, 31/03, 30/04** (sem acumular o ajuste), e nenhuma depois de
   `HOJE` + 12 meses.
4. **Com fim** (`R4`): `MONTHLY`, `startOn` `HOJE`, `endOn` `HOJE` + 2 meses → **3** ocorrências.
5. **Início no passado com efetivação automática** (`R5`): `MONTHLY`, `startOn` `HOJE` − 40 dias, `autoClear` `true`,
   `amountCents` 2500 → ocorrências desde `HOJE` − 40 dias, todas `PENDING` na resposta. Depois da próxima rotina
   (reinicie a API) as que já venceram estão **CLEARED**; as futuras continuam `PENDING` com `autoClear` **true**.
6. Corrente: `pendingCents` passa a incluir todas as ocorrências pendentes e `balanceCents` não muda por elas
   (só as efetivadas pela rotina em `R5` mexem no saldo).
7. A geração é **idempotente**: reinicie a API de novo e confira que o número de ocorrências de `R1` continua **13**
   e que nenhum `occurrenceNumber` repete.
8. Uma ocorrência excluída não volta: exclua a de número 3 de `R1` (`DELETE .../transactions/{id}`), reinicie a API e
   confira que ela continua excluída (o detalhe tem **12** ocorrências).

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

1. `GET .../recurrences` → lista com `R1` a `R5`, cada uma com `ended` **false** e `nextOccurrenceOn`.
2. `GET .../recurrences/{R1}` → detalhe com as ocorrências em ordem de número.
3. Efetive a ocorrência 0 de `R1` à mão. `POST .../recurrences/{R1}/end` → **200** `{"cancelled": N}`, com `N` = as
   ocorrências `PENDING` com data depois de `HOJE` (as de número 1 a 12, menos a excluída no passo 4.8: **11**). Repita →
   `{"cancelled":0}`. A ocorrência 0 (efetivada) continua; o detalhe mostra `ended` **true** e `endedAt`.
4. Reinicie a API: `R1` não ganha ocorrência nenhuma (encerrada). O saldo, o dashboard e a fatura não mudam com o
   encerramento; só o previsto (`pendingCents`) cai.
5. `POST .../recurrences/{R5}/end` → exclui só as pendentes **futuras**; as efetivadas pela rotina e as pendentes
   vencidas ficam.
6. UUID aleatório, recorrência de outro workspace e `abc` → **404**.

## 7. Parcelas que se efetivam sozinhas (US4)

1. `POST .../installment-purchases` com `Corrente`, `Alimentação`, `totalCents` **9000**, `installmentCount` 3,
   `firstOccurredOn` `HOJE` − 40 dias, `autoClear` `true`, `description` "Fone" → a parcela 1 `CLEARED` (sem o
   marcador), as parcelas 2 e 3 `PENDING` com `autoClear` **true**.
2. Reinicie a API e espere a rotina: a parcela 2 (data já vencida) fica **CLEARED**; a 3 (data futura) continua
   **PENDING**. O plano reflete: `clearedCents` **6000**, `pendingCents` **3000**.
3. Sem `autoClear` no registro, nenhuma parcela é efetivada sozinha.

## 8. Papéis e isolamento (US5)

1. Semeie (via banco do dono) uma segunda pessoa como `VIEWER`: `GET .../recurrences` e `GET .../recurrences/{id}` →
   200; `POST .../recurrences`, `POST .../end` e criar lançamento com `autoClear` → **403** e nada muda. Como `EDITOR`:
   cria e encerra, inclusive recorrência de outra pessoa, e o `createdBy` das ocorrências segue a pessoa que criou a
   recorrência.
2. Pessoa de outro workspace tenta listar, consultar, criar e encerrar no seu `$WID` → **404** igual ao de um
   workspace inexistente; um `id` de recorrência seu usado no workspace dela → 404. Sem cookie → **401**.
3. Com dados nos dois workspaces, depois da rotina cada um só tem as suas ocorrências e os seus efetivados.

## 9. Limpeza

Apague as pessoas, workspaces e dados criados nesta sessão (inclusive as `recurrences`) e confira `users =
workspaces = accounts = categories = transactions = recurrences = weekly_budgets = monthly_budgets = 0`.
