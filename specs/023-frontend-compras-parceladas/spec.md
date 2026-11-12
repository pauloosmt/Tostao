# Feature Specification: Compras parceladas no front-end

**Feature Branch**: `023-frontend-compras-parceladas`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Prossiga com os próximos: as compras parceladas com a API da spec 010: lançar uma despesa parcelada pela folha de lançar (valor total, número de parcelas, primeira data), ver o plano de parcelas de um lançamento parcelado e cancelar as parcelas que ainda estão pendentes."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Lançar uma compra parcelada (Priority: P1)

Na folha de lançar, numa **despesa**, a pessoa marca "Parcelar a compra", informa o valor total e em quantas parcelas, e a primeira data.

**Independent Test**: lançar uma despesa de R$ 1.000,00 em 3 parcelas cria as três parcelas mensais (R$ 333,34, R$ 333,33, R$ 333,33), todas aparecendo na lista com o selo "Parcela N de 3".

**Acceptance Scenarios**:

1. **Given** a folha de lançar com o tipo despesa, **When** a pessoa marca "Parcelar a compra", **Then** o valor passa a se chamar "Valor total", aparece o campo "Número de parcelas" (de 2 a 48) e a data passa a ser a da primeira parcela; o resumo mostra a divisão ("3 parcelas de R$ 333,34, 333,33 e 333,33" ou "3 parcelas de R$ 100,00") e as datas da primeira e da última parcela.
2. **Given** receita ou transferência, **Then** a opção "Parcelar a compra" não existe; trocar de tipo com a opção marcada a desmarca.
3. **Given** dados válidos, **When** a pessoa salva, **Then** a API cria o plano (a descrição de cada parcela recebe o sufixo " (N/T)"), a folha fecha com o aviso "Compra parcelada em N vezes." e a lista mostra as parcelas; a primeira parcela fica efetivada, ou pendente se "Pendente" estiver marcado.
4. **Given** número de parcelas fora de 2 a 48 ou não inteiro, total menor que 1 centavo por parcela, categoria ou conta não escolhida, descrição com mais de 190 caracteres, **When** salva, **Then** cada campo mostra o seu erro, nada é enviado e o foco vai ao primeiro erro; recusas da API aparecem no campo certo ou no aviso geral, com os valores mantidos e o envio único.

---

### User Story 2 - Ver o plano e cancelar o que falta (Priority: P1)

**Independent Test**: abrir uma parcela mostra o plano inteiro e "Cancelar parcelas pendentes" exclui só as pendentes.

**Acceptance Scenarios**:

1. **Given** um lançamento que é parcela de uma compra, **When** a pessoa o abre, **Then** a folha mostra "Compra parcelada" com o total, o que já foi efetivado e o que está pendente, e a lista das parcelas (número, data, valor, estado em palavras).
2. **Given** quem pode escrever e parcelas pendentes, **When** escolhe "Cancelar parcelas pendentes", **Then** confirma antes ("As N parcelas pendentes serão excluídas; as efetivadas continuam"), a API exclui só as pendentes e a lista atrás se atualiza com o aviso "N parcelas canceladas."; sem pendentes a ação não aparece.
3. **Given** o leitor, **Then** vê o plano e nenhuma ação.
4. **Given** falha ao carregar o plano, **Then** aviso com "Tentar de novo" sem impedir o resto da folha.

### Edge Cases

- Valores em centavos inteiros; a divisão segue a regra da API (o resto vai, um centavo por parcela, às primeiras) e a tela só a **mostra**.
- Datas das parcelas: a primeira somada a 0, 1, 2... meses (o dia limitado ao último do mês, sem acumular).
- A API não aceita efetivar sozinhas aqui (o marcador `autoClear` fica para a spec de recorrências).

## Requirements *(mandatory)*

- **FR-001**: A folha de lançar MUST oferecer "Parcelar a compra" só em despesa nova e enviar `POST /installment-purchases` (`totalCents`, `installmentCount`, `firstOccurredOn`, `description`, `categoryId`, `accountId`, `notes`, `firstInstallmentStatus`).
- **FR-002**: O resumo da divisão MUST usar a mesma regra de centavos da API; é puro e testado.
- **FR-003**: Um lançamento com `installmentGroupId` MUST mostrar o plano (`GET /installment-purchases/{groupId}`) e, para quem escreve, "Cancelar parcelas pendentes" com confirmação (`POST .../cancel`).
- **FR-004**: Papéis, estados (carregando, erro), acessibilidade, sem dependência nova, ≤ 150 KB; nenhuma mudança no back-end; teste do modo estrito para qualquer `<dialog>` novo (esta spec usa o `SheetDialog` e o `ConfirmDialog` já existentes).

## Success Criteria

- **SC-001**: Os números batem com a API (roteiro real contra o Compose).
- **SC-002**: Poucos testes de valor; a folha de lançar continua como estava para os outros tipos.
