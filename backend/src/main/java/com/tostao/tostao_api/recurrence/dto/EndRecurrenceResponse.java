package com.tostao.tostao_api.recurrence.dto;

/** Quantas ocorrências pendentes futuras o encerramento excluiu (zero se não havia nenhuma ou se já estava encerrada). */
public record EndRecurrenceResponse(int cancelled) {
}
