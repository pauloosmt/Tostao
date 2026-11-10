# Research: Recorrências e efetivação automática

Cada decisão segue o formato: decisão, motivo e alternativas descartadas.

## 1. Como a execução sem pessoa logada convive com o RLS

- **Decisão**: duas funções `SECURITY DEFINER` estreitas na `V11`: `generate_recurrence_occurrences(p_today,
  p_recurrence_id)` e `auto_clear_due(p_today)`. Ambas fixam `search_path = public, pg_temp`, têm `EXECUTE` revogado
  de `PUBLIC` e concedido só a `tostao_app`, e fazem tudo em lote (uma instrução cada), sem bloco de exceção.
- **Motivo**: o RLS das tabelas depende de `app.user_id` e `app.workspace_id` (`app_workspace_id()`, `app_can_write()`),
  que só existem dentro de uma requisição. A execução automática não tem pessoa. As funções rodam como o dono (que
  ignora RLS) mas só produzem o que a regra diz: cada ocorrência gerada copia `workspace_id`, `account_id`,
  `category_id` e `created_by` da **própria recorrência**, e as chaves compostas (`(account_id, workspace_id)`,
  `(category_id, workspace_id, type)`) impedem qualquer mistura entre workspaces; a efetivação só altera as colunas
  `status`, `auto_clear` e `updated_at` das linhas que casam com a regra. É o padrão que a `V2` já usa para o que
  acontece antes de o workspace ser conhecido (cadastro, login).
- **Alternativas descartadas**: `BYPASSRLS` na role da aplicação (afrouxaria o isolamento de todas as requisições,
  não só da rotina); um usuário "de sistema" membro de todos os workspaces (uma identidade privilegiada nova que a
  constituição não prevê e que apareceria em todas as listas de membros); uma role separada só para a rotina (mais
  uma credencial e outro pool, sem ganho: a função já é o limite).

## 2. Uma única função de geração, para o agendador e para a criação

- **Decisão**: `generate_recurrence_occurrences(p_today DATE, p_recurrence_id UUID DEFAULT NULL) RETURNS INTEGER`.
  Com `NULL`, gera para todas as recorrências ativas; com um id, só para ela. O agendador passa `NULL`; a criação
  da recorrência (que já está dentro da requisição, sob RLS, e acabou de inserir a linha em `recurrences`) passa o
  id da recorrência nova.
- **Motivo**: há uma só definição do calendário e do horizonte. Se a criação gerasse por outro caminho (em Java),
  o agendador poderia, depois, discordar dela. A função é determinística e idempotente, então chamá-la a mais
  nunca causa dano: ela produz exatamente o que o agendador produziria na próxima hora.
- **Alternativas descartadas**: gerar na criação em Java e no agendador em SQL (dois calendários); criar a
  recorrência sem gerar e esperar a próxima execução (a pessoa não veria nada por até uma hora, e o SC pede as
  ocorrências na resposta).

## 3. O calendário em SQL

- **Decisão**: a data da ocorrência `n` é calculada **sempre a partir do início**: semanal `start_on + 7 * n`;
  mensal `(start_on + n * interval '1 month')::date`; anual `(start_on + n * interval '1 year')::date`. O Postgres
  limita o dia ao fim do mês e, multiplicando o intervalo, não acumula o ajuste: `2027-01-31 + 1 mês = 2027-02-28`
  e `2027-01-31 + 2 meses = 2027-03-31`; `2028-02-29 + 1 ano = 2029-02-28` e `+ 4 anos = 2032-02-29`.
- **Limites de `n`**: `generate_series(0, limite)` com o limite calculado por frequência a partir do horizonte
  (`(horizonte - início) / 7`; diferença de meses; diferença de anos), e o filtro final `data <= horizonte AND
  data <= fim AND data <= 2100-12-31`. Início depois do horizonte dá limite negativo e nenhuma linha.
- **Horizonte**: `p_today + interval '12 months'`.
- **Motivo**: igual ao raciocínio da 010 (somar a partir do início, nunca da data anterior). O teste confere o
  SQL contra um cálculo independente em `java.time`, escrito com aritmética de ano e mês, para as três
  frequências, dias 28 a 31, ano bissexto e virada de ano.
