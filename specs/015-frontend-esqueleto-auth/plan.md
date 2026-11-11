# Implementation Plan: Esqueleto do front-end e autenticação

**Branch**: `015-frontend-esqueleto-auth` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/015-frontend-esqueleto-auth/spec.md`

## Summary

A base de todas as telas: cadastro, entrada, sessão restaurada, saída, workspace atual, navegação e tema, num PWA instalável sobre o design
system do projeto. O front fala só com `/api/v1` do próprio domínio, só com o cookie `HttpOnly` da API; nada de token, senha ou código
fora do formulário em uso.

Abordagem técnica (decisões em [research.md](research.md)):

- **Cliente da API único** (`api/client.ts`): `fetch` relativo com `same-origin`, tempo limite, Problem Details convertido em `ApiError`,
  falha de rede em `NetworkError`, e o `401` de chamada autenticada avisa o contexto de sessão. Nunca monta cabeçalho de autorização.
- **Três contextos pequenos**: `AuthProvider` (carregando, indisponível, anônima, autenticada), `WorkspaceProvider` (o último escolhido,
  por pessoa, ou o primeiro) e `ThemeProvider` (sistema, claro, escuro). Sem biblioteca de dados.
- **`react-router`** declarativo: `/entrar`, `/cadastro`, `/`, `/perfil` e `*`; `RequireAuth` guarda a localização pedida no **estado** do
  roteador e `safeRedirect` só aceita caminho interno.
- **Telas** (`Login`, `Register`, `Home`, `Profile`, `NotFound`) montadas só com componentes do design system, com validação no
  navegador que espelha a API, foco e `aria-live` tratados.
- **PWA e arquivos**: ícones movidos de `public/public/` da raiz para `frontend/public/` e ligados ao manifest; fontes locais
  (`@fontsource`) no lugar do Google Fonts; `vercel.json` com o fallback de rotas.
- **Verificação**: o guard-rail de origens externas (testado), Vitest + Testing Library + `axe-core`, orçamento de 150 KB, `npm run verify` e
  o CI do front passando a rodar os testes.
- **Escopo**: somente front-end; nenhuma mudança na API.

## Technical Context

**Language/Version**: TypeScript 7, React 19, Vite 8 (já no `package.json`); Node 22 no CI

**Primary Dependencies (novas)**: `react-router`; em desenvolvimento, `vitest`, `jsdom`, `@testing-library/react`,
`@testing-library/user-event`, `@testing-library/jest-dom`, `axe-core`; e `@fontsource/fraunces` e `@fontsource/ibm-plex-sans` (fontes
locais). Versões fixas, estáveis e com semanas de publicadas, conferidas instalando e rodando.

**Storage**: nenhum no servidor. No aparelho, `localStorage` com `tostao.workspace.<idDaPessoa>` e `tostao.theme`.

**Testing**: Vitest em `jsdom`; um `fakeApi` que substitui o `fetch` por uma tabela de rotas e registra o que foi enviado; `axe-core` para
acessibilidade; `node:test` para o script de guard-rails; teste de integração real no navegador contra a API do Docker Compose.

**Target Platform**: navegadores modernos (celular primeiro), instalável como PWA; hospedagem Vercel.

**Project Type**: front-end (SPA/PWA) no monorepo `frontend/`.

**Performance Goals**: JavaScript inicial de até 150 KB comprimidos; entrada utilizável em menos de 2 s em 4G simulada (SC-008).

**Constraints**: nenhum recurso de outro domínio; nenhum cabeçalho de autorização montado à mão; nenhum token, senha ou código em
lugar algum que não seja o formulário em uso; nada de `/api/*` no cache do service worker; tudo em português do Brasil.

**Scale/Scope**: 5 telas e a base (cliente, sessão, navegação, tema) que as próximas 5 specs de front reaproveitam.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | O front não guarda nem lê a sessão (cookie `HttpOnly`); o papel exibido é informativo e a API segue autoridade; nenhum segredo no código ou nas variáveis do front | OK |
| II. Isolamento em duas camadas | Sem dados de workspace nesta spec; o estado da pessoa anterior é descartado (remontagem por `key`) e o identificador do workspace guardado é por pessoa | OK |
| III. Autenticação e rede | Só o domínio do app e `/api/*`; cookie `SameSite=Lax`; sem cabeçalho de autorização à mão; guard-rail de origem externa testado; fontes locais; `vercel.json` sem expor a URL da API | OK |
| IV. Entrada validada, saída controlada | Validação no navegador espelha a API e a API é a autoridade; erros por campo vêm do Problem Details; nada do servidor é inserido como HTML (React escapa); redirecionamento só para caminho interno | OK |
| V. Dados financeiros confiáveis | Nenhum dado financeiro; o formatador de reais do design system usa centavos inteiros | OK |
| VI. Sem tentativa e erro em transação | Não se aplica ao front; o envio duplicado é impedido (FR-018) | OK |
| VII. Simplicidade | Contexts em vez de biblioteca de estado; sem MSW (um `fakeApi` de poucas linhas); sem biblioteca de componentes; sem `vitest-axe`; roteador pronto em vez de próprio | OK |
| VIII. Prova, não promessa | Testes de cada fluxo e erro, de segurança do cliente e de acessibilidade; guard-rail testado; orçamento verificado; [quickstart.md](quickstart.md) executado de verdade num navegador | OK |

**Ponto de atenção (alcance)**: troca o `fonts.css` e acrescenta um comportamento ao `TransactionRow` do design system, que vieram da
pessoa. Mitigação: as duas mudanças são pequenas, aditivas, em commits próprios e com testes; a aparência não muda.

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/015-frontend-esqueleto-auth/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Tipos, estado, armazenamento e validação
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── ui-contract.md   # Rotas, chamadas à API, armazenamento e PWA
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
frontend/
├── index.html                               # AJUSTE: favicon e apple-touch-icon
├── vite.config.ts                           # AJUSTE: manifest com ícones, proxy do preview, Vitest
├── vercel.json                              # NOVO: fallback das rotas
├── package.json                             # AJUSTE: dependências e scripts (test, typecheck, verify)
├── public/                                  # ícones movidos de public/public/ da raiz
├── scripts/
│   ├── check-guardrails.mjs                 # AJUSTE: origens externas; função de varredura exportada
│   ├── check-guardrails.test.mjs            # NOVO: teste do guard-rail
│   └── check-budget.mjs                     # NOVO: orçamento de 150 KB
└── src/
    ├── main.tsx  App.tsx                    # AJUSTE: providers e rotas
    ├── api/        client.ts errors.ts types.ts auth.ts
    ├── auth/       AuthContext.tsx RequireAuth.tsx safeRedirect.ts
    ├── workspace/  WorkspaceContext.tsx
    ├── theme/      ThemeContext.tsx
    ├── storage/    local.ts                 # leitura/escrita segura do localStorage
    ├── validation/ account.ts
    ├── layout/     AppShell.tsx useMediaQuery.ts
    ├── ui/         LoadingScreen.tsx ErrorNotice.tsx ConfirmDialog.tsx PasswordField.tsx
    ├── pages/      LoginPage.tsx RegisterPage.tsx HomePage.tsx ProfilePage.tsx NotFoundPage.tsx
    ├── styles/     app.css
    ├── test/       setup.ts fakeApi.ts render.tsx axe.ts
    └── design-system/                       # AJUSTE: fonts.css local; TransactionRow acessível
.github/workflows/ci.yml                     # AJUSTE: o front passa a rodar typecheck, testes e orçamento
```

**Structure Decision**: uma SPA em pastas por responsabilidade (`api`, `auth`, `workspace`, `theme`, `layout`, `ui`, `pages`); as próximas
specs acrescentam uma pasta por área (`accounts/`, `transactions/`...) reaproveitando `api/`, `ui/` e os contextos.

## Complexity Tracking

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| `react-router` | As 5 specs seguintes acrescentam dezenas de rotas, parâmetros e filtros na consulta; foco, histórico e links vêm prontos | Roteador próprio sobre a History API (reimplementaria o que o pacote já tem e testa) |
| Remontagem do app autenticado com `key` igual ao id da pessoa | Garante que nenhum estado de tela de uma conta sobrevive à troca de conta ou ao fim da sessão, sem depender de cada tela limpar o que guardou | Cada tela limpar o seu estado ao sair (esquecível; vazaria dados) |
| `<dialog>` nativo para confirmar "Sair de todos" | Foco preso, `Esc` e papel de diálogo vêm do navegador | Componente de modal próprio (mais código e mais risco de acessibilidade) |
