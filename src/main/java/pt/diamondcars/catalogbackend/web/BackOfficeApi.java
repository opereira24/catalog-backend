package pt.diamondcars.catalogbackend.web;

/**
 * Path space of the whole back-office API (TASK-003, AC A.1). Every back-office controller (cars
 * here; clients, partners, transactions, finances, leads, notifications, users and {@code me} in
 * the tasks that follow) maps under {@link #PREFIX}, never under the public {@code /api/cars} or
 * {@code /api/leads}.
 *
 * <p>One prefix for everything, not only for the routes that collide with the public ones: it gives
 * one rule for security ({@code config/BackOfficeSurfaceTest} fails if a non-public {@code /api/**}
 * route lives outside it or has no {@code @PreAuthorize}) and one {@code baseURL} for {@code dcbo}.
 * Keeping the {@code dcbo-backend} paths was not an option: two {@code GET /api/cars} handlers do
 * not even start ({@code Ambiguous mapping}), and {@code GET /api/cars/{id}} is public by {@code
 * PublicEndpoints}.
 */
public final class BackOfficeApi {

	/** Prefix of every back-office route; also used to build {@code Location} headers. */
	public static final String PREFIX = "/api/backoffice";

	private BackOfficeApi() {}
}
