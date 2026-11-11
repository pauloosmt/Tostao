# Specification Quality Checklist: Transferência recorrente

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-10
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
  1. **Extensão da recorrência da 011**, não uma funcionalidade nova: mesma rota, mesmo calendário, mesmo horizonte, mesmo
     encerramento e a mesma execução automática. Isso evita duas definições de calendário e mantém a prova da 011 valendo.
  2. **Uma ocorrência é um par de pontas** (uma transferência comum da spec 004), e nenhuma operação deixa uma ponta só. A geração
     precisa ser idempotente por **par**, mesmo sob execuções simultâneas.
  3. **Totais e contagem de encerramento falam em ocorrências**, não em pontas, para os números não dobrarem; a lista do detalhe
     mostra as duas pontas, como a listagem de lançamentos.
  4. **Sem categoria** na transferência (como na spec 004) e **sem destino** em receita e despesa; cada recusa aponta o campo.
  5. **Cartão como destino** vale como pagamento da fatura (spec 008): é o uso mais comum de um pagamento periódico.
  6. A recorrência segue **sem edição** (encerra e cria outra), como na 011.
- Pontos a decidir no `/speckit-plan`: as colunas novas e relaxamentos de restrição em `recurrences` e em `transactions`, o índice único que
  hoje impede duas pontas com o mesmo número, o grupo de transferência determinístico para a geração idempotente, a substituição da função
  de geração, e onde fica a conferência das duas contas.
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
