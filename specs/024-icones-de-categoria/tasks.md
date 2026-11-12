---

description: "Tarefas da feature 024-icones-de-categoria"
---

# Tasks: Ícones de categoria e subcategoria

**Input**: [spec.md](spec.md)

## Regras de trabalho

- Commits pequenos, Conventional Commits em inglês, **sem co-author**, código junto com os testes; a marca `[X]` vai no commit que fecha a tarefa; nunca fazer push
- Durante o trabalho: `npx tsc -b`, `npm run lint` e `npx vitest run <pasta>`; o `npm run verify` completo (em segundo plano, **lendo o `EXIT=` antes de commitar**) fecha a spec. Esta spec toca o back-end: rodar o `./mvnw -B verify` (em segundo plano, `JAVA_HOME` do Temurin 21) antes de fechar
- Poucos testes de valor; README não muda; scripts longos vão para arquivo (Write), sem heredoc complexo

---

- [X] T001 Design system: `categoryIcons`, `IconPicker`, `SwatchPicker`, `CategoryCoin` novo, `categoryIcon` na linha e `icon` no seletor de moedas, CSS e testes (`SRC/design-system/src/**`)
- [X] T002 Back-end: `icon` só nulo ou uma das 39 chaves (criar e editar categoria) com os testes (`backend/.../category`)
- [X] T003 Moedas com ícone nas telas: referência de ícone e cor da mãe, linha de lançamento, escolha de categoria do lançamento e lista de categorias (moeda cheia e chips de subcategoria) (`SRC/transactions/*`, `SRC/categories/CategoriesPage.tsx`)
- [X] T004 O formulário de categoria com pré-visualização, tipo, "Fica dentro de", cores e ícones, criando e editando (`SRC/categories/*`)
- [X] T005 Verificação completa (front e back-end), roteiro real contra a API e registro no `quickstart.md`
