package com.tostao.tostao_api.category.dto;

/**
 * As chaves de ícone de categoria que a API aceita (as mesmas do design system do front). O ícone guardado em
 * {@code categories.icon} é só a chave; qualquer outro texto é recusado com 400 no campo {@code icon}.
 */
public final class CategoryIcons {

	/** Expressão para {@code @Pattern}: nulo passa (o ícone é opcional); as 39 chaves passam; o resto não. */
	public static final String PATTERN = "^(bowl|cart|utensils|coffee|glass|house|key|bolt|drop|wifi|phone|wrench|bus|car|fuel|plane"
			+ "|ticket|game|play|music|dumbbell|heart|pill|scissors|paw|shield|bag|shirt|gift|book|cap|briefcase|laptop|receipt"
			+ "|percent|sprout|undo|star|box)$";

	public static final String MESSAGE = "deve ser um dos ícones de categoria";

	private CategoryIcons() {
	}

}
