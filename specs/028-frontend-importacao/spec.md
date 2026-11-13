# Feature Specification: Importação de extratos no front-end

**Feature Branch**: `028-frontend-importacao`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Pode prosseguir" com a importação de extratos (spec 013): enviar o arquivo de extrato de uma conta (OFX, CSV ou PDF, até 2 MB), ver a prévia do que foi lido (novas, já existentes e com problema), escolher o que importar e a categoria de cada linha, e confirmar; as linhas viram lançamentos efetivados da conta.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Enviar o extrato e ver a prévia (Priority: P1)

**Independent Test**: escolher um arquivo OFX ou CSV de uma conta mostra a prévia com as linhas, sem gravar nada.

**Acceptance Scenarios**:

1. **Given** a lista de contas, **When** quem pode escrever toca em "Importar extrato" numa conta ativa, **Then** abre `/contas/{id}/importar` com o nome da conta, a explicação do que o arquivo precisa ter (OFX, CSV com as colunas data, descrição e valor, ou PDF; até 2 MB) e o campo de arquivo.
2. **Given** um arquivo escolhido, **When** a pessoa toca em "Ler extrato", **Then** o arquivo vai à API (que não grava nada) e a tela mostra o formato reconhecido, o resumo ("N linhas: V novas, E já existentes, I com problema") e as linhas na ordem do arquivo.
3. **Given** nenhum arquivo, ou um arquivo vazio ou maior que 2 MB, **Then** a tela recusa antes de enviar, com o motivo no campo; se a API recusar (formato não reconhecido, nenhum movimento, mais de 1.000 movimentos, 413), mostra a mensagem dela e permite escolher outro arquivo.
4. **Given** envio em andamento, **Then** o botão fica indisponível e nada é enviado duas vezes.

---

### User Story 2 - Escolher o que importar (Priority: P1)

**Acceptance Scenarios**:

1. **Given** a prévia, **Then** cada linha mostra a data, a descrição, o valor com o sinal (entrada positiva, saída negativa), uma caixa para importar e a categoria; as **novas** vêm marcadas e as **já existentes** (a chave já existe na conta, inclusive em lançamento excluído) vêm desmarcadas, indisponíveis e com o selo "Já existe"; as **com problema** vêm indisponíveis, com o motivo; a **possível duplicata** (mesma data e valor de um lançamento sem chave) vem marcada com o selo "Possível duplicata" para a pessoa decidir.
2. **Given** a categoria de cada linha, **Then** vem preenchida com a sugerida pela API (a do último lançamento com a mesma descrição), pode ser trocada ou ficar "Sem categoria", e só oferece as categorias ativas do tipo da linha (despesa para saída, receita para entrada).
3. **Given** "Marcar todas as novas" e "Desmarcar todas", **Then** a seleção muda de uma vez.

---

### User Story 3 - Confirmar a importação (Priority: P1)

**Acceptance Scenarios**:

1. **Given** linhas marcadas, **When** a pessoa toca em "Importar N lançamentos", **Then** a API cria um lançamento efetivado para cada uma (receita se o valor é positivo, despesa se negativo), a tela volta à lista de lançamentos com o aviso "N lançamentos importados" (e quantos já existiam e foram ignorados, quando houver) e a lista já os mostra.
2. **Given** nenhuma linha marcada, **Then** o botão fica indisponível.
3. **Given** uma recusa da API (categoria arquivada, valor ou data fora da faixa, chave repetida), **Then** a mensagem aparece, a seleção e as categorias escolhidas ficam, e a pessoa pode corrigir e tentar de novo; o envio é único.
4. **Given** quem só lê, ou uma conta arquivada ou inexistente, **Then** a importação não está disponível (aviso ou "não encontrada").

### Edge Cases

- Até 1.000 linhas por importação (o limite da API); a prévia mostra todas.
- O arquivo é lido no servidor; o aplicativo não guarda o arquivo nem o conteúdo (só o estado da tela enquanto ela existe).
- Valores em centavos inteiros; as chaves das linhas vêm da prévia e voltam sem mudança.

## Requirements *(mandatory)*

- **FR-001**: O cliente de API MUST aceitar corpo `FormData` (multipart) sem fixar o tipo do conteúdo (o navegador define o limite).
- **FR-002**: A prévia MUST usar `POST /accounts/{id}/import/preview` (campo `file`) e a confirmação `POST /accounts/{id}/import` (`lines`: `key`, `occurredOn`, `description`, `amountCents`, `categoryId?`).
- **FR-003**: Linhas existentes ou com problema MUST ficar indisponíveis para importar; a possível duplicata MUST ser só um aviso.
- **FR-004**: Papéis, estados, acessibilidade, sem dependência nova, ≤ 150 KB (a tela carrega sob demanda), nenhuma mudança no back-end.

## Success Criteria

- **SC-001**: Os números e as regras batem com a API (roteiro real com um CSV e um OFX).
- **SC-002**: Poucos testes de valor.