- **Alternativas descartadas**: somar um intervalo à data da ocorrência anterior com `generate_series(start, end,
  interval)` (acumula o recorte: 31/01, 28/02, 28/03).

## 4. Idempotência e ocorrência excluída que não volta

- **Decisão**: `INSERT ... ON CONFLICT (recurrence_id, occurrence_number) WHERE recurrence_id IS NOT NULL DO NOTHING`,
  apoiado no índice único parcial `transactions_recurrence_occurrence_key`. A função sempre percorre `n` de 0 até o
  limite; as que já existem viram conflito sem efeito.
- **Motivo**: o índice único inclui as linhas excluídas logicamente, então uma ocorrência que a pessoa excluiu
  continua ocupando o seu número e **não é recriada** (FR-011). Sob execuções simultâneas, o índice é quem decide, sem
  `catch` (princípio VI). Reprocessar de 0 custa no máximo ~106 conflitos por recorrência semanal por hora, que o
  SC-013 cobre.
- **Alternativas descartadas**: guardar "gerado até a data X" na recorrência (um segundo valor que diverge quando a
  pessoa exclui ou edita ocorrências, e que a corrida entre duas execuções corromperia); começar sempre de
  `max(n) + 1` (exigiria uma consulta a mais por recorrência e não deixaria a função preencher um número que faltou
  por qualquer motivo; o índice único já resolve sem estado extra).

## 5. A efetivação automática é de uso único, garantida pelo banco

- **Decisão**: `transactions.auto_clear BOOLEAN NOT NULL DEFAULT false` e `CHECK (NOT auto_clear OR status =
  'PENDING')`. Toda instrução que passa um lançamento a efetivado desliga o marcador na mesma instrução:
  `clear` (`set status = 'CLEARED', auto_clear = false`), a edição (`auto_clear = CASE WHEN resultado = 'CLEARED'
  THEN false ELSE coalesce(:autoClear, auto_clear) END`) e `auto_clear_due`.
- **Motivo**: a restrição torna impossível um efetivado com o marcador ligado, então devolvido a pendente o
  lançamento **não** é efetivado de novo sozinho (SC-002) sem nenhuma regra extra, e criar ou editar um efetivado
  com o marcador ligado é recusado pelo serviço (campo `autoClear`) antes de chegar ao banco.
- **Pré-validação no serviço**: pedido com `autoClear = true` e estado resultante efetivado é `400` no campo
  `autoClear`; marcador ausente na edição mantém o atual, e se o estado resultante é efetivado o marcador sai
  desligado sem erro (a pessoa não pediu o marcador).
- **Privilégio**: `GRANT UPDATE (auto_clear) ON transactions TO tostao_app` na `V10`. A restrição e o privilégio
  são os dois lados da mesma garantia.
- **Alternativas descartadas**: efetivar sozinho todo pendente vencido (muda a regra da 009 para todos); marcador
  que persiste e é reavaliado a cada execução (efetivaria de novo um lançamento que a pessoa devolveu a pendente);
  coluna de "data de efetivação agendada" separada da data do lançamento (duas datas para a pessoa manter em dia).

## 6. Efetivar sem deadlock com a edição e a efetivação manual

- **Decisão**: `auto_clear_due` seleciona as linhas vencidas com `ORDER BY id FOR UPDATE` num CTE e atualiza por
  `id`.
- **Motivo**: a edição, a exclusão e a efetivação manual travam a unidade (a linha ou as duas pontas de uma
  transferência) **em ordem de id** (`lockUnit`). A rotina travando também em ordem de id nunca forma ciclo com
  elas. Depois de esperar uma trava, o Postgres relê a linha: uma linha que a pessoa efetivou ou excluiu no meio
  tempo sai do conjunto, então a rotina não a efetiva de novo nem a ressuscita.
- **Transferências**: as duas pontas têm sempre a mesma data, o mesmo estado e o mesmo marcador (a criação e a
  edição as mantêm iguais), então ambas casam com a regra e são efetivadas na mesma instrução.

## 7. Agendador, "hoje" e execuções simultâneas

