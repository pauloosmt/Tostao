package com.tostao.tostao_api.workspace;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca um método de serviço cujo <b>primeiro parâmetro é o {@code UUID workspaceId}</b>. Antes de o
 * método rodar, e dentro da mesma transação, o {@link WorkspaceScopeAspect} confere que o usuário
 * autenticado é membro desse workspace (404 se não for) e fixa o contexto que o RLS usa.
 *
 * <p>Todo serviço de módulo que lê ou grava dados de workspace deve usar esta anotação, além de
 * filtrar explicitamente por {@code workspace_id} nas próprias consultas (segunda camada).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WorkspaceScoped {

	/**
	 * {@code true} para operações que alteram dados: só os papéis OWNER e EDITOR passam, e VIEWER recebe
	 * 403. Quem não é membro recebe 404 de qualquer forma.
	 */
	boolean write() default false;

}
