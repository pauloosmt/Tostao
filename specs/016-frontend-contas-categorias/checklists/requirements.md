# Specification Quality Checklist: Contas e categorias no front-end

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-11
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Nenhuma marcação de esclarecimento. Decisões por padrão que mais mudam o desenho:
  1. **Contas e categorias na mesma spec**: são independentes, mas compartilham a base nova (campo de dinheiro, carregamento de listas
     por workspace, papéis, formulários e confirmações) e as duas entram juntas na navegação. Os commits seguem separados por ideia.
  2. **O leitor consulta e o dono e o editor alteram** (spec 002 e 003): o aplicativo esconde as ações do leitor em vez de mostrá-las
     e esperar o 403; a API continua sendo a autoridade.
  3. **Editar uma categoria não pode apagar o ícone**: a API grava `color` e `icon` exatamente como enviados, então o formulário
     reenvia o ícone existente (achado na leitura do back-end, vira requisito FR-010).
  4. **Os campos de cartão (limite, fechamento, vencimento) são opcionais nos cartões e proibidos nos outros tipos** (back-end): o
     formulário os mostra e os envia só para cartão.
  5. **Dinheiro sempre em centavos inteiros, sem ponto flutuante**: o campo de reais converte o texto com exatidão e recusa o que
     não é claramente um valor (mais de duas casas, texto, fora do limite).
  6. **Telas por endereço** (`/contas/:id`, `/categorias/:id`): permite recarregar, voltar e abrir direto; o leitor que abre um
     formulário vê o aviso de papel.
  7. **Fora de escopo**: lançamentos e movimentos da conta, ícone e reordenação de categorias, mover subcategoria, exclusão
     definitiva, escolha de moeda, convites e membros.
- Pontos a decidir no `/speckit-plan`: o modo de carregar e descartar respostas atrasadas por workspace (sem biblioteca de dados), a
  tradução entre os tipos da API e os do design system, a paleta de cores e seus nomes acessíveis, o formato do campo de dinheiro, a
  forma de avisar o resultado (criada, salva), o desenho das confirmações e dos formulários em página e o que o back-end já
  fornece para o saldo total.
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
