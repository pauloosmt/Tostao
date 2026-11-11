# Research: Contas e categorias no front-end

Decisões que a spec deixou para o plano. A API é a das specs [002](../002-contas/contracts/openapi.yaml) e
[003](../003-categorias/contracts/openapi.yaml); esta spec não a muda. A base (cliente, sessão, workspace, UI comum) é a da
[015](../015-frontend-esqueleto-auth/spec.md).

## 1. Carregar uma lista por workspace, sem biblioteca de dados

**Decisão**: um hook pequeno, `useResource(key, load)`, devolve `{ status: 'loading' | 'error' | 'ready', data, error, reload }`. A chave
(`workspaceId` + as opções, como "mostrar arquivadas") identifica a consulta: o resultado guardado leva a sua chave, e quando a chave
atual é outra o hook se apresenta como "carregando" **por derivação** (sem `setState` dentro de efeito). Uma resposta que chega depois de a
chave ter mudado, ou depois de a tela ter sido desmontada, é descartada. `reload` incrementa um contador que entra na chave.

**Por quê**: cobre a US8 (trocar de workspace) e a borda "resposta atrasada" com poucas linhas, testáveis com o `fakeApi`. É o mesmo
espírito da 015 (contextos em vez de biblioteca de estado).

**Alternativas**: TanStack Query/SWR (cache, revalidação e deduplicação que não são necessários agora; mais uma dependência e um
risco de cache de dados de outro workspace, contra o princípio II); `AbortController` por consulta (o cliente não expõe sinal externo, e o
descarte pela chave dá o mesmo resultado para a tela).

## 2. Telas em página, com aviso de resultado no estado do roteador

**Decisão**: criar e editar são **páginas** (`/contas/nova`, `/contas/:id`, `/categorias/nova`, `/categorias/:id`,
`/categorias/:id/subcategorias/nova`), não janelas. Depois de salvar, volta-se à lista com `navigate(..., { state: { notice } })`; a lista
mostra o aviso ("Conta criada.") numa região de estado e o remove do estado do histórico. O tipo (despesa ou receita) com que a
categoria nasce vai no estado do roteador a partir do botão; ao recarregar, o padrão é despesa.

**Por quê**: páginas dão endereço, "voltar" e recarregar de graça (spec: "recarregar em `/contas/<id>` abre direto o formulário"), funcionam
igual no PWA e no desktop, e não exigem gerenciamento de foco de janela além do `<dialog>` das confirmações. O estado do roteador não
fica na barra de endereços (regra da 015: nada na consulta) e some ao recarregar, que é o certo para um aviso.

**Alternativas**: modais para formulários (foco e histórico mais difíceis, e sem endereço); aviso por contexto global (mais código para um
texto de uma tela só); consulta `?tipo=receita` (permitida pelo guard-rail, mas cria endereço que se herda e se compartilha sem necessidade).

## 3. Papéis: esconder a ação, deixar a API como autoridade

**Decisão**: `usePermissions()` lê o papel do workspace atual (`useWorkspace().current.role`) e devolve `canWrite` (`OWNER` ou `EDITOR`).
As listas só renderizam as ações quando `canWrite`; as páginas de formulário ficam atrás de um `WriteGate` que, para o leitor, mostra o aviso
de papel com o caminho de volta. A recusa `403` da API continua tratada (mensagem de permissão), porque o papel pode mudar em outro aparelho.

**Por quê**: a spec (US8) quer que o aplicativo não ofereça o que a API recusa, sem tornar o front a autoridade (princípio I).

## 4. O campo de dinheiro: reais digitados, centavos exatos

**Decisão**: duas funções puras em `money/`: `parseReais(texto)` → `{ cents }` ou `{ error }`, e `formatReaisInput(cents)` → `"1.234,56"`. O
texto é lido **como texto**, nunca como `Number`/`parseFloat`: sinal opcional (`-` ou `−`), `R$` opcional no começo, espaços ao redor
ignorados, parte inteira com ou sem ponto de milhar bem formado (`1.234.567`, grupos de 3), e, se houver vírgula, 1 ou 2 casas decimais. Os
centavos saem de aritmética de inteiros sobre as strings de dígitos. Fora disso é erro com a mensagem que ensina o formato. O limite é o
da API: |valor| ≤ 1.000.000.000.000 centavos (R$ 10.000.000.000,00); limite de cartão de 0 a esse valor.

| Texto | Resultado |
|---|---|
| `1.234,56` · `1234,56` · `R$ 1.234,56` | 123456 |
| `12,5` | 1250 |
| `-50` · `−50` | -5000 |
| `50` · `1.234` | 5000 · 123400 |
| `12.5` · `1.23` · `12.34,5` | erro: "Use vírgula para os centavos, por exemplo 12,50." |
| `1,234` · `1,2,3` | erro: "Use no máximo 2 casas decimais." / "Informe um valor em reais, como 1.234,56." |
| `abc` · `` (vazio) | erro: "Informe um valor em reais, como 1.234,56." |
| `10000000000,01` | erro: "O valor máximo é R$ 10.000.000.000,00." |

