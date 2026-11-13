---

description: "Tarefas da feature 029-app-desktop"
---

# Tasks: App desktop (Windows)

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**; código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- O front e o back-end **não mudam** (o `npm run verify` do front e o `mvnw verify` ficam como estão); o que vale aqui é `npm test` em `desktop/` (testes do servidor e da configuração com `node --test`, sem Electron) e a conferência do programa empacotado
- A pasta `desktop/` tem o próprio `package.json` e `node_modules` (ignorados pelo git, como `release/`); README não muda

---

- [X] T001 O servidor local (estáticos com o fallback do SPA, proxy de `/api` com `Origin` reescrita, cookies sem `Domain` e `Secure`) e a configuração do endereço da API, com testes (`desktop/src/server.cjs`, `config.cjs`, `desktop/test/*`)
- [X] T002 O shell Electron: janela, instância única, isolamento, navegação restrita, menu com "Configurar servidor…" e a janela de configuração (`desktop/src/main.cjs`, `preload.cjs`, `settings.html`)
- [X] T003 O empacotamento (instalador e portátil) com o front compilado dentro; gerar e conferir o programa empacotado (`desktop/package.json`, `scripts/prepare.mjs`, `.gitignore`)
- [X] T004 Registro no `quickstart.md` (como gerar, abrir, configurar o servidor) e a conferência do executável contra a API
