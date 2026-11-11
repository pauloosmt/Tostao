# Quickstart: validar as telas de contas e categorias

Contrato em [contracts/ui-contract.md](contracts/ui-contract.md); modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e a API local no ar: `docker compose up -d --build` (na raiz). A API fica em `http://127.0.0.1:8080`.
- `cd frontend && npm ci && npm run dev` e abrir `http://localhost:5173` (o proxy do Vite espelha o rewrite da Vercel).
- Uma conta de teste (`qs16...@example.com`) criada pelo próprio aplicativo.

## 1. Verificação automatizada

```bash
cd frontend && npm run verify
cd ../backend && ./mvnw -B verify
```

Esperado: lint com os guard-rails, tipos, testes, build, orçamento de tamanho e testes do build passam; os testes do back-end continuam passando.

## 2. Roteiro com a API real (HTTP)

O roteiro de apoio (fora do repositório, como nas specs anteriores) cria pessoas e workspaces de teste e confere, contra o build servido pelo
`vite preview` e a API do Compose:

1. Criar conta, listar, e o saldo total do relatório bater com a soma das contas ativas.
2. Criar um cartão com limite e dias; recusar limite em conta que não é cartão.
3. Editar nome e saldo; o tipo não muda; uma conta arquivada recusa a edição.
4. Arquivar sai da lista e do total; restaurar volta; restaurar com o nome já tomado dá `409`.
5. Criar categoria principal (com cor), subcategoria sob ela (o tipo é o da principal) e ler a árvore; editar nome e cor **sem apagar o ícone**.
6. Arquivar a principal arquiva as subcategorias; restaurar só a principal; restaurar a subcategoria com a principal arquivada dá `409`.
7. Papéis: o leitor lê tudo e leva `403` nas escritas; o editor altera; quem não é do workspace leva `404`.
8. Limpeza dos dados `qs16*` ao fim.

## 3. Contas no navegador (US1 a US4)

1. Menu: aparecem Início, Contas, Categorias e Perfil; o item atual fica marcado.
2. **Contas** vazia: estado vazio com "Criar conta".
3. "Criar conta": nome, tipo "Conta corrente", saldo `1.500,00` → volta à lista com "Conta criada." e o saldo total de R$ 1.500,00.
4. Saldo `1500.5`, `abc` ou `12,345` → erro no campo, nada enviado. Nome repetido → erro no campo do nome.
5. Tipo "Cartão de crédito": aparecem limite, fechamento e vencimento; trocar para outro tipo some com eles.
6. Saldo negativo (`-200,00`): sinal visível e leitor de tela diz "negativo".
7. Editar: o tipo aparece e não muda; salvar atualiza lista e total. "Arquivar": confirmação, Esc cancela; confirmar tira a conta da lista e do total.
8. "Mostrar arquivadas": a conta volta marcada; "Restaurar" a devolve.
9. Derrubar a API (`docker compose stop api`) e abrir Contas: aviso com "Tentar de novo"; religar e tentar de novo funciona.

## 4. Categorias no navegador (US5 a US7)

1. **Categorias**: o cadastro já cria as categorias padrão (Alimentação, Moradia, Salário...), que aparecem na árvore. Alternar Despesas e Receitas.
   (O estado vazio só aparece num tipo sem nenhuma categoria, por exemplo depois de arquivar todas.)
2. Criar "Pets" (despesa, cor Verde) → aparece na árvore de despesas com o ponto verde. Criar "Alimentação" de novo → erro no campo do nome.
3. "Adicionar subcategoria" em Pets: "Ração". O tipo não é perguntado. Nome repetido no mesmo nível → erro no campo.
4. Editar "Ração": tipo e principal aparecem e não mudam; trocar a cor e salvar.
5. Arquivar "Pets": a confirmação avisa das subcategorias; ao confirmar, as duas somem. "Mostrar arquivadas": voltam marcadas.
6. Restaurar "Pets": só ela volta (a tela diz que as subcategorias não voltam junto). Restaurar "Ração" depois funciona.

