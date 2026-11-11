# Tostão: front-end

React 19, TypeScript e Vite, instalável como PWA, sobre o design system do projeto (`src/design-system`).

## Rodando

```bash
npm ci
npm run dev        # http://localhost:5173 (a API precisa estar no ar)
```

O servidor de desenvolvimento e o `npm run preview` mandam `/api/*` para `http://localhost:8080`.

## Scripts

| Script | O que faz |
|---|---|
| `npm run dev` | servidor de desenvolvimento |
| `npm run build` | confere os tipos e gera o `dist/` |
| `npm test` | testes (Vitest, Testing Library e axe-core) |
| `npm run lint` | Oxlint e as regras do projeto |
| `npm run verify` | tudo, na ordem (é o que o CI roda) |

## Regras

- Só `/api/*` do próprio domínio, sem nenhum recurso externo.
- Só o cookie de sessão: nada de token no navegador nem credencial no endereço.
- Dinheiro sempre em centavos inteiros.
- O papel (dono, editor, leitor) decide só o que mostrar; quem decide é a API.

As regras são verificadas automaticamente em `npm run lint`. Os detalhes de cada tela e decisão estão nas specs
(`specs/015-*` e `specs/016-*`).
