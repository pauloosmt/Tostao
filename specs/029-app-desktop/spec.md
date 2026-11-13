# Feature Specification: App desktop (Windows)

**Feature Branch**: `029-app-desktop`

**Created**: 2026-11-13

**Status**: Draft

**Input**: User description: "Pode prosseguir" com a última entrega combinada: o app desktop com versão executável (instalador e portátil), além do celular pelo PWA, sem enfraquecer os guard-rails do front (nada de token no endereço, nada de origem externa no código).

## Contexto e decisão

O front fala só com `/api/v1` (caminho relativo, mesma origem, cookie HttpOnly). O desktop mantém isso: o app é um **Electron** que sobe um **servidor local** (127.0.0.1) que (1) serve o front já compilado (`frontend/dist`, com o mesmo fallback de rota do SPA) e (2) repassa `/api` à API configurada, reescrevendo a `Origin` para a que a API aceita (exatamente o que o proxy do `vite dev` já faz). Para o front nada muda: a origem dele continua sendo a do app.

Fica fora do código do front: a pasta `desktop/` tem o seu próprio `package.json`. O endereço da API é configuração do aplicativo (arquivo do usuário), nunca código do front.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Abrir o Tostão como um programa (Priority: P1)

**Independent Test**: `desktop/release/` contém um instalador e um executável portátil; abrir o executável mostra a tela de entrar do Tostão numa janela própria, e entrar funciona contra a API.

**Acceptance Scenarios**:

1. **Given** o executável, **When** a pessoa o abre, **Then** vê uma janela "Tostão" (ícone da marca) com o aplicativo (entrar, cadastro, telas), igual ao navegador; fechar a janela encerra o programa; uma segunda abertura traz a janela já aberta para a frente (uma instância só).
2. **Given** a API no endereço configurado, **When** a pessoa entra, **Then** a sessão funciona (cookie HttpOnly guardado pelo app) e continua depois de fechar e abrir o programa; sair apaga a sessão.
3. **Given** a API fora do ar, **Then** o app mostra o aviso de sempre ("Não foi possível abrir o Tostão", com "Tentar de novo"), nunca uma tela em branco.

---

### User Story 2 - Dizer onde está o servidor (Priority: P1)

**Acceptance Scenarios**:

1. **Given** a primeira abertura, **Then** o endereço da API é o padrão local (`http://127.0.0.1:8080`) e a origem aceita pela API é `http://localhost:5173` (os padrões do Docker Compose do projeto).
2. **Given** o menu "Arquivo → Configurar servidor…", **When** a pessoa informa o endereço da API (e, se preciso, a origem que a API aceita), **Then** o endereço é validado (http ou https, sem usuário e senha, sem consulta), guardado no arquivo de configuração do usuário e usado já na próxima chamada; um endereço inválido mostra o motivo e não grava.
3. **Given** as variáveis `TOSTAO_API_URL` e `TOSTAO_API_ORIGIN`, **Then** valem sobre o arquivo (para testes e uso avançado).

---

### User Story 3 - Segurança do shell (Priority: P1)

**Acceptance Scenarios**:

1. **Given** o app, **Then** a janela não tem acesso ao Node (isolamento de contexto, sandbox, sem integração com Node); a navegação fica só no endereço do app; links `https://` abrem no navegador do sistema e todo o resto é bloqueado; pedidos de permissão (câmera, localização...) são negados.
2. **Given** o servidor local, **Then** escuta só em 127.0.0.1; não serve fora da pasta do front (caminho com `..` ou fora dela é recusado); só repassa `/api/` (nunca outro caminho) e nunca monta cabeçalho de autorização: repassa o cookie que a janela mandou.
3. **Given** a API respondendo com `Set-Cookie`, **Then** o servidor local tira `Domain` e `Secure` do cookie (o salto até a janela é pelo próprio computador; o salto até a API é que usa TLS) e mantém `HttpOnly` e `SameSite`.

### Edge Cases

- Porta ocupada: o servidor tenta a porta fixa e, se estiver ocupada, uma livre; o servidor só sobe depois da porta escolhida (a janela abre no endereço certo).
- Upload de extrato (multipart) e respostas grandes passam em fluxo, sem carregar tudo na memória.
- O endereço da API com `https` usa TLS normal; com `http` só para o computador local ou rede de confiança (aviso na tela de configuração).
- O app não guarda senha nem token; a sessão fica só no cookie HttpOnly do Electron.

## Requirements *(mandatory)*

- **FR-001**: `desktop/` MUST ter o servidor local (estáticos + proxy de `/api`) e a configuração, ambos testados sem Electron (`node --test`).
- **FR-002**: O shell MUST aplicar o isolamento, a navegação restrita, a instância única e o menu com "Configurar servidor…".
- **FR-003**: O empacotamento MUST gerar instalador e executável portátil para Windows, com o front compilado dentro do pacote.
- **FR-004**: Nenhuma mudança em `frontend/src` (os guard-rails continuam passando) nem no back-end.

## Success Criteria

- **SC-001**: O programa empacotado abre, serve o front, e uma chamada `/api/v1/health` pelo servidor local responde como a API.
- **SC-002**: Poucos testes de valor; nada novo no JavaScript inicial do front.

## Assumptions

- Só Windows nesta spec (a máquina e o pedido são Windows); macOS e Linux entram com a mesma base quando houver pedido.
- Sem assinatura de código e sem atualização automática por ora (o Windows pode avisar "editor desconhecido" na primeira execução).
