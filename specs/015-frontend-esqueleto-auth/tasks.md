---

description: "Tarefas da feature 015-frontend-esqueleto-auth"
---

# Tasks: Esqueleto do front-end e autenticação

**Input**: documentos em `/specs/015-frontend-esqueleto-auth/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/ui-contract.md](contracts/ui-contract.md), [quickstart.md](quickstart.md)

**Tests**: INCLUÍDOS (constituição I, III, IV e VIII; FR-015 pede o guard-rail testado; SC-002 pede prova de que nada leva credencial; SC-009 pede acessibilidade).

**Organization**: só front-end. Ordem: arquivos e ferramentas (ícones, manifest, fontes locais, guard-rail, testes), depois a base (cliente da API, validação, contextos, componentes de interface), depois as telas, e por fim a verificação, o orçamento e o quickstart real num navegador.

## Format: `[ID] [P?] [Story] Descrição com caminho`

- Abreviação: `FE` = `frontend`, `SRC` = `frontend/src`
- **Commits**: pequenos (**no máximo uns 6 arquivos, uma ideia**), Conventional Commits, mensagem curta em inglês, **sem co-author**; código junto com os testes que o provam; sem commit só para marcar tarefa (a marca `[X]` vai no commit que fecha a tarefa). Antes de reescrever qualquer commit, conferir `git log origin/main..HEAD`
- **Rodar neste computador**: a partir de `frontend/`, `npm run verify` (lint, tipos, testes, build, orçamento). Mandar o log para um arquivo quando for longo
- **O guard-rail proíbe a palavra do cabeçalho de autorização em qualquer arquivo de `src`, testes inclusive**: provar a ausência verificando que nenhum nome de cabeçalho começa com `auth`
- **Versões**: fixar a versão exata de cada dependência nova depois de instalar e rodar; preferir a estável com semanas de publicada

---

## Phase 1: Arquivos e ferramentas

- [X] T001 Mover os arquivos de `public/public/` da raiz para `FE/public/` (`git mv`; `favicon.svg` substitui o do modelo do Vite; `apple-touch-icon.png`, `favicon.ico`, `icons/icon-192.png`, `icons/icon-512.png`, `icons/icon-maskable-512.png`, `icons/favicon-32.png`) deixando a pasta `public/` da raiz de fora do repositório (o `icons.svg` do modelo do Vite sai na T015). Dois commits: o favicon (`feat(frontend): add the favicon`) e os ícones de instalação (`feat(frontend): add the app install icons`)
- [X] T002 [P] Em `FE/vite.config.ts` listar no manifest os ícones de 192, 512 e maskable e as cores da marca (`theme_color` `#0f5f63`, `background_color` `#f6f4ef`), e no `FE/index.html` ligar o `favicon.svg`/`.ico` e o `apple-touch-icon`, `theme-color` e a descrição. Conferir no build que `dist/manifest.webmanifest` lista os ícones e que todos existem em `dist/`
- [X] T003 [P] Instalar `@fontsource/fraunces` e `@fontsource/ibm-plex-sans` e trocar o conteúdo de `SRC/design-system/src/fonts.css` pelos `import` dos pesos do subconjunto `latin` (Fraunces 500 e 600; IBM Plex Sans 400, 500 e 600). Conferir no build que os `woff2` saem em `dist/` e que o CSS final não tem URL externa
- [X] T004 Em `FE/scripts/check-guardrails.mjs` exportar a função de varredura, acrescentar a regra de **origem externa** (`http://` ou `https://` fora de `localhost`, `127.0.0.1` e dos namespaces W3C de SVG/XML) para `.ts`, `.tsx`, `.css` e `index.html`, e criar `FE/scripts/check-guardrails.test.mjs` (`node:test`, diretórios temporários): cada regra pega o que deve e deixa passar o que deve (inclusive o caso do `xmlns` do SVG). Acrescentar o script `test:guardrails` ao `package.json`
- [X] T005 Instalar `vitest`, `jsdom`, `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom` e `axe-core`; configurar o Vitest em `FE/vite.config.ts` (ambiente `jsdom`, `setupFiles`, `globals` falso); criar `SRC/test/setup.ts` (jest-dom, limpeza, `localStorage` zerado a cada teste, `matchMedia` falso), `SRC/test/fakeApi.ts` (tabela de rotas sobre o `fetch`, registro do que foi pedido, cabeçalhos e corpo), `SRC/test/render.tsx` e `SRC/test/axe.ts` (`expectNoSeriousViolations`); scripts `typecheck`, `test` e `verify` no `package.json`; no `.github/workflows/ci.yml` o job do front passa a rodar `npm run verify`. Um teste de fumaça prova que o ambiente funciona
- [X] T006 [P] [US4] Em `SRC/design-system/src/components/TransactionRow.tsx` fazer a linha com `onClick` renderizar um `<button type="button">` (sem `onClick`, segue `div`) mantendo a aparência, e criar `TransactionRow.test.tsx` (clique, Enter e Espaço acionam; papel de botão; sem `onClick` não é botão; axe sem violações)

