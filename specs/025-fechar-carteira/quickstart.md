# Quickstart: Fechar a carteira

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Tenha ao menos uma conta.

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Início**: a Carteira tem o botão "Fechar carteira" no canto superior direito.
2. **Fechar**: a capa com costura tracejada desce sobre o saldo (0,45 s), a aba de trás some, o fecho dourado diz "Carteira fechada" e o saldo vira "R$ ••••" com "Saldo oculto"; o botão vira "Abrir carteira".
3. **Lembrar**: recarregar mantém como ficou (fechada ou aberta); a escolha fica só neste navegador (`tostao:carteira-fechada`, com "1" ou nada).
4. **Leitor de tela**: fechada, o saldo e a frase do fecho não são lidos; o botão informa o estado.
5. **Reduzir movimento** (sistema): a troca é imediata.
6. Temas claro e escuro, desktop e celular (a Carteira empilhada). Sem contas, o botão não aparece.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **841 testes em 64 arquivos**, `tsc` e build ok, JavaScript inicial de **115,5 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Fora desta spec

Só a Carteira é escondida: Receitas, Despesas, Resultado, as barras e as listas continuam mostrando valores. Um "modo discreto" para a tela inteira seria outra mudança, passando o mesmo `closed` aos outros componentes.
