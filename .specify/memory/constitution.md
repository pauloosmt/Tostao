# Tostão Constitution

## Core Principles

### I. Segredos e autorização no servidor (NON-NEGOTIABLE)
Segredos (chaves, senhas, connection strings, `JWT_SECRET`) MUST vir de variável de ambiente e nunca
entrar no repositório. Todo arquivo local de segredos MUST estar no `.gitignore`, conferido antes do
primeiro commit. Em produção, a API MUST recusar subir se o `JWT_SECRET` ainda for o valor de
desenvolvimento. Autorização MUST ser decidida no servidor; nenhum papel ou flag vindo do cliente é
confiável.

### II. Isolamento por workspace em duas camadas (NON-NEGOTIABLE)
Todo dado pertence a um workspace e toda tabela de dados MUST ter `workspace_id`. O isolamento MUST
existir nas duas camadas ao mesmo tempo: RLS no Postgres (a aplicação conecta com uma role sem
`BYPASSRLS`, com o workspace definido por transação via `SET LOCAL`) e filtro explícito por workspace
na camada de serviço. Exceções (como achar o usuário pelo e-mail no login) MUST usar uma função
estreita e explícita, nunca desligar a proteção geral. Rotas de dados MUST ficar sob
`/api/v1/workspaces/{wid}/...` e conferir a participação do usuário antes de qualquer consulta. Todo
módulo com dados MUST ter teste automatizado que tenta acessar dados de outro workspace e espera 403
ou 404.

### III. Autenticação e rede
O JWT assinado (HMAC) MUST ser entregue só em cookie `HttpOnly`, `Secure` e `SameSite=Lax`, com sessão
stateless, senhas em BCrypt e renovação do cookie a cada requisição autenticada. É proibido receber
token por header `Authorization` montado à mão, por URL ou por query string. CSRF fica desabilitado por
causa do `SameSite`. CORS MUST aceitar uma única origem por ambiente, com `allowCredentials`. As
respostas MUST levar CSP `default-src 'none'`, `frame-ancestors 'none'`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer` e `X-Content-Type-Options`; respostas autenticadas MUST levar
`Cache-Control: no-store`. HTTPS é obrigatório fora do ambiente local.

### IV. Entrada validada, saída controlada
Todo input MUST passar por DTO com Bean Validation e lista de campos permitidos. Entidades JPA MUST
NUNCA entrar nem sair direto pela API; a resposta MUST sempre usar DTO de saída. Consultas MUST ser
parametrizadas, sem concatenação de string. Toda saída MUST ser escapada no formato do destino, não só
na tela: CSV (células iniciadas por `=`, `+`, `-` ou `@`), e-mails, PDFs, logs e nomes de arquivo.
Erros MUST sair em Problem Details (RFC 7807), sem vazar dados internos.

### V. Dados financeiros confiáveis
Dinheiro MUST ser inteiro em centavos (`BIGINT`), nunca ponto flutuante, com a moeda na conta. O
schema MUST mudar somente por migration Flyway versionada, e migration já aplicada MUST NUNCA ser
editada. Lançamentos MUST usar exclusão lógica (`deleted_at`). IDs MUST ser UUID. O saldo da conta é
calculado a partir do saldo inicial e dos lançamentos; só vira coluna materializada se a consulta
ficar comprovadamente lenta. Conta e categoria de um lançamento MUST pertencer ao mesmo workspace,
garantido por chave estrangeira composta `(id, workspace_id)`.

### VI. Transações de banco sem tentativa e erro
Uma transação do Postgres que falha aborta inteira. O código MUST checar a pré-condição antes de agir
(por exemplo, e-mail já existente) e MUST NUNCA tentar e cair no `catch` dentro da mesma
`@Transactional`.

### VII. Simplicidade na escala do projeto
Um usuário hoje, um backend, um banco. Infraestrutura ou abstração pensada para milhares de usuários
ou para necessidades futuras especulativas MUST ser rejeitada; complexidade nova MUST ter justificativa
concreta. Três linhas parecidas valem mais que uma abstração precoce.

### VIII. Prova, não promessa
Funcionalidade só conta como pronta com evidência real: teste automatizado, chamada de API, log ou
captura de tela. Regras de saldo e transferência MUST ter testes unitários; integração MUST usar
Testcontainers com Postgres real. Bug relatado MUST ser reproduzido antes de ser corrigido.

## Restrições Técnicas e Guard-rails

- **Stack aprovada**: Java 21, Spring Boot, Maven, PostgreSQL, Flyway, Spring Security,
  springdoc-openapi; front-end em React, TypeScript e Vite como PWA. Mudar a stack exige emenda desta
  constituição.
- **Ambientes**: o desenvolvimento local MUST usar o Postgres do Docker Compose e NUNCA apontar para o
  banco de produção. Dado de teste criado numa sessão MUST ser limpo depois.
- **Hospedagem**: front na Vercel, API no Render e banco no Supabase. O navegador MUST falar só com o
  domínio da Vercel; `/api/*` é encaminhado ao Render por rewrite, para o cookie `SameSite=Lax`
  funcionar.
- **Guard-rails automatizados**: o lint do front MUST bloquear `Authorization` manual e token ou senha
  em rota ou query string; o CI MUST rodar build e testes a cada push.
- **Backup**: `pg_dump` agendado e teste periódico de restauração.
- **Observabilidade**: Actuator para saúde e métricas; logs estruturados sem dados sensíveis.

## Fluxo de Desenvolvimento

- Toda feature nova segue o ciclo Spec Kit: `specify`, `clarify` (se houver dúvida real), `plan`,
  `tasks`, `analyze`, `implement`. Specs têm numeração sequencial que nunca é reaproveitada, e cada
  spec MUST ser conferida contra esta constituição antes do `plan`.
- Ficam fora do ciclo: bug pontual, CSS, configuração e correção de segurança urgente. Para esses:
  investigar, corrigir, testar de verdade e commitar.
- Commits MUST seguir Conventional Commits, ser pequenos, com mensagem curta e uma só ideia, e MUST NUNCA
  incluir co-author.
- Nunca afirmar que git, deploy ou migration aconteceu sem checar. Perguntar antes de decisão grande e
  difícil de reverter (migration em produção, apagar dado real, mudar arquitetura, force push).
- Antes de concluir que um deploy falhou, testar em janela anônima: o service worker do PWA e o cache
  do navegador podem servir a versão antiga.

## Governance

Esta constituição prevalece sobre qualquer outra prática do projeto. Ela só muda rodando
`/speckit-constitution`, nunca por edição manual. Violação de princípio MUST é erro crítico no
`/speckit-analyze` e bloqueia o `/speckit-implement`. Emendas seguem versionamento semântico: MAJOR
para remoção ou redefinição incompatível de princípio, MINOR para princípio ou seção nova, PATCH para
esclarecimentos. Toda spec e plano MUST declarar conformidade, e desvios exigem justificativa
registrada no plano.

**Version**: 1.0.0 | **Ratified**: 2026-11-05 | **Last Amended**: 2026-11-05
