# Quickstart: Limites semanais no front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Registre despesas na semana e abra "Orçamentos".

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Mensal | Semanal**: o seletor tem o tamanho das opções e a pílula **desliza** até a escolhida (e o toque encolhe de leve); quem pede menos movimento não vê a animação.
2. **Semana**: o rótulo "Semana 46 · 09/11 a 15/11/2026" e as setas "Semana anterior" e "Próxima semana"; virar o ano (semana 1 que começa em dezembro, semana 53) cai na semana certa.
3. **Limites da semana**: o mesmo que o mensal: geral em destaque, por categoria, restante, "Perto do limite" e "Limite atingido", total gasto na semana e fora dos limites.
4. **Novo limite**, **Editar**, **Remover** (com confirmação) e **Copiar para as próximas semanas** (1 a 52).
5. **Independência**: voltar a "Mensal" mostra o mês; os limites mensais e semanais não se misturam.
6. **Leitor**: vê as barras e nenhuma ação, nos dois períodos.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **862 testes em 63 arquivos**, `tsc` e build ok, JavaScript inicial de **109,1 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose; script fora do repositório)

**21 de 21** conferências: a semana corrente da API é a que o front calcula (e a segunda-feira inicia a semana de 7 dias); limite geral e de categoria (`201` ao criar, `200` ao substituir); categoria de receita e semana inexistente (`2026-W54`) recusadas (`400`); o acompanhamento (total gasto, fora dos limites, `categoryId` nulo no geral, percentual numérico, estado `WITHIN` e `EXCEEDED` após reduzir o limite); copiar para 2 semanas cria os 4 limites, a segunda cópia só conta os existentes, a semana seguinte (calculada como o front) recebeu os limites e 53 semanas é `400`; os limites mensais ficam separados; remover é `204` e de novo `404`; sem cookie `401`. A limpeza removeu só os dados `qs22*`.

## 4. Achado no navegador

O seletor "Mensal | Semanal" ficou esticado na largura da tela (uma caixa grande no fundo) e sem resposta visual ao trocar: agora tem o tamanho das opções e a pílula deslizante (a que o redesign tinha tirado do `Segmented`); vale também para o seletor de tema do perfil e o de tipo das categorias.
