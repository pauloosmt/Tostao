# Implementation Plan: Membros e convites

**Branch**: `012-membros-convites` | **Date**: 2026-11-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/012-membros-convites/spec.md`

## Summary

O dono de um workspace convida outras pessoas pelo e-mail, com o papel EDITOR ou VIEWER, e a pessoa convidada aceita com
um código secreto de uso único. Qualquer membro vê quem faz parte; o dono muda papéis, remove membros e revoga convites;
quem não é dono pode sair. Os papéis já são aplicados em todo o sistema (specs 001 a 011): esta funcionalidade acrescenta
como o vínculo nasce, muda e termina.

Abordagem técnica (decisões detalhadas em [research.md](research.md)):

- **Migration `V12` (schema)**: tabela `invitations` com RLS só para o OWNER; `UPDATE (role)` e `DELETE` em
  `workspace_members` sob políticas novas (só o dono altera ou remove quem não é dono, e cada membro pode sair); o índice
  único que garante **um só OWNER por workspace**; a política que deixa os co-membros verem nome e e-mail uns dos outros;
  e a função `app_is_owner`. **Migration `V13` (função)**: `accept_invitation`, `SECURITY DEFINER` estreita, no padrão das
  funções da `V2`.
- **O aceite é a única operação sem workspace**: a pessoa ainda não é membro, então o RLS por workspace não a alcança. A
  função valida o código (pela impressão SHA-256), a validade, o estado e o **e-mail da conta, lido do banco** (nunca do
  corpo), trava o convite e o workspace, confere o limite de 20 membros e cria o vínculo, tudo numa instrução do banco,
  atômica sob corrida. Qualquer falha de código responde igual.
- **O código** tem 256 bits (32 bytes de `SecureRandom` em Base64 URL-safe), aparece só na resposta que cria o convite e
  nunca é guardado nem registrado: o banco guarda só o SHA-256 em hexadecimal, com índice único.
- **`@WorkspaceScoped(owner = true)`**: atributo novo que o aspecto existente confere (OWNER passa, EDITOR e VIEWER
  recebem 403, não membro recebe 404). O padrão `write = false/true` continua igual.
- **Módulo novo `membership`**: `MemberService` e `MemberController` (listar, mudar papel, remover ou sair),
  `InvitationService` e `InvitationController` (convidar, listar, revogar, aceitar), `InvitationCodes` (geração e impressão).
  Rotas: `GET /api/v1/workspaces/{wid}/members`, `PUT` e `DELETE /api/v1/workspaces/{wid}/members/{userId}`,
  `POST` e `GET /api/v1/workspaces/{wid}/invitations`, `DELETE /api/v1/workspaces/{wid}/invitations/{id}` e
  `POST /api/v1/invitations/accept`.
- **Sem e-mail**: o código volta na resposta e o dono o repassa por outro meio.
- **Efeito imediato**: a autorização já é lida do banco a cada requisição, então mudar papel, remover e sair valem na
  próxima requisição, sem tocar em sessões. `GET /api/v1/me` (spec 001) já lê os workspaces do banco e passa a incluir os
  aceitos sem mudança.
- **Escopo**: somente backend.

## Technical Context

**Language/Version**: Java 21 (Temurin), Spring Boot 4.1.2

**Primary Dependencies**: Spring Web MVC, Spring JDBC (`JdbcClient`), Bean Validation, Spring Security (contexto e aspecto
da `001`), springdoc-openapi, Flyway. Nenhuma dependência nova (`SecureRandom` e `MessageDigest` são do JDK).

**Storage**: PostgreSQL 17. `V12` (tabela, políticas, índice único de dono) e `V13` (função de aceite).

**Testing**: JUnit 5, MockMvc, Testcontainers (Postgres real, role `tostao_app`), `OwnerJdbc` para semear e inspecionar,
threads reais para a corrida do aceite, `OutputCaptureExtension` para provar que o código não vai a nenhum registro.

**Target Platform**: servidor Linux em container (Render); desenvolvimento em Windows com Docker Desktop.

**Project Type**: serviço web (API REST) em monorepo `backend/` e `frontend/`.

**Performance Goals**: convidar, aceitar e listar membros em menos de 1 s cada (SC-011).

**Constraints**: nenhum segredo em log nem em resposta posterior; toda consulta das requisições filtra por `workspace_id`;
nenhuma exceção capturada dentro de transação; entrada validada antes de qualquer gravação; a resposta do aceite não
distingue os motivos de um código inválido.

**Scale/Scope**: no máximo 20 membros e 20 convites pendentes por workspace.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Princípio | Como o plano atende | Status |
|---|---|---|
| I. Segredos e autorização no servidor | O código é gerado no servidor, mostrado uma vez e guardado só como SHA-256; o e-mail do aceite vem da conta, não do corpo; papel e participação vêm do banco no aspecto; `created_by` vem da sessão; sem segredo novo de configuração | OK |
| II. Isolamento em duas camadas | `invitations` com RLS só para o OWNER do workspace do contexto e filtro explícito por `workspace_id` em toda consulta; `workspace_members` com políticas de alteração e remoção; teste de acesso cruzado por operação e com cada camada removida. O aceite roda antes do vínculo e por isso usa uma função estreita (Complexity Tracking) | OK, com a ressalva registrada |
| III. Autenticação e rede | Reaproveita a `SecurityConfig` (todo `/api/v1/**` exige sessão); `Cache-Control: no-store` também na resposta que traz o código; nenhuma rota pública nova | OK |
| IV. Entrada validada, saída controlada | DTOs com campos fechados e Bean Validation (e-mail, papel, código), erro por campo em Problem Details, consultas parametrizadas, DTOs de saída próprios que nunca trazem hash, código nem dados de outros workspaces | OK |
| V. Dados financeiros confiáveis | Os dados que a pessoa criou ficam no workspace (`created_by` não muda e a remoção não apaga nada); o vínculo muda só por colunas privilegiadas; schema só por migration | OK |
| VI. Sem tentativa e erro em transação | Limites e estados conferidos antes de gravar; unicidade do convite pendente e do dono garantida por índice; o aceite devolve um resultado (`outcome`) em vez de levantar exceção; nenhum `catch` dentro de transação | OK |
| VII. Simplicidade | Um módulo pequeno, duas migrations, uma função; sem e-mail, sem fila, sem token JWT de convite; o código é um segredo aleatório comparado por impressão | OK |
| VIII. Prova, não promessa | Função de aceite provada direto no banco; corrida de 20 aceites; código ausente de listagens, erros e registros; isolamento por camada; papéis com efeito imediato; [quickstart.md](quickstart.md) real | OK |

**Ponto de atenção (alcance)**: acrescenta um atributo ao aspecto de autorização, políticas em `workspace_members` e
`users` e um índice único em `workspace_members`. Mitigação: a suíte inteira das specs 001 a 011 roda sem alteração
depois de cada passo (FR-023, SC-010); o atributo novo tem padrão `false`.

Reavaliação depois do desenho (Phase 1): nenhuma mudança.

## Project Structure

### Documentation (this feature)

```text
specs/012-membros-convites/
├── plan.md              # Este arquivo
├── research.md          # Decisões e alternativas
├── data-model.md        # Tabela, políticas, função, regras e respostas
├── quickstart.md        # Roteiro de validação
├── contracts/
│   └── openapi.yaml     # Contrato da API
└── tasks.md             # Tarefas
```

### Source Code (repository root)

```text
backend/
├── src/main/resources/db/migration/
│   ├── V12__members_invitations.sql                  # invitations, políticas de membros, dono único, co-membros
│   └── V13__accept_invitation.sql                    # função accept_invitation
├── src/main/java/com/tostao/tostao_api/
│   ├── workspace/
│   │   ├── WorkspaceScoped.java                      # AJUSTE: atributo owner (padrão false)
│   │   └── WorkspaceScopeAspect.java                 # AJUSTE: confere OWNER quando owner = true
│   ├── membership/
│   │   ├── InvitationCodes.java                      # NOVO: gera o código e calcula a impressão
│   │   ├── MemberService.java                        # NOVO: listar, mudar papel, remover ou sair
│   │   ├── MemberController.java                     # NOVO: 3 rotas
│   │   ├── InvitationService.java                    # NOVO: convidar, listar, revogar, aceitar
│   │   ├── InvitationController.java                 # NOVO: 4 rotas
│   │   └── dto/                                      # NOVO: pedidos e respostas
│   └── common/error/                                 # NOVO: MemberNotFoundException, InvitationNotFoundException,
│                                                     #       InvalidInvitationException, MembershipConflictException
└── src/test/java/com/tostao/tostao_api/membership/
    ├── MembershipSchemaTest.java                     # V12: tabela, políticas, dono único, co-membros
    ├── AcceptInvitationFunctionTest.java             # V13 direto no banco
    ├── InvitationCodesTest.java                      # unitário
    ├── OwnerOnlyAspectTest.java                      # atributo owner do aspecto
    ├── ListMembersTest.java                          # listar membros
    ├── ChangeRemoveMemberTest.java                   # mudar papel, remover, sair
    ├── CreateInvitationTest.java                     # convidar, listar, revogar
    ├── AcceptInvitationTest.java                     # aceitar
    ├── MembershipEffectsTest.java                    # efeito imediato nos dados das specs anteriores
    ├── MembershipAccessTest.java                     # papéis e isolamento
    ├── MembershipLayersTest.java                     # cada camada
    ├── InvitationSecretTest.java                     # o código nunca vaza
    └── MembershipConcurrencyTest.java                # corrida do aceite e desempenho
```

**Structure Decision**: continua o monólito em camadas por módulo. Membros e convites formam um módulo novo
(`membership`), porque têm tabela, rotas e regras próprias; o aspecto de autorização continua em `workspace`.

## Complexity Tracking

| Escolha | Por quê | Alternativa simples descartada |
|---|---|---|
| O aceite é uma função `SECURITY DEFINER` estreita | A pessoa que aceita ainda não é membro: o RLS por workspace (`app_workspace_id`) não existe para ela e a tabela `invitations` só é visível ao dono. A função recebe só o código e o id da pessoa (da sessão), lê o e-mail da conta, e só consegue criar o vínculo do convite que casa com ele. É o padrão da `V2` (cadastro e login) | Dar `SELECT` em `invitations` a quem não é membro (exporia convites de outros); `BYPASSRLS` na aplicação (afrouxa todo o isolamento) |
| O código é comparado pela impressão SHA-256, sem hash lento | O código tem 256 bits de entropia aleatória, então não há dicionário nem força bruta a retardar (ao contrário de uma senha); uma busca por índice único é suficiente e dá o aceite em tempo constante para qualquer entrada | Argon2/bcrypt (custo sem ganho de segurança, e não permite busca por índice) |