- **Decisão**: `AutomationScheduler` com `@Scheduled(initialDelay = 10 s, fixedDelay = 1 h)` (ao iniciar e depois a
  cada hora), ativo por `@ConditionalOnProperty("tostao.automation.enabled", matchIfMissing = true)`. Chama
  `AutomationService.runOnce(hoje)`. "Hoje" é `Period.today(clock.instant())` (fuso `America/Sao_Paulo`, o mesmo
  do dashboard e da fatura).
- **Trava**: `runOnce` abre uma transação e executa `select pg_try_advisory_xact_lock(<chave fixa>)`. Se não
  conseguir, a execução é pulada (outra está em curso) e devolve `skipped`. A trava some no fim da transação.
  A trava só evita trabalho repetido: a **correção** não depende dela, porque a geração é idempotente (índice
  único) e a efetivação é condicional (`status = 'PENDING' AND auto_clear`); um teste roda as duas funções em
  paralelo sem a trava e confere o mesmo resultado.
- **Falhas**: o `try/catch` fica no agendador, **fora** de qualquer transação, só para registrar o erro e deixar a
  próxima hora tentar de novo. Dentro da transação não há captura (princípio VI).
- **Testes**: o `pom.xml` passa `-Dtostao.automation.enabled=false` ao `surefire`, então nenhuma suíte tem o
  agendador rodando por baixo; os testes chamam `runOnce(data)` com a data que querem (inclusive no passado e no
  futuro). Não existe `src/test/resources/application.properties` (um arquivo com esse nome esconderia o principal).
- **Alternativas descartadas**: `@Scheduled(cron)` à meia-noite (um reinício depois da meia-noite perderia o dia até
  a noite seguinte); uma biblioteca de agendamento com tabela de travas (ShedLock) para uma instância só (a trava
  consultiva do Postgres basta e não traz dependência); `ApplicationReadyEvent` mais um agendador (duas entradas
  para a mesma coisa).

## 8. A tabela `recurrences`

- **Decisão**: registro próprio, com `UNIQUE (id, workspace_id)` e chaves compostas para conta e para categoria (a
  última por `(category_id, workspace_id, type)`, o mesmo mecanismo de `transactions`), `CHECK`s de valor, texto e
  datas, `ended_at` nulo enquanto ativa, RLS de leitura por workspace e de escrita por papel (`app_can_write`,
  `created_by = app_user_id()`), `SELECT` e `INSERT` na tabela e `UPDATE` só em `(ended_at, updated_at)`. Sem `DELETE`.
- **Motivo**: a recorrência tem dados que nenhuma ocorrência carrega (frequência, início, fim, estado), ao contrário
  do plano de parcelas da 010, e a regra não é editável, então o privilégio de `UPDATE` mínimo basta.
- **Ocorrência → recorrência**: `transactions` ganha `recurrence_id` e `occurrence_number` (`>= 0`), com a chave
  composta `(recurrence_id, workspace_id) → recurrences (id, workspace_id)` e a restrição de que só receita ou
  despesa é ocorrência e de que uma linha não é ao mesmo tempo parcela e ocorrência. Os dois campos nascem na
  inserção e nunca mudam (sem `GRANT UPDATE`).
- **Alternativas descartadas**: guardar a regra em colunas JSON; reaproveitar `installment_*` (semânticas
  diferentes: a parcela tem total fixo, a ocorrência não).

## 9. Encerrar sem perder uma geração simultânea

- **Decisão**: `end`, numa transação: (1) `SELECT ... FOR UPDATE` na recorrência (404 se não existe no workspace);
  se já encerrada, devolve `cancelled = 0`; (2) `UPDATE recurrences SET ended_at = now(), updated_at = now()`; (3)
  travar `FOR UPDATE`, em ordem de `occurrence_number`, as ocorrências pendentes vivas com data depois de hoje e
  excluí-las logicamente (`deleted_at`, `updated_at`), repetindo a condição no `UPDATE`; devolve quantas.
