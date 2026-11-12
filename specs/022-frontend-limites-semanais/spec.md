# Feature Specification: Limites semanais no front-end

**Feature Branch**: `022-frontend-limites-semanais`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Você não vai desenvolver o limite semanal? Sim: a tela de Orçamentos (spec 020) ganha o acompanhamento semanal com a API da spec 006: o mesmo que o mensal (limite geral e por categoria, gasto, restante e estado; definir, editar, remover e copiar), pela semana ISO de segunda a domingo."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver o orçamento da semana (Priority: P1)

Na tela de Orçamentos, um seletor "Mensal | Semanal" troca o período; no semanal, o seletor de período anda de semana em semana.

**Independent Test**: escolher "Semanal" mostra a semana corrente (segunda a domingo) com os limites semanais, o gasto, o restante e o estado, igual ao mensal.

**Acceptance Scenarios**:

1. **Given** a tela de Orçamentos, **When** a pessoa escolhe "Semanal", **Then** o período vira a semana ISO corrente, rotulada "Semana 46 · 09/11 a 15/11/2026", e a tela mostra os limites semanais com a mesma apresentação do mensal (geral em destaque, por categoria, total e fora dos limites).
2. **Given** o seletor de semana, **When** a pessoa anda para a anterior ou a próxima, **Then** vê a outra semana, virando o ano certo (a semana 1 de um ano pode começar em dezembro; a 52 ou 53 termina em janeiro), sem nunca mostrar os números de uma semana no lugar de outra; o seletor não anda além de 2000 e 2100.
3. **Given** "Mensal" escolhido de novo, **Then** volta ao mês que estava; a escolha entre mensal e semanal fica na memória da tela (não vai ao endereço nem ao armazenamento).
4. **Given** um semana sem limites, **Then** a tela explica, como no mensal.

---

### User Story 2 - Definir, editar, remover e copiar limites da semana (Priority: P1)

**Acceptance Scenarios**:

1. **Given** quem pode escrever, **When** escolhe "Novo limite" no semanal, **Then** a folha pede a categoria (ou o geral) e o valor, e o limite é da semana mostrada; editar, remover (com confirmação) e copiar funcionam como no mensal.
2. **Given** "Copiar para as próximas semanas", **When** a pessoa informa de 1 a 52, **Then** a API copia só onde não há limite e a tela conta criados, já existentes e ignorados por categoria arquivada.
3. **Given** quem só lê, **Then** não vê nenhuma ação, nos dois períodos.

### Edge Cases

- Semana ISO: segunda a domingo, ano ISO de 2000 a 2100; a semana é um texto `AAAA-Www`, calculado em componentes locais (nunca pelo fuso).
- Os limites semanais e mensais são independentes (tabelas e ids próprios): definir um não mexe no outro.

## Requirements *(mandatory)*

- **FR-001**: O semanal MUST ler `GET /budgets?week=AAAA-Www` e usar `PUT /budgets`, `PUT /budgets/{id}`, `DELETE /budgets/{id}` e `POST /budgets/copy` (`fromWeek`, `weeks` de 1 a 52).
- **FR-002**: A tela MUST reaproveitar a apresentação e as folhas do mensal; só o período muda.
- **FR-003**: A aritmética de semanas MUST ser pura, testada nas viradas de ano (53 semanas, semana 1 em dezembro).
- **FR-004**: Papéis, estados (carregando, erro, vazio), acessibilidade e orçamento de 150 KB como na spec 020; nenhuma mudança no back-end; sem dependência nova.

## Success Criteria

- **SC-001**: Os números batem com a API (roteiro real contra o Compose).
- **SC-002**: Poucos testes de valor; o mensal continua intacto.
