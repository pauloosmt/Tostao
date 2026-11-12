---

description: "Tarefas da feature 019-frontend-dashboard"
---

# Tasks: Dashboard do mês no Início

**Input**: [plan.md](plan.md), [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda

---

- [X] T001 API e meses: `api/dashboard.ts`, tipos, `dates/months.ts` e o `homeRoutes` dos testes servindo o dashboard (`SRC/api`, `SRC/dates`, `SRC/test`)
- [X] T002 O Início com o dashboard: seletor de mês, Carteira com o fecho, totais e variação, pendentes, categorias e contas (`SRC/pages/HomePage.tsx`, `SRC/home/*`)
- [X] T003 Verificação completa, roteiro real contra a API (números batem) e registro no `quickstart.md`
