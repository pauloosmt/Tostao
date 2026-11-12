# Feature Specification: Cartão de crédito e faturas no front-end

**Feature Branch**: `021-frontend-cartao`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Prossiga com os próximos: o cartão de crédito com a API da spec 008: o resumo do cartão (limite, devido, disponível, fatura aberta) e as faturas por mês de vencimento, com os lançamentos que as compõem, no visual novo. Leitura apenas; as compras parceladas e as recorrências vêm em specs próprias."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver o cartão: limite, devido e fatura aberta (Priority: P1)

Na lista de contas, cada cartão de crédito ativo tem o atalho "Fatura". A pessoa abre e vê o resumo do cartão.

**Independent Test**: com um cartão com limite e dias cadastrados, a tela mostra o limite, o devido, o disponível (com a barra de uso) e a fatura aberta com fechamento, vencimento e total até agora.

**Acceptance Scenarios**:

1. **Given** um cartão ativo na lista de contas, **When** a pessoa toca em "Fatura", **Then** abre `/contas/{id}/fatura` com o nome do cartão no cabeçalho.
2. **Given** um cartão com limite, **When** a tela abre, **Then** mostra "Limite", "Devido" (o saldo negativo da conta) e "Disponível" (limite menos devido; negativo é dito "Passou R$ … do limite"), e uma barra do devido sobre o limite com o texto de estado ("Perto do limite", "Limite atingido").
3. **Given** um cartão sem limite, **When** a tela abre, **Then** mostra o devido e diz "Sem limite cadastrado", sem barra.
4. **Given** a fatura aberta, **When** a tela abre, **Then** diz o mês dela, o período, o dia de fechamento, o de vencimento e o total até agora.
5. **Given** um cartão sem os dias de fechamento e vencimento, **When** a tela abre, **Then** explica que as faturas precisam dos dois dias e, para quem pode escrever, leva à edição da conta.
6. **Given** uma conta que não é cartão, arquivada ou inexistente, **When** o endereço é aberto, **Then** a tela diz que o cartão não foi encontrado (ou que a conta não é um cartão), com o caminho de volta às contas.
7. **Given** carregamento ou falha, **Then** estado de carregando e aviso com "Tentar de novo".

---

### User Story 2 - Ver uma fatura (Priority: P1)

**Acceptance Scenarios**:

1. **Given** a fatura aberta como ponto de partida, **When** a pessoa usa o seletor de mês (de vencimento), **Then** vê a fatura daquele mês com o estado em palavras (aberta, fechada, paga, vencida), o total, o pago e o restante, e o período, o fechamento e o vencimento.
2. **Given** os lançamentos da fatura, **When** ela aparece, **Then** cada linha mostra a data, a descrição, a categoria e o valor: compra ou saque positivos como gasto, estorno negativo como crédito; uma fatura sem lançamentos diz "Nenhum lançamento nesta fatura".
3. **Given** a troca de mês, **When** a nova fatura carrega, **Then** nunca aparecem os números da fatura anterior no lugar.

### Edge Cases

- Valores em centavos inteiros; mês de vencimento de 2000-01 a 2100-12.
- Cartão arquivado: a API ainda responde; a tela mostra um aviso "Arquivado" e nenhum atalho de escrita.
- A API recusa com 409 quando faltam os dias (mensagem própria) e quando a conta não é cartão: a tela mostra a mensagem da API.

## Requirements *(mandatory)*

- **FR-001**: A tela MUST ler `GET /accounts/{id}/card-summary` e `GET /accounts/{id}/invoices/{AAAA-MM}` (sem cache entre cartões, meses e workspaces).
- **FR-002**: O estado da fatura MUST aparecer em palavras (OPEN: aberta, CLOSED: fechada, PAID: paga, OVERDUE: vencida), nunca só por cor.
- **FR-003**: A lista de contas MUST oferecer "Fatura" só nos cartões de crédito ativos.
- **FR-004**: Somente leitura; sem dependência nova; JavaScript inicial ≤ 150 KB; axe nas telas.

## Success Criteria

- **SC-001**: Os números da tela batem com a API (roteiro real contra o Compose).
- **SC-002**: Poucos testes de valor; nenhuma mudança no back-end.
