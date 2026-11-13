# Tostão

![CI](https://github.com/pauloosmt/Tostao/actions/workflows/ci.yml/badge.svg)

App open source de **finanças pessoais** para o computador (Windows) e para o celular (PWA), pensado para **várias pessoas na mesma carteira**
(workspace): contas, lançamentos, orçamentos, cartão de crédito, parcelas, recorrências, importação de extratos e convites.

- Back-end: Java 21, Spring Boot 4, PostgreSQL, Flyway
- Front-end: React 19, TypeScript, Vite, instalável como PWA
- Desktop: Electron (Windows), com o front dentro
- Produção: Vercel (site) + Render (API) + Supabase (banco)

## Sumário

1. [O que o Tostão faz](#o-que-o-tostão-faz)
2. [Como as peças se encaixam](#como-as-peças-se-encaixam)
3. [Rodando no seu computador](#rodando-no-seu-computador)
4. [Configuração](#configuração)
5. [Testes e qualidade](#testes-e-qualidade)
6. [A API](#a-api)
7. [Segurança e decisões de projeto](#segurança-e-decisões-de-projeto)
8. [App desktop (Windows)](#app-desktop-windows)
9. [Publicando (Supabase, Render e Vercel)](#publicando-supabase-render-e-vercel)
10. [Manter a API acordada (UptimeRobot)](#manter-a-api-acordada-uptimerobot)
11. [Levar os dados locais para a produção](#levar-os-dados-locais-para-a-produção)
12. [Estrutura do repositório](#estrutura-do-repositório)
13. [Como o projeto é desenvolvido](#como-o-projeto-é-desenvolvido)

## O que o Tostão faz

| Área | O que tem |
|---|---|
| **Conta e carteira** | Cadastro e login por cookie de sessão; "sair de todos os aparelhos"; bloqueio de 15 minutos após 5 tentativas erradas; uma carteira (workspace) por conta, compartilhável |
| **Contas** | Conta corrente, poupança, dinheiro, investimento e cartão de crédito (com limite e dias de fechamento e vencimento), com saldo inicial; arquivar e restaurar |
| **Categorias** | Dois níveis (categoria e subcategoria), com ícone e cor; arquivar e restaurar |
| **Lançamentos** | Receita, despesa e transferência entre contas; efetivados ou pendentes ("a pagar/receber"), com paginação e filtros por período, conta, categoria, tipo, situação e texto |
| **Início (dashboard)** | Receitas, despesas e resultado do período com a variação sobre o anterior, pendentes, receitas e despesas por categoria (com subcategorias) e evolução diária; botão "Fechar carteira" para esconder o saldo |
| **Orçamentos** | Limite mensal por categoria e limite semanal, com cópia do período anterior |
| **Cartão de crédito** | Fatura de cada mês, resumo do cartão e pagamentos (uma transferência para o cartão) |
| **Compras parceladas** | Uma compra vira N parcelas nos meses certos; cancelar o restante |
| **Recorrências** | Lançamentos e transferências que se repetem (geradas por uma rotina horária da API) |
| **Membros e convites** | Papéis de dono, editor e leitor; convite por código; sair ou remover membros |
| **Importação de extratos** | Arquivos CSV e OFX (até 2 MB) com prévia, detecção de duplicados e sugestão de categoria antes de gravar |
| **Relatórios** | Saldo de cada conta e total da carteira, com o saldo previsto considerando os pendentes |
| **PWA e desktop** | Instalável no celular; app Windows (Electron) que abre direto na carteira |

Dinheiro é sempre guardado em **centavos inteiros**, e a interface é toda em português do Brasil.

## Como as peças se encaixam

```
                          navegador / PWA                     app desktop (Electron)
                                 │                                   │
                                 ▼                                   ▼
                  ┌──────────────────────────┐        ┌──────────────────────────┐
                  │ Vercel: site (React)     │        │ servidor local 127.0.0.1 │
                  │ /api/*  ── rewrite ──┐   │        │ front + proxy de /api    │
                  └──────────────────────┼───┘        └────────────┬─────────────┘
                                         ▼                         ▼
                              ┌────────────────────────────────────────┐
                              │ Render: API Spring Boot (/api/v1/...)  │
                              └──────────────────┬─────────────────────┘
                                                 ▼
                                   Supabase: PostgreSQL (Flyway + RLS)
```

O navegador só fala com o domínio do site; `/api/*` é encaminhado à API por um rewrite da Vercel. Assim o cookie de sessão
(`HttpOnly`, `Secure`, `SameSite=Lax`) é "de primeira parte" e não precisa de configuração de CORS entre domínios. No desenvolvimento, o
proxy do Vite faz o mesmo papel; no app desktop, o servidor local.

## Rodando no seu computador

Precisa de Docker Desktop, Node.js 22+ e (para os testes do back-end) o Docker ligado.

```bash
docker compose up -d --build            # PostgreSQL (127.0.0.1:5442) e API (127.0.0.1:8080)
cd frontend && npm ci && npm run dev    # http://localhost:5173
```

- A API responde em `http://127.0.0.1:8080/api/v1/health`.
- Com o perfil `local` (padrão do Compose), a documentação interativa fica em `http://127.0.0.1:8080/swagger-ui.html`.
- O front em `npm run dev` manda `/api/*` para `http://localhost:8080`.
- Para parar: `docker compose down` (os dados ficam no volume `pgdata`; `docker compose down -v` apaga tudo).

## Configuração

Copie `.env.example` para `.env` (ignorado pelo git) para mudar os valores. Os padrões servem **só** para o ambiente local.

| Variável | Para quê | Em produção |
|---|---|---|
| `DB_MIGRATION_URL`, `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | Conexão do dono do schema (Flyway cria tabelas, role e políticas) | Supabase, usuário `postgres.<ref>` |
| `DB_URL`, `DB_USER`, `DB_APP_PASSWORD` | Conexão da API, com a role `tostao_app` (sem `BYPASSRLS`) | Supabase, usuário `tostao_app.<ref>` |
| `JWT_SECRET` | Assinatura da sessão, mínimo de 32 bytes; a API **recusa subir** fora do perfil `local` sem ele | gerado pelo Render |
| `COOKIE_SECURE` | `true` em HTTPS | `true` |
| `CORS_ORIGIN` | A única origem aceita (o endereço do site) | `https://tostao-roan.vercel.app` |

Nenhum segredo entra no repositório; tudo vem de variáveis de ambiente.

## Testes e qualidade

```bash
cd backend && ./mvnw verify          # ~1.400 testes; usa Testcontainers (Postgres real), precisa do Docker
cd frontend && npm run verify        # lint + guard-rails, tipos, ~900 testes, build e orçamento de tamanho
cd desktop && npm test               # servidor local e configuração do app desktop (node --test)
```

O CI (GitHub Actions) roda o back-end e o front a cada push. Pontos que o projeto verifica automaticamente:

- cada módulo tenta ler dados de **outro workspace** e espera 403/404;
- o lint do front proíbe cabeçalho `Authorization` manual, token em rota ou query string e recursos de outro domínio;
- o JavaScript inicial do front fica abaixo de 150 KB (gzip);
- acessibilidade (axe-core) nas telas.

## A API

Tudo em `/api/v1`, JSON, erros no formato Problem Details (RFC 7807). Rotas de dados ficam sob `/workspaces/{wid}/...` e a API confere se
você é membro da carteira antes de qualquer consulta. A referência completa e experimentável está no Swagger (perfil `local`).

| Grupo | Rotas principais |
|---|---|
| Autenticação | `POST /auth/register`, `/auth/login`, `/auth/logout`, `/auth/logout-all`; `GET /me` |
| Carteiras | `GET /workspaces`, `GET /workspaces/{wid}` |
| Contas | `/accounts` (listar, criar, editar, `archive`, `restore`) |
| Categorias | `/categories` (idem) |
| Lançamentos | `/transactions` (CRUD, `POST /{id}/clear` efetiva um pendente) |
| Parcelas | `/installment-purchases` (criar, consultar, `cancel`) |
| Recorrências | `/recurrences` (criar, listar, `POST /{id}/end`) |
| Cartão | `/accounts/{id}/card-summary`, `/invoices`, `/invoices/{AAAA-MM}` |
| Orçamentos | `/budgets` (semanal), `/monthly-budgets` (mensal), cada um com `POST /copy` |
| Dashboard e relatórios | `/dashboard`, `/reports/balance` |
| Importação | `POST /accounts/{id}/import/preview` (multipart) e `POST /accounts/{id}/import` |
| Membros e convites | `/members`, `/invitations`; `POST /api/v1/invitations/accept` |
| Saúde | `GET /api/v1/health` (+ `/liveness`, `/readiness`), públicas |

## Segurança e decisões de projeto

As regras estão na [constituição do projeto](.specify/memory/constitution.md); as principais:

- **Isolamento em duas camadas:** RLS no PostgreSQL (a API conecta com uma role sem `BYPASSRLS`, e o workspace é definido por transação) **e** filtro por
  workspace na camada de serviço.
- **Sessão em cookie:** JWT assinado, só em cookie `HttpOnly`/`Secure`/`SameSite=Lax`; senhas em BCrypt; sem token em header, URL ou
  `localStorage`. CORS com uma única origem.
- **Cabeçalhos:** CSP restritiva, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` em respostas autenticadas.
- **Entrada e saída:** todo corpo passa por DTO com Bean Validation e lista de campos permitidos (campo desconhecido é recusado); entidades
  nunca saem direto pela API.
- **Dados financeiros:** dinheiro em centavos (`BIGINT`), IDs UUID, exclusão lógica de lançamentos, schema só por migration Flyway
  versionada (migration aplicada nunca é editada), conta e categoria sempre do mesmo workspace (chave estrangeira composta).
- **Simplicidade:** um backend, um banco; nada de infraestrutura pensada para uma escala que o projeto não tem.

## App desktop (Windows)

Um Electron com o front dentro e um servidor local (127.0.0.1) que serve o site e repassa `/api` à API, como o rewrite da Vercel faz.

```bash
cd desktop
npm install
npm run dist     # gera desktop/release/Tostao-Instalador.exe e Tostao-Portatil.exe
npm start        # abre direto do código, sem empacotar (usa frontend/dist)
```

- O app abre apontando para a **produção**. Para outro servidor (por exemplo, o Docker local), use **Arquivo → Configurar servidor…**
  (`Ctrl+,`) com `http://127.0.0.1:8080` e a origem `http://localhost:5173`. A barra de menus aparece com `Alt`.
- A tela de login do site oferece **"Baixar o app para Windows"** só em navegador de PC com Windows; o link vai para a última *release* do GitHub
  (arquivo `Tostao-Instalador.exe`).
- O instalador **não é assinado**: o Windows pode avisar ("Windows protegeu seu PC") ou, com o Smart App Control ligado, bloquear. Para distribuir
  amplamente, assine com um certificado de assinatura de código.
- Se o terminal definir `ELECTRON_RUN_AS_NODE=1` (o do VS Code define), o programa abre como Node e fecha na hora; abra pelo Explorador de Arquivos.
- Detalhes: [specs/029-app-desktop](specs/029-app-desktop).

## Publicando (Supabase, Render e Vercel)

Resumo (o passo a passo, com os valores de cada variável, está em [docs/deploy.md](docs/deploy.md)):

1. **Supabase:** crie o projeto e use o *Session pooler* (porta 5432) nas variáveis `DB_*`.
2. **Render:** *New → Blueprint* sobre este repositório; ele lê o [render.yaml](render.yaml) (container Docker de `backend/`, health check em
   `/api/v1/health`) e pede as variáveis. A primeira subida roda as migrations.
3. **Vercel:** importe o repositório com *Root Directory* `frontend`. O [frontend/vercel.json](frontend/vercel.json) traz o fallback das rotas do
   app, o rewrite de `/api/*` para o Render e o redirect do instalador.
4. Defina `CORS_ORIGIN` no Render com o endereço do site e confira em uma janela anônima (o service worker do PWA pode servir uma versão antiga).

## Manter a API acordada (UptimeRobot)

No plano gratuito o Render põe a API para dormir após 15 minutos sem pedidos. Cadastre um monitor **HTTP(s)** a cada 5 minutos em:

```
https://tostao-api.onrender.com/api/v1/health
```

Responde `{"status":"UP"}` (200), ou 503 se o banco não responde. Também existem `/api/v1/health/liveness` e `/api/v1/health/readiness`, que não
consultam o banco. Mais detalhes em [docs/deploy.md](docs/deploy.md).

## Levar os dados locais para a produção

O schema da produção é criado pelo Flyway; o que falta é o **conteúdo** do banco local. O script copia só os dados (nada de dados pessoais vai
para o Git), numa transação, e recusa se a produção já tiver usuários:

```bash
bash scripts/local-to-prod.sh     # no Git Bash, com o Docker ligado; pede host, usuário e senha do banco de produção
```

No PowerShell use o caminho completo: `& "C:\Program Files\Git\bin\bash.exe" scripts/local-to-prod.sh`.

## Estrutura do repositório

```
backend/            API Spring Boot (Java 21, Maven, Flyway), um pacote por módulo (account, auth, budget, category, ...)
  src/main/resources/db/migration/   migrations V1..V15
frontend/           React + TypeScript + Vite (PWA); design system em src/design-system
desktop/            app Windows em Electron (servidor local, configuração, empacotamento)
specs/              uma pasta por funcionalidade: spec, plano, tarefas e registro da validação (001 a 029)
docs/               guias operacionais (deploy)
scripts/            utilitários (cópia dos dados locais para a produção)
.specify/           constituição do projeto e modelos do Spec Kit
compose.yaml        PostgreSQL + API para o desenvolvimento local
render.yaml         Blueprint do Render
```

Cada pasta tem o próprio detalhamento: o front em [frontend/README.md](frontend/README.md).

## Como o projeto é desenvolvido

- **Spec Kit:** cada funcionalidade segue `specify → plan → tasks → implement`, numerada e registrada em `specs/` (de `001-autenticacao-workspace`
  a `029-app-desktop`), e conferida contra a constituição antes de implementar. Ficam fora do ciclo apenas bug pontual, CSS, configuração e
  correção de segurança urgente.
- **Prova, não promessa:** uma funcionalidade só conta como pronta com teste automatizado e uma conferência real (chamada de API, log ou captura).
  Cada `quickstart.md` registra o que foi verificado.
- **Commits:** Conventional Commits, pequenos, com mensagem curta em inglês e uma só ideia.
