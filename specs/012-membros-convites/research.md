# Research: Membros e convites

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. O convite é um código secreto, não um link assinado nem um e-mail

- **Decisão**: o convite gera um código aleatório de 256 bits (32 bytes de `SecureRandom`, Base64 URL-safe sem
  preenchimento, 43 caracteres). Ele volta **só** na resposta que cria o convite; o banco guarda apenas o SHA-256 em
  hexadecimal (64 caracteres), com índice único.
- **Motivo**: o sistema não envia e-mail (a spec decide assim: sem infraestrutura de mensagens nem confirmação de
  e-mail), então a entrega é do dono. Um segredo aleatório de uso único, amarrado a um e-mail e a um workspace, é o
  menor mecanismo que impede alguém de entrar sem ter sido convidado. Com 256 bits não há como adivinhar, então não
  é preciso limitar tentativas nem usar hash lento.
- **Impressão em vez do código**: se o banco vazar, as impressões não servem para aceitar convites (a função recebe o
  código e calcula a impressão).
- **Alternativas descartadas**: JWT assinado com o convite dentro (não se revoga sem uma lista, e o segredo de assinatura
  vira peça sensível nova); link com o identificador do convite (identificador não é segredo); código curto de 6
  dígitos (precisaria de limite de tentativas e continuaria adivinhável); guardar o código para reexibi-lo (obrigaria a
  protegê-lo como senha, e a spec manda mostrar uma vez).

## 2. O e-mail amarra o código a uma pessoa

- **Decisão**: o e-mail do convite é normalizado como no cadastro (`EmailNormalizer`) e o aceite só vale se o e-mail da
  **conta** de quem aceita é igual. O e-mail da conta é lido da tabela `users` dentro da função, a partir do id da pessoa
  que vem da sessão; nunca do corpo da requisição.
- **Motivo**: sem isso, quem obtivesse o código (um print, uma conversa encaminhada) entraria no workspace. Com a
  amarra, o código sozinho não basta: é preciso também ser a pessoa convidada.
- **Resposta uniforme**: código inexistente, expirado, usado, revogado e de outro e-mail respondem **exatamente** igual
  (404 "Convite inválido ou expirado"), para ninguém descobrir quem foi convidado nem testar e-mails.

## 3. Um só OWNER, garantido pelo banco

- **Decisão**: `CREATE UNIQUE INDEX workspace_members_one_owner ON workspace_members (workspace_id) WHERE role = 'OWNER'`.
- **Motivo**: a spec define um dono por workspace e proíbe convidar, promover ou rebaixar o dono. A restrição no banco
  garante a regra mesmo que um defeito de serviço a esquecesse. Todo workspace existente tem exatamente um OWNER (o
  `register_user` cria o pessoal assim), então o índice é aplicável sem migração de dados.
- **Alternativas descartadas**: só regra de serviço (sem prova no banco); permitir vários donos (precisaria de regra de
  "último dono" e transferência, fora do escopo).

## 4. Políticas novas em `workspace_members` e `users`

- **Decisão (`V12`)**:
  - `GRANT UPDATE (role), DELETE ON workspace_members TO tostao_app`; **sem** `INSERT` (o vínculo só nasce pelo cadastro e
    pela função de aceite).
  - Política de `UPDATE`: `USING (workspace_id = app_workspace_id() AND app_is_owner(workspace_id) AND role <> 'OWNER' AND
    user_id <> app_user_id()) WITH CHECK (workspace_id = app_workspace_id() AND role IN ('EDITOR','VIEWER'))`.
  - Política de `DELETE`: `USING (workspace_id = app_workspace_id() AND role <> 'OWNER' AND (app_is_owner(workspace_id)
    OR user_id = app_user_id()))`.
  - Política de leitura de `users`: `users_comembers` (`FOR SELECT`) para quem divide o workspace do contexto com o
    usuário. A coluna-a-coluna já limita o que `tostao_app` lê (`id, name, email, created_at`); o hash e a versão de
    sessão continuam inalcançáveis.
  - `app_is_owner(p_workspace)`: como `app_can_write`, mas só `role = 'OWNER'`.
