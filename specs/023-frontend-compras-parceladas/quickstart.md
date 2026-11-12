# Quickstart: Compras parceladas no front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Tenha uma conta e uma categoria de despesa.

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Lançar**: no tipo **Despesa**, marque "Parcelar a compra": o valor vira "Valor total", a data vira "Data da primeira parcela", aparece "Número de parcelas" (2 a 48) e o resumo da divisão ("3 parcelas: 1 de R$ 333,34 e 2 de R$ 333,33. De 12/11/2026 a 12/01/2027."). Em receita e transferência a opção não existe; trocar de tipo desmarca.
2. **Salvar**: a folha fecha com "Compra parcelada em 3 vezes." e a lista mostra as parcelas, cada uma "Descrição (N/T)" com o selo "Parcela N de T"; a primeira efetivada (ou pendente, se marcado "Primeira parcela pendente") e as futuras pendentes.
3. **Erros**: parcelas fora de 2 a 48, valor menor que 1 centavo por parcela, última parcela depois de 2100; recusas da API no campo certo.
4. **Plano**: tocar numa parcela abre a folha com "Compra parcelada": total, efetivado e pendente, e cada parcela com data, valor e estado em palavras.
5. **Cancelar parcelas pendentes**: pede confirmação ("As N parcelas pendentes serão excluídas. As efetivadas continuam."), exclui só as pendentes e avisa "N parcelas canceladas."; sem pendentes a ação não aparece.
6. **Leitor**: vê o plano, nenhuma ação. Celular (360 px), tema claro e escuro.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **877 testes em 64 arquivos**, `tsc` e build ok, JavaScript inicial de **110,6 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose; script fora do repositório)

**19 de 19** conferências: criar o plano (grupo, descrição base, total de parcelas); a **divisão em centavos do front é a da API** (33.334, 33.333, 33.333); as datas (31/01, 28/02, 31/03, sem acumular o ajuste) e o sufixo " (N/T)" de cada parcela; número, total e estado de cada parcela (só a primeira efetivada); totais efetivado e pendente; despesas com sinal negativo; as parcelas na lista de lançamentos com o grupo; consultar o plano; primeira parcela pendente deixa todas pendentes; parcelas fora de 2 a 48, menos de 1 centavo por parcela, última parcela depois de 2100 e categoria de receita, todos recusados; cancelar exclui só as 2 pendentes (o total original continua 3), de novo devolve zero; plano inexistente `404` e sem cookie `401`. A limpeza removeu só os dados `qs23*`.

## 4. Fora desta spec

O marcador `autoClear` (efetivar as parcelas pendentes sozinhas na data) fica para a spec de recorrências; editar uma parcela solta continua como qualquer lançamento (a API trata cada parcela como um lançamento).
