package pt.diamondcars.catalogbackend.util;

/**
 * HTML-entity sanitizer for free-text fields submitted by the public site (TASK-015 requirement
 * 3), byte-for-byte equivalent to {@code dcbo}'s own {@code sanitizeString} ({@code
 * dcbo/src/utils/validation.js:329-341}): trims the value, then escapes {@code <}, {@code >},
 * {@code "}, {@code '} and {@code /} (in that exact order, deliberately never escaping {@code &})
 * so a payload like {@code <script>alert(1)</script>} is stored as {@code
 * &lt;script&gt;alert(1)&lt;/script&gt;} — never as a literal tag a browser could later render.
 *
 * <p>Kept as a plain static utility (not a Spring bean): sanitization is a pure function of its
 * input, with no dependency worth injecting.
 */
public final class TextSanitizer {

	private TextSanitizer() {}

	/**
	 * Sanitizes one free-text value.
	 *
	 * @param value the raw value to sanitize, or {@code null}
	 * @return {@code null} when {@code value} is {@code null}; otherwise the trimmed value with
	 *     {@code <}, {@code >}, {@code "}, {@code '} and {@code /} replaced by their HTML entity
	 *     equivalents
	 */
	public static String sanitize(String value) {
		if (value == null) {
			return null;
		}
		return value.trim()
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&#x27;")
				.replace("/", "&#x2F;");
	}
}
