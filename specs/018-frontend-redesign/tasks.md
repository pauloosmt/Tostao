---

description: "Tarefas da feature 018-frontend-redesign"
---

# Tasks: Redesign do front-end

**Input**: [plan.md](plan.md), [spec.md](spec.md)

**Tests**: poucos e de valor (pedido do usuário em 2026-11-12). Ajustar ou apagar os testes que só descreviam o visual antigo; manter as provas de regra.

## Format: `[ID] Descrição com caminho` (`SRC` = `frontend/src`)

- **Commits**: pequenos (uns 6 a 8 arquivos, uma ideia), Conventional Commits, mensagem curta em inglês, **sem co-author**; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- **Verificação**: durante o trabalho, `npx vitest run <pasta>`, `npx tsc -b` e `npm run lint`; o `npm run verify` completo (em segundo plano, conferindo o código de saída) antes do commit que fecha a spec. Sem `./mvnw verify` (back-end intacto)
- **Guard-rail**: nenhuma origem externa, nenhum cabeçalho de autorização à mão; sem dependência nova; README não muda

---

- [X] T001 Design system: fontes pelo `@fontsource`, `TransactionRow` como botão com `tags`, tipo desconhecido de conta; testes de Nav, Segmented, AccountCard e dos componentes novos (`design-system/src/**`)
- [X] T002 Entrar e criar conta com `WalletGate` (`SRC/pages/LoginPage.tsx`, `RegisterPage.tsx`, estilos e testes ajustados)
- [X] T003 Moldura: navegação com lançar e usuário, `PageHeader` em cada tela, atalho para Categorias em Contas (`SRC/layout/AppShell.tsx`, páginas, `styles/app.css`)
- [X] T004 Início com a Carteira: saldo total, estado vazio, contas (`SRC/pages/HomePage.tsx`)
- [X] T005 Folha de lançar: contexto `useLaunch`, `LaunchSheet` (dialog + Sheet), tecla N, formulário com `AmountInput`, `OptionTile`, `CoinPicker`; sai a rota `/lancamentos/novo` (`SRC/transactions/*`, `layout/AppShell.tsx`)
- [X] T006 Editar, efetivar e excluir na folha; leitor vê os detalhes (`SRC/transactions/*`)
- [X] T007 Lista de lançamentos: cabeçalho, moedas, tostão, filtros numa folha (`SRC/transactions/TransactionsPage.tsx`, `TransactionFilters.tsx`)
- [X] T008 Contas, categorias e perfil com cabeçalho, moedas e avatar (`SRC/accounts/*`, `categories/*`, `pages/ProfilePage.tsx`)
- [ ] T009 Verificação completa (`npm run verify`), orçamento, roteiro no navegador e registro no `quickstart.md`; marcar o que falta
