# Specification Quality Checklist: Importação de extratos

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
  1. **Duas etapas sem estado no servidor**: prévia (não grava) e confirmação (grava). A prévia não é guardada, o que
     evita uma tabela de rascunhos; a confirmação confere tudo de novo e a chave de importação torna repetir inofensivo.
  2. **A chave de importação é por conta e inclui lançamentos excluídos**: o mesmo critério da spec 011 (ocorrência
     excluída não volta). Importar de novo nunca desfaz uma exclusão feita pela pessoa.
  3. **Formatos**: OFX (o que os bancos exportam, com identificador próprio) e um CSV genérico reconhecido pelo nome
     das colunas. Layouts específicos de banco ficam fora.
  4. **Linhas ruins não derrubam o arquivo na prévia**, mas qualquer linha ruim derruba a **confirmação** inteira
     (atomicidade): o que a pessoa confirma é o que entra.
  5. **Tudo entra efetivado**, só receita e despesa, e a categoria pode faltar: o banco já aceita receita e despesa
     sem categoria desde a spec 004 e as specs 005 a 008 já tratam o grupo "Sem categoria". Para achá-las depois, a
     listagem ganha o filtro `uncategorized`.
  6. **Possível duplicata** (mesma data e valor de um lançamento digitado à mão) é só aviso; o sistema não decide pela
     pessoa.
  7. **O arquivo é só dado**: limites de 2 MB e 1.000 movimentos, sem resolver entidades externas, sem registrar
     conteúdo em log.
- Fora de escopo, por escolha: desfazer em lote, transferências entre contas importadas, conciliação de saldo, layouts
  de banco específicos, importação de pendentes.
- Pontos a decidir no `/speckit-plan`: como ler OFX 1 (SGML) e 2 (XML) com segurança, a leitura de CSV e de
  codificação, a coluna e o índice único da chave em `transactions`, a rota (multipart na prévia, JSON na
  confirmação), o filtro `uncategorized` na listagem e o desempenho da sugestão de categoria.
- Pronta para `/speckit-clarify` (opcional) ou `/speckit-plan`.
