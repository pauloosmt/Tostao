# Specification Quality Checklist: Lançamento pendente

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-09
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
  1. **Regra única**: o pendente nunca conta no realizado (saldo, dashboard, orçamentos, fatura). É a decisão
     de maior alcance: mexe em consultas das specs 002, 005, 006, 007 e 008, e por isso FR-017 e SC-011 exigem
     que nada mude para quem não tem pendentes.
  2. O estado já existe no banco (`PENDING`/`CLEARED`, padrão `CLEARED`); falta a API e o privilégio de
     atualizar a coluna (migration nova), então não há dado novo a migrar.
  3. Estado manual: sem efetivação automática por data (fica para recorrências e agendamentos).
  4. Transferência: as duas pontas sempre no mesmo estado.
  5. Efetivar é idempotente e não depende de conta ou categoria ativas.
  6. Mostrar o pendente dentro de orçamentos e faturas fica para depois; aqui só o saldo previsto, o bloco do
     dashboard e o filtro da listagem mostram o previsto.
- A spec cita "duas camadas" de isolamento e papéis porque são exigências da constituição (v1.0.0); não são
  escolhas de tecnologia.
- Pontos a decidir no `/speckit-plan`: forma da rota de efetivar, como aplicar o filtro de estado nas consultas
  das specs anteriores sem duplicar regra, e o privilégio de `UPDATE (status)`.
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
