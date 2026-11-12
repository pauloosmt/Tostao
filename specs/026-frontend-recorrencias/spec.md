# Feature Specification: Recorrências no front-end

**Feature Branch**: `026-frontend-recorrencias`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Pode seguir" com as recorrências: criar uma receita, despesa ou transferência que se repete (semanal, mensal ou anual), ver as recorrências e as ocorrências de cada uma, encerrar a recorrência e, se a pessoa quiser, efetivar as ocorrências sozinhas na data, usando a API das specs 011 e 014.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver as recorrências (Priority: P1)

**Independent Test**: com recorrências cadastradas, a tela "Recorrências" lista cada uma com o que se repete, o valor, a frequência, a próxima data e o estado.

**Acceptance Scenarios**:

1. **Given** recorrências no workspace, **When** a tela abre, **Then** cada uma aparece com a descrição, o valor com o sinal do tipo (receita positiva, despesa negativa, transferência sem sinal), a frequência em palavras ("Toda semana", "Todo mês", "Todo ano"), a conta e a categoria (ou "De A para B" na transferência), a **próxima ocorrência** ("Próxima: 10/12/2026", ou "Sem próximas ocorrências"), o selo "Efetiva sozinha" quando ligado e o selo "Encerrada" quando encerrada; as ativas vêm antes das encerradas.
2. **Given** nenhuma recorrência, **Then** a tela explica o que fazer e, para quem pode escrever, oferece "Nova recorrência".
3. **Given** carregamento ou falha, **Then** estado de carregando e aviso com "Tentar de novo".
4. **Given** o menu lateral, **Then** "Recorrências" aparece; no celular, o atalho fica na tela de Contas (como Orçamentos e Categorias).

---

### User Story 2 - Criar uma recorrência (Priority: P1)

**Independent Test**: criar "Aluguel", despesa de R$ 1.200,00, todo mês, a partir de hoje, cria a recorrência e as ocorrências já aparecem na lista de lançamentos como "Recorrente".

**Acceptance Scenarios**:

1. **Given** quem pode escrever, **When** escolhe "Nova recorrência", **Then** uma folha pede o tipo (despesa, receita ou transferência), o valor de cada ocorrência, a frequência, "Começa em" (hoje), "Termina em" (opcional), a descrição, a conta (De e Para na transferência), a categoria em moedas (menos na transferência), observações e a caixa "Efetivar sozinhas na data".
2. **Given** dados válidos, **When** salva, **Then** a API cria a recorrência (valor positivo em centavos; o sinal vem do tipo), a folha fecha com "Recorrência criada." e a lista a mostra.
3. **Given** valor vazio ou fora do limite, descrição vazia ou com mais de 200 caracteres, conta ou categoria não escolhida, início fora de 366 dias atrás até 2100, fim antes do início ou depois de 2100, **When** salva, **Then** cada campo mostra o seu erro, nada é enviado e o foco vai ao primeiro erro; recusas da API aparecem no campo certo ou no aviso geral, com os valores mantidos e o envio único.

---

### User Story 3 - Ver as ocorrências e encerrar (Priority: P1)

**Acceptance Scenarios**:

1. **Given** uma recorrência, **When** a pessoa a abre, **Then** a folha mostra os dados dela e as ocorrências (data, valor, estado em palavras: Efetivada ou Pendente) com o total, o efetivado e o pendente.
2. **Given** quem pode escrever e uma recorrência ativa, **When** escolhe "Encerrar recorrência", **Then** confirma antes ("As ocorrências pendentes futuras serão excluídas; as que já ocorreram continuam") e a API exclui as pendentes futuras; a folha fecha com "Recorrência encerrada. N ocorrências canceladas."; encerrar de novo cancela zero.
3. **Given** um lançamento que nasceu de uma recorrência, **When** a pessoa o abre, **Then** a folha dele oferece "Ver recorrência".
4. **Given** quem só lê, **Then** vê a lista e os detalhes e nenhuma ação.

### Edge Cases

- Valor sempre positivo na API (o sinal vem do tipo); datas `AAAA-MM-DD` sem fuso; frequências `WEEKLY`, `MONTHLY` e `YEARLY`.
- O início pode ser até 366 dias no passado (as ocorrências passadas nascem de uma vez).
- Uma recorrência encerrada não tem "Encerrar" e não cria mais ocorrências.

## Requirements *(mandatory)*

- **FR-001**: A tela MUST ler `GET /recurrences` e `GET /recurrences/{id}`, criar por `POST /recurrences` e encerrar por `POST /recurrences/{id}/end`.
- **FR-002**: O formulário MUST reaproveitar as regras do lançamento (valor, descrição, conta, destino, categoria, observações) e acrescentar frequência, início, fim e "efetivar sozinhas".
- **FR-003**: Papéis, estados (carregando, erro, vazio), acessibilidade, sem dependência nova, ≤ 150 KB; nenhuma mudança no back-end; todo `<dialog>` novo usa o `SheetDialog` já coberto pelo teste do modo estrito.

## Success Criteria

- **SC-001**: Os números batem com a API (roteiro real contra o Compose).
- **SC-002**: Poucos testes de valor.
