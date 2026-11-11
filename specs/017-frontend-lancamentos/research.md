# Research: Lançamentos no front-end

Decisões que a spec deixou para o plano. A API é a das specs [004](../004-lancamentos/contracts/openapi.yaml) e 009 (estado pendente/efetivado, `POST .../clear`); esta spec não a muda.
A base é a da [015](../015-frontend-esqueleto-auth/spec.md) e da [016](../016-frontend-contas-categorias/spec.md) (cliente, `useResource`, `useSubmit`, `describeFailure`, `MoneyField`, `ConfirmDialog`, papéis).

## 1. Onde vivem os filtros e a página: um contexto em memória, por workspace

**Decisão**: um `TransactionFiltersProvider`, dentro do `RequireAuth` (o `key` por pessoa já o remonta ao trocar de conta), guarda `{ workspaceId, filters, page }`. Quando o
`workspaceId` guardado é outro que o atual, o valor visto é o padrão (filtros vazios, página 1), **por derivação**, sem `setState` em efeito. A lista e o formulário leem e
escrevem por um hook `useTransactionFilters()`. Nada vai ao endereço, ao `localStorage` ou à API além dos parâmetros do contrato.

**Por quê**: a spec (FR-005) pede que os filtros sobrevivam à ida a um lançamento e à volta, e que a busca por texto não fique no histórico do navegador. O estado do
roteador não sobrevive a uma navegação nova ("Novo lançamento" depois "Salvar"), e o armazenamento do navegador é só para tema e workspace (constituição). Um contexto em memória
resolve sem nenhuma das duas, e some ao recarregar (aceitável: é um filtro, não um dado).

**Alternativas**: parâmetros no endereço (a busca iria para o histórico; mais código de leitura e escrita); `sessionStorage` (proibido pela constituição e pelo contrato da 015).

## 2. Consultar a página: a chave leva tudo o que muda o resultado

**Decisão**: `useResource` com a chave `transactions:<workspace>:<página>:<filtros serializados>`. Aplicar um filtro volta à página 1. A resposta que chega com outra chave é descartada (já garantido
pelo hook). `Anterior` e `Próxima` mudam a página no contexto; os totais (`totalPages`) vêm da resposta. Uma página que falha mostra o erro com "Tentar de novo" no lugar da lista
(nunca mistura com a anterior).

**Página inexistente**: se a API devolver uma página além do fim (`items` vazio com `page` > `totalPages`), a tela cai na última página existente (uma consulta de correção, uma vez).

## 3. Nomes de contas e categorias: duas listas carregadas junto, com as arquivadas

**Decisão**: um hook `useReferenceData(workspaceId)` carrega `listAccounts(wid, true)` e `listCategories(wid, true)` (com as arquivadas) e devolve mapas de nome por identificador e as listas
ativas para os seletores. A lista de lançamentos e os formulários usam o mesmo hook. A lista de lançamentos só mostra os itens quando a referência também carregou (senão apareceria um
identificador); uma falha na referência é erro da tela, com "Tentar de novo".

**Por quê**: um lançamento antigo pode apontar para uma conta ou categoria arquivada; o nome tem de aparecer (spec US1.5). São listas pequenas e já tem cliente.

## 4. Valor, data e texto

- **Valor**: o `MoneyField` da 016, com a regra extra "maior que zero" (a validação do formulário). Corpo em centavos inteiros positivos; o sinal vem do tipo.
- **Data**: um `<input type="date">` nativo (via `Field`), valor `AAAA-MM-DD`. A validação confere o formato, que o dia existe no calendário e o ano de 2000 a 2100. "Hoje" é a data local do aparelho
  (`todayISO()`), montada com os componentes locais e nunca com `toISOString()` (que usa UTC e erraria o dia à noite).
- **Texto longo**: o design system não tem área de texto; `TextAreaField` em `ui/` reaproveita as classes `tx-field__*`, com rótulo, dica, erro e contador.
- **Rótulo do dia** na lista: `formatDay(iso, hoje)` devolve "Hoje", "Ontem" ou "3 de nov. de 2026", montado a partir do dia como componentes locais (nunca `new Date('2026-11-03')`, que é UTC).

