# Specification Quality Checklist: Lançamentos no front-end

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
  1. **O estado pendente/efetivado entra nesta spec** (API da 009): `status` é parte do cadastro e da edição, e "Efetivar" é uma ação da linha.
     Fica de fora o "efetivar sozinho" (`autoClear`), que pertence às recorrências.
  2. **Parcelas, recorrências e importados só aparecem** (com selo): a criação deles tem tela própria nas specs seguintes; a edição aceita o
     que a API aceitar e mostra a recusa como vier.
  3. **A lista mostra as duas pontas de uma transferência** como a API devolve (uma por conta); o formulário resolve origem e destino pela
     direção do valor e pela conta da outra ponta, para qualquer ponta aberta.
  4. **Os filtros ficam em memória da sessão, por workspace**: nem no endereço (a busca por texto não deve ir para o histórico do navegador),
     nem no armazenamento do navegador (a constituição só permite tema e workspace atual).
  5. **Páginas com botões, não rolagem infinita**: casa com a paginação da API e com a acessibilidade (foco e leitor de tela).
  6. **O valor é sempre positivo na digitação**: o sinal vem do tipo, como na API; reaproveita o campo de dinheiro da 016.
  7. **Fora de escopo**: resumo e totais por período, compra parcelada, recorrências, importação, anexos, etiquetas, lote.
- Pontos a decidir no `/speckit-plan`: onde vivem os filtros (contexto), o modelo da página de resultados, a escolha de conta e categoria
  (listas carregadas uma vez por tela), o agrupamento por dia, a resolução de origem e destino da transferência, o campo de data e o de texto
  longo (o design system não tem), e o comportamento de página inexistente.
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
