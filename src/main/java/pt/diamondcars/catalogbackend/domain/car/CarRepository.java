package pt.diamondcars.catalogbackend.domain.car;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Car}, with the derived queries TASK-013 requirement 8 lists
 * as needed by the public site's listing pages.
 */
public interface CarRepository extends JpaRepository<Car, UUID> {

	/**
	 * Lists cars not yet sold, most recently created first — the listing order the public site
	 * uses, equivalent to {@code dc/src/services/firebaseService.js:24-29}.
	 *
	 * @param pageable pagination/sort request
	 * @return a page of cars where {@code vendido = false}, ordered by {@code createdAt} descending
	 */
	Page<Car> findByVendidoFalseOrderByCreatedAtDesc(Pageable pageable);

	/**
	 * Lists cars flagged as highlighted and not yet sold — the set shown on the site's homepage
	 * carousel.
	 *
	 * @return cars where {@code destaque = true} and {@code vendido = false}
	 */
	List<Car> findByDestaqueTrueAndVendidoFalse();
}
