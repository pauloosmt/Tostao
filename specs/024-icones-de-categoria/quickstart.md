# Quickstart: Ícones de categoria e subcategoria

Para ver no navegador: `docker compose up -d --build` (a API precisa da validação nova de `icon`) e, em `frontend/`, `npm run dev` (http://localhost:5173).

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Categorias**: cada principal com a moeda cheia (cor + ícone) e cada subcategoria como chip com a moeda vazada na cor da mãe; categorias antigas sem ícone ganham um pelo nome ("Mercado" ganha o carrinho) e, sem sugestão ("Xodó"), a inicial.
2. **Nova categoria**: pré-visualização da moeda ao vivo; ao digitar "Academia" o ícone acompanha o nome (haltere); tipo (Despesa/Receita), "Fica dentro de", 8 cores e os 39 ícones por grupo com busca; salvar grava a chave (`dumbbell`).
3. **Escolher um ícone** vale mesmo que o nome mude; "Sem ícone, usar a inicial" não envia ícone.
4. **Subcategoria** (por "Fica dentro de" ou por "Adicionar subcategoria"): moeda vazada na cor da mãe, sem escolha de cor, sem cor no pedido.
5. **Editar**: abre com nome, cor e ícone gravados; o tipo e a mãe não mudam.
6. **Lançamentos**: a linha e a escolha de categoria do lançamento mostram as moedas com o ícone; a subcategoria usa a cor da mãe.
7. **Leitor**: vê as moedas e nenhuma ação. Temas claro e escuro, desktop e celular.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **837 testes em 63 arquivos** (na rodada da 024; a carteira da 025 veio depois), `tsc` e build ok, JavaScript inicial de **115,1 KB** de 150 KB (gzip) e testes do build ok.
- `./mvnw -B verify` (back-end): BUILD SUCCESS, **1414 testes, 0 falhas** (os casos novos de `icon` entraram nos testes de criar e editar categoria).

## 3. Roteiro com a API real (HTTP direto no Compose; script fora do repositório)

**15 de 15** conferências: as 39 chaves do pacote são aceitas (`201`); `qualquercoisa`, `CART` (maiúsculas), vazio e emoji são recusados (`400`, no campo `icon`) e nenhuma categoria é criada por eles; a principal grava e devolve cor e ícone; a subcategoria grava ícone próprio, **sem cor** e com o tipo herdado; sem ícone fica nulo; editar com ícone válido `200`, inválido `400`, e sem ícone limpa; a árvore traz o ícone e a cor nula da subcategoria. A limpeza removeu só os dados `qs24*`.

## 4. Decisões

- O DTO de lançamento **não** mudou: a moeda da linha resolve ícone e cor pela lista de categorias que o front já carrega.
- O formulário de categoria virou uma **folha** (rotas filhas da lista), como o de lançamento; os testes dos dois formulários antigos viraram um só.
- `ColorPicker` e o CSS dele saíram (o `SwatchPicker` o substitui); categorias de cor fora das 8 da casa mantêm a cor até a pessoa escolher outra.
