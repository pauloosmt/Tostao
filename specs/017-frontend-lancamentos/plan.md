# Implementation Plan: Lançamentos no front-end

**Branch**: `017-frontend-lancamentos` | **Date**: 2026-11-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/017-frontend-lancamentos/spec.md`

## Summary

A tela central do Tostão: a lista de lançamentos (do mais recente ao mais antigo, agrupada por dia, paginada, com filtros), o registro de receita, despesa e transferência, o estado
pendente/efetivado, e editar, efetivar e excluir. Usa a API das specs 004 e 009 sem mudá-la, a base da 015 e as peças da 016.

Abordagem técnica (decisões em [research.md](research.md)):

- **Filtros e página em um contexto em memória, por workspace** (derivado, sem efeito), que sobrevive à ida ao formulário e à volta; nada vai ao endereço nem ao armazenamento do navegador.
- **Consulta por chave** (`useResource`): workspace + página + filtros; a resposta atrasada é descartada. Os nomes de contas e categorias (com as arquivadas) vêm de um hook de referência.
- **Lista com o `TransactionRow` do design system**, agrupada por dia; `TransactionRow` ganha `tags` (selos extras) de forma aditiva.
- **Formulário único** para receita, despesa e transferência, com a troca de tipo descartando o que não vale; a transferência é aberta pela origem e destino reais, qualquer que seja a ponta.
- **Pendente e efetivar** pela caixa "Pendente" e pela ação "Efetivar" da página do lançamento; **excluir** com confirmação.
- **Peças novas pequenas**: `TextAreaField`, `Pager`, funções puras de data, validação, agrupamento e transferência.
- **Escopo**: somente front-end; nenhuma mudança na API; sem dependência nova.

## Technical Context

**Language/Version**: TypeScript 7, React 19, Vite 8, `react-router` 8 (da 015)

**Primary Dependencies (novas)**: nenhuma.

**Storage**: nenhum no servidor; nada novo no aparelho.

**Testing**: Vitest + Testing Library + `axe-core` com o aplicativo inteiro (`renderApp`) e uma API de mentira com memória para lançamentos; testes puros de data, validação, agrupamento e transferência; roteiro HTTP contra o Docker Compose.

**Target Platform**: navegadores modernos (celular primeiro), PWA; hospedagem Vercel.

**Project Type**: front-end (SPA/PWA) no monorepo `frontend/`.

**Performance Goals**: JavaScript inicial dentro dos 150 KB comprimidos (hoje 94,0 KB); a lista de 20 itens por página rende e rola sem travar.

**Constraints**: só `/api/*`; só o cookie; nada de credencial no endereço; dinheiro em centavos inteiros; datas como `AAAA-MM-DD` sem fuso; nenhuma ação destrutiva sem confirmação; sem dependência nova; tudo em português do Brasil.

**Scale/Scope**: 3 telas (lista, formulário, detalhe), 1 contexto, 1 hook de referência, 3 peças de interface, módulos puros de data, validação e transferência.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | O papel só decide o que mostrar; toda recusa vem da API (`403` tratado); nenhum segredo novo | OK |
| II. Isolamento em duas camadas | Toda chamada vai ao workspace atual; filtros e lista são zerados ao trocar de workspace; a resposta atrasada é descartada; endereço de outro workspace vira "não encontrado" | OK |
| III. Autenticação e rede | Só `/api/*` do próprio domínio, só o cookie; os filtros não vão ao endereço (a busca não fica no histórico); nada no armazenamento do navegador | OK |
| IV. Entrada validada, saída controlada | Validação no navegador espelha a API (a API é a autoridade); erros de campo vão ao campo; descrições vêm da API e o React as escapa | OK |
| V. Dados financeiros confiáveis | Centavos inteiros em todo o caminho (corpo positivo, resposta com sinal); campo de dinheiro exato da 016; datas sem fuso (nunca `toISOString` nem `new Date('AAAA-MM-DD')`); a transferência é tratada como um todo | OK |
| VI. Sem tentativa e erro em transação | Não se aplica ao front; o envio duplicado é impedido por um guarda síncrono | OK |
| VII. Simplicidade | Contexto em vez de biblioteca de estado ou endereço; `<input type=date>` e `<textarea>` nativos; botões de página em vez de rolagem infinita; sem dependência nova | OK |
| VIII. Prova, não promessa | Testes de cada fluxo, erro, papel, filtro, página e troca de workspace; testes puros dos limites de data e valor; axe nas telas; roteiro real contra a API; o que só o navegador mostra é dito no quickstart | OK |

**Ponto de atenção (alcance)**: uma mudança aditiva no design system (`TransactionRow.tags`), com teste, em commit próprio; `fakeApi` ganha uma API de mentira de lançamentos (só em testes).

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/017-frontend-lancamentos/
├── spec.md  plan.md  research.md  data-model.md  quickstart.md  tasks.md
├── contracts/ui-contract.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
frontend/src/
├── api/
│   ├── types.ts                       # + Transaction, TransactionPage, Create/UpdateTransaction
│   └── transactions.ts                # NOVO: listar (com filtros), ler, criar, editar, efetivar, excluir
├── dates/
│   └── dates.ts                       # NOVO: todayISO, validação de data, formatDay (puro)
├── transactions/
│   ├── filters.tsx / useFilters.ts    # NOVO: contexto dos filtros e da página, por workspace
│   ├── reference.ts                   # NOVO: useReferenceData (contas e categorias, nomes e opções)
│   ├── rows.ts                        # NOVO: linha de exibição, agrupamento por dia, selos, resolução da transferência
│   ├── Pager.tsx                      # NOVO
│   ├── TransactionFilters.tsx         # NOVO: painel de filtros
│   ├── TransactionsPage.tsx           # NOVO: lista
│   ├── TransactionForm.tsx            # NOVO: formulário único
│   ├── TransactionFormPage.tsx        # NOVO: criar, editar, consultar (leitor)
│   └── *.test.ts(x)
├── validation/transactionForm.ts      # NOVO: regras, montadores do corpo, do lançamento para o formulário
├── ui/TextAreaField.tsx               # NOVO
├── layout/AppShell.tsx                # + "Lançamentos" na navegação
├── App.tsx                            # + as 3 rotas
├── auth/RequireAuth.tsx               # + TransactionFiltersProvider
├── design-system/src/components/TransactionRow.tsx   # + tags
└── test/fakeTransactions.ts           # NOVO: API de mentira com memória
```

**Structure Decision**: uma pasta por assunto (`transactions/`, `dates/`) como em `accounts/` e `categories/`; as peças compartilhadas seguem em `ui/`, `validation/`, `api/`.

## Complexity Tracking

Sem violações da constituição a justificar. Duas escolhas que poderiam parecer "a mais", com motivo:

| Escolha | Por quê | Alternativa mais simples recusada |
|---|---|---|
| Contexto dos filtros | Os filtros têm de sobreviver à ida ao formulário e à volta sem ir ao endereço nem ao armazenamento | Parâmetros no endereço (a busca iria ao histórico) ou estado local (perderia os filtros) |
| API de mentira com memória para lançamentos | A transferência de duas pontas, a paginação e os filtros só se provam com uma API que se comporta como a real | Respostas fixas por teste (não pegariam erro de página, filtro ou ponta de transferência) |
