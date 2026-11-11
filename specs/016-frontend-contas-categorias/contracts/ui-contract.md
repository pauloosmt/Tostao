# Contrato do front-end: telas, chamadas à API e permissões

A API é a das specs [002](../../002-contas/contracts/openapi.yaml) e [003](../../003-categorias/contracts/openapi.yaml); esta spec não a muda.
Aqui ficam os compromissos das telas com a API, com o papel da pessoa e com o navegador, que os testes conferem. Valem também os da
[015](../../015-frontend-esqueleto-auth/contracts/ui-contract.md) (cliente único, só `/api/*`, só o cookie, nada na consulta do endereço).

## Rotas e quem faz o quê

| Rota | Dono / Editor | Leitor |
|---|---|---|
| `/contas` | lista, total, mostrar arquivadas, "Criar conta", "Editar", "Arquivar", "Restaurar" | lista, total, mostrar arquivadas |
| `/contas/nova` | formulário de criação | aviso de papel + "Voltar às contas" |
| `/contas/:id` | formulário de edição (conta ativa) ou "restaure antes" (arquivada) | aviso de papel + "Voltar às contas" |
| `/categorias` | árvore, tipo, mostrar arquivadas, "Criar categoria", "Adicionar subcategoria", "Editar", "Arquivar", "Restaurar" | árvore, tipo, mostrar arquivadas |
| `/categorias/nova` | formulário de criação de principal | aviso de papel + "Voltar às categorias" |
| `/categorias/:id` | formulário de edição | aviso de papel + "Voltar às categorias" |
| `/categorias/:id/subcategorias/nova` | formulário de criação de subcategoria | aviso de papel + "Voltar às categorias" |

Conta ou categoria que não existe no workspace atual (ou é de outro): a tela "não encontrada" com o caminho de volta, igual nos dois casos.
O papel é o do workspace atual da 015 (`useWorkspace`); a API continua sendo a autoridade e responde `403` ao que o papel não permite.

## Chamadas à API

Todas em `/api/v1/workspaces/{wid}` do workspace atual, só com o cookie, com o limite de tempo do cliente (15 s):

| Quando | Chamada | Resultado |
|---|---|---|
| abrir "Contas" | `GET /accounts` (+ `?includeArchived=true` se marcado) e `GET /reports/balance`, em paralelo | as duas 200 mostram a lista e o total; qualquer falha mostra "Tentar de novo" |
| abrir `/contas/:id` | `GET /accounts/{id}` | 200 abre o formulário; 404 "não encontrada" |
| criar conta | `POST /accounts` `{name, type, initialBalanceCents, [creditLimitCents, closingDay, dueDay só em cartão]}` | 201 volta à lista com o aviso; 400, 403, 409 tratados |
| salvar conta | `PUT /accounts/{id}` `{name, initialBalanceCents, [limite e dias só em cartão]}` | 200 volta à lista com o aviso |
| arquivar / restaurar conta | `POST /accounts/{id}/archive` · `/restore` | 200 recarrega a lista e o total; 409 (nome em uso) explicado |
| abrir "Categorias" | `GET /categories` (+ `?includeArchived=true` se marcado) | a árvore dos dois tipos; o tipo aberto filtra na tela |
| abrir `/categorias/:id` e `/categorias/:id/subcategorias/nova` | `GET /categories/{id}` | 200 abre; 404 "não encontrada"; a principal da subcategoria precisa estar ativa |
| criar categoria | `POST /categories` `{name, kind, [color]}` ou `{name, parentId, [color]}` (a subcategoria não envia `kind`) | 201 volta à lista no tipo certo, com o aviso |
| salvar categoria | `PUT /categories/{id}` `{name, color, icon}` com o `icon` **atual** reenviado; sem `kind` nem `parentId` | 200 volta à lista com o aviso |
| arquivar / restaurar categoria | `POST /categories/{id}/archive` · `/restore` | 200 recarrega; 409 explicado |

Valores monetários sempre como inteiros em centavos (nunca `Number` de uma string com vírgula, nunca ponto flutuante). Nenhuma chamada leva credencial,
nome de pessoa ou dado do workspace na consulta do endereço além do `includeArchived`.

## O que cada tela anuncia (leitores de tela)

| Momento | Anúncio |
|---|---|
| carregando uma lista | região de estado: "Carregando contas…" / "Carregando categorias…" |
| erro de carga | alerta com o texto e "Tentar de novo" |
| depois de salvar, arquivar ou restaurar | região de estado com o aviso ("Conta criada.", "Conta arquivada.", "Categoria restaurada.") |
| erro de campo | o erro no campo (ligado por `aria-describedby`) e o foco no primeiro campo com erro |
| erro geral de envio | alerta com o texto |
| confirmação | diálogo nativo com título e descrição; o foco começa em "Cancelar"; Esc cancela |
| trocar a opção "Mostrar arquivadas" | a lista recarrega com a região de estado |

## Armazenamento do navegador

Nada novo: continuam só o tema e o identificador do workspace atual (015). As listas e os formulários vivem só na memória da tela.

## Recursos do aplicativo

Nenhum recurso novo vem de outro domínio. As cores da paleta são dados do app, não de uma URL. O service worker segue sem guardar `/api/*`.