- **Motivo**: o banco passa a impedir, por conta própria, que alguém além do dono altere ou apague vínculos alheios, que o
  dono mude o próprio papel ou o de outro dono, e que alguém vire OWNER por `UPDATE`. A listagem de membros precisa
  mostrar nome e e-mail dos co-membros, e hoje `users_self` só deixa ver a si mesmo.
- **Alternativas descartadas**: devolver o nome pela função `SECURITY DEFINER` (uma função a mais para um `SELECT`
  simples); `UPDATE` de tabela inteira (afrouxaria a imutabilidade do vínculo).

## 5. A tabela `invitations` e quem a enxerga

- **Decisão**: colunas `id`, `workspace_id`, `email`, `role`, `code_hash`, `created_by`, `created_at`, `expires_at`,
  `accepted_at`, `accepted_by`, `revoked_at`; RLS ligado; `SELECT`, `INSERT` e `UPDATE (revoked_at)` para `tostao_app`;
  políticas que exigem `workspace_id = app_workspace_id() AND app_is_owner(workspace_id)` (e `created_by =
  app_user_id()` no `INSERT`). Sem `DELETE`: o convite é um registro (usado ou revogado fica no histórico).
- **Um convite pendente por e-mail**: índice único parcial `(workspace_id, email) WHERE accepted_at IS NULL AND revoked_at
  IS NULL`. Convidar de novo revoga o pendente anterior (inclusive expirado) na mesma transação, antes do `INSERT`.
- **`accepted_at` e `accepted_by`**: só a função de aceite os escreve (sem privilégio de `UPDATE` para a aplicação).
- **Motivo**: só o dono precisa ver e mexer em convites; um EDITOR ou VIEWER não enxerga nem os e-mails pendentes.

## 6. A função `accept_invitation`

- **Decisão**: `accept_invitation(p_code_hash TEXT, p_user_id UUID) RETURNS TABLE (outcome TEXT, workspace_id UUID,
  workspace_name TEXT, role TEXT)`, `SECURITY DEFINER`, `search_path` fixo, `EXECUTE` só para `tostao_app`. Passos:
  1. ler o e-mail da pessoa em `users` (`WHERE id = p_user_id`); sem linha, `outcome = 'INVALID'`;
  2. `SELECT ... FROM invitations WHERE code_hash = p_code_hash FOR UPDATE` (trava o convite);
  3. `INVALID` se não existe, `accepted_at` ou `revoked_at` preenchidos, `expires_at <= now()` ou e-mail diferente;
  4. travar a linha do workspace (`FOR UPDATE`), serializando os aceites do mesmo workspace;
  5. `ALREADY_MEMBER` se a pessoa já é membro (o convite **não** é consumido); `FULL` se o workspace tem 20 membros;
  6. `INSERT` do vínculo com o papel do convite, marcar `accepted_at` e `accepted_by`, `outcome = 'ACCEPTED'`.
- **Sem exceção**: os desfechos voltam como `outcome`, então o serviço mapeia `INVALID` para 404, `ALREADY_MEMBER` e
  `FULL` para 409, sem `catch` (princípio VI).
- **Corrida**: dois aceites do mesmo código disputam o `FOR UPDATE` do convite; o segundo relê o convite já usado e recebe
  `INVALID`. Dois aceites de convites diferentes no mesmo workspace disputam a trava do workspace, então o limite de 20
  nunca é ultrapassado.
- **Alternativas descartadas**: duas instruções no serviço (ler o convite e inserir o vínculo) sob RLS: o RLS não deixa
  uma pessoa que ainda não é membro ler `invitations` nem inserir em `workspace_members`.

## 7. `@WorkspaceScoped(owner = true)`

- **Decisão**: novo atributo `owner` (padrão `false`) em `@WorkspaceScoped`. O aspecto, que já lê o papel do banco,
  responde 403 (`InsufficientRoleException`) quando `owner = true` e o papel não é OWNER; para quem não é membro continua
  404. `write` e `owner` são independentes: `owner = true` implica o mesmo que `write = true` para o dono.
- **Motivo**: a autorização de gestão fica declarada no método (como `write`), na mesma camada que todo o sistema já usa,
  e não espalhada por `if` nos serviços. O padrão `false` mantém todos os usos atuais intactos.

## 8. Remover, sair e mudar papel: as regras no serviço, a garantia no banco

