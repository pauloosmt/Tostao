#!/bin/bash
# Copia os DADOS do banco local (Docker Compose) para o banco de produção (Supabase). O schema já existe lá (o Flyway da API o criou).
#
# Uso:
#   scripts/local-to-prod.sh                 exporta, mostra o resumo, pede confirmação e carrega
#   scripts/local-to-prod.sh --export-only ARQUIVO.sql   só exporta (não toca em produção)
#
# O arquivo exportado tem dados pessoais e hashes de senha: fica numa pasta temporária, nunca no repositório, e é apagado no fim.
# A produção precisa estar VAZIA de usuários (o script recusa se não estiver) e a carga roda numa transação só: ou entra tudo, ou nada.
# Requer o Docker (usa o container do Postgres local e uma imagem postgres:17 para falar com a produção).
set -euo pipefail
export MSYS_NO_PATHCONV=1

LOCAL_CONTAINER="${LOCAL_CONTAINER:-tostao-postgres-1}"
LOCAL_USER="${LOCAL_USER:-tostao}"
LOCAL_DB="${LOCAL_DB:-tostao}"

# Ordem que respeita as chaves estrangeiras. login_attempts (bloqueios de login) e flyway_schema_history ficam de fora.
# Categorias: as de primeiro nível antes das subcategorias (o trigger de dois níveis confere o pai).
TABLES=(users workspaces workspace_members accounts categories recurrences transactions monthly_budgets weekly_budgets invitations)

local_psql() { docker exec -i "$LOCAL_CONTAINER" psql -U "$LOCAL_USER" -d "$LOCAL_DB" -v ON_ERROR_STOP=1 "$@"; }

query_for() {
  case "$1" in
    categories) echo "select * from categories order by (parent_id is not null), id" ;;
    *) echo "select * from $1" ;;
  esac
}

export_data() {
  local file="$1"
  {
    echo "SET client_encoding = 'UTF8';"
    for table in "${TABLES[@]}"; do
      echo "COPY public.$table FROM stdin;"
      local_psql -q -c "COPY ($(query_for "$table")) TO STDOUT"
      echo '\.'
    done
  } > "$file"
}

counts_local() {
  for table in "${TABLES[@]}"; do echo "$table|$(local_psql -tA -c "select count(*) from $table")"; done
}

if [ "${1:-}" = "--export-only" ]; then
  [ -n "${2:-}" ] || { echo "Informe o arquivo de saída."; exit 2; }
  export_data "$2"
  echo "Exportado em $2 (contém dados pessoais: não commite)."
  exit 0
fi

FILE="$(mktemp "${TMPDIR:-/tmp}/tostao-dados-XXXXXX.sql")"
trap 'rm -f "$FILE"' EXIT

echo "Exportando o banco local…"
export_data "$FILE"
echo
echo "Linhas que serão copiadas:"
counts_local | column -t -s'|'
echo

read -r -p "Host do banco de produção (ex.: aws-1-us-west-2.pooler.supabase.com): " PROD_HOST
read -r -p "Usuário (postgres.<ref do projeto>): " PROD_USER
read -r -s -p "Senha do banco de produção (não aparece na tela): " PGPASSWORD
echo
export PGPASSWORD
CONN="host=$PROD_HOST port=5432 dbname=postgres user=$PROD_USER sslmode=require"
prod_psql() { docker run --rm -i -e PGPASSWORD "${PG_IMAGE:-postgres:17}" psql "$CONN" -v ON_ERROR_STOP=1 "$@"; }

echo "Conferindo a produção…"
EXISTING="$(prod_psql -tA -c "select count(*) from users")"
if [ "$EXISTING" != "0" ]; then
  echo "A produção já tem $EXISTING usuário(s). Não vou misturar dados: esvazie-a antes ou fale com quem mantém o projeto."
  exit 1
fi

read -r -p "Carregar esses dados na PRODUÇÃO ($PROD_HOST)? Digite SIM para continuar: " ANSWER
[ "$ANSWER" = "SIM" ] || { echo "Cancelado. Nada foi alterado."; exit 1; }

echo "Carregando (uma transação só)…"
prod_psql --single-transaction -f - < "$FILE"

echo
echo "Conferindo o resultado (local | produção):"
OK=1
for table in "${TABLES[@]}"; do
  LOCAL_N="$(local_psql -tA -c "select count(*) from $table")"
  PROD_N="$(prod_psql -tA -c "select count(*) from $table")"
  MARK="ok"; [ "$LOCAL_N" = "$PROD_N" ] || { MARK="DIFERENTE"; OK=0; }
  printf '  %-18s %5s | %5s  %s\n' "$table" "$LOCAL_N" "$PROD_N" "$MARK"
done
[ "$OK" = 1 ] && echo "Pronto: os dados estão na produção. Entre no site com o mesmo e-mail e senha de sempre." || { echo "Há diferenças: confira acima."; exit 1; }
