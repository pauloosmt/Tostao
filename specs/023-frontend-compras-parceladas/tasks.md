---

description: "Tarefas da feature 023-frontend-compras-parceladas"
---

# Tasks: Compras parceladas no front-end

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda; scripts longos vão para arquivo (Write), sem heredoc complexo

---

- [X] T001 Base pura e API: `api/installments.ts`, tipos, `validation/installmentForm.ts` (divisão em centavos, datas, validação, corpo do pedido), fixtures, com testes (`SRC/api`, `SRC/validation`, `SRC/test`)
- [ ] T002 A folha de lançar com "Parcelar a compra" (só em despesa nova) e o envio do plano (`SRC/transactions/TransactionForm.tsx`, `TransactionFormPage.tsx`) com testes
- [ ] T003 O plano e "Cancelar parcelas pendentes" na folha de um lançamento parcelado (`SRC/transactions/InstallmentPlan.tsx`, `TransactionFormPage.tsx`, `TransactionDetails.tsx`) com testes
- [ ] T004 Verificação completa, roteiro real contra a API e registro no `quickstart.md`
