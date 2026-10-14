package pt.diamondcars.catalogbackend.web.dto.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.LocalDate;
import pt.diamondcars.catalogbackend.web.dto.TransactionRequest;

/**
 * Rejects a calendar date outside {@code [}{@link TransactionRequest#MIN_DATA}{@code , }{@link
 * TransactionRequest#MAX_DATA}{@code ]}, the range every {@code DATE} column of the back-office
 * accepts (TASK-006, AC E.2, {@code followUpDate} of a lead). {@code null} is valid.
 *
 * <p>Why: Jackson parses {@code "-5000-01-01"} and {@code "+10000-01-01"} into a {@link LocalDate}
 * without complaint. Unchecked, {@code -5000-01-01} reached the {@code DATE} column of PostgreSQL and
 * came back as a 409 {@code Pedido em conflito} (the driver error is translated to a {@code
 * DataIntegrityViolationException}), the wrong status for an input error.
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = StorableDate.Validator.class)
public @interface StorableDate {

	/**
	 * @return the message of a violation
	 */
	String message() default "deve estar entre 0001-01-01 e 9999-12-31";

	/**
	 * @return validation groups this constraint belongs to (unused by this API)
	 */
	Class<?>[] groups() default {};

	/**
	 * @return payload metadata attached to a violation (unused by this API)
	 */
	Class<? extends Payload>[] payload() default {};

	/** Validates that a {@link LocalDate} is {@code null} or within the storable range. */
	class Validator implements ConstraintValidator<StorableDate, LocalDate> {

		/**
		 * @param value the date to validate, or {@code null}
		 * @param context the validation context (unused)
		 * @return {@code true} if {@code value} is {@code null} or within the storable range
		 */
		@Override
		public boolean isValid(LocalDate value, ConstraintValidatorContext context) {
			return value == null
					|| (!value.isBefore(TransactionRequest.MIN_DATA) && !value.isAfter(TransactionRequest.MAX_DATA));
		}
	}
}
