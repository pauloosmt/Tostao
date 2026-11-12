# Feature Specification: Orçamentos mensais no front-end

**Feature Branch**: `020-frontend-orcamentos`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Prossiga com os próximos: os orçamentos (limites de gasto por mês) com a API da spec 007: ver o acompanhamento do mês (limite geral e por categoria, com gasto, restante e estado), definir, editar e remover limites, e copiar os limites para os próximos meses, no visual novo."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver o orçamento do mês (Priority: P1)

A pessoa abre "Orçamentos" e vê, para o mês escolhido, quanto já gastou em relação a cada limite.

**Independent Test**: com limites definidos, a tela mostra o limite geral e os de categoria, cada um com o gasto, o restante, o percentual e a barra; trocar o mês mostra o outro mês.

**Acceptance Scenarios**:

1. **Given** limites no mês, **When** a tela abre, **Then** o mês corrente vem escolhido (com o seletor de mês); o limite **geral** (todas as despesas) aparece em destaque com "R$ gasto de R$ limite" e o restante; cada limite de categoria aparece numa barra com o mesmo texto; barras a partir de 80% mostram "Perto do limite" e a partir de 100% "Limite atingido" (nunca só pela cor).
2. **Given** o acompanhamento, **When** a tela mostra o total, **Then** diz o total gasto no mês e o gasto que nenhum limite de categoria cobre; os valores dos limites nunca são somados.
3. **Given** um limite de categoria arquivada, **When** aparece, **Then** leva a marca "Arquivada" (o gasto continua contado).
4. **Given** um mês sem limites, **When** a tela abre, **Then** explica o que fazer e, para quem pode escrever, oferece "Novo limite" e (se o mês anterior tem limites) "Copiar do mês anterior".
5. **Given** carregamento, falha (com "Tentar de novo") e troca de mês, **Then** a tela nunca mostra os números de um mês no lugar de outro.

---

### User Story 2 - Definir, editar e remover limites (Priority: P1)

**Independent Test**: definir um limite novo, mudar o valor dele e removê-lo, com os números da tela acompanhando.

**Acceptance Scenarios**:

1. **Given** quem pode escrever, **When** escolhe "Novo limite", **Then** uma folha pede a categoria (o limite geral ou uma categoria de despesa ativa, a principal e "Principal › Sub") e o valor em reais; salvar cria o limite (ou troca o valor se já existia para aquele escopo e mês) e a tela se atualiza com o aviso.
2. **Given** um limite, **When** a pessoa escolhe "Editar", **Then** uma folha pede só o novo valor; **When** escolhe "Remover", **Then** confirma antes e o limite some (o gasto continua contado no total).
3. **Given** valor vazio, inválido ou acima do limite do sistema, **When** salva, **Then** o erro aparece no campo e nada é enviado; zero é permitido (limite zero).
4. **Given** uma recusa da API (categoria arquivada ou de receita, limite que não existe mais), **Then** a mensagem aparece como a API disse, os valores digitados ficam e o envio é único.
5. **Given** quem só lê, **Then** não vê "Novo limite", "Editar", "Remover" nem "Copiar".

---

### User Story 3 - Copiar os limites para os próximos meses (Priority: P2)

**Acceptance Scenarios**:

1. **Given** limites no mês, **When** a pessoa escolhe "Copiar para os próximos meses" e informa quantos meses (1 a 24), **Then** a API copia só onde ainda não há limite e a tela diz quantos foram criados, quantos já existiam e quantos foram ignorados por categoria arquivada.

### Edge Cases

- Todos os valores em centavos inteiros; o valor digitado vale o campo de dinheiro de sempre.
- Mês de 2000-01 a 2100-12; fora disso o seletor não anda.
- Limite zero: a barra mostra o gasto como estourado quando há gasto.
- A semana ISO (limites semanais da spec 006) fica para depois.

## Requirements *(mandatory)*

- **FR-001**: A tela `/orcamentos` MUST ler `GET /monthly-budgets?month=AAAA-MM` e mostrar o acompanhamento (geral, categorias, total, fora dos limites), sem cache entre meses e workspaces.
- **FR-002**: Definir MUST usar `PUT /monthly-budgets` (201 criou, 200 substituiu), editar `PUT /monthly-budgets/{id}`, remover `DELETE` com confirmação e copiar `POST /monthly-budgets/copy`.
- **FR-003**: Quem só lê MUST ver tudo e nenhuma ação; a API continua recusando (403) o que o papel não permite.
- **FR-004**: Estados de carregando, erro e vazio; acessibilidade (axe); sem dependência nova; JavaScript inicial ≤ 150 KB.
- **FR-005**: O item "Orçamentos" entra no menu lateral; no celular, o atalho fica na tela de Contas (como Categorias).

## Success Criteria

- **SC-001**: Os números da tela batem com a API (roteiro real contra o Compose).
- **SC-002**: Poucos testes de valor (acompanhamento e estados, definir/editar/remover/copiar, papéis).
- **SC-003**: Nenhuma mudança no back-end.
