# Feature Specification: Fechar a carteira

**Feature Branch**: `025-fechar-carteira`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "execute essa alteração" (o pacote colado pelo usuário: o botão "Fechar carteira" no Início, com a capa que desce sobre o saldo, o fecho que passa a dizer "Carteira fechada", a escolha lembrada neste navegador e a leitura de tela sem o saldo).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Esconder o saldo com um toque (Priority: P1)

**Independent Test**: no Início, "Fechar carteira" esconde o saldo e a frase do fecho; "Abrir carteira" desfaz; a escolha vale ao recarregar.

**Acceptance Scenarios**:

1. **Given** o Início com contas, **When** a pessoa toca em "Fechar carteira", **Then** uma capa com costura tracejada desce sobre o saldo (0,45 s), a aba de trás some, o fecho dourado diz "Carteira fechada" e no lugar do saldo aparece "R$ ••••" com "Saldo oculto"; o botão vira "Abrir carteira".
2. **Given** a carteira fechada, **When** a pessoa toca em "Abrir carteira", **Then** tudo volta como estava.
3. **Given** a carteira fechada, **When** a página é recarregada ou o Início é aberto de novo, **Then** continua fechada (e, se foi aberta, continua aberta); a escolha fica só neste navegador e guarda apenas "1" ou nada, nunca um valor financeiro; sem armazenamento disponível (aba anônima, bloqueio), vale só para a visita e nada quebra.
4. **Given** a carteira fechada, **When** um leitor de tela lê a tela, **Then** o saldo e a frase do fecho não são lidos (ficam escondidos da leitura); "Carteira fechada" e "Saldo oculto" são lidos no lugar; o botão informa o estado (`aria-pressed`).
5. **Given** "reduzir movimento" ligado no sistema, **Then** a troca é imediata, sem animação.
6. **Given** um workspace sem contas, **Then** o botão não aparece (não há saldo a esconder).

### Edge Cases

- Só a Carteira é escondida: os cartões de Receitas, Despesas e Resultado, as barras e as listas continuam mostrando valores (um "modo discreto" para a tela inteira seria outra mudança).
- Funciona no tema claro e no escuro, no desktop e no celular (o formato empilhado).

## Requirements *(mandatory)*

- **FR-001**: O `WalletHero` MUST aceitar `closed` e `onToggle` (sem `onToggle`, nenhum botão; sem `closed`, tudo como antes) e os ícones `lock` e `unlock`.
- **FR-002**: O hook `useWalletClosed` MUST ler e gravar `tostao:carteira-fechada` (`"1"` ou ausente) com tolerância a armazenamento indisponível.
- **FR-003**: O Início MUST ligar o hook à Carteira quando há contas.
- **FR-004**: A leitura de tela MUST ignorar o saldo e a frase do fecho enquanto fechada; sem dependência nova; nenhuma mudança no back-end.

## Success Criteria

- **SC-001**: As conferências do pacote passam (testes) e a conferência visual fica com quem usa (claro e escuro, desktop e celular).
