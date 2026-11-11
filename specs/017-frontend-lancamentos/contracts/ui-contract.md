# Contrato do front-end: telas, chamadas à API e permissões

A API é a das specs [004](../../004-lancamentos/contracts/openapi.yaml) e 009; esta spec não a muda. Valem também os contratos da
[015](../../015-frontend-esqueleto-auth/contracts/ui-contract.md) e da [016](../../016-frontend-contas-categorias/contracts/ui-contract.md).

## Rotas e quem faz o quê

| Rota | Dono / Editor | Leitor |
|---|---|---|
| `/lancamentos` | lista, filtros, páginas, "Novo lançamento", abrir uma linha | lista, filtros, páginas, abrir uma linha |
| `/lancamentos/novo` | formulário de criação | aviso de papel + "Voltar aos lançamentos" |
| `/lancamentos/:id` | formulário de edição, "Efetivar" (pendente), "Excluir" | os dados do lançamento só para leitura |

Lançamento que não existe no workspace atual (ou é de outro): "não encontrado" com a volta, igual nos dois casos.

## Chamadas à API

Todas em `/api/v1/workspaces/{wid}` do workspace atual, só com o cookie, limite de tempo de 15 s:

| Quando | Chamada | Resultado |
|---|---|---|
| abrir "Lançamentos" | `GET /accounts?includeArchived=true` e `GET /categories?includeArchived=true` (referência) e `GET /transactions?page=..&size=20[&filtros]` | a lista só aparece com as três 200; qualquer falha mostra "Tentar de novo" |
| aplicar filtro / trocar de página | `GET /transactions?...` com `from`, `to`, `accountId`, `categoryId`, `type`, `status`, `q` (só os preenchidos) | a lista da página pedida; filtro novo volta à página 1 |
| abrir `/lancamentos/:id` | `GET /transactions/{id}` (e a referência) | 200 abre; 404 "não encontrado" |
| criar | `POST /transactions` `{type, accountId, [destinationAccountId], amountCents, occurredOn, description, [notes], [categoryId], [status:'PENDING']}` | 201 volta à lista com o aviso |
| salvar | `PUT /transactions/{id}` `{accountId, [destinationAccountId], amountCents, occurredOn, description, [notes], [categoryId], status}` (sem o `type`) | 200 volta à lista com o aviso |
| efetivar | `POST /transactions/{id}/clear` | 200 volta à lista com o aviso |
| excluir | `DELETE /transactions/{id}` | 204 volta à lista com o aviso |

Valores sempre como inteiros em centavos (positivos no corpo; com sinal na resposta). Nenhum filtro vai para o endereço do navegador: só os parâmetros acima, montados pelo cliente, vão à API; nada de credencial
na consulta. As datas vão como `AAAA-MM-DD`, sem fuso.

## O que cada tela anuncia (leitores de tela)

| Momento | Anúncio |
|---|---|
| carregando a lista ou a referência | região de estado: "Carregando lançamentos…" |
| erro de carga | alerta com o texto e "Tentar de novo" |
| depois de registrar, salvar, efetivar ou excluir | região de estado com o aviso |
| erro de campo | o erro no campo (ligado por `aria-describedby`) e o foco no primeiro campo com erro |
| erro geral de envio | alerta com o texto |
| confirmação de exclusão | diálogo nativo com título e descrição; o foco começa em "Cancelar"; Esc cancela |
| mudar de página | a lista recarrega com a região de estado e o foco vai ao título da lista |
| filtros ativos | o botão "Filtros (N)" diz quantos há |

## Armazenamento do navegador

Nada novo: só tema e workspace atual. Os filtros e a página vivem só na memória da sessão.

## Recursos do aplicativo

Nenhum recurso novo vem de outro domínio. O service worker segue sem guardar `/api/*`.
