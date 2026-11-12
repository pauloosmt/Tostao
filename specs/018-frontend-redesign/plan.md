# Implementation Plan: Redesign do front-end ("a carteira aberta")

**Branch**: `018-frontend-redesign` | **Date**: 2026-11-12 | **Spec**: [spec.md](spec.md)

## Summary

Refazer as telas das specs 015 a 017 com os componentes novos do design system, sem mudar API, regras ou textos de negócio. Só front-end, sem dependência nova.

## Decisões

- **Design system**: o que o usuário escreveu vale, com três correções que a constituição e a acessibilidade pedem: fontes pelo `@fontsource` (nada de Google Fonts), `TransactionRow` com ação continua um `<button>` e mantém `tags`, e o tipo de conta desconhecido continua "Conta". A pílula deslizante do menu e do segmentado saiu no redesign (testes dela trocados por testes de comportamento).
- **Folha de lançar** (`transactions/LaunchSheet`): um `<dialog>` modal (foco preso, Esc, fundo inerte; o mesmo mecanismo do `ConfirmDialog`) com o `Sheet` do design system: lateral no desktop, `tx-sheet--bottom` abaixo de 720 px. Hospedada no `AppShell`, aberta por um contexto (`useLaunch`) pelo botão da navegação, pela tecla N e por "Novo lançamento"; a rota `/lancamentos/novo` deixa de existir. A edição abre a mesma folha por `/lancamentos/:id` (a lista fica atrás).
- **Formulário de lançamento** continua um só (`TransactionForm`) com as regras de `validation/transactionForm.ts`; muda a apresentação: `AmountInput` (valor em destaque), `OptionTile` para tipo e atalhos de data, `CoinPicker` para a categoria. O botão principal fica no rodapé da folha (atributo `form`).
- **Filtros** abrem numa folha (o mesmo `<dialog>`), com o contexto de filtros da 017 intacto.
- **Início**: `WalletHero` com o saldo total das contas ativas (soma da lista de contas); a API de dashboard fica para a spec seguinte.
- **Navegação**: Início, Lançamentos, Contas, Categorias no menu lateral (Perfil pelo rodapé); na barra de baixo, Início, Lançamentos, Lançar, Contas, Perfil; Categorias por um atalho em Contas.
- **Testes**: poucos e de valor, conforme o pedido do usuário: cada tela tem os fluxos principais; saem as variações repetidas, o que for só aparência e os axe redundantes; ficam as provas de regra (centavos, papéis, outro workspace).

## Technical Context

**Language/Version**: TypeScript 7, React 19, Vite 8, `react-router` 8 (da 015)
**Testing**: Vitest + Testing Library + axe nas telas principais; roteiro no navegador.
**Constraints**: só `/api/*`; só o cookie; dinheiro em centavos; datas sem fuso; tudo em português; JavaScript inicial ≤ 150 KB (hoje 100,5 KB).