- **Decisão**: `MemberService` lê o alvo sob RLS (`members_visible`); não achou no workspace = 404 (`MemberNotFound`).
  - **Mudar papel** (`owner = true`): alvo OWNER, o próprio dono ou papel pedido OWNER = 409 (`MembershipConflict`); papel
    inválido = 400 no campo `role`; papel igual ao atual responde 200 sem efeito.
  - **Remover ou sair** (`@WorkspaceScoped`, qualquer membro): alvo OWNER = 409; quem chama não é dono e o alvo não é ele
    mesmo = 403; senão `DELETE`. O dono removendo a si mesmo cai no caso "alvo OWNER" = 409.
- **Motivo**: as mensagens e os códigos precisos ficam no serviço, e as políticas do `V12` repetem as mesmas regras como
  rede de proteção: se um defeito do serviço deixasse passar, o banco recusaria.
- **Efeito imediato**: a autorização é lida do banco em cada requisição (`WorkspaceScopeAspect`), então a próxima
  requisição da pessoa afetada já recebe o novo papel ou 404. As sessões não são invalidadas (a pessoa continua logada
  para o resto do sistema).

## 9. Convidar com limites e concorrência

- **Decisão**: `InvitationService.create` (`owner = true`, transação) toma `pg_advisory_xact_lock` com uma chave derivada
  do `workspace_id`, e depois: confere e-mail e papel (400 por campo), recusa se o e-mail já é membro (409), revoga o
  pendente do mesmo e-mail, recusa se há 20 convites pendentes ou 20 membros (409) e insere o convite, devolvendo o código.
- **Motivo**: a trava consultiva serializa os convites do mesmo workspace sem precisar de privilégio de `UPDATE` na tabela
  `workspaces` (que a aplicação só lê). Sem ela, dois convites simultâneos poderiam ultrapassar os 20 pendentes.
- **Validade**: `expires_at = now() + 7 days`, calculada no banco (mesmo relógio que a função de aceite usa).
- **Pendentes na lista**: `accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()`.

## 10. Nada de segredo em resposta posterior nem em registro

- **Decisão**: o DTO de criação (`InvitationCreatedResponse`) é o único com o campo `code`; o de listagem não tem. O
  `toString` de um e do pedido de aceite mascara o código (como `AuthResult` faz com o token). Nenhum log registra o
  corpo; o teste captura a saída do processo (`OutputCaptureExtension`) durante convidar e aceitar e confirma que o código
  não aparece. A resposta de criação leva `Cache-Control: no-store` (já aplicado a toda resposta autenticada).

## 11. Rotas e respostas

- **Decisão**: `GET /workspaces/{wid}/members` (qualquer membro); `PUT /workspaces/{wid}/members/{userId}` (corpo
  `{role}`, dono); `DELETE /workspaces/{wid}/members/{userId}` (204; dono remove, membro sai); `POST
  /workspaces/{wid}/invitations` (201 com o código); `GET /workspaces/{wid}/invitations` (dono); `DELETE
  /workspaces/{wid}/invitations/{id}` (204, dono); `POST /invitations/accept` (200 com o workspace e o papel).
- **Motivo**: `DELETE` com o próprio id é a forma única de "sair", sem rota extra. `{userId}` e `{id}` malformados viram 404
  pelo tratador de `PathVariable` que já existe.
- **Alternativas descartadas**: `POST /leave` separado (duas rotas para o mesmo efeito); `PATCH` para o papel (o
  projeto usa `PUT`).

## 12. Efeito em specs anteriores

- **Decisão**: nenhuma consulta nem operação existente muda de resultado. O aspecto ganha um atributo com padrão `false`;
  `workspace_members` ganha privilégios e políticas só de `UPDATE` e `DELETE` (que nenhum código atual usa); `users`
  ganha uma política de leitura que só **acrescenta** linhas visíveis a quem já está num workspace compartilhado;
  `GET /api/v1/me` continua lendo as próprias linhas (`user_id = app_user_id()`).
- **Risco verificado**: `users_comembers` pode fazer `GET /me` ver mais de uma linha de `users`? Não: a consulta filtra
  `where id = :id`, e `MeService` entra só com o contexto de usuário (sem workspace), então a política não casa com
  ninguém além da própria pessoa.
