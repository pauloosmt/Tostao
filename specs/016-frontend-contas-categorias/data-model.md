# Data Model: Contas e categorias no front-end

O front não tem banco. Aqui ficam os tipos que vêm da API, o estado das telas, as regras de validação, as mensagens e as rotas.

## Tipos da API (espelham os contratos 002 e 003)

```ts
type AccountType = 'CHECKING' | 'SAVINGS' | 'CASH' | 'CREDIT_CARD' | 'INVESTMENT'

interface Account {
  id: string; name: string; type: AccountType; currency: string
  initialBalanceCents: number; balanceCents: number
  creditLimitCents: number | null; closingDay: number | null; dueDay: number | null   // só nos cartões
  archived: boolean; createdAt: string; updatedAt: string
}
interface CreateAccount { name: string; type: AccountType; initialBalanceCents: number
                          creditLimitCents?: number; closingDay?: number; dueDay?: number }
interface UpdateAccount { name: string; initialBalanceCents: number
                          creditLimitCents?: number; closingDay?: number; dueDay?: number }   // o tipo não é enviado
interface BalanceReport { accounts: { id: string; name: string; type: AccountType; balanceCents: number }[]; totalBalanceCents: number }

type CategoryKind = 'INCOME' | 'EXPENSE'
interface Category { id: string; name: string; kind: CategoryKind; parentId: string | null
                     color: string | null; icon: string | null; archived: boolean; createdAt: string }
interface CategoryTree extends Category { subcategories: Category[] }
interface CreateCategory { name: string; kind?: CategoryKind; parentId?: string; color?: string; icon?: string }
interface UpdateCategory { name: string; color?: string; icon?: string }   // kind e parentId não são enviados
```

Chamadas (todas em `/api/v1/workspaces/{wid}`, `wid` = workspace atual, só com o cookie): `GET /accounts[?includeArchived=true]`,
`GET /accounts/{id}`, `POST /accounts`, `PUT /accounts/{id}`, `POST /accounts/{id}/archive`, `POST /accounts/{id}/restore`,
`GET /reports/balance`, `GET /categories[?includeArchived=true]`, `GET /categories/{id}`, `POST /categories`, `PUT /categories/{id}`,
`POST /categories/{id}/archive`, `POST /categories/{id}/restore`.

**Regra do ícone (FR-010)**: `PUT /categories/{id}` grava `color` e `icon` exatamente como vierem (`null` se omitidos). O formulário lê a categoria
(`GET /categories/{id}`) e reenvia `icon` com o valor atual; só `name` e `color` mudam por vontade da pessoa.

## Estado das telas (memória)

```text
Resource<T> =
  | { status: 'loading' }                 // consultando (inclusive quando a chave muda: outro workspace ou outra opção)
  | { status: 'error', error }            // rede/servidor: "Tentar de novo"
  | { status: 'ready', data: T }
```

A consulta guarda a chave com que foi feita (`workspaceId` + opções + contador de recarga). Quando a chave atual é outra, o estado
visto é `loading`; a resposta que chega com uma chave que já não é a atual é descartada. Nenhum dado de lista sobrevive a uma troca de workspace.

```text
Tela de contas   : Resource<{ accounts: Account[]; total: number }>   (duas chamadas em paralelo; falhando uma, é erro)
Tela de categorias: Resource<CategoryTree[]> + tipo aberto (EXPENSE | INCOME, padrão EXPENSE) + mostrar arquivadas
Formulário       : valores em texto + mensagens por campo + aviso geral + envio em andamento (guarda síncrono)
Confirmação      : item alvo + aberta + ação em andamento
Aviso de resultado: texto vindo do estado do roteador (uma vez; some ao recarregar)
```

## Validação (espelha a API; a API é a autoridade)

