# Deploy: Supabase + Render + Vercel

O navegador só fala com a Vercel; `/api/*` é encaminhado ao Render por um rewrite no `frontend/vercel.json` (junto do fallback das
rotas do app; a URL do serviço não é segredo), então o cookie de sessão
(`SameSite=Lax`, HttpOnly, Secure) funciona como no ambiente local. Ordem: banco, API, front, rewrite e por fim a origem do CORS.

## 1. Banco (Supabase)

1. Crie um projeto no Supabase e guarde a senha do banco. Anote a **referência** do projeto (`<ref>`, na URL do painel).
2. Em *Connect → Session pooler* (porta 5432, IPv4; o Render não alcança a conexão direta, que é só IPv6), copie o host
   (`aws-0-<regiao>.pooler.supabase.com`). Não use o *Transaction pooler* (6543): o Flyway usa lock de sessão e o driver usa prepared statements, que esse modo não aceita sem ajustes.
3. Escolha uma senha para a role de aplicação (`DB_APP_PASSWORD`); a migration V2 cria a role `tostao_app` com ela.

Valores que vão para a API:

| Variável | Valor |
|---|---|
| `DB_MIGRATION_URL` e `DB_URL` | `jdbc:postgresql://aws-0-<regiao>.pooler.supabase.com:5432/postgres?sslmode=require` |
| `DB_MIGRATION_USER` | `postgres.<ref>` |
| `DB_MIGRATION_PASSWORD` | a senha do banco |
| `DB_USER` | `tostao_app.<ref>` (o pooler exige o `.<ref>` em todo usuário) |
| `DB_APP_PASSWORD` | a senha escolhida no passo 3 |

## 2. API (Render)

1. Suba o repositório para o GitHub (o Render e a Vercel leem de lá).
2. No Render: *New → Blueprint*, escolha o repositório; ele lê o [render.yaml](../render.yaml) e cria o serviço `tostao-api`.
3. Preencha as variáveis pedidas (tabela acima). `JWT_SECRET` é gerado pelo Render. Deixe `CORS_ORIGIN` para o passo 4, ou preencha já se souber o endereço do site.
4. Confira `https://tostao-api.onrender.com/api/v1/health` → `{"status":"UP"}`. A primeira subida roda as migrations (cria o schema).
5. No plano free o serviço "dorme" após 15 min sem uso e a primeira chamada seguinte leva cerca de 1 minuto.

Se o nome `tostao-api` já estiver em uso, o Render dá outro endereço: use o que ele mostrar no passo 4.

## 3. Front (Vercel)

1. *Add New → Project*, escolha o repositório e defina **Root Directory = `frontend`**. O Vite é detectado sozinho (`npm run build`, saída `dist`).
2. Sem variáveis de ambiente: o front chama `/api` no próprio domínio.
3. Anote o endereço do site (ex.: `https://tostao.vercel.app`).

## 4. Ligar os dois

1. A regra `/api/:path*` → `https://tostao-api.onrender.com/api/:path*` já está no `frontend/vercel.json`. Se o Render der outro endereço
   ao serviço, troque-o ali e faça push (o fallback do app já ignora `/api/`, então a ordem das regras não importa).
2. No Render, defina `CORS_ORIGIN` com o endereço exato do site na Vercel (sem barra no fim) e reimplante. A API só aceita essa origem
(e recusa as demais com 403). Se usar domínio próprio, use esse domínio aqui.

## 5. Conferir

Abra o site numa **janela anônima** (o service worker do PWA pode servir versão antiga): cadastre-se, crie uma conta e um lançamento,
saia e entre de novo. Isso exercita o cookie, o rewrite, o CORS e o banco.

## Manter a API acordada (UptimeRobot)

No plano free o Render põe a API para dormir após 15 minutos sem pedidos. Um monitor que a chame a cada 5 minutos evita isso (e mostra se ela caiu).
Rotas públicas, sem login, em `GET` ou `HEAD`:

| Rota | Para que serve |
|---|---|
| `https://tostao-api.onrender.com/api/v1/health` | **a recomendada**: confere a API e o banco; responde `{"status":"UP"}` (200) ou `DOWN` (503) se o banco não responde |
| `https://tostao-api.onrender.com/api/v1/health/liveness` | só "o processo está vivo" (200 mesmo com o banco fora) |
| `https://tostao-api.onrender.com/api/v1/health/readiness` | "pronta para receber tráfego" (também não consulta o banco) |

No UptimeRobot: *Add New Monitor → HTTP(s)*, URL = a primeira rota, intervalo de **5 minutos** (o mínimo do plano grátis), e, se quiser,
*Keyword monitoring* com a palavra `UP`. Um 503 na rota recomendada significa que o banco (Supabase) não respondeu; uma API dormindo ou fora do ar dá erro de conexão ou de tempo.

Observações: o plano free do Render dá 750 horas de instância por mês, o bastante para um serviço ligado o mês inteiro; e o Supabase free pausa
projetos sem atividade por uma semana, o que o monitor também ajuda a evitar (a rota recomendada abre uma conexão com o banco a cada chamada).

## App desktop e celular

- **Celular:** abra o site da Vercel e use *Adicionar à tela inicial* (PWA).
- **Desktop (Windows):** a tela de login mostra "Baixar o app para Windows" só em navegador de PC com Windows. O link (`/baixar/windows`) é um redirect do
  `frontend/vercel.json` para o arquivo `Tostao-Instalador.exe` da última *release* do GitHub, então o instalador precisa estar publicado lá:
  1. `cd desktop && npm run dist` gera `desktop/release/Tostao-Instalador.exe` (e o portátil).
  2. No GitHub: *Releases → Draft a new release*, crie uma tag (ex.: `v0.1.0`), anexe o `Tostao-Instalador.exe` e publique.
  3. O app já vem apontando para a produção; para outro servidor, *Arquivo → Configurar servidor…* (Ctrl+,).
  O instalador não é assinado: o Windows pode avisar (SmartScreen) ou, com o Smart App Control ligado, bloquear.