## 5. A lista, agrupada por dia, com o `TransactionRow` do design system

**Decisão**: a API entrega em ordem (do mais recente ao mais antigo); a tela só agrupa as linhas consecutivas do mesmo dia sob um título. Cada linha é um `TransactionRow` com ação (vira botão),
que abre `/lancamentos/:id`. O mapeamento é: `INCOME`→receita, `EXPENSE`→despesa, `TRANSFER`→transferência; o valor passado é o absoluto (o `Money` põe o sinal pelo tipo); a conta é o nome da conta; a categoria
é o nome da categoria (ou "Sem categoria"; na transferência, "Para <conta>" na saída e "De <conta>" na entrada); `pending` vem do estado. Os selos extras (parcela, recorrente, importado) entram por uma
propriedade nova e aditiva do `TransactionRow`, `tags` (uma lista de textos mostrados como `Badge` neutro na linha de apoio, ao lado de "Pendente"): a linha é um botão, então o selo faz parte do
mesmo conteúdo e não pode ser outro controle. A mudança no design system é pequena, com teste, em commit próprio.

**Tipo ou estado desconhecido**: a linha usa o estilo de despesa com a descrição e o rótulo "Lançamento", sem quebrar.

## 6. Transferência: origem e destino resolvidos pelo sinal e pela outra ponta

**Decisão**: a API devolve uma linha por ponta; `accountId` é a conta da ponta e `counterpartAccountId` a outra. A saída tem `amountCents < 0`, a entrada `> 0`. A função
`transferEnds(tx)` devolve `{ origin, destination }`: se o valor é negativo, origem = `accountId` e destino = `counterpartAccountId`; senão, o contrário. A edição envia sempre `accountId = origem` e
`destinationAccountId = destino` (regra da API), qualquer que seja a ponta aberta. O tipo não é enviado (não muda).

## 7. Pendente e efetivar

**Decisão**: o formulário tem a caixa "Pendente (ainda não aconteceu)". Criar envia `status: 'PENDING'` quando marcada e omite o campo (efetivado) quando não. Editar envia `status` sempre
(`'PENDING'` ou `'CLEARED'`), para refletir a caixa. "Efetivar" é uma ação da página de edição (e de consulta, para quem altera), que chama `POST .../clear` e volta à lista. Não há botão na linha: a linha já é um
botão (não se aninha controle interativo).

## 8. Excluir

**Decisão**: na página de edição, "Excluir" abre o `ConfirmDialog` (tom de perigo, foco em "Cancelar") com o efeito escrito pelo tipo do lançamento (transferência: as duas pontas; parcela ou
recorrência: só ela). O sucesso volta à lista com o aviso; a falha mantém tudo e mostra o erro (mesmo mapeamento de `describeFailure`).

## 9. Papéis

**Decisão**: o leitor abre `/lancamentos/:id` e vê um resumo só de leitura (os mesmos dados, sem formulário nem botões). `/lancamentos/novo` fica atrás do `WriteGate`, como em 016. Dono e editor veem o
formulário de edição com as ações.

## 10. Como testar

**Decisão**: como na 016: `renderApp` com `/me` (papel) e o `fakeApi` com rotas do workspace. O `fakeApi` ganha uma API de mentira com memória para lançamentos (lista paginada e filtrada pelos parâmetros
do contrato, criar, editar, efetivar, excluir, e a lógica da transferência de duas pontas), usada nos testes de tela; as funções puras (data, validação, resolução de transferência, agrupamento) têm testes
unitários próprios. O roteiro real (HTTP no Compose) cria receita, despesa, transferência e pendente, filtra, edita, efetiva e exclui, e confere os saldos.

## 11. Orçamento de tamanho e back-end

**Decisão**: sem divisão de código por rota agora (94,0 KB de 150 KB; as telas novas são de ordem de 10 KB). **Nenhuma mudança no back-end.**
