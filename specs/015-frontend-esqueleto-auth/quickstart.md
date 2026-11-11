# Quickstart: validar o esqueleto do front-end

Contrato em [contracts/ui-contract.md](contracts/ui-contract.md); modelo em [data-model.md](data-model.md).

## Pré-requisitos

- Docker Desktop ligado e a API local no ar: `docker compose up -d --build` (na raiz). A API fica em `http://127.0.0.1:8080`.
- `cd frontend && npm ci`. O proxy do Vite (`/api` → `http://localhost:8080`) espelha o rewrite da Vercel, em `npm run dev` e em
  `npm run preview`.

## 1. Verificação automatizada

```bash
cd frontend && npm run verify
```

Esperado: lint com os guard-rails, checagem de tipos, testes, build e orçamento de tamanho passam. Os testes do back-end continuam
passando (`cd backend && ./mvnw -B verify`).

## 2. Entrada, cadastro e sessão (US1, US2, US3)

Com `npm run dev` (ou `npm run build && npm run preview`), abra `http://localhost:5173` num navegador.

1. Sem sessão, qualquer endereço (`/`, `/perfil`) leva a `/entrar`; nenhuma tela interna aparece.
2. **Cadastro**: "Criar conta"; nome, e-mail novo e senha válida → entra no início, com o seu nome e o workspace pessoal (dono).
3. Senha sem número, com 7 caracteres ou com mais de 72 bytes → erro no campo, nada enviado. E-mail já usado → erro no campo de e-mail.
4. **Recarregar** a página: continua logada, no mesmo lugar, sem passar pela entrada.
5. **Sair**: volta para `/entrar`; o botão "voltar" do navegador não mostra tela interna.
6. **Entrar** com a senha errada → "E-mail ou senha incorretos." (igual para e-mail inexistente), senha limpa e foco na senha.
7. Errar a senha repetidamente até o bloqueio → mensagem com o tempo de espera, e o botão indisponível durante a espera.
8. Abrir `/perfil` deslogada, entrar → cai em `/perfil` (endereço retomado). O endereço não ganha `?redirect=` nem nada parecido.
9. Duas abas logadas: sair numa; na outra, qualquer ação leva à entrada com "Sua sessão terminou. Entre de novo."
10. "Sair de todos os aparelhos" → confirmação; ao confirmar, volta à entrada; a outra aba também perde a sessão.

## 3. Estrutura e workspace (US4)

1. Largura de celular (< 720 px): barra inferior com "Início" e "Perfil"; largura de desktop (≥ 960 px): menu lateral. Item atual marcado.
2. `/qualquer-coisa` → "Página não encontrada" com o caminho de volta.
3. Perfil: nome, e-mail, workspaces com o papel em português, tema e as duas formas de sair.
4. Com um segundo workspace (convidar a pessoa para outro workspace pela API da spec 012), trocar no perfil e **recarregar**: a escolha continua.
5. Derrubar a API (`docker compose stop api`) e usar o aplicativo: aviso de erro com "Tentar de novo", nunca tela em branco; religar e tentar de novo funciona.

## 4. PWA e tema (US5)

1. Em `npm run preview` (build de produção), o navegador oferece "instalar"; o ícone aparece nos tamanhos 192/512 e o maskable.
2. Desligar a rede nas ferramentas do navegador e recarregar: o aplicativo abre e as telas de API avisam que estão sem conexão.
3. Nas ferramentas de rede, **todas** as requisições vão para o próprio domínio: nenhuma para `fonts.googleapis.com` ou outro host. Nenhuma
   resposta de `/api/*` está no cache do service worker.
4. Tema do sistema claro/escuro seguido por padrão; no perfil forçar claro ou escuro e recarregar: a escolha continua.
5. Teclado: percorrer tudo só com Tab, Shift+Tab, Enter e Esc; o foco é sempre visível.

## 5. Limpeza

Apagar a conta criada no teste (`qs15*@example.com`) e confirmar `users = workspaces = workspace_members = 0`.

## 6. Resultado da validação (2026-11-11)

**Automatizado**

- `npm run verify` (front): guard-rails 10, Vitest 205 em 20 arquivos, `tsc` e build ok, orçamento de **86,6 KB** de 150 KB (gzip) e 16 testes do build.
- `./mvnw -B verify` (back-end): BUILD SUCCESS, **1414 testes, 0 falhas**.

**Roteiro por HTTP** (build de produção no `vite preview` + API do Compose; script fora do repositório): **59 de 59** conferências.
Cobre o fallback das rotas (`/`, `/entrar`, `/cadastro`, `/perfil` e uma rota qualquer devolvem o app; `/api/` nunca), manifest, ícones e service
worker sem `/api` no pré-cache, nenhuma origem externa na página, cadastro (201, `409`, e `400` com erro no campo para senha sem número, de 6
caracteres e de 73 bytes), cookie `HttpOnly` e `SameSite=Lax` sem token no corpo, entrar e sair, a mesma resposta para senha errada e e-mail
inexistente, bloqueio `429` com `Retry-After` (900 s na 6ª tentativa), sair de todos os aparelhos derrubando duas sessões e a API recusando origem
desconhecida (`403`). Limpeza: só os dados `qs15*` foram apagados; a conta real que já existia no banco de desenvolvimento ficou intacta (por isso as
contagens finais são 1|1|1, não zero).

**Visto no navegador por quem usa o projeto:** a tela de cadastro (que mostrou o erro de servidor causado pelo CORS de `127.0.0.1`, depois
corrigido no proxy), o início, a barra de navegação e o seletor de tema no escuro.

**Ainda sem conferência no navegador** (itens das seções 2 a 4 que só ele mostra): recarregar mantendo a sessão e o lugar, o botão "voltar" depois de
sair, duas abas ("Sua sessão terminou"), o botão "instalar" e o modo sem rede do PWA, a lista de requisições da aba de rede, o tema do sistema e a
navegação só por teclado. Os comportamentos correspondentes têm teste automatizado (sessão, redirecionamento, foco, tema, confirmação), mas a
conferência visual fica com quem usa.
