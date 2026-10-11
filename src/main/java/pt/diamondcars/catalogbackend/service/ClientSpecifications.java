package pt.diamondcars.catalogbackend.service;

import org.springframework.data.jpa.domain.Specification;
import pt.diamondcars.catalogbackend.domain.client.Client;

/**
 * Builds the {@link Specification} {@link ClientService#list} uses for the optional free-text
 * search of {@code GET /api/backoffice/clients} (TASK-004), ported unchanged from {@code
 * dcbo-backend}, mirroring the filter-combination approach {@code BackOfficeCarSpecifications}
 * already established for {@code GET /api/backoffice/cars}.
 */
final class ClientSpecifications {

	private ClientSpecifications() {}

	/**
	 * Builds a specification matching clients whose {@code name}, {@code email}, {@code phone} or
	 * {@code nif} contains the given text, case-insensitively.
	 *
	 * @param search the free text to search for, or {@code null}/blank to not filter at all
	 * @return the combined specification; matches every client when {@code search} is {@code
	 *     null}/blank
	 */
	static Specification<Client> matching(String search) {
		if (search == null || search.isBlank()) {
			return (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
		}
		String pattern = "%" + search.trim().toLowerCase() + "%";
		return (root, query, criteriaBuilder) ->
				criteriaBuilder.or(
						criteriaBuilder.like(criteriaBuilder.lower(root.<String>get("name")), pattern),
						criteriaBuilder.like(
								criteriaBuilder.lower(criteriaBuilder.coalesce(root.<String>get("email"), "")),
								pattern),
						criteriaBuilder.like(
								criteriaBuilder.lower(criteriaBuilder.coalesce(root.<String>get("phone"), "")),
								pattern),
						criteriaBuilder.like(
								criteriaBuilder.lower(criteriaBuilder.coalesce(root.<String>get("nif"), "")),
								pattern));
	}
}
