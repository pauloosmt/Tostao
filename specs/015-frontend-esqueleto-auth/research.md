# Research: Esqueleto do front-end e autenticação

Decisões técnicas e as alternativas descartadas. Spec em [spec.md](spec.md). Contrato da API em
[../001-autenticacao-workspace/contracts/openapi.yaml](../001-autenticacao-workspace/contracts/openapi.yaml).

## 1. Roteamento: `react-router`, no modo declarativo

**Decisão**: `react-router` (o pacote único, que já inclui os componentes de DOM) com `BrowserRouter`, `Routes`, `Route`,
`Navigate`, `Outlet` e `NavLink`. Rotas: `/entrar`, `/cadastro`, `/` (início), `/perfil` e `*` (não encontrada). As telas
internas ficam sob um `RequireAuth` e um `AppShell` (layout com `Outlet`).

**Por quê**: as próximas 5 specs de front acrescentam dezenas de rotas, com parâmetros e filtros na consulta; histórico, "voltar",
foco e links vêm prontos. Um roteador próprio seria código a manter e a testar sem ganho.

**Descartado**: roteador próprio sobre a History API (reimplementaria `Navigate`, estado de navegação e correspondência de rotas);
roteamento por `hash` (feio, e a Vercel não precisaria do fallback, mas a URL não ficaria limpa).

## 2. Estado: Context e hooks, sem biblioteca de dados

**Decisão**: três contextos pequenos: `AuthProvider` (estado `loading | anonymous | authenticated`, a pessoa, os workspaces, o aviso
de sessão terminada, `login`, `register`, `logout`, `logoutAll`), `WorkspaceProvider` (workspace atual derivado de `/me` e da
escolha guardada) e `ThemeProvider` (sistema, claro ou escuro). Sem Redux e sem biblioteca de cache de dados (TanStack Query) nesta
spec: só há uma consulta (`/me`). Se as telas de lista pedirem cache e revalidação, a spec 016 decide e acrescenta.

**Por quê**: simplicidade (princípio VII). O contexto de autenticação já concentra o que muda raramente.

## 3. O cliente da API

**Decisão**: um módulo `api/client.ts` com `request<T>(method, path, { body, auth })`:

- `fetch` em `/api/v1` + caminho, `credentials: 'same-origin'` (o cookie vai porque o domínio é o do app), `Content-Type`
  JSON só quando há corpo, `Accept: application/json, application/problem+json`;
- limite de tempo de 15 s por `AbortController`;
- resposta `2xx`: `204` devolve `undefined`, senão JSON; resposta de erro: lê Problem Details e lança `ApiError { status, title, detail,
  errors[{field,message}], retryAfterSeconds }`; falha de rede ou tempo esgotado lança `NetworkError`; corpo que não é JSON vira um
  `ApiError` genérico;
- o `401` de uma chamada com `auth: true` (padrão) avisa o `AuthProvider` por um ouvinte registrado (`onUnauthorized`), que encerra a
  sessão em memória; o `401` de `login` e `register` (`auth: false`) segue como erro comum de credencial;
- **nunca** monta cabeçalho de autorização: o cookie `HttpOnly` é o único mecanismo.

**Por quê**: um ponto único para o que a constituição exige (só `/api/*`, só cookie) e para o tratamento de erro; os testes verificam
tudo o que o app envia num lugar só.

## 4. Sessão e "continuar logada"

**Decisão**: ao montar, o `AuthProvider` chama `GET /me`; `200` vira `authenticated`, `401` vira `anonymous` (sem aviso), erro de rede
vira um estado `unavailable` com "Tentar de novo". A sessão em si nunca é lida (cookie `HttpOnly`). `logout` chama `POST /auth/logout`:
só depois do `204` o estado vai para `anonymous` (se falhar, a pessoa continua logada e vê o aviso). Ao sair ou perder a sessão, a
subárvore autenticada é remontada com `key` igual ao identificador da pessoa, o que descarta qualquer estado de tela da conta anterior.

**Por quê**: não há como "ver" a sessão no navegador; `/me` é a única fonte de verdade. A `key` por pessoa garante que nada da conta
anterior sobrevive, sem depender de cada tela limpar o que guardou.

## 5. Retomar o endereço pedido, sem redirecionamento aberto

**Decisão**: `RequireAuth` redireciona para `/entrar` com `state.from` (a localização pedida). Depois de entrar, `safeRedirect(from)`
só aceita caminhos internos (começam com `/`, não com `//`, sem esquema, e que não sejam `/entrar` nem `/cadastro`); qualquer outra coisa
cai em `/`. A localização vai no estado do roteador (memória e histórico), nunca na consulta do endereço.

**Por quê**: o `state` evita colocar um alvo editável na URL (e portanto qualquer `?redirect=`), que é o vetor clássico de redirecionamento
aberto, e não conflita com a regra de não ter token ou código na consulta.

## 6. Workspace atual e o que fica no aparelho

**Decisão**: `localStorage` com `tostao.workspace.<idDaPessoa>` (só o UUID do workspace) e `tostao.theme` (`system | light | dark`),
sempre dentro de `try/catch` (modo privado, armazenamento bloqueado, cota). O workspace atual é o guardado se ainda constar na lista
de `/me`, senão o primeiro. Nada de dado financeiro, token ou senha.

**Por quê**: chave por pessoa evita que outra conta no mesmo aparelho herde a escolha; guardar só o identificador não vaza nada.

## 6b. Tema

