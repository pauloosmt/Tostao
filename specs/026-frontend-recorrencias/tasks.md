---

description: "Tarefas da feature 026-frontend-recorrencias"
---

# Tasks: Recorrências no front-end

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda; scripts longos vão para arquivo (Write), sem heredoc complexo

---

- [X] T001 API, tipos, fixtures e a validação do formulário (`api/recurrences.ts`, `validation/recurrenceForm.ts`, `test/fixtures.ts`) com testes
- [X] T002 A tela `/recorrencias` (lista, estados), o item no menu, a rota e o atalho em Contas (`SRC/recurrences/*`, `App.tsx`, `AppShell.tsx`, `AccountsPage.tsx`) com testes
- [X] T003 A folha "Nova recorrência" (`SRC/recurrences/RecurrenceForm.tsx`, rotas filhas) com testes
- [X] T004 A folha de detalhes com as ocorrências e "Encerrar recorrência"; "Ver recorrência" na folha do lançamento (`SRC/recurrences/*`, `transactions/TransactionFormPage.tsx`) com testes
- [X] T005 Verificação completa, roteiro real contra a API e registro no `quickstart.md`
