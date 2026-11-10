# Specification Quality Checklist: Membros e convites

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
  1. **Sem e-mail**: o sistema não tem como enviar mensagens, então o convite devolve um **código de uso único**, mostrado
     uma só vez ao dono, que o repassa. O e-mail do convite amarra o código a uma pessoa (o aceite só vale para a conta
     com esse e-mail); não é meio de entrega.
  2. **Aceite fora do workspace**: a pessoa ainda não é membro, então o aceite é a única rota sem `{wid}`
     (`/api/v1/invitations/accept`). Isso pede uma função de banco estreita (como as da V2), porque o RLS por workspace
     não alcança quem ainda não é membro.
  3. **Resposta única para código ruim**: inexistente, expirado, usado, revogado e de outro e-mail respondem igual, para
     ninguém descobrir quem foi convidado.
  4. **Um só dono**: o papel OWNER não se convida, não se muda e não sai; passar a titularidade fica fora.
  5. **Reconvidar substitui**: um convite pendente por e-mail e workspace; convidar de novo invalida o anterior (é o
     "reenviar").
  6. **Efeito imediato**: a autorização é lida do banco por requisição, então mudar papel, remover e sair valem na hora.
  7. **Limites de abuso**: 20 membros e 20 convites pendentes por workspace; validade de 7 dias.
- Verificado no código: `GET /api/v1/me` (spec 001) já devolve a pessoa e os seus workspaces, lidos do banco; os aceitos
  passam a aparecer sozinhos (FR-022), sem rota nova.
- Pontos a decidir no `/speckit-plan`: a tabela `invitations` e suas políticas (só o OWNER lê e escreve), a função de
  aceite no banco (e-mail lido da conta, expiração, limite, atomicidade), a impressão do código (hash) e a geração
  segura, as políticas e privilégios novos em `workspace_members` (alterar papel, remover, sair) e em `users` (os
  co-membros enxergam nome e e-mail), e as rotas.
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
