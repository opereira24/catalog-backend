package pt.diamondcars.catalogbackend.web;

import java.beans.PropertyEditorSupport;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;

/**
 * Binds {@code ?tipo=} of {@code GET /api/backoffice/transactions} (TASK-005, AC A.2) to {@link
 * TransactionType} by its lower-case value only, the one {@code dcbo} sends ({@code compra}, {@code
 * venda}, {@code receita}, {@code despesa}). Registered by {@link
 * TransactionController#bindTipoByItsStoredValue}. Anything else, the Java constant name {@code
 * VENDA} included, is a 400 {@code Parametro 'tipo' com valor invalido}: {@link
 * TransactionType#fromValue(String)} throws, Spring turns it into a {@code
 * MethodArgumentTypeMismatchException}, and {@link ApiExceptionHandler} maps that. An empty or
 * blank {@code ?tipo=} binds to {@code null}, meaning "no filter", like the empty {@code ?carroId=}
 * and {@code ?from=} of the same route and like Spring's default for any enum: it is what a form's
 * "all types" option sends ({@code URLSearchParams} with the field empty). Fixed by {@code
 * TransactionControllerTest#anEmptyTipoFilterListsEveryType}.
 *
 * <p>A property editor and not a {@code Converter<String, TransactionType>} bean (what {@code
 * dcbo-backend} has, and what the AC described), measured in TASK-005: the converter bean is
 * registered and refuses {@code VENDA}, but Spring's {@code TypeConverterDelegate} catches the
 * {@code ConversionFailedException} and falls back to {@code Enum.valueOf}, so {@code ?tipo=VENDA}
 * answered 200. A custom editor is consulted before the conversion service and has no such
 * fallback. Fixed by {@code TransactionControllerTest#theTipoFilterOnlyAcceptsTheLowerCaseValues}.
 */
final class TransactionTypeQueryConverter extends PropertyEditorSupport {

	@Override
	public void setAsText(String text) {
		setValue(text == null || text.isBlank() ? null : TransactionType.fromValue(text));
	}
}
