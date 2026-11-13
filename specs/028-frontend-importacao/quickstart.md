# Quickstart: Importação de extratos no front-end

Para ver no navegador: `docker compose up -d` e, em `frontend/`, `npm run dev` (http://localhost:5173). Tenha uma conta e um arquivo de extrato (um CSV de exemplo: primeira linha `data;descricao;valor`, depois `2026-11-05;Padaria Central;-23,50`).

## 1. Roteiro no navegador (quem usa o projeto confere)

1. **Contas**: cada conta ativa tem "Importar extrato" (quem só lê não vê; a arquivada não tem).
2. **Arquivo**: "Importar extrato" abre `/contas/{id}/importar` com a explicação (OFX, CSV com data, descrição e valor, ou PDF; até 2 MB) e o campo de arquivo. Sem arquivo, vazio ou maior que 2 MB: o erro aparece antes de enviar.
3. **Ler extrato**: o arquivo vai à API, que **não grava nada**; a tela mostra o formato, "N linhas", "V novas · E já existentes · I com problema" e cada linha com data, descrição e valor com sinal.
4. **Linhas**: as novas vêm marcadas; as já existentes ("Já existe") e as com problema (com o motivo) ficam indisponíveis; a possível duplicata ("Possível duplicata") vem marcada para você decidir; "Marcar todas as novas" e "Desmarcar todas".
5. **Categoria por linha**: vem a sugerida (a do último lançamento com a mesma descrição), só do tipo da linha (despesa para saída, receita para entrada), ou "Sem categoria".
6. **Importar N lançamentos**: volta à lista de lançamentos com "N lançamentos importados" (e "M já existiam e foram ignoradas"); os lançamentos nascem efetivados.
7. **Erros**: formato não reconhecido, arquivo vazio ou com mais de 1.000 movimentos aparecem no campo e permitem escolher outro arquivo; uma recusa na confirmação (por exemplo categoria arquivada) mostra a mensagem e mantém a seleção.
8. Celular e temas claro e escuro.

## 2. Verificação automatizada (2026-11-13)

- `npm run verify` (front): guard-rails 10, Vitest **889 testes em 70 arquivos**, `tsc` e build ok, JavaScript inicial de **114,4 KB** de 150 KB (gzip) e testes do build ok.
- Back-end: não rodado (a spec não toca o back-end).

## 3. Roteiro com a API real (HTTP direto no Compose, com arquivos CSV e OFX; script fora do repositório)

**25 de 25** conferências: a prévia de um CSV (formato, total e contagens; data ISO, descrição, valor com sinal e tipo; chave, `existing`, `possibleDuplicate`, sugestão e problemas; número da linha em ordem) e **sem gravar nada**; confirmar cria lançamentos efetivados e importados, com sinal e categoria, e o saldo da conta muda; a prévia do mesmo arquivo depois marca as importadas como existentes e a categoria usada vira sugestão; confirmar de novo devolve `200` sem criar e conta os ignorados; a possível duplicata (mesma data e valor de um lançamento sem chave); uma linha com problema traz o motivo e não tem chave; um OFX é lido (`OFX`, valor e data); arquivo vazio `400`, formato não reconhecido `400` **no campo `file`**, mais de 2 MB recusado, confirmar sem linhas e com chave repetida `400`, categoria de receita numa saída recusada, linha sem categoria nasce sem categoria, conta arquivada `409` e sem cookie `401`. A limpeza removeu só os dados `qs28*`.

## 4. Decisões

- O cliente de API ganhou o envio de arquivo (`FormData`/multipart): não fixa o tipo do conteúdo, para o navegador pôr o limite das partes; a prévia e a confirmação têm 60 s (o padrão é 15 s).
- O arquivo e o conteúdo vivem só no estado da tela; nada é guardado no aplicativo.
- A tela carrega sob demanda, como as outras menos usadas.

## 5. Fora desta spec

O PDF depende de o servidor conseguir ler o texto do arquivo (a API faz); a tela só envia e mostra o que a API devolve. Escolher o formato ou mapear colunas à mão (a API reconhece pelo conteúdo).
