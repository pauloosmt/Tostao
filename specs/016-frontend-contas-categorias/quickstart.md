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

1. **Categorias** vazia: estado vazio com "Criar categoria". Alternar Despesas e Receitas.
2. Criar "Alimentação" (despesa, cor Verde) → aparece na árvore de despesas com o ponto verde.
3. "Adicionar subcategoria" em Alimentação: "Mercado". O tipo não é perguntado. Nome repetido no mesmo nível → erro no campo.
4. Editar "Mercado": tipo e principal aparecem e não mudam; trocar a cor e salvar.
5. Arquivar "Alimentação": a confirmação avisa das subcategorias; ao confirmar, as duas somem. "Mostrar arquivadas": voltam marcadas.
6. Restaurar "Alimentação": só ela volta (a tela diz que as subcategorias não voltam junto). Restaurar "Mercado" depois funciona.

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
