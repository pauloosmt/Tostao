# Research: Autenticação e Workspace

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Como o JWT em cookie entra no Spring Security

- **Decisão**: usar o suporte a resource server com Nimbus (`NimbusJwtDecoder` e `NimbusJwtEncoder`,
  HS256) e um `BearerTokenResolver` customizado que lê o valor do cookie `tostao_session`. O resolvedor
  nunca olha o header `Authorization`, nem parâmetros de formulário ou query string.
- **Motivo**: reaproveita validação de assinatura, expiração e montagem de `Authentication` já testadas,
  sem filtro de segurança escrito à mão. As dependências já estão no `pom.xml`.
- **Alternativas**: filtro próprio que valida o JWT (mais código para errar); sessão do servidor com
  `HttpSession` (contraria o princípio de sessão stateless da constituição).

## 2. Renovação e expiração

- **Decisão**: o JWT leva `sub` (id do usuário), `iat` e `exp` (365 dias). Um filtro posterior à
  autenticação emite um novo token e um novo `Set-Cookie` a cada requisição autenticada (janela
  deslizante de 1 ano). Na prática a pessoa só sai pelo perfil: o logout apaga o cookie com `Max-Age=0`.
- **Motivo**: cumpre FR-010 e FR-011 sem tabela de refresh token (o escopo já a descartou). Não há `exp`
  infinito porque um JWT sem validade nunca poderia ser invalidado por tempo.
- **Limite conhecido**: sem estado no servidor, um token copiado (por exemplo, de um celular perdido)
  continua válido até expirar, mesmo depois do logout. Com sessão de 1 ano o risco cresce. A spec define o
  logout como invalidação no navegador (FR-011) e o risco é aceito por ora; o caminho para encerrá-lo é
  uma coluna `session_version` em `users` conferida no decodificador, que permitiria "sair de todos os
  aparelhos".
- **Alternativas**: tabela de sessões (estado, mais uma tabela, e o escopo já a descartou); token de
  acesso curto mais refresh (complexidade sem ganho nesta escala).

## 3. Atributos e nome do cookie

- **Decisão**: nome `tostao_session`; `HttpOnly`; `Secure` controlado por `COOKIE_SECURE`; `SameSite=Lax`;
  `Path=/api` (o navegador só o envia para a API); `Max-Age` de 365 dias (renovado a cada uso). Construído com `ResponseCookie`.
- **Motivo**: segue a constituição (princípio III) e o desenho do rewrite na Vercel, que mantém o
  navegador sempre no mesmo domínio.

## 4. Verificação de partida

- **Decisão**: um componente `SecurityStartupCheck` falha a inicialização se `JWT_SECRET` tiver menos de
  32 bytes, ou se `COOKIE_SECURE=true` e o segredo for igual ao valor de desenvolvimento.
- **Motivo**: cumpre o princípio I (guard-rail automatizado) e é testável.

## 5. Role de aplicação sem `BYPASSRLS`

- **Decisão**: a migration `V2` cria `tostao_app` (`LOGIN`, `NOBYPASSRLS`, `NOSUPERUSER`) de forma
  idempotente, com a senha vinda de um placeholder do Flyway (`${app_password}`) alimentado pela
  variável `DB_APP_PASSWORD`. As tabelas continuam pertencendo ao usuário de migração, então as políticas
  valem para `tostao_app` sem precisar de `FORCE`.
- **Motivo**: um mecanismo só para Compose, Testcontainers e Supabase, sem scripts de inicialização que
  rodam antes das migrations. O Flyway calcula o checksum sobre o script bruto, então a senha não afeta o
  checksum e não fica no repositório.
- **Atenção**: o Flyway passa a usar conexão própria (`DB_MIGRATION_*`, o dono do schema), e o
  `spring.datasource` usa `tostao_app`. Rotação de senha é feita à mão com `ALTER ROLE`, não editando
  a migration. No Supabase, o usuário do pooler tem o formato `tostao_app.<ref-do-projeto>`.
- **Alternativas**: criar a role fora das migrations (passo manual esquecível e inviável no
  Testcontainers); `FORCE ROW LEVEL SECURITY` com uma única role (o dono ainda precisaria de exceções
  para migrar).

## 6. Como o contexto do workspace chega ao banco

- **Decisão**: `TenantSession.enter(userId, workspaceId)` executa
  `select set_config('app.user_id', ?, true), set_config('app.workspace_id', ?, true)`, com valores
  parametrizados. O terceiro argumento `true` limita o valor à transação, como `SET LOCAL`. As políticas
  leem `current_setting('app.workspace_id', true)::uuid`, e valor ausente resulta em nenhuma linha visível.
- **Motivo**: não vaza entre requisições que reaproveitam a conexão do pool, e `SET LOCAL` literal não
  aceita parâmetro.
- **Cuidado**: o método exige transação ativa e lança erro caso contrário, para nunca definir contexto
  fora de uma transação.

## 7. Onde fica a checagem de participação

- **Decisão**: anotação `@WorkspaceScoped` em métodos de serviço cujo primeiro parâmetro é o `workspaceId`,
  tratada por um aspecto com ordem inferior à do advice de transação, de modo que rode dentro dela. O
  aspecto lê o usuário do `SecurityContext`, consulta `workspace_members` (com `app.user_id` já
  definido), lança `NotFound` se não houver linha e então fixa `app.workspace_id`.
- **Motivo**: um único ponto de aplicação da regra, impossível de esquecer em um controlador novo se o
  serviço for anotado, e coerente com os módulos 002 a 005, que herdam a anotação.
