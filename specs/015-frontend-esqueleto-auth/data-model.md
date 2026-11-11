# Data Model: Esqueleto do front-end e autenticação

O front não tem banco. Aqui ficam os tipos que vêm da API, o estado em memória, o que é guardado no aparelho e as regras de validação.

## Tipos da API (espelham o contrato da 001)

```ts
type Role = 'OWNER' | 'EDITOR' | 'VIEWER'

interface WorkspaceMembership { id: string; name: string; baseCurrency: string; role: Role }
interface Me { id: string; name: string; email: string; workspaces: WorkspaceMembership[] }

interface FieldError { field: string; message: string }
interface Problem { type: string; title: string; status: number; detail?: string; errors?: FieldError[] }

class ApiError extends Error { status: number; title: string; detail?: string; errors: FieldError[]; retryAfterSeconds?: number }
class NetworkError extends Error {}            // sem rede, tempo esgotado ou resposta ilegível
```

Chamadas usadas (todas em `/api/v1`, só com o cookie): `POST /auth/register`, `POST /auth/login` (ambas devolvem `Me`),
`POST /auth/logout` (204), `POST /auth/logout-all` (204), `GET /me`.

## Estado de sessão (memória)

```text
AuthState =
  | { status: 'loading' }                                       // consultando /me ao abrir
  | { status: 'unavailable' }                                   // /me falhou por rede/servidor: "Tentar de novo"
  | { status: 'anonymous', expired: boolean }                   // expired = a sessão terminou (aviso na entrada)
  | { status: 'authenticated', me: Me }
```

Transições: `loading` → `authenticated` (200) | `anonymous` (401) | `unavailable` (rede/5xx); `authenticated` → `anonymous{expired:true}` (401
em qualquer chamada autenticada); `authenticated` → `anonymous{expired:false}` (logout 204); `anonymous` → `authenticated` (login ou
cadastro 2xx); `unavailable` → `loading` ("Tentar de novo"). Nenhum token ou senha existe no estado.

## O que é guardado no aparelho (`localStorage`, sempre em `try/catch`)

| Chave | Valor | Quando muda |
|---|---|---|
| `tostao.workspace.<idDaPessoa>` | UUID do workspace atual | ao trocar no perfil |
| `tostao.theme` | `system`, `light` ou `dark` | ao escolher no perfil |

Nada mais é guardado: sem token, senha, código, e-mail ou dado financeiro. O `service worker` não guarda respostas de `/api/*`.

## Regras de validação (espelham o back-end; a API é a autoridade)

| Campo | Regra | Mensagem |
|---|---|---|
| Nome (cadastro) | 1 a 100 caracteres, sem espaços só nas pontas | "Informe seu nome." / "Use no máximo 100 caracteres." |
| E-mail | formato de e-mail, até 254 caracteres | "Informe um e-mail válido." / "Use no máximo 254 caracteres." |
| Senha (cadastro) | 8 a 72 bytes em UTF-8, ao menos uma letra e um número | "Use de 8 a 72 caracteres, com letra e número." |
| E-mail e senha (entrada) | só obrigatórios (a API decide) | "Informe seu e-mail." / "Informe sua senha." |

O e-mail é enviado sem espaços nas pontas; a senha é enviada exatamente como digitada. Erros de campo da API (`errors[]`) são mostrados no
campo de mesmo nome (`name`, `email`, `password`); os demais viram o aviso geral.

## Mensagens da API tratadas

| Situação | Onde | Mensagem na tela |
|---|---|---|
| `401` no login | entrada | "E-mail ou senha incorretos." |
| `429` no login | entrada | "Muitas tentativas. Tente de novo em N s." (`Retry-After`) |
| `409` no cadastro | e-mail | "Este e-mail já está cadastrado." |
| `400` com `errors[]` | campo | a mensagem da API |
| `401` em chamada autenticada | entrada | "Sua sessão terminou. Entre de novo." |
| `NetworkError` / `5xx` | aviso geral | "Não foi possível falar com o servidor. Tente de novo." |

## Rotas

| Rota | Quem vê | Tela |
|---|---|---|
| `/entrar` | sem sessão (com sessão vai para `/`) | Entrada |
| `/cadastro` | sem sessão (com sessão vai para `/`) | Cadastro |
| `/` | com sessão | Início |
| `/perfil` | com sessão | Perfil |
| `*` | todos | Não encontrada |