**Checkpoint**: ícones e manifest, fontes locais, guard-rail testado, ambiente de testes funcionando, CI rodando testes

---

## Phase 2: Base

- [X] T007 Criar `SRC/api/errors.ts`, `SRC/api/types.ts`, `SRC/api/client.ts` e `SRC/api/auth.ts` (`register`, `login`, `logout`, `logoutAll`, `me`) como em [research.md](research.md) §3 e [data-model.md](data-model.md), com `SRC/api/client.test.ts`: caminho relativo, mesmo domínio e cookie, nenhum nome de cabeçalho começando com `auth`, nada de credencial no endereço, `204`, Problem Details com `errors`, `Retry-After`, corpo que não é JSON, falha de rede, tempo esgotado e o ouvinte de `401` (que não dispara nas chamadas de entrada e cadastro)
- [X] T008 [P] Criar `SRC/validation/account.ts` (nome, e-mail, senha com bytes em UTF-8; mensagens do [data-model.md](data-model.md)) e `SRC/validation/account.test.ts` (limites: 100 e 101 caracteres, 254 e 255, senha de 7, 8, 72 e 73 bytes com acentos e emoji, sem letra, sem número, e-mails válidos e inválidos)
- [X] T009 [P] Criar `SRC/storage/local.ts` (`get`/`set`/`remove` seguros: nunca lançam com armazenamento bloqueado ou cheio) e `SRC/storage/local.test.ts`
- [X] T010 Criar `SRC/auth/safeRedirect.ts`, `SRC/auth/AuthContext.tsx` (`AuthProvider`) e `SRC/auth/useAuth.ts` (o contrato e `useAuth`, separado por causa da regra de fast refresh) (`AuthProvider`, `useAuth`, ouvinte de `401`, `login`, `register`, `logout` que só limpa depois do `204`, `logoutAll`, nova tentativa de `/me`) com `SRC/auth/safeRedirect.test.ts` e `SRC/auth/AuthContext.test.tsx`: restaura a sessão, `401` sem aviso na abertura, aviso de sessão terminada em `401` posterior, a falha ao sair mantém a sessão, `unavailable` e nova tentativa, nenhum token ou senha no estado
- [X] T011 Criar `SRC/workspace/WorkspaceContext.tsx` e `SRC/theme/ThemeContext.tsx` com os testes: workspace guardado por pessoa, primeiro quando não há escolha ou ela não vale mais, outra conta não herda a escolha; tema sistema/claro/escuro aplicado em `data-theme` e lembrado, leitura antes da primeira pintura em `main.tsx`
- [X] T012 Criar `SRC/ui/LoadingScreen.tsx`, `ErrorNotice.tsx`, `ConfirmDialog.tsx` (`<dialog>` nativo), `PasswordField.tsx` (campo com "Mostrar senha" e `autocomplete` certo) e `SRC/styles/app.css` (layout e utilitários só com tokens), com `SRC/ui/ui.test.tsx` (estados anunciados, "Tentar de novo", confirmar e cancelar com `Esc`, mostrar e esconder a senha, axe)

**Checkpoint**: o cliente, a sessão, o workspace, o tema e a interface comum estão provados sem nenhuma tela

---

## Phase 3: Telas

- [X] T013 [US1] Criar `SRC/pages/LoginPage.tsx` e `SRC/pages/LoginPage.test.tsx`: entrar com sucesso; a mesma mensagem para e-mail inexistente e senha errada, senha limpa e foco na senha; bloqueio `429` com a espera e botão indisponível; validação local com foco no primeiro erro; envio duplicado; servidor fora do ar com "Tentar de novo"; aviso de sessão terminada; o caminho para criar conta; axe
- [X] T014 [US2] Criar `SRC/pages/RegisterPage.tsx` e `SRC/pages/RegisterPage.test.tsx`: cadastro com sucesso; regras de nome, e-mail e senha no campo; `409` no campo de e-mail mantendo os outros valores e limpando a senha; erros de campo da API e aviso geral; envio duplicado; axe
- [X] T015 [US4] Criar `SRC/layout/useMediaQuery.ts`, `SRC/layout/AppShell.tsx`, `SRC/pages/HomePage.tsx`, `SRC/pages/NotFoundPage.tsx`, `SRC/App.tsx` (rotas e `RequireAuth`, remontagem do app autenticado por `key`) e `SRC/main.tsx` (providers e tema antes da primeira pintura), removendo os restos do modelo do Vite (`SRC/App.css`, `SRC/index.css`, `SRC/assets/*`); testes `AppShell.test.tsx`, `App.test.tsx`: navegação inferior abaixo de 720 px e lateral a partir de 960 px, item atual marcado, redireciona sem sessão, retoma o endereço pedido, não encontrada, a conta anterior não deixa nada, o endereço com `?token=` não é lido nem repassado
- [X] T016 [US3] [US4] Criar `SRC/pages/ProfilePage.tsx` e `SRC/pages/ProfilePage.test.tsx`: dados da pessoa e workspaces com o papel em português, trocar de workspace (e continuar depois de recarregar), tema, "Sair" (só depois do `204`, e a falha avisa), "Sair de todos os aparelhos" com confirmação, e volta da navegação depois de sair sem tela interna; axe