**Por quê**: ponto flutuante erra em centavos (`0.1 + 0.2`) e a constituição (princípio V) manda inteiros. Uma regra estrita, com mensagem
que ensina, evita o "arredondar em silêncio" que a spec proíbe. Esse campo será reaproveitado por lançamentos, orçamentos e recorrências.

**Alternativas**: `Intl.NumberFormat` para ler (não lê, só formata); biblioteca de máscara de moeda (dependência, e mascarar enquanto
digita atrapalha leitores de tela e a edição do meio do número).

## 5. Tipos da conta: da API para o design system

**Decisão**: `account/accountTypes.ts` traduz `CHECKING | SAVINGS | CASH | CREDIT_CARD | INVESTMENT` para os tipos do `AccountCard`
(`corrente | poupanca | dinheiro | cartao | investimento`) e guarda os rótulos do seletor. Hoje o `AccountCard` cai em "Conta corrente" para um
tipo que não conhece. Para um tipo **desconhecido** da API (spec: "rótulo genérico") a tradução devolve um valor que o `AccountCard` não
conhece, e o `AccountCard` passa a cair no rótulo genérico "Conta", com o ícone de banco (mudança pequena e aditiva no design system, com teste).

**Por quê**: a spec pede que uma versão futura da API não quebre a lista, e o texto "Conta corrente" para algo que não é seria uma
informação errada na tela.

## 6. Formulários: o mesmo padrão da entrada e do cadastro, sem repetir o guarda de envio

**Decisão**: cada formulário guarda o texto digitado em estado local, valida com funções puras (`validation/accountForm.ts`,
`validation/categoryForm.ts`) que espelham a API e devolvem uma mensagem por campo, e envia só se tudo vale. Um hook `useSubmit()` (em
`forms/`) concentra o guarda de envio duplicado que a entrada e o cadastro repetem (referência síncrona + estado), e as duas páginas
da 015 passam a usá-lo (commit próprio, sem mudar comportamento). O mapeamento de erros da API para a tela é uma função pura
(`forms/apiErrors.ts`): `400` com `errors[]` vira mensagens por campo (os campos desconhecidos viram o aviso geral), `409` vira a
mensagem de nome em uso no campo do nome, `403` vira a mensagem de permissão, `404` vira "não encontrada" e o resto é o aviso de servidor
com "Tentar de novo".

**Por quê**: são seis formulários-ação (criar, editar, arquivar, restaurar, de conta e de categoria); copiar o mesmo guarda e o mesmo
mapeamento seis vezes é o caminho de divergir. É a primeira vez que o padrão se repete o bastante para valer extrair (princípio VII).

## 7. Seletor, caixa de marcação e paleta: três peças pequenas

**Decisão**: o design system não tem seletor nem caixa de marcação. Em `ui/`: `SelectField` (um `<select>` nativo com o rótulo, a dica e o
erro do `Field`, reaproveitando as classes `tx-field__*`), `CheckboxField` ("Mostrar arquivadas") e `ColorPicker` (um grupo de rádios, cada um
com a cor e um nome acessível, mais "Sem cor"). A paleta é uma lista fixa de 10 cores com nome em português.

**Por quê**: `<select>` e `<input type="checkbox">` nativos são acessíveis, abrem o seletor do celular e não pedem biblioteca; a paleta
fixa evita um seletor livre de cor (spec) e garante contraste do ponto sobre o fundo claro e escuro.

**Alternativas**: `Segmented` para o tipo da conta (cinco opções não cabem em 320 px); `<input type="color">` (cor livre, sem nome acessível, e
o valor volta em minúsculas que a API aceita mas a lista não padroniza).

## 8. Confirmações

**Decisão**: o `ConfirmDialog` da 015 ganha a opção `tone` (`danger`, o atual, ou `primary`); arquivar usa `primary` porque não é
destrutivo (a conta e os lançamentos continuam, e dá para restaurar). O texto explica o efeito (sai do total; subcategorias junto).

## 9. Navegação

**Decisão**: `Nav` passa a ter Início, Contas (`bank`), Categorias (`tag`) e Perfil (`wallet`). As quatro cabem na barra inferior de 320 px
(a pílula da 015 já anima por índice). O item atual de `/contas/...` e `/categorias/...` é o da seção.

## 10. Como testar

**Decisão**: os testes de tela sobem o aplicativo inteiro (`renderApp`) no endereço da tela, com o `fakeApi` respondendo `/me` com um papel
(dono, editor ou leitor) e as rotas `/api/v1/workspaces/{id}/...` da tela. Assim o papel, o workspace atual, o roteador e os avisos são os
reais. O `fakeApi` ganha um ajudante para o prefixo do workspace. O roteiro real (HTTP contra o Docker, como na 015) cria conta, categoria
e subcategoria, edita, arquiva e restaura, e confere o saldo total contra o relatório da API.

## 11. Orçamento de tamanho e back-end

**Decisão**: sem divisão de código por rota agora; o JavaScript inicial está em 86,6 KB de 150 KB e as telas novas são pequenas (a
medição do `check-budget` decide; se passar de 130 KB, divide-se por rota na própria spec). **Nenhuma mudança no back-end**: o relatório
de saldo (`/reports/balance`) e a árvore de categorias já existem.
