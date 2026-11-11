# Contrato do front-end: rotas, chamadas à API e armazenamento

A API é a da spec 001 ([openapi.yaml](../../001-autenticacao-workspace/contracts/openapi.yaml)); esta spec não a muda. Aqui ficam os
compromissos do aplicativo com a API e com o navegador, que os testes conferem.

## Rotas

| Rota | Acesso | Comportamento |
|---|---|---|
| `/entrar` | sem sessão | formulário de entrada; com sessão redireciona para `/` |
| `/cadastro` | sem sessão | formulário de cadastro; com sessão redireciona para `/` |
| `/` | com sessão | início: saudação, workspace atual, estado vazio |
| `/perfil` | com sessão | dados da pessoa, workspaces, tema, sair, sair de todos os aparelhos |
| qualquer outra | todos | "Página não encontrada" com o caminho de volta |

Sem sessão, qualquer rota interna redireciona para `/entrar` guardando a localização pedida no **estado** do roteador (nunca na consulta
do endereço). Depois de entrar, volta-se a ela só se for um caminho interno seguro; senão, `/`.

## Chamadas à API

| Quando | Chamada | Resultado |
|---|---|---|
| abrir/recarregar | `GET /api/v1/me` | 200 entra; 401 vai para a entrada; rede/5xx mostra "Tentar de novo" |
| enviar a entrada | `POST /api/v1/auth/login` `{email, password}` | 200 entra; 401, 429 e 400 tratados no formulário |
| enviar o cadastro | `POST /api/v1/auth/register` `{name, email, password}` | 201 entra; 409 e 400 tratados no formulário |
| "Sair" | `POST /api/v1/auth/logout` | 204 sai; falha mantém a sessão e avisa |
| "Sair de todos" | `POST /api/v1/auth/logout-all` | 204 sai; falha mantém a sessão e avisa |

Todas: caminho relativo `/api/v1/...`, `credentials: 'same-origin'`, sem cabeçalho de autorização montado à mão, sem token, senha ou
código no endereço, com limite de tempo de 15 s. Um `401` de qualquer chamada autenticada que não seja a da entrada ou do cadastro
encerra a sessão em memória e leva à entrada com o aviso.

## Armazenamento do navegador

`localStorage`: `tostao.workspace.<idDaPessoa>` e `tostao.theme` (ver [data-model.md](../data-model.md)). Nada de `sessionStorage`,
`IndexedDB` ou cookie escrito pelo aplicativo. O service worker não guarda respostas de `/api/*`.

## Recursos do aplicativo

Nenhum recurso vem de outro domínio: fontes, scripts, estilos e imagens saem do próprio aplicativo; a verificação automática recusa
`http://` e `https://` externos no código, nos estilos e no `index.html`.

## PWA

Manifest com `name` "Tostão", `short_name` "Tostão", `lang` `pt-BR`, `display` `standalone`, `theme_color` `#0f5f63`, `background_color`
`#f6f4ef`, ícones `icons/icon-192.png`, `icons/icon-512.png` e `icons/icon-maskable-512.png` (`purpose: maskable`); `apple-touch-icon.png` e
`favicon` no `index.html`. Atualização automática (`registerType: autoUpdate`).
