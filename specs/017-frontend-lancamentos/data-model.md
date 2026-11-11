# Data Model: Lançamentos no front-end

O front não tem banco. Aqui ficam os tipos que vêm da API, o estado das telas, as regras de validação, as mensagens e as rotas.

## Tipos da API (espelham os contratos 004 e 009)

```ts
type TransactionType = 'INCOME' | 'EXPENSE' | 'TRANSFER'
type TransactionStatus = 'CLEARED' | 'PENDING'

interface Transaction {
  id: string; accountId: string; type: TransactionType
  amountCents: number            // COM sinal: receita/entrada > 0, despesa/saída < 0
  occurredOn: string             // AAAA-MM-DD
  description: string; notes: string | null; categoryId: string | null
  transferGroupId: string | null; counterpartAccountId: string | null    // só nas transferências
  createdBy: string; createdAt: string; updatedAt: string
  status: TransactionStatus
  installmentGroupId: string | null; installmentNumber: number | null; installmentCount: number | null   // parcelas
  autoClear: boolean; recurrenceId: string | null; occurrenceNumber: number | null                      // recorrências
  imported: boolean
}
interface TransactionPage { items: Transaction[]; page: number; size: number; totalItems: number; totalPages: number }

interface CreateTransaction {
  type: TransactionType; accountId: string; destinationAccountId?: string   // destino só em TRANSFER
  amountCents: number            // POSITIVO, 1 a 1.000.000.000.000
  occurredOn: string; description: string; notes?: string
  categoryId?: string            // obrigatório em INCOME/EXPENSE, proibido em TRANSFER
  status?: 'PENDING'             // ausente = efetivado
}
interface UpdateTransaction {    // o tipo não é enviado
  accountId: string; destinationAccountId?: string; amountCents: number; occurredOn: string
  description: string; notes?: string; categoryId?: string
  status: 'PENDING' | 'CLEARED'  // a tela sempre diz o estado
}
```

Chamadas (em `/api/v1/workspaces/{wid}/transactions`, só com o cookie): `GET ?page&size&from&to&accountId&categoryId&type&q&status`, `GET /{id}`, `POST`, `PUT /{id}`, `POST /{id}/clear`, `DELETE /{id}` (204).
Mais, para os nomes e seletores, `GET /accounts?includeArchived=true` e `GET /categories?includeArchived=true` (016).

**Regras da API que a tela respeita**: a edição é completa (campo opcional ausente limpa o valor: por isso `notes` ausente limpa as observações e a tela reenvia as que existem); em transferência, `accountId` é sempre a
origem; categoria só em receita e despesa, do mesmo tipo e ativa; `from`/`to` inclusivos e `to` não anterior a `from`; `q` até 100 caracteres, `%` e `_` literais.

## Estado das telas (memória)

```text
Filtros (contexto, por workspace)  : { workspaceId, filters: { from?, to?, accountId?, categoryId?, type?, status?, q? }, page }
                                     (o padrão é tudo vazio e página 1; outro workspace = padrão)
Referência (por workspace)         : contas e categorias (com arquivadas) -> mapas de nome + listas ativas para os seletores
Lista                              : Resource<TransactionPage> (chave: workspace + página + filtros) + painel de filtros aberto/fechado + rascunho do filtro
Formulário                         : valores em texto + erros por campo + aviso geral + envio em andamento (guarda síncrono)
Confirmação de exclusão            : aberta + ação em andamento
Aviso de resultado                 : texto do estado do roteador (uma vez; some ao recarregar)
```

## Validação do formulário (espelha a API; a API é a autoridade)

| Campo | Regra | Mensagem |
|---|---|---|
| Tipo (criação) | despesa, receita ou transferência | "Escolha o tipo." |
| Valor | reais do campo de dinheiro, de R$ 0,01 a R$ 10.000.000.000,00 | as do campo + "O valor deve ser maior que zero." / "O valor máximo é R$ 10.000.000.000,00." |
| Data | `AAAA-MM-DD`, dia que existe, ano de 2000 a 2100 | "Informe a data." / "Informe uma data válida." / "Use um ano de 2000 a 2100." |
| Descrição | 1 a 200 caracteres, sem os espaços das pontas | "Informe a descrição." / "Use no máximo 200 caracteres." |
| Observações | até 1.000 caracteres (opcionais; vazias não são enviadas) | "Use no máximo 1.000 caracteres." |
| Conta (receita, despesa) | uma conta ativa | "Escolha a conta." |
| Categoria (receita, despesa) | uma categoria ativa do mesmo tipo | "Escolha a categoria." |
| Origem e destino (transferência) | duas contas ativas, diferentes | "Escolha a conta de origem." / "Escolha a conta de destino." / "A origem e o destino precisam ser diferentes." |

Filtros: data final não anterior à inicial ("A data final não pode ser anterior à inicial."), busca até 100 caracteres ("Use no máximo 100 caracteres."), datas válidas ("Informe uma data válida.").

## Mensagens da API tratadas

| Situação | Onde | Mensagem na tela |
|---|---|---|
| `400` com `errors[]` | campo de mesmo nome (`amountCents`→valor, `occurredOn`→data, `description`, `notes`, `accountId`, `destinationAccountId`, `categoryId`) | a da API; o que não casar vira o aviso geral |
| `409`/`422` de conta ou categoria arquivada ou de tipo | aviso geral | a da API (a tela não a reescreve) |
| `403` | aviso geral | "Você não tem permissão para alterar isto neste workspace." |
| `404` | tela "não encontrado" ou aviso + lista atualizada | "Não encontrada. A lista foi atualizada." |
| rede / `5xx` | aviso geral | "Não foi possível falar com o servidor. Tente de novo." |
| `401` | cliente (base) | volta à entrada com o aviso de sessão terminada |

## Textos fixos

Avisos de resultado: "Lançamento registrado.", "Lançamento salvo.", "Lançamento efetivado.", "Lançamento excluído.". Selos: "Pendente", "Parcela N de M", "Recorrente", "Importado".
Texto da confirmação de excluir: "O lançamento sai da lista e do saldo da conta." (transferência: "A transferência sai da lista e do saldo das duas contas." ; parcela e recorrência: acrescenta "Só esta parcela é excluída." / "Só esta ocorrência é excluída.").

## Rotas

| Rota | Quem vê | Tela |
|---|---|---|
| `/lancamentos` | com sessão | lista paginada, filtros, "Novo lançamento" (dono e editor) |
| `/lancamentos/novo` | com sessão; leitor vê o aviso de papel | criar receita, despesa ou transferência |
| `/lancamentos/:id` | com sessão | dono e editor: edição, efetivar, excluir; leitor: consulta; inexistente = não encontrado |
