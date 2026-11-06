-- Tostão · V1: schema da fase 1 (auth, workspaces, contas, categorias, lançamentos)
-- Regras: dinheiro em centavos (BIGINT), todo dado tem workspace_id, exclusão lógica em lançamentos.
-- Esta migration, depois de aplicada, nunca é editada: mudanças vão em V2, V3...

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- e-mail único sem diferenciar maiúsculas
CREATE UNIQUE INDEX users_email_key ON users (lower(email));

CREATE TABLE workspaces (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT        NOT NULL,
    base_currency CHAR(3)     NOT NULL DEFAULT 'BRL',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspace_members (
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         TEXT        NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX workspace_members_user_idx ON workspace_members (user_id);

CREATE TABLE accounts (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name                  TEXT        NOT NULL,
    type                  TEXT        NOT NULL
        CHECK (type IN ('CHECKING', 'SAVINGS', 'CASH', 'CREDIT_CARD', 'INVESTMENT')),
    currency              CHAR(3)     NOT NULL DEFAULT 'BRL',
    initial_balance_cents BIGINT      NOT NULL DEFAULT 0,
    credit_limit_cents    BIGINT      CHECK (credit_limit_cents >= 0),
    closing_day           SMALLINT    CHECK (closing_day BETWEEN 1 AND 31),
    due_day               SMALLINT    CHECK (due_day BETWEEN 1 AND 31),
    archived              BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- alvo das chaves estrangeiras compostas: garante o mesmo workspace
    UNIQUE (id, workspace_id)
);
CREATE INDEX accounts_workspace_idx ON accounts (workspace_id);

-- Dois níveis de hierarquia: a regra (subcategoria não tem filha) é validada no serviço.
CREATE TABLE categories (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    parent_id    UUID,
    name         TEXT        NOT NULL,
    kind         TEXT        NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
    color        TEXT,
    icon         TEXT,
    archived     BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, workspace_id),
    FOREIGN KEY (parent_id, workspace_id) REFERENCES categories (id, workspace_id),
    CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX categories_workspace_idx ON categories (workspace_id, parent_id);

-- Valor com sinal: receita > 0, despesa < 0. Transferência = duas linhas (saída negativa e
-- entrada positiva) com o mesmo transfer_group_id, sem categoria.
CREATE TABLE transactions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id      UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    account_id        UUID        NOT NULL,
    category_id       UUID,
    type              TEXT        NOT NULL CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    amount_cents      BIGINT      NOT NULL CHECK (amount_cents <> 0),
    occurred_on       DATE        NOT NULL,
    description       TEXT        NOT NULL,
    notes             TEXT,
    status            TEXT        NOT NULL DEFAULT 'CLEARED' CHECK (status IN ('PENDING', 'CLEARED')),
    transfer_group_id UUID,
    created_by        UUID        NOT NULL REFERENCES users (id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    -- conta e categoria precisam ser do mesmo workspace do lançamento
    FOREIGN KEY (account_id, workspace_id) REFERENCES accounts (id, workspace_id),
    FOREIGN KEY (category_id, workspace_id) REFERENCES categories (id, workspace_id),
    CHECK (type <> 'INCOME' OR amount_cents > 0),
    CHECK (type <> 'EXPENSE' OR amount_cents < 0),
    CHECK ((type = 'TRANSFER') = (transfer_group_id IS NOT NULL)),
    CHECK (type <> 'TRANSFER' OR category_id IS NULL)
);
CREATE INDEX transactions_workspace_date_idx
    ON transactions (workspace_id, occurred_on) WHERE deleted_at IS NULL;
CREATE INDEX transactions_workspace_category_date_idx
    ON transactions (workspace_id, category_id, occurred_on) WHERE deleted_at IS NULL;
CREATE INDEX transactions_workspace_account_date_idx
    ON transactions (workspace_id, account_id, occurred_on) WHERE deleted_at IS NULL;
CREATE INDEX transactions_transfer_group_idx
    ON transactions (workspace_id, transfer_group_id) WHERE transfer_group_id IS NOT NULL;

-- RLS ligado em todas as tabelas. Sem políticas, qualquer role que não seja dona da tabela
-- (ou que tenha BYPASSRLS) não enxerga nada, o que também bloqueia a API automática do Supabase.
-- A spec 001 cria a role da aplicação (sem BYPASSRLS), as políticas por workspace e o
-- SET LOCAL do workspace por transação.
ALTER TABLE users             ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspaces        ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounts          ENABLE ROW LEVEL SECURITY;
ALTER TABLE categories        ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions      ENABLE ROW LEVEL SECURITY;
