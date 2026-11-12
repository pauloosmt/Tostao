# Quickstart: Cartão de crédito e faturas no front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Crie uma conta do tipo **Cartão de crédito** (com limite e os dias de fechamento e vencimento) e lance algumas despesas e um estorno nela.

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Contas**: o cartão ativo tem o atalho "Fatura" (o corrente, a poupança e o cartão arquivado não têm; o leitor também vê o atalho).
2. **Resumo**: Limite, Devido e Disponível, a barra "Uso do limite" ("Perto do limite" a partir de 80%); passando do limite, "Passou R$ … do limite"; sem limite, "Sem limite cadastrado".
3. **Fatura aberta**: o estado em palavras (Aberta, Fechada, Paga, Vencida), total, pago e restante, período, fechamento e vencimento.
4. **Lançamentos da fatura**: compra como gasto (−), estorno como crédito (+), com data e categoria.
5. **Mês**: as setas trocam o mês de vencimento, sem mostrar a fatura anterior no lugar.
6. **Cartão sem os dias**: "Faturas indisponíveis" e, para quem pode escrever, "Editar a conta".
7. **Celular (360 px)** e tema claro e escuro.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **851 testes em 62 arquivos**, `tsc` e build ok, JavaScript inicial de **108,2 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose; script fora do repositório)

**20 de 20** conferências: o resumo do cartão (limite, devido = compra menos estorno, disponível, nome, arquivado, fatura aberta com mês `AAAA-MM` e datas `AAAA-MM-DD`, total aberto igual ao devido); a fatura do mês (estado `OPEN`, total, pago e restante, duas linhas, compra positiva e estorno negativo); o mês anterior vazio; cartão sem os dias (resumo `200` com fatura aberta nula, fatura `409`); conta corrente (`409` no resumo e na fatura); conta inexistente `404`; mês inválido `400`; sem cookie `401`. A limpeza removeu só os dados `qs21*`.

## 4. Fora desta spec

As compras parceladas (spec 010) e as recorrências (011 e 014) têm specs próprias; o pagamento da fatura é uma transferência comum (o resumo já o reflete no devido).