**Checkpoint**: todos os fluxos da spec funcionam em teste

---

## Phase 4: PWA, hospedagem e prova

- [ ] T017 [US5] Criar `FE/vercel.json` (fallback das rotas para `/index.html`, sem tocar em `/api/`) e `FE/scripts/check-budget.mjs` (JavaScript inicial em gzip até 150 KB) ligado ao `verify`; acrescentar o `preview.proxy` de `/api` ao `vite.config.ts`; teste do manifest e do service worker no build (`FE/scripts/check-build.test.mjs`: ícones existem, nenhuma URL externa no `dist/`, nenhuma rota de `/api/` no pré-cache)
- [ ] T018 Rodar `npm run verify` completo e o `./mvnw -B verify` do back-end; corrigir qualquer falha nova sem enfraquecer testes; registrar as contagens
- [ ] T019 [P] Atualizar `FE/README.md` (como rodar, scripts, estrutura, regras: só `/api/*`, só cookie, sem origem externa) e o `README.md` da raiz (seção do front)
- [ ] T020 Executar o [quickstart.md](quickstart.md) num navegador contra a API do Docker Compose (cadastro, sessão, saída, bloqueio, workspace, offline, PWA, tema, teclado), com limpeza ao fim; marcar as tarefas restantes como `[X]` junto do último commit que as fecha; não fazer push

---

## Dependencies & Execution Order

- **Phase 1**: T001 primeiro (T002 usa os ícones); T003, T004, T005 e T006 independentes; T006 depois de T005 (usa o ambiente de testes)
- **Phase 2**: T007 depois de T005; T008, T009 em paralelo; T010 depois de T007 e T009; T011 depois de T009 e T010; T012 depois de T005
- **Phase 3**: T013 e T014 depois de T010 e T012; T015 depois de T010, T011 e T012; T016 depois de T015
- **Phase 4**: T017 depois de T001 a T003; T018 depois de tudo; T020 por último

## Commits previstos (cada um pequeno, com os testes que o provam)

1. `feat(frontend): add the favicon` e `feat(frontend): add the app install icons` — T001
2. `feat(frontend): describe the PWA with the brand icons` — T002
3. `feat(frontend): serve the fonts from the app` — T003
4. `feat(frontend): forbid external origins in the guard-rails` — T004
5. `chore(frontend): add the test tooling` — T005
6. `fix(frontend): make the transaction row usable by keyboard` — T006
7. `feat(frontend): add the API client` — T007
8. `feat(frontend): add the account validation rules` — T008
9. `feat(frontend): add the safe browser storage` — T009
10. `feat(frontend): add the session` — T010
11. `feat(frontend): add the workspace and the theme` — T011
12. `feat(frontend): add the shared interface pieces` — T012
13. `feat(frontend): add the sign-in page` — T013
14. `feat(frontend): add the sign-up page` — T014
15. `feat(frontend): add the app shell and the home` — T015
16. `feat(frontend): add the profile page` — T016
17. `feat(frontend): add the hosting fallback and the size budget` — T017
18. `docs: describe the front-end in the README` — T019, com as marcas de T018 e T020 no último commit

## Implementation Strategy

1. Ferramentas primeiro (T001 a T006): ícones, fontes locais, guard-rail e testes funcionando antes de qualquer tela.
2. A base (T007 a T012) é provada sem telas: cliente, sessão, workspace, tema e interface comum.
3. As telas (T013 a T016), da entrada ao perfil: o fluxo completo é o MVP.
4. Orçamento, fallback e quickstart real num navegador fecham a prova.
5. Em cada fase: testes verdes antes de seguir; commits pequenos e sem co-author; nunca push sem pedido.
