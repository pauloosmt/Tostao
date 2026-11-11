# Implementation Plan: Contas e categorias no front-end

**Branch**: `016-frontend-contas-categorias` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/016-frontend-contas-categorias/spec.md`

## Summary

As primeiras telas com dados financeiros: contas (lista com saldo total, criar, editar, arquivar, restaurar, cartão de crédito) e categorias
(árvore por tipo com subcategorias, criar, editar, arquivar, restaurar), sempre no workspace atual e respeitando o papel (dono e editor
alteram, leitor consulta). Usa a API das specs 002 e 003 sem mudá-la e a base da 015.

Abordagem técnica (decisões em [research.md](research.md)):

- **Carregamento** com um hook pequeno (`useResource`): a consulta tem uma chave (workspace + opções); a resposta de uma chave que ficou
  para trás é descartada; trocar de workspace nunca deixa dado do anterior na tela. Sem biblioteca de dados.
- **Telas em página** (`/contas/...`, `/categorias/...`), com aviso de resultado no estado do roteador; confirmação só para arquivar.
- **Campo de dinheiro exato**: texto em reais lido sem ponto flutuante, centavos inteiros, formatos e mensagens definidos, limites da API.
- **Papéis**: `usePermissions` e `WriteGate` escondem as ações do leitor; o `403` da API continua tratado.
- **Formulários**: validação pura espelhando a API, mapeamento único de erros da API para a tela e um `useSubmit` que concentra o guarda de
  envio duplicado (a entrada e o cadastro da 015 passam a usá-lo).
- **Peças novas de interface**: `SelectField`, `CheckboxField`, `MoneyField` e `ColorPicker` (paleta fixa de 10 cores com nome acessível).
- **Design system**: o `AccountCard` ganha um rótulo genérico para tipo desconhecido e o `ConfirmDialog`, a opção `tone`; mudanças pequenas,
  aditivas e com teste. A navegação ganha Contas e Categorias.
- **Escopo**: somente front-end; nenhuma mudança na API.

## Technical Context

**Language/Version**: TypeScript 7, React 19, Vite 8, `react-router` 8 (já na 015)

**Primary Dependencies (novas)**: nenhuma.

**Storage**: nenhum no servidor; nada novo no aparelho (só tema e workspace atual, da 015).

**Testing**: Vitest + Testing Library + `axe-core` com o aplicativo inteiro (`renderApp`) e o `fakeApi` respondendo `/me` com um papel e as rotas
do workspace; testes unitários puros do campo de dinheiro, da validação e do mapeamento de erros; `node:test` do build (orçamento); roteiro
HTTP contra o Docker Compose; os itens que só o navegador mostra ficam listados no quickstart.

**Target Platform**: navegadores modernos (celular primeiro), PWA; hospedagem Vercel (a mesma da 015).

**Project Type**: front-end (SPA/PWA) no monorepo `frontend/`.

**Performance Goals**: JavaScript inicial dentro dos 150 KB comprimidos (hoje 86,6 KB); listas de centenas de itens rolam sem travar.

**Constraints**: só `/api/*`; só o cookie; nada de credencial no endereço; valores monetários sempre em centavos inteiros; nenhuma ação
destrutiva sem confirmação; tudo em português do Brasil; sem dependência nova.

**Scale/Scope**: 7 telas (2 listas, 5 formulários), 4 componentes de interface, 3 módulos puros (dinheiro, validação, erros) e o carregamento
de dados que as próximas specs de front (lançamentos, dashboard, orçamentos) reaproveitam.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | O papel só decide o que mostrar; toda recusa vem da API (`403` tratado); nenhum segredo, token ou credencial novo | OK |
| II. Isolamento em duas camadas | Toda chamada vai ao workspace atual; a consulta que ficou para trás é descartada e a troca de workspace limpa a tela; um endereço de outro workspace vira "não encontrada", sem pista | OK |
| III. Autenticação e rede | Só `/api/*` do próprio domínio, só o cookie; nada na consulta do endereço além de `includeArchived`; guard-rails seguem valendo; nenhum recurso externo | OK |
| IV. Entrada validada, saída controlada | Validação no navegador espelha a API (a API é a autoridade); erros de campo da API vão ao campo; nomes vêm da API e o React os escapa (nada de HTML); a cor só sai da paleta fixa | OK |
| V. Dados financeiros confiáveis | Centavos inteiros em todo o caminho; o campo de reais lê texto sem ponto flutuante e recusa o ambíguo; saldo total é o do relatório da API (consistente com a lista) | OK |
| VI. Sem tentativa e erro em transação | Não se aplica ao front; o envio duplicado é impedido por um guarda síncrono | OK |
| VII. Simplicidade | Hook de 30 linhas em vez de biblioteca de dados; `<select>` e `<input type=checkbox>` nativos; formulários em página em vez de janelas; sem dependência nova; o guarda de envio é extraído só agora, porque a repetição chegou a seis | OK |
| VIII. Prova, não promessa | Testes de cada fluxo, erro, papel e troca de workspace; testes puros dos limites do dinheiro; axe nas telas; roteiro real contra a API; o que só o navegador mostra é dito no quickstart | OK |

**Ponto de atenção (alcance)**: três mudanças em arquivos que já existem e não são desta spec: o `AccountCard` e o `ConfirmDialog` (aditivas, com teste) e as
páginas de entrada e cadastro (trocam o guarda local pelo `useSubmit`). Mitigação: cada uma em commit próprio, sem mudar comportamento, com os testes
existentes passando sem alteração.

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/016-frontend-contas-categorias/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/ui-contract.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
frontend/src/
├── api/
│   ├── types.ts                     # + Account, BalanceReport, Category, CategoryTree e os pedidos
│   ├── accounts.ts                  # NOVO: listar, ler, criar, editar, arquivar, restaurar, saldo
│   └── categories.ts                # NOVO
├── money/
│   ├── reais.ts                     # NOVO: parseReais / formatReaisInput (puro)
│   └── reais.test.ts
├── data/
│   ├── useResource.ts               # NOVO: carregamento por chave, descarte do atrasado
│   └── useResource.test.tsx
├── forms/
│   ├── useSubmit.ts                 # NOVO: guarda de envio duplicado
│   ├── apiErrors.ts                 # NOVO: erro da API -> mensagens por campo / aviso geral
│   └── *.test.ts(x)
├── permissions/
│   ├── usePermissions.ts            # NOVO: canWrite pelo papel do workspace atual
│   ├── WriteGate.tsx                # NOVO: aviso de papel para o leitor
│   └── *.test.tsx
├── accounts/
│   ├── accountTypes.ts              # NOVO: API <-> design system, rótulos
│   ├── AccountsPage.tsx             # NOVO: lista, total, arquivadas, ações
│   ├── AccountFormPage.tsx          # NOVO: criar e editar
│   └── *.test.tsx
├── categories/
│   ├── palette.ts                   # NOVO: as 10 cores e seus nomes
│   ├── CategoriesPage.tsx           # NOVO: árvore por tipo, arquivadas, ações
│   ├── CategoryFormPage.tsx         # NOVO: criar principal, criar subcategoria, editar
│   └── *.test.tsx
├── validation/
│   ├── accountForm.ts               # NOVO
│   └── categoryForm.ts              # NOVO
├── ui/
│   ├── SelectField.tsx  CheckboxField.tsx  MoneyField.tsx  ColorPicker.tsx   # NOVOS
│   └── ConfirmDialog.tsx            # + tone
├── layout/AppShell.tsx              # + Contas e Categorias na navegação
├── pages/LoginPage.tsx  RegisterPage.tsx   # passam a usar useSubmit
├── App.tsx                          # + as 7 rotas
├── design-system/src/components/AccountCard.tsx   # + rótulo genérico
└── test/fakeApi.ts                  # + ajudante do prefixo do workspace
```

**Structure Decision**: uma pasta por assunto (`accounts/`, `categories/`) com as telas e os testes juntos, e as peças compartilhadas
(`money/`, `data/`, `forms/`, `permissions/`, `ui/`) em pastas próprias, seguindo a divisão da 015.

## Complexity Tracking

Sem violações da constituição a justificar. Duas escolhas que poderiam parecer "a mais", com motivo:

| Escolha | Por quê | Alternativa mais simples recusada |
|---|---|---|
| Hook `useResource` próprio | O descarte por chave cobre a troca de workspace (princípio II) em 30 linhas testáveis | Biblioteca de dados com cache (dependência e risco de cache entre workspaces) |
| `useSubmit` extraído | Seis ações repetiriam o mesmo guarda e o mesmo mapeamento de erros | Copiar o guarda em cada formulário (divergência) |
