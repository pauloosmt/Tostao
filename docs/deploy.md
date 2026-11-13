# Deploy: Supabase + Render + Vercel

O navegador só fala com a Vercel; `/api/*` é encaminhado ao Render por um rewrite configurado **no painel da Vercel** (a URL da API
não entra no código; o `frontend/vercel.json` só tem o fallback das rotas do app), então o cookie de sessão
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

1. Na Vercel, em *Project Settings → Rewrites*, crie a regra `/api/:path*` → `https://tostao-api.onrender.com/api/:path*` (o endereço do passo 2).
   (O fallback do app já ignora `/api/`, então a ordem não importa.) Se o painel da sua conta não oferecer essa tela, a alternativa é pôr a regra
   no `frontend/vercel.json` (e ajustar o teste que hoje proíbe URL nesse arquivo).
2. No Render, defina `CORS_ORIGIN` com o endereço exato do site na Vercel (sem barra no fim) e reimplante. A API só aceita essa origem
(e recusa as demais com 403). Se usar domínio próprio, use esse domínio aqui.

## 5. Conferir

Abra o site numa **janela anônima** (o service worker do PWA pode servir versão antiga): cadastre-se, crie uma conta e um lançamento,
saia e entre de novo. Isso exercita o cookie, o rewrite, o CORS e o banco.

## App desktop e celular

- Desktop: em *Arquivo → Configurar servidor…* (Ctrl+,) use `https://tostao-api.onrender.com` e, como origem, o endereço do site na Vercel.
- Celular: abra o site da Vercel e use *Adicionar à tela inicial* (PWA).