| Campo | Regra | Mensagem |
|---|---|---|
| Nome da conta | 1 a 100 caracteres, sem os espaços das pontas | "Informe o nome." / "Use no máximo 100 caracteres." |
| Tipo da conta | um dos cinco (só na criação) | "Escolha o tipo da conta." |
| Saldo inicial | reais válidos, \|valor\| ≤ R$ 10.000.000.000,00 | as do campo de dinheiro (abaixo) |
| Limite do cartão | vazio, ou reais de 0 até R$ 10.000.000.000,00 | "O limite não pode ser negativo." + as do campo de dinheiro |
| Dia de fechamento / vencimento | vazio, ou inteiro de 1 a 31 | "Use um dia de 1 a 31." |
| Nome da categoria | 1 a 60 caracteres, sem os espaços das pontas | "Informe o nome." / "Use no máximo 60 caracteres." |
| Tipo da categoria principal | receita ou despesa | "Escolha o tipo." |
| Cor | uma da paleta, ou nenhuma | (sempre válida: só se escolhe da paleta) |

Campo de dinheiro (ver [research.md](research.md) §4): aceita `-`/`−`, `R$`, milhar com ponto bem formado e 1 ou 2 casas depois da vírgula; as mensagens são
"Informe um valor em reais, como 1.234,56." · "Use vírgula para os centavos, por exemplo 12,50." · "Use no máximo 2 casas decimais." · "O valor máximo é
R$ 10.000.000.000,00." (e a mesma coisa com "mínimo" e o sinal para o saldo negativo).

Os campos do cartão (limite, fechamento, vencimento) só existem na tela e só são enviados quando o tipo é `CREDIT_CARD`. Ao trocar o tipo na criação
para algo que não é cartão, os valores digitados neles são descartados (nunca enviados).

## Mensagens da API tratadas

| Situação | Onde | Mensagem na tela |
|---|---|---|
| `400` com `errors[]` | campo de mesmo nome (`name`, `initialBalanceCents`→saldo, `creditLimitCents`→limite, `closingDay`, `dueDay`, `type`, `color`) | a da API; o que não casar com um campo vira o aviso geral |
| `409` ao criar/editar conta | campo do nome | "Já existe uma conta ativa com este nome." |
| `409` ao restaurar conta | aviso da lista | "Já existe uma conta ativa com este nome. Renomeie a ativa antes de restaurar esta." |
| `409` ao criar/editar categoria | campo do nome | "Já existe uma categoria ativa com este nome aqui." |
| `409` ao restaurar categoria | aviso da lista | "Já existe uma categoria ativa com este nome aqui." ou "Restaure antes a categoria principal." (conforme o `detail` da API) |
| `403` | aviso geral | "Você não tem permissão para alterar isto neste workspace." |
| `404` | tela "não encontrada" ou aviso + lista atualizada | "Não encontrada. A lista foi atualizada." |
| `NetworkError` / `5xx` | aviso geral | "Não foi possível falar com o servidor. Tente de novo." |
| `401` | cliente (base) | volta à entrada com o aviso de sessão terminada |

## Paleta de cores das categorias (valores `#RRGGBB`)

Dez cores fixas com nome em português (para `aria-label`): Verde, Azul, Vermelho, Laranja, Amarelo, Roxo, Rosa, Turquesa, Marrom, Cinza. Mais "Sem cor" (nenhuma cor
enviada). Uma cor já gravada que não está na paleta (outra versão, ou outra via) aparece como está na lista e, no formulário, como a escolha "Cor atual" até a pessoa trocar.

## Rotas

| Rota | Quem vê | Tela |
|---|---|---|
| `/contas` | com sessão | lista de contas, total, "Mostrar arquivadas", ações (dono/editor) |
| `/contas/nova` | com sessão; leitor vê o aviso de papel | criar conta |
| `/contas/:id` | com sessão; leitor vê o aviso; arquivada pede restaurar | editar conta; inexistente = não encontrada |
| `/categorias` | com sessão | árvore por tipo, "Mostrar arquivadas", ações (dono/editor) |
| `/categorias/nova` | com sessão; leitor vê o aviso | criar categoria principal |
| `/categorias/:id` | com sessão; leitor vê o aviso | editar categoria (ou subcategoria); inexistente = não encontrada |
| `/categorias/:id/subcategorias/nova` | com sessão; leitor vê o aviso | criar subcategoria da principal `:id` (precisa estar ativa) |
