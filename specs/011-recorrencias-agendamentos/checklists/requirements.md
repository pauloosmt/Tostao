# Specification Quality Checklist: Recorrências e efetivação automática

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
  1. A efetivação automática é **opt-in** (desligada por padrão) e **de uso único**: o marcador some quando o
     lançamento é efetivado por qualquer caminho. Isso preserva a regra da spec 009 ("a data nunca efetiva sozinha")
     para tudo que não ligar o marcador, e evita que um lançamento devolvido a pendente seja efetivado de novo.
  2. A recorrência é um **registro próprio** (diferente do plano de parcelas, que é só um grupo de linhas), porque
     tem dados que não existem em nenhuma ocorrência (frequência, início, fim, estado).
  3. As ocorrências são **geradas adiante** (12 meses depois de hoje) como pendentes comuns, e a geração é
     idempotente: nunca duplica e nunca recria uma ocorrência excluída.
  4. A **regra da recorrência não é editável** nesta versão (encerra-se e cria-se outra), o que evita decidir o que
     fazer com ocorrências já editadas à mão.
  5. Encerrar exclui só as ocorrências **pendentes com data depois de hoje**; efetivadas e pendentes vencidas ficam.
  6. Início de até 366 dias no passado, para limitar quantas ocorrências nascem de uma vez (no máximo ~106 numa
     recorrência semanal).
  7. Recorrência só de receita e despesa; transferência recorrente fica para depois.
  8. A execução automática roda ao iniciar e a cada hora, sem pessoa logada, e o fuso é `America/Sao_Paulo`.
- Dependência assumida: a efetivação automática das parcelas fecha a pendência explícita da spec 010.
- Pontos a decidir no `/speckit-plan`: como a execução sem pessoa logada respeita o RLS (função estreita no banco
  versus contexto por workspace), o agendador e a trava contra execuções simultâneas, as colunas novas em
  `transactions` (marcador, recorrência de origem e número), a tabela `recurrences` e suas políticas, e as rotas.
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
