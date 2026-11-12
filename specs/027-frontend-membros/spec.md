# Feature Specification: Membros e convites no front-end

**Feature Branch**: `027-frontend-membros`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Pode seguir" com os membros e convites (spec 012): ver quem participa do workspace, o dono convidar pessoas (o código aparece uma vez e é combinado fora do aplicativo, já que não há envio de e-mail), mudar o papel, remover, sair do workspace e entrar num workspace com um código de convite.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Ver quem participa (Priority: P1)

**Independent Test**: o Perfil leva a "Membros", que lista as pessoas do workspace atual com o papel e desde quando participam.

**Acceptance Scenarios**:

1. **Given** o Perfil, **When** a pessoa toca em "Membros" no workspace atual, **Then** abre `/membros` com a lista: avatar, nome, e-mail, o papel em palavras (Dono, Editor, Leitor), "Desde dd/mm/aaaa" e o selo "Você" na própria linha; o dono vem primeiro.
2. **Given** qualquer papel, **Then** vê a lista; carregamento, falha ("Tentar de novo") e troca de workspace sem misturar listas.

---

### User Story 2 - Mudar papel, remover e sair (Priority: P1)

**Acceptance Scenarios**:

1. **Given** o dono, **When** escolhe "Mudar papel" de um membro (que não é o dono), **Then** uma folha oferece Editor e Leitor (com o que cada um pode); salvar muda o papel e a lista se atualiza com o aviso. O papel do dono não muda.
2. **Given** o dono, **When** escolhe "Remover" num membro, **Then** confirma antes ("Fulano perde o acesso ao workspace; os lançamentos dele continuam") e a pessoa sai da lista.
3. **Given** um membro que não é dono, **When** escolhe "Sair do workspace" na própria linha, **Then** confirma antes e, ao sair, o aplicativo atualiza os workspaces da pessoa e leva ao Início do primeiro workspace que sobrar.
4. **Given** o dono, **Then** não tem "Sair" nem "Remover" na própria linha (a API recusa: o dono não sai); quem não é dono não vê "Mudar papel" nem "Remover" nos outros.
5. **Given** recusa da API (403, 404, 409), **Then** a mensagem aparece como a API disse e a lista se atualiza quando o membro já não existe.

---

### User Story 3 - Convidar por um código (Priority: P1)

**Acceptance Scenarios**:

1. **Given** o dono, **When** a tela de Membros abre, **Then** vê "Convites pendentes" (e-mail, papel, "Expira em dd/mm/aaaa") e o botão "Convidar".
2. **Given** "Convidar", **When** informa o e-mail e o papel (Editor ou Leitor) e salva, **Then** a folha mostra o **código do convite uma única vez**, com "Copiar" e o aviso "Combine a entrega do código com a pessoa; ele não aparece de novo" (o aplicativo não envia e-mail); ao fechar, o código some e o convite entra na lista de pendentes.
3. **Given** e-mail vazio ou inválido, **Then** o erro aparece no campo e nada é enviado; "Esta pessoa já participa do workspace", o limite de convites ou de membros aparecem como a API disse, com os valores mantidos.
4. **Given** um convite pendente, **When** o dono escolhe "Revogar" e confirma, **Then** ele sai da lista (o código deixa de valer).
5. **Given** quem não é dono, **Then** não vê convites nem "Convidar".

---

### User Story 4 - Entrar com um código (Priority: P1)

**Acceptance Scenarios**:

1. **Given** o Perfil, **When** a pessoa escolhe "Entrar com um código de convite", **Then** uma folha pede o código; ao aceitar, o aplicativo atualiza os workspaces, passa a usar o novo e leva ao Início com o aviso "Você entrou em <nome> como <papel>".
2. **Given** um código inválido, vencido, já usado ou revogado, **Then** a tela diz "Código de convite inválido ou vencido" (a API responde igual para todos os casos); quem já participa ou workspace cheio mostram a mensagem da API.
3. **Given** o código, **Then** nunca vai a endereço, armazenamento ou registro; fica só no campo e na chamada.

### Edge Cases

- O código do convite aparece só na resposta da criação e só na folha; nunca é guardado no aplicativo depois de fechada.
- Papéis: `OWNER`, `EDITOR`, `VIEWER`; só Editor e Leitor se dão ou convidam.

## Requirements *(mandatory)*

- **FR-001**: MUST usar `GET/PUT/DELETE /members`, `GET/POST/DELETE /invitations` e `POST /api/v1/invitations/accept`.
- **FR-002**: O contexto de sessão MUST ganhar `refresh()` (consulta `/me` de novo sem passar por "carregando") para o aceite e a saída atualizarem os workspaces.
- **FR-003**: O JavaScript inicial MUST continuar dentro de 150 KB: as telas menos usadas passam a carregar sob demanda (divisão por rota).
- **FR-004**: Papéis, estados, acessibilidade, sem dependência nova, nenhuma mudança no back-end; todo `<dialog>` usa o `SheetDialog` ou o `ConfirmDialog` já cobertos.

## Success Criteria

- **SC-001**: Os números e as regras batem com a API (roteiro real contra o Compose, com duas contas).
- **SC-002**: Poucos testes de valor.
