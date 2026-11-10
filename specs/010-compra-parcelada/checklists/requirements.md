# Specification Quality Checklist: Compra parcelada

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
  1. A compra parcelada **não é um registro novo**: é um conjunto de despesas comuns (spec 004) ligadas por um
     grupo. Cada parcela é editável, efetivável e excluível como qualquer lançamento.
  2. Divisão em centavos exatos: o resto vai, um centavo por parcela, para as primeiras; a soma é sempre o total.
  3. Primeira parcela efetivada (ou pendente se pedido), seguintes sempre pendentes, pela regra da spec 009.
  4. Datas: primeira data mais N-1 meses, sempre a partir da primeira, com o dia limitado ao fim do mês.
  5. De 2 a 48 parcelas; descrição de até 190 caracteres para o sufixo " (N/T)" caber nos 200 do lançamento.
  6. Cancelar o plano exclui só as parcelas pendentes e mantém as efetivadas.
  7. Efetivação automática por data e recorrências ficam para a spec 011.
- Dependência assumida: no cartão, só parcela efetivada entra na fatura (consequência da regra única da 009).
- Pontos a decidir no `/speckit-plan`: nomes das colunas novas em `transactions` e suas restrições, forma da rota
  de cancelar, e onde fica o código (serviço próprio ou dentro de `TransactionService`).
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
