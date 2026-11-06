-- Tostão · V4: categorias (spec 003)
-- A tabela categories já existe na V1. Aqui entram restrições, hierarquia garantida pelo banco, nome
-- único entre irmãs ativas, escrita por papel no RLS, a revogação do DELETE e o conjunto padrão de
-- categorias (também no cadastro, via register_user). Depois de aplicada, esta migration nunca é
-- editada: mudanças vão em V5, V6...

-- Nome já sem espaços nas pontas, de 1 a 60 caracteres; cor #RRGGBB; ícone de 1 a 40 caracteres.
ALTER TABLE categories ADD CONSTRAINT categories_name_trimmed
    CHECK (name = btrim(name) AND char_length(name) BETWEEN 1 AND 60);
ALTER TABLE categories ADD CONSTRAINT categories_color_format
    CHECK (color IS NULL OR color ~ '^#[0-9A-Fa-f]{6}$');
ALTER TABLE categories ADD CONSTRAINT categories_icon_length
    CHECK (icon IS NULL OR char_length(icon) BETWEEN 1 AND 40);

-- A subcategoria só aponta para um pai do mesmo workspace e do mesmo tipo.
ALTER TABLE categories ADD CONSTRAINT categories_id_workspace_kind_key UNIQUE (id, workspace_id, kind);
ALTER TABLE categories ADD CONSTRAINT categories_parent_kind_fkey
    FOREIGN KEY (parent_id, workspace_id, kind) REFERENCES categories (id, workspace_id, kind);

-- Dois níveis apenas: o pai não pode ser subcategoria, e quem tem filhas não pode ganhar um pai.
-- Um CHECK não consulta outra linha, por isso é um gatilho. O serviço valida antes; isto é a rede de
-- segurança.
CREATE FUNCTION categories_two_levels() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM categories p
                   WHERE p.id = NEW.parent_id AND p.workspace_id = NEW.workspace_id
                     AND p.parent_id IS NOT NULL) THEN
            RAISE EXCEPTION 'uma subcategoria não pode ter filhas' USING ERRCODE = 'check_violation';
        END IF;
        IF EXISTS (SELECT 1 FROM categories c
                   WHERE c.parent_id = NEW.id AND c.workspace_id = NEW.workspace_id) THEN
            RAISE EXCEPTION 'uma categoria com filhas não pode ter pai' USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER categories_two_levels
    BEFORE INSERT OR UPDATE OF parent_id ON categories
    FOR EACH ROW EXECUTE FUNCTION categories_two_levels();

-- Nome único entre irmãs ativas, sem diferenciar maiúsculas: as principais do mesmo tipo e as
-- subcategorias do mesmo pai.
CREATE UNIQUE INDEX categories_active_root_name_key
    ON categories (workspace_id, kind, lower(name)) WHERE NOT archived AND parent_id IS NULL;
CREATE UNIQUE INDEX categories_active_child_name_key
    ON categories (workspace_id, parent_id, lower(name)) WHERE NOT archived AND parent_id IS NOT NULL;

-- ============================================================================
-- Escrita por papel: só OWNER e EDITOR gravam categorias, também no banco (app_can_write vem da V3).
-- ============================================================================

DROP POLICY categories_tenant ON categories;

CREATE POLICY categories_read ON categories
    FOR SELECT TO tostao_app
    USING (workspace_id = app_workspace_id());

CREATE POLICY categories_insert ON categories
    FOR INSERT TO tostao_app
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

CREATE POLICY categories_update ON categories
    FOR UPDATE TO tostao_app
    USING (workspace_id = app_workspace_id() AND app_can_write(workspace_id))
    WITH CHECK (workspace_id = app_workspace_id() AND app_can_write(workspace_id));

-- Categoria não se apaga: só se arquiva. Sem política de DELETE e sem o privilégio.
REVOKE DELETE ON categories FROM tostao_app;

-- ============================================================================
-- Conjunto padrão de categorias
-- ============================================================================

-- Copia o conjunto padrão para o workspace. Só register_user e esta migration a executam; a role da
-- aplicação não tem permissão de execução.
CREATE FUNCTION seed_default_categories(p_workspace UUID) RETURNS void
    LANGUAGE plpgsql
    SET search_path = public, pg_temp
AS $$
BEGIN
    INSERT INTO categories (workspace_id, name, kind)
    SELECT p_workspace, v.name, v.kind
    FROM (VALUES
        ('Alimentação', 'EXPENSE'),
        ('Moradia',     'EXPENSE'),
        ('Transporte',  'EXPENSE'),
        ('Saúde',       'EXPENSE'),
        ('Lazer',       'EXPENSE'),
        ('Educação',    'EXPENSE'),
        ('Outros',      'EXPENSE'),
        ('Salário',     'INCOME'),
        ('Rendimentos', 'INCOME'),
        ('Outros',      'INCOME')
    ) AS v(name, kind)
    ON CONFLICT DO NOTHING;

    INSERT INTO categories (workspace_id, parent_id, name, kind)
    SELECT p_workspace, p.id, v.name, p.kind
    FROM (VALUES
        ('Alimentação', 'Mercado'),
        ('Alimentação', 'Restaurantes'),
        ('Moradia',     'Aluguel'),
        ('Moradia',     'Contas de casa'),
        ('Transporte',  'Combustível'),
        ('Transporte',  'Transporte público'),
        ('Saúde',       'Farmácia'),
        ('Saúde',       'Consultas'),
        ('Lazer',       'Viagens'),
        ('Lazer',       'Assinaturas')
    ) AS v(parent_name, name)
    JOIN categories p
      ON p.workspace_id = p_workspace AND p.parent_id IS NULL AND p.kind = 'EXPENSE'
     AND p.name = v.parent_name AND NOT p.archived
    ON CONFLICT DO NOTHING;
END
$$;

REVOKE ALL ON FUNCTION seed_default_categories(UUID) FROM PUBLIC;

-- Cadastro atômico: usuário, workspace pessoal, associação OWNER e categorias padrão numa chamada.
-- Mesma assinatura e mesmos privilégios da V2; o corpo é o da V2 mais a semente. E-mail já existente
-- continua devolvendo zero linhas, sem levantar exceção.
CREATE OR REPLACE FUNCTION register_user(p_email TEXT, p_password_hash TEXT, p_name TEXT)
    RETURNS TABLE (new_user_id UUID, new_workspace_id UUID)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
DECLARE
    v_user      UUID;
    v_workspace UUID;
BEGIN
    INSERT INTO users (email, password_hash, name)
    VALUES (lower(trim(p_email)), p_password_hash, p_name)
    ON CONFLICT ((lower(email))) DO NOTHING
    RETURNING id INTO v_user;

    IF v_user IS NULL THEN
        RETURN;
    END IF;

    INSERT INTO workspaces (name) VALUES ('Pessoal') RETURNING id INTO v_workspace;
    INSERT INTO workspace_members (workspace_id, user_id, role) VALUES (v_workspace, v_user, 'OWNER');
    PERFORM seed_default_categories(v_workspace);

    RETURN QUERY SELECT v_user, v_workspace;
END
$$;

-- Workspaces que já existiam e não têm nenhuma categoria recebem o conjunto uma única vez. A migration
-- roda uma só vez, então o conjunto nunca é recriado depois que a pessoa edita ou arquiva.
SELECT seed_default_categories(w.id)
FROM workspaces w
WHERE NOT EXISTS (SELECT 1 FROM categories c WHERE c.workspace_id = w.id);
