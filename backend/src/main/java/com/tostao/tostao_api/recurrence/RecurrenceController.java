package com.tostao.tostao_api.recurrence;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.recurrence.dto.CreateRecurrenceRequest;
import com.tostao.tostao_api.recurrence.dto.EndRecurrenceResponse;
import com.tostao.tostao_api.recurrence.dto.RecurrenceDetailResponse;
import com.tostao.tostao_api.recurrence.dto.RecurrenceResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/recurrences")
class RecurrenceController {

	private final RecurrenceService recurrences;

	RecurrenceController(RecurrenceService recurrences) {
		this.recurrences = recurrences;
	}

	/** Cria a recorrência e devolve o detalhe com as ocorrências geradas, com o caminho da consulta em {@code Location}. */
	@PostMapping
	ResponseEntity<RecurrenceDetailResponse> create(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CreateRecurrenceRequest request) {
		RecurrenceDetailResponse created = recurrences.create(workspaceId, request);
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/recurrences/" + created.id());
		return ResponseEntity.created(location).body(created);
	}

	@GetMapping
	List<RecurrenceResponse> list(@PathVariable("wid") UUID workspaceId) {
		return recurrences.list(workspaceId);
	}

	@GetMapping("/{id}")
	RecurrenceDetailResponse get(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID id) {
		return recurrences.get(workspaceId, id);
	}

	/** Encerra a recorrência e exclui as ocorrências pendentes futuras; já encerrada responde 200 com zero. */
	@PostMapping("/{id}/end")
	EndRecurrenceResponse end(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID id) {
		return recurrences.end(workspaceId, id);
	}

}
