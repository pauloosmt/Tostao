---

description: "Tarefas da feature 021-frontend-cartao"
---

# Tasks: Cartão de crédito e faturas no front-end

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda; scripts longos vão para arquivo (Write), sem heredoc complexo
- Nada de `<dialog>` novo sem o teste do modo estrito (a lição da spec 020); esta spec não usa folhas

---

- [X] T001 API e tipos: `api/invoices.ts`, tipos do resumo e da fatura, fixtures (`SRC/api`, `SRC/test`)
- [X] T002 A tela `/contas/:id/fatura` (resumo, fatura do mês, seletor de mês) e o atalho "Fatura" na lista de contas (`SRC/cards/*`, `SRC/App.tsx`, `SRC/accounts/AccountsPage.tsx`)
- [X] T003 Verificação completa, roteiro real contra a API e registro no `quickstart.md`