- **Motivo**: a geração toma `FOR SHARE` nas linhas de `recurrences` que processa. Se uma geração está em curso, o
  `FOR UPDATE` do encerramento espera ela terminar e, quando continua, o passo 3 já enxerga as ocorrências novas
  (cada instrução em `READ COMMITTED` tira um retrato novo). Se o encerramento vence, a geração relê a linha, vê
  `ended_at` preenchido e a deixa de fora. Em nenhuma ordem sobra uma ocorrência futura pendente de uma
  recorrência encerrada. A mesma condição `status = 'PENDING'` repetida no `UPDATE` faz uma efetivação simultânea
  (manual ou automática) de uma ocorrência prevalecer: uma efetivada nunca é desfeita.
- **"Depois de hoje"**: `occurred_on > hoje` (hoje no fuso do sistema), a mesma definição de "futura" da spec.

## 10. Rotas e respostas

- **Decisão**: `POST /recurrences` (201, corpo = detalhe, `Location`), `GET /recurrences` (200, lista de resumos),
  `GET /recurrences/{id}` (200, detalhe com as ocorrências), `POST /recurrences/{id}/end` (200, `{cancelled}`).
  `id` malformado vira 404 pelo tratador de `PathVariable` que já existe.
- **Resumo** (lista): os dados da recorrência, `ended`, `endedAt` e `nextOccurrenceOn` (a menor data entre as
  ocorrências pendentes não excluídas de hoje em diante, a "próxima"; nulo se não há; uma pendente vencida não é a
  próxima). **Detalhe**: o resumo mais `totalCents`, `clearedCents`,
  `pendingCents` (positivos, das não excluídas) e `occurrences` (lançamentos em ordem de número). Os totais são
  somados em Java a partir da própria lista, como no plano da 010.
- **Sem paginação**: o limite natural é pequeno (recorrências são dezenas) e as ocorrências de uma recorrência não
  passam de ~106 na criação mais as geradas depois a 12 meses de horizonte.
- **Alternativas descartadas**: `PUT` para editar (decisão da spec); `DELETE /recurrences/{id}` (a spec encerra e
  mantém as efetivadas; `DELETE` sugere apagar tudo).

## 11. Validação de entrada da recorrência

- **Decisão**: `CreateRecurrenceRequest` (record de campos fechados): `accountId`, `categoryId`, `type`
  (`INCOME` ou `EXPENSE`; `TRANSFER` é recusado no campo `type`), `amountCents` (`Long`, 1 a 10¹²), `description`
  (1 a 200, já sem espaços), `notes` (opcional, até 1.000), `frequency` (`WEEKLY`, `MONTHLY`, `YEARLY`), `startOn`
  (`LocalDate`), `endOn` (opcional) e `autoClear` (`Boolean` opcional, padrão `false`).
- **Regras cruzadas no serviço, antes de gravar**: `startOn` entre `hoje - 366 dias` e 2100-12-31 (campo `startOn`);
  `endOn` entre `startOn` e 2100-12-31 (campo `endOn`); conta e categoria ativas e do tipo, com as mesmas
  conferências e travas da criação de lançamentos (`UsableRefs`).
- **Motivo do limite de 366 dias**: o início no passado gera as ocorrências desde o início; 366 dias limitam o lote
  a ~53 semanais, mensais 13, anuais 2.

## 12. Efeito em specs anteriores

- **Decisão**: o `SELECT` de lançamentos lê três colunas a mais; `clear` e a edição passam a escrever `auto_clear`; o
  registro de compra parcelada aceita `autoClear`. Com o marcador sempre desligado e sem recorrências, nenhum
  resultado de consulta existente muda (FR-026).
- **Risco verificado**: `TransactionResponse` só é construído pelo `ROW_MAPPER` de `TransactionService`; testes
  antigos não chamam o construtor. O construtor de `InstallmentService` e o de `TransactionService` continuam com
  `(JdbcClient)`, porque testes antigos os instanciam assim.
- **Efetivação manual com o marcador ligado**: sem o ajuste de `clear` e da edição, a nova restrição quebraria a
  efetivação de uma linha com `auto_clear = true`. Os dois ajustes e o teste que prova a efetivação manual de um
  lançamento com o marcador ligado fazem parte da mesma tarefa (T005).
