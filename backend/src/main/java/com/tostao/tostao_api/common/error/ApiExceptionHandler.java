package com.tostao.tostao_api.common.error;

import java.util.List;

import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Traduz exceções em Problem Details (RFC 7807), sem vazar detalhes internos. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldProblem> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(ApiExceptionHandler::toProblem)
				.toList();
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Um ou mais campos são inválidos");
		problem.setTitle("Requisição inválida");
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Corpo da requisição ausente, malformado ou com campos não permitidos");
		problem.setTitle("Requisição inválida");
		String field = offendingField(ex);
		if (field != null) {
			problem.setProperty("errors", List.of(new FieldProblem(field, "valor inválido")));
		}
		return ResponseEntity.badRequest().body(problem);
	}

	/**
	 * Campo do corpo que o Jackson não conseguiu ler (tipo errado, decimal num campo inteiro, campo não
	 * permitido), para a resposta apontar o campo; {@code null} quando o corpo nem é um JSON legível. Só o
	 * nome do campo sai, nunca a mensagem interna do Jackson.
	 */
	private static String offendingField(HttpMessageNotReadableException ex) {
		if (ex.getCause() instanceof tools.jackson.core.JacksonException jackson) {
			for (int i = jackson.getPath().size() - 1; i >= 0; i--) {
				String name = jackson.getPath().get(i).getPropertyName();
				if (name != null && !name.isBlank()) {
					return name;
				}
			}
		}
		return null;
	}

	/**
	 * Identificador de rota que não é UUID responde como identificador inexistente (404), igual a um
	 * UUID válido que não existe. Outros parâmetros com tipo errado continuam 400.
	 */
	@Override
	protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (ex instanceof MethodArgumentTypeMismatchException mismatch
				&& mismatch.getParameter().hasParameterAnnotation(PathVariable.class)) {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Recurso não encontrado");
			problem.setTitle("Não encontrado");
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
		}
		// parâmetro de consulta com tipo errado (data, número, UUID, enum): 400 apontando o parâmetro
		if (ex instanceof MethodArgumentTypeMismatchException mismatch
				&& mismatch.getParameter().hasParameterAnnotation(RequestParam.class)) {
			ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
					"Um ou mais campos são inválidos");
			problem.setTitle("Requisição inválida");
			problem.setProperty("errors", List.of(new FieldProblem(mismatch.getName(), "valor inválido")));
			return ResponseEntity.badRequest().body(problem);
		}
		return super.handleTypeMismatch(ex, headers, status, request);
	}

	@ExceptionHandler(EmailAlreadyExistsException.class)
	ResponseEntity<ProblemDetail> emailAlreadyExists(EmailAlreadyExistsException ex) {
		return problem(HttpStatus.CONFLICT, "E-mail já cadastrado", ex.getMessage());
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ResponseEntity<ProblemDetail> invalidCredentials(InvalidCredentialsException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "Credenciais inválidas", ex.getMessage());
	}

	@ExceptionHandler(UnauthenticatedException.class)
	ResponseEntity<ProblemDetail> unauthenticated(UnauthenticatedException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "Não autenticado", ex.getMessage());
	}

	@ExceptionHandler(TooManyAttemptsException.class)
	ResponseEntity<ProblemDetail> tooManyAttempts(TooManyAttemptsException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
		problem.setTitle("Muitas tentativas");
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
				.body(problem);
	}

	@ExceptionHandler(WorkspaceNotFoundException.class)
	ResponseEntity<ProblemDetail> workspaceNotFound(WorkspaceNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
	}

	@ExceptionHandler(InsufficientRoleException.class)
	ResponseEntity<ProblemDetail> insufficientRole(InsufficientRoleException ex) {
		return problem(HttpStatus.FORBIDDEN, "Sem permissão", ex.getMessage());
	}

	@ExceptionHandler(AccountNotFoundException.class)
	ResponseEntity<ProblemDetail> accountNotFound(AccountNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
	}

	@ExceptionHandler(AccountNameAlreadyExistsException.class)
	ResponseEntity<ProblemDetail> accountNameAlreadyExists(AccountNameAlreadyExistsException ex) {
		return problem(HttpStatus.CONFLICT, "Nome de conta já em uso", ex.getMessage());
	}

	@ExceptionHandler(AccountArchivedException.class)
	ResponseEntity<ProblemDetail> accountArchived(AccountArchivedException ex) {
		return problem(HttpStatus.CONFLICT, "Conta arquivada", ex.getMessage());
	}

	@ExceptionHandler(CategoryNotFoundException.class)
	ResponseEntity<ProblemDetail> categoryNotFound(CategoryNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
	}

	@ExceptionHandler(CategoryNameAlreadyExistsException.class)
	ResponseEntity<ProblemDetail> categoryNameAlreadyExists(CategoryNameAlreadyExistsException ex) {
		return problem(HttpStatus.CONFLICT, "Nome de categoria já em uso", ex.getMessage());
	}

	@ExceptionHandler(CategoryConflictException.class)
	ResponseEntity<ProblemDetail> categoryConflict(CategoryConflictException ex) {
		return problem(HttpStatus.CONFLICT, "Conflito de estado", ex.getMessage());
	}

	@ExceptionHandler(TransactionNotFoundException.class)
	ResponseEntity<ProblemDetail> transactionNotFound(TransactionNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
	}

	@ExceptionHandler(TransactionConflictException.class)
	ResponseEntity<ProblemDetail> transactionConflict(TransactionConflictException ex) {
		return problem(HttpStatus.CONFLICT, "Conflito de estado", ex.getMessage());
	}

	@ExceptionHandler(BudgetNotFoundException.class)
	ResponseEntity<ProblemDetail> budgetNotFound(BudgetNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
	}

	@ExceptionHandler(InstallmentPlanNotFoundException.class)
	ResponseEntity<ProblemDetail> installmentPlanNotFound(InstallmentPlanNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
	}

	@ExceptionHandler(RecurrenceNotFoundException.class)
	ResponseEntity<ProblemDetail> recurrenceNotFound(RecurrenceNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "Não encontrado", ex.getMessage());
	}

	@ExceptionHandler(BudgetConflictException.class)
	ResponseEntity<ProblemDetail> budgetConflict(BudgetConflictException ex) {
		return problem(HttpStatus.CONFLICT, "Conflito de estado", ex.getMessage());
	}

	@ExceptionHandler(InvoiceNotAvailableException.class)
	ResponseEntity<ProblemDetail> invoiceNotAvailable(InvoiceNotAvailableException ex) {
		return problem(HttpStatus.CONFLICT, "Conflito de estado", ex.getMessage());
	}

	@ExceptionHandler(FieldValidationException.class)
	ResponseEntity<ProblemDetail> fieldValidation(FieldValidationException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Um ou mais campos são inválidos");
		problem.setTitle("Requisição inválida");
		problem.setProperty("errors", List.of(new FieldProblem(ex.field(), ex.getMessage())));
		return ResponseEntity.badRequest().body(problem);
	}

	/**
	 * Corrida residual de nome de conta: duas gravações simultâneas passaram pela checagem prévia e o
	 * índice único barrou a segunda. Chega aqui só depois do rollback, fora de qualquer transação.
	 * Vale para o nome de conta e para o nome de categoria entre irmãs ativas.
	 */
	@ExceptionHandler(DuplicateKeyException.class)
	ResponseEntity<ProblemDetail> duplicateKey(DuplicateKeyException ex) {
		String message = ex.getMostSpecificCause().getMessage();
		if (message != null && message.contains("accounts_active_name_key")) {
			return problem(HttpStatus.CONFLICT, "Nome de conta já em uso",
					new AccountNameAlreadyExistsException().getMessage());
		}
		if (message != null && (message.contains("categories_active_root_name_key")
				|| message.contains("categories_active_child_name_key"))) {
			return problem(HttpStatus.CONFLICT, "Nome de categoria já em uso",
					new CategoryNameAlreadyExistsException().getMessage());
		}
		throw ex;
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(title);
		return ResponseEntity.status(status).body(problem);
	}

	private static FieldProblem toProblem(FieldError error) {
		return new FieldProblem(error.getField(), error.getDefaultMessage());
	}

	/** Item de {@code errors[]} na resposta de validação. */
	public record FieldProblem(String field, String message) {
	}

}
