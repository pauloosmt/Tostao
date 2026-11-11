# Tostão: front-end

React 19 + TypeScript + Vite, instalável como PWA, sobre o design system do projeto (`src/design-system`). Esta é a base: cadastro,
entrada, sessão, workspace atual, tema e perfil. As telas de contas, lançamentos e demais vêm nas próximas specs.

## Rodando

```bash
npm ci                 # dependências exatas do package-lock.json
npm run dev            # http://localhost:5173
```

A API precisa estar no ar (`docker compose up -d --build`, na raiz do repositório). O servidor de desenvolvimento e o `npm run preview`
mandam `/api/*` para `http://localhost:8080`, como a hospedagem fará em produção.

A API só aceita no CORS a origem de `CORS_ORIGIN` (padrão `http://localhost:5173`). O proxy local fala com ela por essa origem, então o
app funciona também por `127.0.0.1` e na porta do `npm run preview` (4173). Se mudar o `CORS_ORIGIN`, exporte o mesmo valor ao rodar o Vite.

## Scripts

| Script | O que faz |
|---|---|
| `npm run dev` | servidor de desenvolvimento com recarga |
| `npm run build` | confere os tipos (`tsc -b`) e gera o `dist/` (com o service worker e o manifest) |
| `npm run preview` | serve o `dist/` |
| `npm test` | testes (Vitest, jsdom, Testing Library e axe-core) |
| `npm run lint` | Oxlint e a verificação das regras do projeto (abaixo) |
| `npm run test:guardrails` | testa a própria verificação das regras |
| `npm run check:budget` | o JavaScript inicial, com gzip, cabe em 150 KB (roda depois do build) |
| `npm run test:build` | confere o `dist/`: ícones, manifest, service worker, hospedagem, nenhuma origem externa |
| `npm run verify` | tudo, na ordem; é o que o CI roda |

## Estrutura

```text
src/
├── api/          cliente único da API (/api/v1), erros e chamadas de autenticação
├── auth/         sessão (AuthProvider, useAuth), RequireAuth e o redirecionamento seguro
├── workspace/    workspace atual, por pessoa, e os papéis em português
├── theme/        tema sistema, claro ou escuro
├── storage/      localStorage que nunca lança
├── validation/   regras de cadastro e entrada (a API é a autoridade)
├── layout/       AppShell: barra de baixo no celular, menu lateral a partir de 960 px
├── pages/        Entrar, Cadastro, Início, Perfil, Não encontrada
├── ui/           carregando, aviso de erro, confirmação (<dialog>), campo de senha
├── styles/       app.css (só tokens do design system)
├── design-system/  componentes, tokens e fontes do projeto
└── test/         fakeApi, axe e helpers de renderização
```

## Regras (verificadas automaticamente em `npm run lint`)

- **Só `/api/*`:** o app fala apenas com o próprio domínio. Nenhuma URL `http(s)://` externa no código, nos estilos ou no `index.html`
  (as fontes são locais, com `@fontsource`).
- **Só o cookie:** a sessão é um cookie `HttpOnly` definido pela API. Nunca se monta cabeçalho de autorização à mão, nem se guarda
  token no navegador.
- **Nada de credencial no endereço:** senha, token ou código nunca vão em query string. O lugar pedido antes de entrar fica no estado
  do roteador, e só volta-se a um caminho interno seguro (sem consulta nem fragmento).
- No navegador só se guardam o tema e o identificador do workspace atual (por pessoa).
- O service worker não guarda respostas de `/api/*`.

## Hospedagem (Vercel)

O `vercel.json` tem só o fallback das rotas (`/perfil` recarregado devolve o `index.html`), sem tocar em `/api/`. O repasse de `/api/*`
para a API em produção é configurado no painel da Vercel, para a URL da API não ficar no código.
