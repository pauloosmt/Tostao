# Quickstart: App desktop (Windows)

## Gerar

```bash
cd frontend && npm run build        # ou deixe o passo abaixo compilar o front se faltar o dist
cd ../desktop && npm install        # primeira vez (Electron e electron-builder)
npm run dist                        # prepara (front + ícone) e gera os executáveis em desktop/release/
```

Saídas em `desktop/release/` (ignorada pelo git): `Tostao-Instalador.exe` (instalador NSIS) e `Tostao-Portatil.exe` (um único arquivo, sem instalar). O front compilado vai dentro do programa; depois de mudar o front, rode `npm run dist` de novo (use `node scripts/prepare.mjs --rebuild` para forçar a recompilação).

## Usar

1. Abra o programa. Por padrão ele fala com a produção (`https://tostao-api.onrender.com`, origem `https://tostao-roan.vercel.app`); para o Docker Compose do projeto, configure `http://127.0.0.1:8080` e `http://localhost:5173` (passo 3).
2. O app serve o front em `http://127.0.0.1:47321` (se a porta estiver ocupada, usa uma livre) e repassa `/api` à API.
3. **Arquivo → Configurar servidor…** (Ctrl+,) muda o endereço da API e a origem que ela aceita; salvar recarrega a janela. A barra de menus fica escondida (aparece com Alt). Valores em `config.json` na pasta de dados do usuário; `TOSTAO_API_URL` e `TOSTAO_API_ORIGIN` têm precedência.
4. Os dados do app (sessão, preferências) ficam na pasta do usuário, e uma segunda abertura só traz a janela existente para a frente.

## Verificado

- `npm test` em `desktop/`: 17 testes (`node --test`), sem Electron: estáticos e fallback do SPA, caminho fora da pasta recusado, só GET/HEAD, proxy (método, caminho, consulta, cookie, `Origin` reescrita, sem `Authorization`), cookie sem `Domain`/`Secure`, envio grande em fluxo, API fora do ar → 502, configuração mudando sem reiniciar, porta ocupada, validação/leitura/gravação da configuração.
- O front e o back-end não mudaram nesta spec; o `npm run verify` do front (892 testes, JS inicial 114,5 KB) passou depois da troca de fonte feita no mesmo dia.
- Os três executáveis foram abertos de verdade: pasta `win-unpacked`, instalado (instalação silenciosa e desinstalação de teste) e portátil. Cada um subiu o servidor local, entregou o front com a League Spartan (e nenhuma Fraunces) e a saúde da API pelo proxy.
- Roteiro contra a API real pelo programa (`qs029.sh`): 11/11. Index e fallback de rota; saúde pelo proxy; cadastro (Origin aceita); cookie de sessão com HttpOnly e sem `Domain`/`Secure`; rota autenticada com o cookie; 401 sem cookie e com `Authorization` solto; o logout passa pelo proxy e limpa o cookie. Limpeza só dos dados `qs29*`.

## Observações

- Os executáveis **não são assinados**. Com o Smart App Control do Windows ligado, o sistema bloqueia a abertura ("uma política de Controle de Aplicativo bloqueou este arquivo"); o SmartScreen também pode avisar. Para distribuir a outras pessoas, assine com um certificado de assinatura de código.
- Se o terminal definir `ELECTRON_RUN_AS_NODE=1` (o do VS Code define), o programa abre como Node e fecha na hora; abrir pelo Explorador de Arquivos funciona.
- Conferência visual que fica com você: o aspecto da janela com a barra de menus escondida e a janela "Configurar servidor" (as respostas de erro de endereço aparecem sob cada campo).
