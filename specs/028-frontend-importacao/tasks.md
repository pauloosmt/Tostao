---

description: "Tarefas da feature 028-frontend-importacao"
---

# Tasks: Importação de extratos no front-end

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar, com o comando encadeado só depois de conferir**) fecha a spec. Sem `./mvnw verify` (back-end intacto)
- Poucos testes de valor; README não muda; scripts longos vão para arquivo (Write), sem heredoc complexo
- A tela carrega sob demanda (como as outras menos usadas)

---

- [X] T001 O cliente de API com corpo `FormData`; `api/imports.ts`, tipos e fixtures; a regra do arquivo (tamanho e vazio) (`api/client.ts`, `api/imports.ts`, `validation/importFile.ts`, `test/fixtures.ts`) com testes
- [X] T002 A rota `/contas/:id/importar`: arquivo, "Ler extrato", erros; o atalho "Importar extrato" na lista de contas (`SRC/imports/*`, `App.tsx`, `accounts/AccountsPage.tsx`) com testes
- [X] T003 A prévia (resumo, linhas, seleção, categoria por linha) e a confirmação com o aviso na lista de lançamentos (`SRC/imports/*`) com testes
- [X] T004 Verificação completa, roteiro real contra a API (um CSV e um OFX) e registro no `quickstart.md`
