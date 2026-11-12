---

description: "Tarefas da feature 022-frontend-limites-semanais"
---

# Tasks: Limites semanais no front-end

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda; scripts longos vão para arquivo (Write), sem heredoc complexo

---

- [X] T001 Semanas: `dates/weeks.ts` (semana ISO pura) com testes (`SRC/dates`)
- [ ] T002 `api/budgets.ts` servindo os dois períodos (mesmo formato `period`), a tela de Orçamentos com "Mensal | Semanal", as folhas servindo os dois e os testes do semanal (`SRC/api`, `SRC/budgets/*`, `SRC/test`)
- [ ] T003 Verificação completa, roteiro real contra a API e registro no `quickstart.md`
