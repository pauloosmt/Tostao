# Quickstart: Orçamentos mensais no front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Registre algumas despesas no mês e abra "Orçamentos" (no menu lateral; no celular, o atalho está na tela de Contas).

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Novo limite**: escolha "Geral" ou uma categoria de despesa, digite o valor (ex.: 800) e salve; a barra aparece com "R$ gasto de R$ limite" e "Restam R$ …".
2. **Estados em palavras**: a partir de 80% a barra mostra "Perto do limite" e a partir de 100% "Limite atingido", com "Passou R$ … do limite".
3. **Editar** (só o valor) e **Remover** (com confirmação); o gasto continua contado no "Total gasto no mês".
4. **Mês**: as setas trocam o mês, sem mostrar o anterior no lugar.
5. **Copiar para os próximos meses**: 1 a 24; o aviso conta criados, já existentes e ignorados por categoria arquivada.
6. **Leitor**: vê as barras e nenhum botão de ação. **Celular (360 px)**: as barras e as folhas de baixo; tema claro e escuro.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **843 testes em 60 arquivos**, `tsc` e build ok, JavaScript inicial de **107,0 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose; script fora do repositório)

**24 de 24** conferências: limite geral e de categoria (`201` ao criar, `200` ao substituir); categoria de receita e valor negativo recusados (`400`); o acompanhamento tem o formato que o front lê (mês, total gasto, fora dos limites, `categoryId` nulo no geral, percentual numérico); gasto, restante, percentual e estado batem (`WITHIN`, e `EXCEEDED` com restante negativo ao passar do limite); editar o valor volta o estado para dentro do limite; limite zero é aceito e o percentual vem **nulo** (a tela trata); copiar cria os limites nos meses seguintes, a segunda cópia só conta os que já existiam, 25 meses é `400`; remover é `204` e de novo `404`; mês inválido `400` e sem cookie `401`. A limpeza removeu só os dados `qs20*`.

## 4. Fora desta spec

Os limites **semanais** (spec 006, `/budgets`) e a tela deles; hoje só o orçamento mensal aparece.
