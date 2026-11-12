# Quickstart: Recorrências no front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Tenha uma conta e uma categoria.

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Menu**: "Recorrências" no menu lateral; no celular, o atalho está na tela de Contas (ao lado de Orçamentos e Categorias).
2. **Lista**: cada recorrência com a moeda da categoria, o valor com sinal (receita +, despesa −, transferência sem sinal), "Todo mês · Próxima: dd/mm/aaaa", a conta e a categoria (ou "Para Reserva"), os selos "Efetiva sozinha" e "Encerrada"; as ativas antes das encerradas.
3. **Nova recorrência**: tipo, valor, frequência (Toda semana, Todo mês, Todo ano), "Começa em" (hoje), "Termina em (opcional)", descrição, conta (De e Para na transferência), categoria em moedas, observações e "Efetivar sozinhas na data". Salvar fecha com "Recorrência criada." e os lançamentos já aparecem na lista como "Recorrente".
4. **Erros**: valor, descrição, conta e categoria pedidos; início com mais de 366 dias; fim antes do início; recusas da API no campo certo.
5. **Detalhes**: tocar numa recorrência mostra os dados, o total, o efetivado e o pendente, e cada ocorrência com data, valor e estado em palavras.
6. **Encerrar recorrência**: pede confirmação, exclui as pendentes futuras ("Recorrência encerrada. N ocorrências canceladas."); a encerrada e o leitor não têm o botão.
7. **Do lançamento**: num lançamento que nasceu de uma recorrência, "Ver recorrência" leva aos detalhes.
8. Celular (360 px), tema claro e escuro.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **861 testes em 66 arquivos**, `tsc` e build ok, JavaScript inicial de **118,1 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose; script fora do repositório)

**23 de 23** conferências: criar despesa mensal com "efetivar sozinhas" (campos que o front lê, valor positivo, sem destino); o detalhe traz as ocorrências (vinculadas à recorrência, com estado) e os totais; ocorrências de despesa negativas e de receita positivas; receita semanal com fim (ocorrências de 7 em 7 dias, fim devolvido); transferência anual (destino, sem categoria); o início de até **366 dias atrás** é aceito e 370 dias é `400` (a regra do front); fim antes do início, transferência para a mesma conta, despesa sem categoria e frequência inválida são recusadas (`400`, no campo `categoryId` quando é a categoria); a lista traz as recorrências sem as ocorrências e com a próxima data; encerrar cancela as pendentes futuras, a recorrência fica encerrada e encerrar de novo cancela zero; inexistente `404` e sem cookie `401`. A limpeza removeu só os dados `qs26*`.

**Achados:** o número da ocorrência começa em **0** (o front não o usa); depois de encerrar, a ocorrência pendente **de hoje** continua (só as futuras saem), por isso a lista mostra "Encerrada" em vez da próxima data.

## 4. Fora desta spec

Editar uma recorrência (a API não tem edição: encerra-se e cria-se outra) e o agendamento que efetiva sozinhas (é do servidor, spec 011).
