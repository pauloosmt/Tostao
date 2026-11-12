---

description: "Tarefas da feature 020-frontend-orcamentos"
---

# Tasks: Orçamentos mensais no front-end

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda; shell: scripts longos vão para arquivo (Write), sem heredoc complexo

---

- [X] T001 API e tipos: `api/budgets.ts`, tipos do acompanhamento, fixture `makeMonthBudget` (`SRC/api`, `SRC/test`)
- [X] T002 A tela `/orcamentos`: seletor de mês, limite geral, limites por categoria, total e fora dos limites, estados, item no menu e atalho em Contas (`SRC/budgets/*`, `SRC/App.tsx`, `SRC/layout/AppShell.tsx`, `SRC/accounts/AccountsPage.tsx`)
- [ ] T003 Definir, editar, remover e copiar limites em folhas, com os papéis (`SRC/budgets/*`)
- [ ] T004 Verificação completa, roteiro real contra a API e registro no `quickstart.md`
