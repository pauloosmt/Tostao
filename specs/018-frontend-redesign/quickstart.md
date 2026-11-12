# Quickstart: Redesign do front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Entre com uma conta de teste (ou crie uma em "Criar conta").

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Entrar e criar conta**: o formulário dentro da carteira, com o fecho dourado como botão de enviar; erros nos campos; no celular (< 720 px) a versão compacta.
2. **Início**: a Carteira com o saldo total (centavos menores) e o workspace com o papel; sem conta, "Crie sua primeira conta"; abaixo, as contas.
3. **Menu**: no desktop, o botão "Novo lançamento" com a dica N, os itens e o usuário no rodapé (leva ao Perfil); no celular, a barra de baixo com o botão redondo no meio e o Perfil como item; "Categorias" por um atalho em Contas.
4. **Lançar**: pelo botão, pela tecla **N** (fora de campos de texto) e por "Novo lançamento" na lista: a folha lateral (desktop) ou de baixo (celular), com o valor em destaque, o tipo, "Hoje/Ontem", a categoria em moedas, De/Para na transferência, "Pendente"; Esc, "Fechar" e o toque no fundo fecham; salvar fecha com o aviso e a lista atrás atualiza.
5. **Lista**: o título com "N lançamentos", as moedas de categoria, o tostão dourado em "Pendente", "Filtros" abrindo uma folha (aplicar, limpar, fechar).
6. **Editar**: tocar numa linha abre a folha preenchida; Efetivar (no pendente) e Excluir (com confirmação) no fim da folha; o leitor vê só os detalhes.
7. **Contas, categorias e perfil**: cabeçalhos, moedas nas categorias, avatar no perfil.
8. **Teclado e leitor de tela**: Tab pela folha (o foco fica preso nela), Esc fecha e o foco volta ao botão que abriu; 320 px e zoom de 200% sem rolagem horizontal; tema claro, escuro e do sistema.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **827 testes em 58 arquivos** (eram 890: os testes de lançamento viraram um arquivo enxuto, e saíram os que só descreviam o visual antigo), `tsc` e build ok, JavaScript inicial de **103,5 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Decisões e achados

- O design system novo importava as fontes do Google Fonts, o que a constituição proíbe (o guard-rail barrava): voltou ao `@fontsource`.
- A linha de lançamento com ação voltou a ser um `<button>` (o `div` clicável não é alcançável por teclado) e manteve os selos de parcela, recorrência e importação.
- O `close` do diálogo de confirmação (excluir) subia pelo React e fechava a folha inteira; a folha agora só fecha com o `close` dela.
- As telas de entrar e criar conta ganharam `noValidate` no portão da carteira: a validação é a do aplicativo, não o balão do navegador.
- O Início usa o saldo total das contas ativas; o resumo do mês ("Sobram R$ …") e o seletor de mês ficam para o dashboard (próxima spec).

## 4. Limpeza

Esta spec não criou dados fora dos testes automatizados.