## 5. Papéis e workspace (US8)

1. Com um leitor (convidar a pessoa com papel de leitor pela API da spec 012): as listas aparecem sem nenhum botão de alterar; abrir `/contas/nova`
   mostra o aviso de papel com o caminho de volta.
2. Com dois workspaces: trocar no perfil e abrir Contas e Categorias; só aparecem os dados do workspace novo, e nada do anterior.
3. `/contas/<id de uma conta de outro workspace>` → "Página não encontrada".

## 6. Teclado, estreito e tema

1. Só com Tab, Shift+Tab, Enter, Espaço e Esc, criar uma conta e uma categoria; o foco vai ao primeiro campo com erro e, ao salvar, à lista.
2. Largura de 320 px e zoom de 200%: cartões, árvore e formulários sem rolagem horizontal; tema claro e escuro com os pontos de cor legíveis.

## 7. Limpeza

Apagar os dados do teste (`qs16*@example.com` e os workspaces deles) e confirmar que só restam os dados que já existiam antes.

## 8. Resultado da validação (2026-11-11)

**Automatizado**

- `npm run verify` (front): guard-rails 10, Vitest **569 testes em 43 arquivos**, `tsc` e build ok, JavaScript inicial de **94,0 KB** de 150 KB (gzip) e testes do build ok.
- `./mvnw -B verify` (back-end): BUILD SUCCESS, **1414 testes, 0 falhas** (a API não mudou nesta spec).

**Roteiro com a API real** (HTTP direto no Compose; script fora do repositório): **90 de 90** conferências.
Cobre conta comum, com saldo negativo e no limite de R$ 10 bilhões (e um centavo além, recusado); cartão com e sem limite e dias (campos nulos quando
não informados); limite em conta que não é cartão, dia 32, limite negativo, nome de 101 caracteres e nome repetido (`400`/`409`); o total do relatório
igual à soma da lista; editar sem enviar o tipo, mudar o tipo recusado e o que não é reenviado no cartão voltando a nulo; arquivar (idempotente, fora
da lista e do total, aparece com `includeArchived`), editar arquivada (`409`), restaurar com o nome tomado (`409`) e restaurar; categoria principal e
subcategoria sem enviar o tipo (herda), cor fora do formato (`400`), nome em uso (`409` com o detalhe que a tela usa); **editar sem reenviar o ícone o
apaga** (por isso a tela o reenvia); arquivar a principal arrasta as subcategorias, restaurar a principal não as devolve, restaurar a subcategoria com a
principal arquivada e criar sob principal arquivada dão `409` com os detalhes que a tela traduz; leitor (lê tudo, `403` em toda escrita), editor
(escreve), intrusa e workspace inexistente (`404` com o mesmo detalhe) e sem cookie (`401`). A limpeza removeu só os dados `qs16*`; ficaram os dados
da conta real que já existia.

**Achado na validação:** o cadastro já cria as categorias padrão do workspace (Alimentação, Moradia, Salário...), então o roteiro do navegador usa
nomes novos (seção 4).

**Visto no navegador por quem usa o projeto:** a tela de cadastro de conta, o menu de tipos (que no tema escuro abria branco, corrigido) e a lista de
categorias (que ficou pesada com três botões por linha, simplificada).

**Ainda sem conferência no navegador** (itens das seções 3 a 6 que só ele mostra): o fluxo completo de contas e de categorias passo a passo, a
confirmação ao arquivar, o leitor e a troca de workspace de ponta a ponta, o teclado, a largura de 320 px e o zoom de 200%. Os comportamentos
correspondentes têm teste automatizado (569 testes, com axe nas telas), mas a conferência visual fica com quem usa.

**Observação sobre o ambiente de teste:** numa máquina com pouca memória livre (cerca de 1 GB), o Vitest com muitos processos em paralelo ficava
lento e estourava o tempo. O projeto passou a rodar no máximo 2 processos de teste por vez, com esperas assíncronas mais folgadas; a rodada completa leva
cerca de 2 a 3 minutos nessa máquina.
