# Quickstart: validar as telas de lançamentos

Contrato em [contracts/ui-contract.md](contracts/ui-contract.md); modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e a API local no ar: `docker compose up -d --build`.
- `cd frontend && npm ci && npm run dev`, abrir `http://localhost:5173`.
- Uma conta de teste (`qs17...@example.com`) criada pelo aplicativo, com duas contas (Carteira e Reserva) criadas na tela de Contas. As categorias padrão já existem.

## 1. Verificação automatizada

```bash
cd frontend && npm run verify
cd ../backend && ./mvnw -B verify
```

## 2. Roteiro com a API real (HTTP)

Script fora do repositório, como nas specs anteriores, contra a API do Compose:

1. Criar despesa, receita e transferência; o saldo das contas muda (despesa e saída reduzem, receita e entrada aumentam); a transferência cria duas pontas ligadas.
2. Listar: ordem do mais recente ao mais antigo, 20 por página, `totalItems` e `totalPages` coerentes; cada filtro (`from`, `to`, `accountId`, `categoryId` com subcategorias, `type`, `status`, `q` com `%` e `_` literais) e a combinação.
3. Pendente: criado com `status=PENDING` não conta no saldo; `POST .../clear` o efetiva e conta; efetivar de novo não dá erro; editar de volta para pendente.
4. Transferência: editar pela ponta de destino (origem e destino reais) muda as duas pontas; efetivar e excluir valem para o par.
5. Regras: valor zero ou além do limite, data fora de 2000 a 2100, descrição vazia ou longa, categoria de outro tipo ou arquivada, conta arquivada, origem igual ao destino, categoria em transferência (`400`/`409`).
6. Papéis: o leitor lista e lê, `403` em criar, editar, efetivar e excluir; o editor escreve; intruso e workspace inexistente `404` com o mesmo detalhe; sem cookie `401`.
7. Limpeza dos dados `qs17*` ao fim.

## 3. Lista e filtros no navegador (US1 e US4)

1. Menu: aparecem Início, Contas, Categorias, Lançamentos e Perfil; o item atual fica marcado.
2. **Lançamentos** vazio: estado vazio com "Novo lançamento".
3. Com vários lançamentos: grupos por dia ("Hoje", "Ontem", data), valores com sinal, "Pendente" marcado, categoria ou "Sem categoria"; transferência com "Para ..." e "De ...".
4. Mais de 20 lançamentos: "Próxima" e "Anterior", "Página 2 de N", botões indisponíveis nos extremos.
5. "Filtros": período, conta, categoria (uma principal inclui as subcategorias), tipo, estado e busca; "Aplicar" volta à página 1; "Filtros (N)" mostra a contagem; "Limpar" devolve tudo. Data final antes da inicial: erro no campo.
6. Abrir um lançamento e voltar: os filtros e a página continuam. Trocar de workspace (Perfil) e voltar: filtros zerados.

## 4. Registrar, editar, efetivar e excluir (US2, US3, US5 e US6)

1. "Novo lançamento": despesa, valor `35,90`, data de hoje, "Almoço", conta Carteira, categoria "Alimentação › Restaurantes" → volta à lista com "Lançamento registrado."
2. Valor `0`, `-5`, `abc`: erro no campo. Descrição vazia: erro e foco. Trocar de despesa para receita: a categoria escolhida some se não serve ao tipo.
3. Marcar "Pendente": a linha aparece com "Pendente"; o saldo da conta não muda (tela de Contas).
4. Transferência: origem Carteira, destino Reserva, `100,00` → duas pontas na lista; origem igual ao destino: erro no campo do destino.
5. Abrir uma linha: formulário preenchido, tipo visível e não editável; abrir a ponta de destino da transferência: origem e destino certos.
6. "Efetivar" num pendente: a marca some e o saldo muda. "Excluir": confirmação com foco em "Cancelar", Esc cancela; confirmar exclui (numa transferência, as duas pontas).
7. Derrubar a API (`docker compose stop api`): aviso com "Tentar de novo", nunca tela em branco.

## 5. Papéis e workspace (US7)

1. Leitor: lista e detalhe sem ações; `/lancamentos/novo` mostra o aviso de papel.
2. Dois workspaces: trocar no perfil e abrir Lançamentos; só os dados do novo; `/lancamentos/<id de outro workspace>` mostra "não encontrado".

## 6. Teclado, estreito e tema

1. Só com Tab, Shift+Tab, Enter, Espaço e Esc, registrar um lançamento e filtrar a lista.
2. 320 px e zoom de 200%: linhas, filtros e formulário sem rolagem horizontal; tema claro e escuro.

## 7. Limpeza

Apagar os dados de teste (`qs17*@example.com` e os workspaces deles) e conferir que só restam os dados que já existiam.
