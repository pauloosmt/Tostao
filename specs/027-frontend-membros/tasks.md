---

description: "Tarefas da feature 027-frontend-membros"
---

# Tasks: Membros e convites no front-end

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda; scripts longos vão para arquivo (Write), sem heredoc complexo

---

- [X] T001 API, tipos, fixtures e `refresh()` no contexto de sessão (`api/members.ts`, `api/invitations.ts`, `auth/AuthContext.tsx`, `test/fixtures.ts`) com testes
- [X] T002 A tela `/membros`: lista, mudar papel, remover e sair; link "Membros" no Perfil (`SRC/members/*`, `pages/ProfilePage.tsx`, `App.tsx`) com testes
- [X] T003 Convites: pendentes, "Convidar" com o código mostrado uma vez e "Copiar", revogar (`SRC/members/*`) com testes
- [ ] T004 "Entrar com um código de convite" no Perfil: aceitar, atualizar os workspaces e passar a usar o novo (`SRC/members/*`, `pages/ProfilePage.tsx`) com testes
- [ ] T005 Telas sob demanda (divisão por rota) para segurar o orçamento de 150 KB (`App.tsx`)
- [ ] T006 Verificação completa, roteiro real contra a API (duas contas) e registro no `quickstart.md`