- **Resposta**: 404 tanto para workspace de outra pessoa quanto para inexistente (FR-016). Sem sessão, 401.
- **Alternativas**: checagem manual em cada controlador (esquecível); `HandlerInterceptor` (roda fora
  da transação, então não consegue fixar o contexto de banco).

## 8. Funções `SECURITY DEFINER`

- **Decisão**: cinco funções estreitas, todas com `SET search_path = public, pg_temp`, `REVOKE ... FROM
  PUBLIC` e `GRANT EXECUTE` só para `tostao_app`: `register_user`, `find_user_for_login`,
  `login_lockout_status`, `record_login_failure` e `reset_login_attempts`. Ver
  [data-model.md](data-model.md).
- **Motivo**: são as únicas operações que precisam enxergar linhas sem workspace definido (FR-020).
  Rodam como o dono das tabelas, então não dependem de `BYPASSRLS` nem de desligar políticas.
- **Cadastro atômico**: `register_user` insere usuário, workspace e associação OWNER em uma chamada.
  Usa `INSERT ... ON CONFLICT DO NOTHING` no índice de e-mail e devolve vazio em caso de conflito, sem
  levantar exceção que abortaria a transação (princípio VI).

## 9. Limite de tentativas

- **Decisão**: tabela `login_attempts` com chave no e-mail normalizado. `record_login_failure` faz um
  upsert atômico: se o bloqueio anterior já expirou, a contagem recomeça em 1; ao chegar em 5, grava
  `locked_until = now() + 15 min`. `login_lockout_status` devolve o instante de desbloqueio ou nulo. Em
  login bem-sucedido, `reset_login_attempts` apaga a linha.
- **Motivo**: sobrevive a reinício (FR-013), é atômico sob concorrência e trata e-mails inexistentes
  igual aos existentes, porque a chave é o texto do e-mail, não o usuário.
- **Custo aceito**: um atacante pode criar linhas para e-mails arbitrários. Mitigação simples: a
  função de falha apaga linhas com mais de 24 horas sem bloqueio ativo a cada chamada limitada.
- **Alternativas**: contador em memória (reiniciar zera, vai contra FR-013); Bucket4j ou Redis
  (infraestrutura além da escala, princípio VII); limite por IP (fora do escopo, depende da hospedagem).

## 10. Tempo de resposta uniforme

- **Decisão**: quando o e-mail não existe, comparar a senha recebida com um hash BCrypt fictício
  pré-computado na partida, para que os dois caminhos paguem o custo do BCrypt. A mensagem de erro é a
  mesma nos dois casos.
- **Motivo**: cumpre FR-009 e SC-006 sem atraso artificial.

## 11. Ordem do login e transações

- **Decisão**: o login roda sem `@Transactional` abrangente. Cada passo (status de bloqueio, busca do
  usuário, registro de falha ou zeragem) é uma chamada curta de função que confirma sozinha. A falha de
  senha é um retorno normal, nunca uma exceção dentro de transação, então o contador sempre persiste.
- **Motivo**: se o registro de falha ficasse na mesma transação que depois faz rollback por exceção, o
  contador seria desfeito (princípio VI).

## 12. Política de senha e limites

- **Decisão**: mínimo de 8 caracteres, com ao menos uma letra e um número, e máximo de 72 bytes em UTF-8
  (limite real do BCrypt, que trunca em silêncio acima disso). Nome com até 100 caracteres; e-mail com
  até 254, normalizado com `trim` e `toLowerCase(Locale.ROOT)`.
- **Motivo**: evita aceitar senha que o BCrypt cortaria sem avisar.

## 13. CORS e cabeçalhos

- **Decisão**: `CorsConfigurationSource` com uma origem (`CORS_ORIGIN`), `allowCredentials=true`, métodos
  `GET, POST, PUT, DELETE, OPTIONS`, sem curingas. Os cabeçalhos CSP, `X-Frame-Options`,
  `Referrer-Policy` e `X-Content-Type-Options` já existem na `SecurityConfig` da fase 0. O
  `Cache-Control: no-store` é o padrão do Spring Security para respostas protegidas.

## 14. Erros

- **Decisão**: `@RestControllerAdvice` produzindo `ProblemDetail` (RFC 7807) para validação (400, com
  lista de campos), e-mail já cadastrado (409), credenciais inválidas (401), bloqueio (429 com
  `Retry-After`) e não encontrado (404). Entry point e handler de acesso negado do Spring Security também
  respondem em Problem Details e sem o header `WWW-Authenticate: Bearer`.
- **Motivo**: contrato uniforme (princípio IV) e nenhum vazamento de detalhe interno.

## 15. Documentação da API

- **Decisão**: adicionar `springdoc-openapi` e manter o contrato desenhado em
  [contracts/openapi.yaml](contracts/openapi.yaml) como referência para os testes. A UI do Swagger fica
  fechada fora do perfil local.
- **Motivo**: o escopo prevê Swagger UI, e o contrato escrito antes serve de checagem contra o que a
  implementação gera.

## 16. Teste de isolamento

- **Decisão**: o Testcontainers sobe o Postgres com o usuário de migração e a role `tostao_app` criada
  pela própria `V2` (placeholder de senha vindo de propriedade de teste). O teste de RLS conecta como
  `tostao_app` e consulta tabelas sem `WHERE`. O teste de camada de serviço usa a conexão do dono, que
  ignora RLS, para provar que o filtro explícito sozinho já isola (SC-003). Uma verificação extra falha o
  build se qualquer tabela de `public` sem exceção listada estiver sem RLS ligado.
- **Motivo**: prova as duas camadas separadamente e protege os módulos 002 a 005 de esquecerem o RLS.

## Resultado

Nenhum item ficou em aberto: todas as dúvidas do Technical Context foram resolvidas acima.
