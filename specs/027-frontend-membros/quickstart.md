# Quickstart: Membros e convites no front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Para testar de ponta a ponta, use duas contas (duas janelas, uma anônima): a dona e a convidada.

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Membros**: Perfil → "Membros" (no workspace atual): avatar, nome, e-mail, papel em palavras (Dono, Editor, Leitor), "Desde dd/mm/aaaa" e o selo "Você"; o dono vem primeiro.
2. **Convidar** (só o dono): "Convidar" → e-mail e papel (Editor ou Leitor) → "Criar convite": a folha mostra o **código uma única vez**, com "Copiar código" e o aviso de que o aplicativo não envia e-mail. Ao "Concluir", o código some e o convite entra em "Convites pendentes" (com "Expira em").
3. **Entrar com um código** (conta convidada): Perfil → "Entrar com um código de convite" → cole o código → "Aceitar convite": "Você entrou em <workspace> como <papel>" → "Ir para o Início" (o novo workspace passa a ser o atual). Código errado, vencido, usado ou revogado: "Código de convite inválido ou vencido."
4. **Mudar papel** (dono): "Mudar papel" abre a folha com Editor e Leitor; **Remover** pede confirmação.
5. **Sair** (quem não é dono): "Sair do workspace" na própria linha, com confirmação; depois o aplicativo atualiza os workspaces e vai ao Início do que sobrar. O dono não tem "Sair" nem "Remover" na própria linha.
6. **Revogar** um convite pendente (dono): confirma e o código deixa de valer.
7. Leitor e editor veem a lista e nenhuma ação sobre os outros. Celular e temas claro e escuro.

## 2. Verificação automatizada (2026-11-12)

- `npm run verify` (front): guard-rails 10, Vitest **877 testes em 68 arquivos**, `tsc` e build ok, JavaScript inicial de **114,3 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose, com duas contas; script fora do repositório)

**29 de 29** conferências: a lista de membros com os campos que o front lê; convidar devolve o código só ali (201), o e-mail normalizado e o convite **sem** código na lista de pendentes; a convidada não vê convites do workspace; e-mail inválido (`400` no campo `email`), papel `OWNER` e convidar quem já participa (`409`) recusados; código errado `404`; aceitar devolve o workspace, o nome e o papel, e o `/me` da convidada passa a ter os dois workspaces; o mesmo código de novo `404`; o convite aceito sai dos pendentes; mudar papel (`200`), papel inválido (`400`), mudar o papel do dono (`409`) e quem não é dono mudando papel (`403`); o dono não sai nem é removido (`409`); convite revogado `204` e o código deixa de valer (`404`, igual ao inexistente); a convidada sai sozinha (`204`) e perde o acesso; o dono remove um membro; sem cookie `401`. A limpeza removeu só os dados `qs27*`.

## 4. Decisões

- O código do convite vive só no estado da folha e na chamada: nunca em endereço, armazenamento ou registro (testado: some do documento ao fechar).
- As telas menos usadas (orçamentos, cartão, categorias, recorrências, membros e as folhas delas) agora **carregam sob demanda**: o JavaScript inicial caiu para 114 KB mesmo com as telas novas, e o orçamento de 150 KB fica folgado.
- O contexto de sessão ganhou `refresh()` (relê o `/me` sem a tela de "carregando"), usado ao aceitar um convite e ao sair de um workspace.

## 5. Fora desta spec

Trocar o dono do workspace e apagar o workspace (a API não tem); o envio do convite por e-mail (decisão do projeto: sem e-mail por ora).
