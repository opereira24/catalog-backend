package pt.diamondcars.catalogbackend.web.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Flat pagination envelope for {@code GET /api/cars} (TASK-014 requirement 1), with exactly the
 * fields the task specifies — {@code content}, {@code page}, {@code size}, {@code totalElements},
 * {@code totalPages} — instead of Spring Data's own {@code PagedModel}, whose nested {@code page}
 * object would be a different JSON shape than what this contract asks for.
 *
 * @param <T> the type of element in {@link #content}
 * @param content the elements of the current page
 * @param page zero-based index of the current page
 * @param size the page size that was actually used (after any capping)
 * @param totalElements total number of elements across every page
 * @param totalPages total number of pages
 */
public record PagedResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

	/**
	 * Builds a {@link PagedResponse} from a Spring Data {@link Page}.
	 *
	 * @param page the page to adapt
	 * @param <T> the type of element in the page
	 * @return the corresponding flat pagination envelope
	 */
	public static <T> PagedResponse<T> from(Page<T> page) {
		return new PagedResponse<>(
				page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}
}
