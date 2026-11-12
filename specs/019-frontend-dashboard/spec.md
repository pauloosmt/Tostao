# Feature Specification: Dashboard do mês no Início

**Feature Branch**: `019-frontend-dashboard`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Prossiga com os próximos: o Início mostra o resumo do mês com a API de dashboard da spec 005: a Carteira com o saldo e o fecho 'Sobram R$ … em novembro', receitas, despesas e resultado com a comparação com o mês anterior, o que está pendente, os gastos por categoria e as contas, com o seletor de mês, no visual novo da spec 018."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver como foi o mês (Priority: P1)

A pessoa abre o Início e vê o mês corrente: o saldo de hoje na Carteira com a frase do fecho ("Sobram R$ 1.723,60 em novembro" ou "Faltam R$ 200,00 em novembro"), as receitas, as despesas e o resultado do mês.

**Independent Test**: com lançamentos no mês, o Início mostra o saldo total, o fecho com o resultado do mês, e os três totais; trocar o mês muda os números.

**Acceptance Scenarios**:

1. **Given** um workspace com contas e lançamentos, **When** o Início abre, **Then** a Carteira mostra o saldo total das contas ativas (hoje), o fecho dourado com o resultado do mês (sobra ou falta), e abaixo as receitas, as despesas e o resultado do mês.
2. **Given** o seletor de mês (anterior/próximo), **When** a pessoa troca, **Then** os números são os do mês escolhido; o saldo da Carteira continua sendo o de hoje e o fecho fala do mês escolhido; fora de 01/2000 a 12/2100 os botões ficam indisponíveis.
3. **Given** o mês anterior com valores, **When** o Início mostra receitas e despesas, **Then** cada uma traz a variação sobre o mês anterior ("12,5% a mais", "3% a menos", "igual"); quando o anterior é zero, não mostra percentual.
4. **Given** um mês sem lançamentos, **When** o Início abre, **Then** os totais aparecem zerados e a frase "Nenhum lançamento neste mês" convida a lançar (quem pode escrever).
5. **Given** a falha ao carregar, **When** o Início abre, **Then** mostra o aviso com "Tentar de novo"; trocar de mês nunca mostra os números do mês anterior como se fossem do novo (volta a "carregando").
6. **Given** um workspace sem contas, **When** o Início abre, **Then** a Carteira mostra "Crie sua primeira conta", como na spec 018.

---

### User Story 2 - Pendentes e gastos por categoria (Priority: P1)

**Independent Test**: o Início mostra quanto está pendente no mês e para onde foi o dinheiro, por categoria.

**Acceptance Scenarios**:

1. **Given** lançamentos pendentes no mês, **When** o Início abre, **Then** uma linha com o tostão dourado diz "N pendentes" e o valor de receitas e despesas pendentes, e leva à lista filtrada por pendentes; sem pendentes, a linha não aparece. O pendente nunca entra nos totais do mês.
2. **Given** despesas por categoria, **When** o Início abre, **Then** a lista "Gastos por categoria" mostra cada categoria principal com o total, o percentual e uma barra; "Sem categoria" aparece quando há; sem despesas, a seção diz que não há gastos no mês.

---

### User Story 3 - As contas (Priority: P2)

**Acceptance Scenarios**:

1. **Given** contas ativas, **When** o Início abre, **Then** a seção "Contas" lista cada uma com o tipo e o saldo de hoje.

### Edge Cases

- Todos os valores são centavos inteiros; o fecho usa o resultado do mês (receitas menos despesas, realizados).
- A resposta atrasada de um mês anterior é descartada.
- Percentuais da API vêm com duas casas; a tela mostra no máximo uma casa e vírgula.
- Semana: a API também serve a semana ISO; esta spec mostra só o mês (a semana entra com os orçamentos semanais).

## Requirements *(mandatory)*

- **FR-001**: O Início MUST ler `GET /workspaces/{id}/dashboard?month=AAAA-MM` (sem cache entre meses nem workspaces) e mostrar a Carteira, o fecho, os totais, a variação, os pendentes, os gastos por categoria e as contas.
- **FR-002**: O seletor de mês MUST usar o mês corrente como começo, ir de 2000-01 a 2100-12 e nunca misturar meses.
- **FR-003**: Pendentes MUST aparecer à parte dos totais; "Ver pendentes" MUST abrir a lista já filtrada por estado pendente.
- **FR-004**: Valores em centavos inteiros, formatados em reais; sem dependência nova; o JavaScript inicial continua dentro de 150 KB.
- **FR-005**: Estados de carregando, erro (com "Tentar de novo") e vazio MUST existir; acessibilidade (axe) nas telas.

## Success Criteria

- **SC-001**: Os números do Início batem com a API (conferência no roteiro real contra o Compose).
- **SC-002**: Poucos testes de valor (resumo, troca de mês sem mistura, pendentes, categorias, erro, vazio), sem inflar a rodada.
- **SC-003**: Nenhuma mudança no back-end.
