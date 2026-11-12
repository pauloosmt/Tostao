---

description: "Tarefas da feature 025-fechar-carteira"
---

# Tasks: Fechar a carteira

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- `npx tsc -b`, `npm run lint` e testes focados durante o trabalho; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Back-end intacto

---

- [X] T001 `WalletHero` com `closed` e `onToggle`, ícones `lock` e `unlock`, CSS da capa, o hook `useWalletClosed` e a ligação no Início, com testes (`SRC/design-system/src/**`, `SRC/hooks`, `SRC/home/Dashboard.tsx`)
- [X] T002 Verificação completa e registro no `quickstart.md`
