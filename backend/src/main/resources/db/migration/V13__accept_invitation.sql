-- Tostão · V13: aceite de convite (spec 012)
-- A pessoa que aceita um convite ainda não é membro do workspace: o RLS por workspace (app_workspace_id) não existe para
-- ela, e a tabela invitations só é visível ao dono. Por isso o aceite é uma função SECURITY DEFINER estreita, no mesmo
-- padrão das funções da V2 (cadastro e login): fixa o search_path, só a role da aplicação a executa, recebe só o que
-- precisa (a impressão do código e o id da pessoa, que vem da sessão) e só consegue criar o vínculo do convite que casa
-- com ele. Os desfechos voltam como resultado (outcome), sem exceção.
-- Depois de aplicada, esta migration nunca é editada: mudanças vão em V14, V15...

-- Desfechos:
--   INVALID         pessoa inexistente; código inexistente; convite usado, revogado ou expirado; e-mail da conta
--                   diferente do e-mail do convite. Todos iguais para quem chama: ninguém descobre quem foi convidado.
--   ALREADY_MEMBER  a pessoa já é membro do workspace do convite (o convite não é consumido).
--   FULL            o workspace já tem 20 membros.
--   ACCEPTED        o vínculo foi criado com o papel do convite e o convite marcado como usado.
-- O convite e a linha do workspace ficam travados (FOR UPDATE) até o fim da transação: um segundo aceite do mesmo
-- código relê o convite já usado e recebe INVALID, e os aceites do mesmo workspace são serializados (o limite de 20
-- membros nunca é ultrapassado).
CREATE FUNCTION accept_invitation(p_code_hash TEXT, p_user_id UUID)
    RETURNS TABLE (outcome TEXT, workspace_id UUID, workspace_name TEXT, role TEXT)
    LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = public, pg_temp
AS $$
#variable_conflict use_column
DECLARE
    v_email TEXT;
    v_inv   invitations%ROWTYPE;
    v_name  TEXT;
BEGIN
    -- o e-mail vem da conta, nunca do corpo da requisição
    SELECT lower(btrim(u.email)) INTO v_email FROM users u WHERE u.id = p_user_id;
    IF v_email IS NULL THEN
        RETURN QUERY SELECT 'INVALID'::text, NULL::uuid, NULL::text, NULL::text;
        RETURN;
    END IF;

    SELECT * INTO v_inv FROM invitations i WHERE i.code_hash = p_code_hash FOR UPDATE;
    IF NOT FOUND
       OR v_inv.accepted_at IS NOT NULL
       OR v_inv.revoked_at IS NOT NULL
       OR v_inv.expires_at <= now()
       OR v_inv.email <> v_email THEN
        RETURN QUERY SELECT 'INVALID'::text, NULL::uuid, NULL::text, NULL::text;
        RETURN;
    END IF;

    -- serializa os aceites do mesmo workspace
    SELECT w.name INTO v_name FROM workspaces w WHERE w.id = v_inv.workspace_id FOR UPDATE;

    IF EXISTS (SELECT 1 FROM workspace_members m
               WHERE m.workspace_id = v_inv.workspace_id AND m.user_id = p_user_id) THEN
        RETURN QUERY SELECT 'ALREADY_MEMBER'::text, NULL::uuid, NULL::text, NULL::text;
        RETURN;
    END IF;

    IF (SELECT count(*) FROM workspace_members m WHERE m.workspace_id = v_inv.workspace_id) >= 20 THEN
        RETURN QUERY SELECT 'FULL'::text, NULL::uuid, NULL::text, NULL::text;
        RETURN;
    END IF;

    INSERT INTO workspace_members (workspace_id, user_id, role)
    VALUES (v_inv.workspace_id, p_user_id, v_inv.role);

    UPDATE invitations SET accepted_at = now(), accepted_by = p_user_id WHERE id = v_inv.id;

    RETURN QUERY SELECT 'ACCEPTED'::text, v_inv.workspace_id, v_name, v_inv.role;
END
$$;

REVOKE ALL ON FUNCTION accept_invitation(TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION accept_invitation(TEXT, UUID) TO tostao_app;
