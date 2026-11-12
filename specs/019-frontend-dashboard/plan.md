# Implementation Plan: Dashboard do mês no Início

**Branch**: `019-frontend-dashboard` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

## Summary

O Início (spec 018) passa a ler o dashboard mensal da API (spec 005) em vez de listar contas e saldo. Só front-end, sem dependência nova.

## Decisões

- **Dados**: `api/dashboard.ts` (`getDashboard(workspaceId, month)`) e tipos em `api/types.ts`; `useResource(\`dashboard:${wid}:${month}\`)` (a chave leva o mês: trocar volta a "carregando" e descarta a resposta atrasada).
- **Mês**: puro, em `dates/months.ts` (`currentMonth`, `shiftMonth` com limites 2000-01 e 2100-12, `monthLabel` "Novembro de 2026"); estado local do Início.
- **Telas**: `HomePage` mantém o cabeçalho (saudação + `MonthSwitcher`) e delega o corpo a `home/Dashboard.tsx` (Carteira, totais, pendentes, categorias, contas), com `home/format.ts` para a frase do fecho e a variação.
- **Pendentes → lista filtrada**: usa o contexto de filtros da spec 017 (`apply({ status: 'PENDING' })`) e navega para `/lancamentos`.
- **Categorias**: `ProgressBar` (tom da marca) por categoria principal, com o percentual da API.
- **Testes**: poucos, de valor; o `homeRoutes` dos testes passa a servir o dashboard (e os testes do Início/App continuam usando-o).
