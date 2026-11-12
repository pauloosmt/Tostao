# Quickstart: Dashboard do mês no Início

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Entre, crie uma conta e alguns lançamentos (receita, despesa, uma pendente).

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Início**: a Carteira com o saldo de hoje e o fecho dourado ("Sobram R$ … em novembro" ou "Faltam R$ …"); abaixo, Receitas, Despesas e Resultado com "N% a mais/menos que em outubro" (some quando o mês anterior é zero).
2. **Mês**: as setas do seletor trocam o mês; os números mudam e nunca ficam os do mês anterior (aparece "Carregando o resumo do mês…").
3. **Pendentes**: a linha com o tostão dourado ("2 pendentes", a pagar e a receber) e "Ver pendentes", que abre Lançamentos já filtrada por pendente. O pendente não entra nos totais.
4. **Gastos por categoria**: barras com valor e percentual; "Sem categoria" quando houver; sem despesa, "Nenhuma despesa neste mês."
5. **Contas**: o saldo de cada uma; sem contas, "Crie sua primeira conta".
6. **Celular (360 px)**: a Carteira empilhada; tema claro e escuro.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **832 testes em 59 arquivos**, `tsc` e build ok, JavaScript inicial de **104,8 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose; script fora do repositório)

**17 de 17** conferências: o formato do painel é o que o front lê (período `MONTH`, percentual numérico, `categoryId` nulo no "Sem categoria", conta com nome, tipo e saldo); receita, despesa, resultado e saldo batem com os lançamentos; o pendente fica à parte (valor e quantidade) e fora dos totais; a soma dos grupos de categoria é a despesa; a comparação traz o mês anterior e o percentual nulo quando ele é zero; o mês anterior vazio zera os totais e o saldo de hoje não muda; mês inválido é `400` e sem cookie é `401`. A limpeza removeu só os dados `qs19*`.

**Achado:** a API recusa despesa sem categoria (como já se sabia desde a spec 017), então "Sem categoria" só aparece se vier de outra origem (importação); a tela trata o caso.

## 4. Fora desta spec

A semana ISO (a API também serve `week=`) entra com os orçamentos semanais; os gráficos de saldo por dia ficam para depois.
