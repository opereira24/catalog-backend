package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import pt.diamondcars.catalogbackend.web.dto.ApiError;

/**
 * Unit tests of {@link ApiExceptionHandler} without Spring: the handler has a default constructor
 * and only reads the request URI.
 */
class ApiExceptionHandlerTest {

	private final ApiExceptionHandler handler = new ApiExceptionHandler();

	/**
	 * A database integrity violation is a 409 with a generic message that never leaks the constraint.
	 * It is the response of a car {@code POST}/{@code PUT} that loses the race against the deletion of
	 * its partner ({@code cars_partner_id_fkey}, TASK-015, K-PD1/K-PD3 of {@code
	 * PartnerDeleteConcurrencyTest}).
	 */
	@Test
	void aDataIntegrityViolationIsAConflictThatDoesNotNameTheConstraint() {
		DataIntegrityViolationException violation = new DataIntegrityViolationException(
				"ERROR: insert or update on table \"cars\" violates foreign key constraint \"cars_partner_id_fkey\"");

		ResponseEntity<ApiError> response =
				handler.handleDataIntegrityViolation(violation, new MockHttpServletRequest("PUT", "/api/backoffice/cars/x"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		ApiError body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.status()).isEqualTo(409);
		assertThat(body.message()).isEqualTo("Pedido em conflito com o estado atual dos dados");
		assertThat(body.path()).isEqualTo("/api/backoffice/cars/x");
		assertThat(body.message()).doesNotContain("cars_partner_id_fkey");
	}
}