**Decisão**: o `ThemeProvider` põe `data-theme="light|dark"` no `<html>` quando a pessoa força um, e remove o atributo para seguir o
sistema (o `tokens.css` já trata os dois casos e `prefers-color-scheme`). Um pequeno script inline não é usado: a leitura acontece
antes da primeira pintura pelo próprio `main.tsx` (síncrono, antes de `createRoot`), o que evita o clarão do tema errado.

## 7. Fontes locais

**Decisão**: `@fontsource/fraunces` (pesos 500 e 600) e `@fontsource/ibm-plex-sans` (400, 500 e 600), importando só os arquivos do
subconjunto `latin` (`latin-<peso>.css`), que cobre o português. `fonts.css` do design system passa a importar esses CSS em vez do Google
Fonts. O Vite empacota os `woff2` no próprio build e o service worker os guarda.

**Por quê**: constituição (o navegador fala só com o domínio do app) e funcionamento offline do PWA. O subconjunto `latin` evita
empacotar as dezenas de arquivos dos outros alfabetos.

**Descartado**: manter o Google Fonts (viola a regra e quebra offline); autohospedar os arquivos à mão em `public/` (perde as atualizações
do pacote e o `font-display` ajustado).

## 8. Ícones e manifest

**Decisão**: os arquivos de `public/public/` da raiz vão para `frontend/public/` (`favicon.ico`, `favicon.svg`, `apple-touch-icon.png`,
`icons/icon-192.png`, `icons/icon-512.png`, `icons/icon-maskable-512.png`, `icons/favicon-32.png`); o `index.html` liga o favicon e o
`apple-touch-icon`; o manifest do `vite-plugin-pwa` lista 192, 512 e maskable 512; `theme_color` é o `--brand` e `background_color` é o
`--surface-100` do tema claro. Os restos do modelo do Vite (`icons.svg`, `src/assets/*`, `App.css`) saem.

## 9. A verificação automática do projeto

**Decisão**: o `check-guardrails.mjs` ganha uma segunda família de regras: **origem externa proibida** em `.ts`, `.tsx`, `.css` e no
`index.html` (qualquer `http://` ou `https://` que não seja `localhost`, `127.0.0.1` ou os namespaces de SVG e XML do W3C), e a regra do
cabeçalho continua valendo para tudo em `src`, **inclusive os testes**. Por isso os testes nunca escrevem a palavra
proibida; para provar a ausência do cabeçalho eles verificam que nenhum nome de cabeçalho começa com `auth`. A função de varredura passa a
ser exportada e testada com `node:test`, em diretórios temporários.

**Por quê**: a regra só protege se for testada; e fica proibido, de fato, o que a constituição proíbe.

## 10. Testes

**Decisão**: Vitest (o mesmo `vite.config.ts`), `jsdom`, `@testing-library/react`, `@testing-library/user-event`,
`@testing-library/jest-dom` e `axe-core` direto (um auxiliar `expectNoSeriousViolations`, sem o invólucro `vitest-axe`, que está parado
desde 2025). Sem MSW: um auxiliar `fakeApi` substitui o `fetch` global por uma tabela de rotas, o que dá controle total do que foi pedido
(método, caminho, cabeçalhos, corpo) e mantém o conjunto de dependências pequeno. As versões são fixas, estáveis e já com semanas de
publicadas; o plano de tarefas confere cada uma instalando e rodando antes de seguir.

**Limite conhecido**: o `jsdom` não calcula layout nem cores, então o axe não avalia contraste lá; o contraste é responsabilidade do
design system (tokens). As demais regras (rótulos, nomes de botões, papéis ARIA, ordem de títulos) valem.

## 11. Hospedagem: o fallback das rotas

**Decisão**: `frontend/vercel.json` com a regra de SPA (`/((?!api/).*)` → `/index.html`), para recarregar `/perfil` não dar 404. O
rewrite de `/api/*` para o Render **não** entra no arquivo: ele depende da URL do serviço (e a constituição manda manter isso fora do
código); a pessoa o configura no painel da Vercel.
**Revisto no deploy (2026-11-13)**: a regra de `/api/*` passou para o `vercel.json` (a URL do serviço não é segredo e o arquivo é a forma garantida); o teste agora exige a regra, com destino https e nenhuma outra URL. No desenvolvimento e no `vite preview`, o proxy do Vite espelha o rewrite.

## 12. Orçamento de tamanho

**Decisão**: `scripts/check-budget.mjs` lê `dist/assets/*.js` e falha se o JavaScript inicial passar de 150 KB comprimidos (gzip). Entra em
`npm run verify` e no CI. O orçamento só vale para o bundle de entrada: telas futuras carregam sob demanda (`React.lazy`).

## 13. Acessibilidade da interação

**Decisão**: erros de formulário aparecem junto ao campo (`aria-invalid`, `aria-describedby`, já no `Field`) e num resumo no topo com
`role="alert"` quando há mais de um; depois de uma validação que falha o foco vai para o primeiro campo com erro; depois de uma falha
de credencial o foco vai para a senha; a troca de tela move o foco para o `h1` (`tabIndex=-1`) e atualiza o `document.title`; o
carregamento é um `role="status"`; a confirmação de "Sair de todos os aparelhos" usa o `<dialog>` nativo com `showModal()` (foco preso
e `Esc` de graça).

## 14. O ajuste do design system

**Decisão**: o `TransactionRow` com `onClick` passa a renderizar um `<button type="button">` (sem `onClick`, continua `div`), com a mesma
aparência e o foco visível que o CSS já previa. A mudança é aditiva e vem com o primeiro teste do design system.
