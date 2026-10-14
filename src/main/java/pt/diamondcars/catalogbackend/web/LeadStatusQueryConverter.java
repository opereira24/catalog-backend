package pt.diamondcars.catalogbackend.web;

import java.beans.PropertyEditorSupport;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;

/**
 * Binds {@code ?status=} of {@code GET /api/backoffice/leads} (TASK-006, AC E.2) to {@link
 * LeadStatus} by its stored lower-case value only ({@code ativo}, ..., {@code desistiu}). Registered
 * by {@link BackOfficeLeadController#bindStatusByItsStoredValue}. Anything else, the Java constant
 * name {@code ATIVO} included, is a 400 {@code Parametro 'status' com valor invalido}: {@link
 * LeadStatus#fromValue(String)} throws, Spring turns it into a {@code
 * MethodArgumentTypeMismatchException}, and {@link ApiExceptionHandler} maps that. An empty or blank
 * {@code ?status=} binds to {@code null}, meaning "no filter" (what a form's "all" option sends).
 *
 * <p>A property editor and not a {@code Converter<String, LeadStatus>} bean (what {@code
 * dcbo-backend} has as {@code LeadStatusQueryConverter}): the converter bean refuses {@code ATIVO},
 * but Spring's {@code TypeConverterDelegate} then falls back to {@code Enum.valueOf}, so {@code
 * ?status=ATIVO} answered 200 (measured in TASK-005 for {@code ?tipo=}, see {@link
 * TransactionTypeQueryConverter}). Fixed by {@code
 * BackOfficeLeadControllerTest#theStatusFilterOnlyAcceptsTheStoredLowerCaseValues}.
 */
final class LeadStatusQueryConverter extends PropertyEditorSupport {

	@Override
	public void setAsText(String text) {
		setValue(text == null || text.isBlank() ? null : LeadStatus.fromValue(text));
	}
}
