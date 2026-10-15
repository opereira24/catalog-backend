package pt.diamondcars.catalogbackend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Assertions that a write endpoint answers with the {@code createdAt}/{@code updatedAt} that were
 * actually written to its row (TASK-020).
 *
 * <p>{@code @CreationTimestamp}/{@code @UpdateTimestamp} are only filled when Hibernate runs the
 * {@code INSERT}/{@code UPDATE}, i.e. at flush. A service that maps its response before the flush
 * ({@code save} of an entity with a generated id, or dirty checking alone) answers {@code null}
 * timestamps on a creation and the previous {@code updatedAt} on an update. The assertions compare
 * the response with the row, not only with "not null" or "after": equality proves the response is
 * the written value (measured equal to the microsecond: {@code TIMESTAMPTZ}, and Hibernate truncates
 * the clock to the column's precision).
 */
public final class WrittenTimestamps {

	/**
	 * The {@code updated_at} {@link #makeStale} writes: far enough in the past that any value
	 * written by the request under test is after it.
	 */
	public static final Instant STALE = Instant.parse("2020-01-01T00:00:00Z");

	private WrittenTimestamps() {}

	/**
	 * Overwrites a row's {@code updated_at} with {@link #STALE}, so that an update answering the
	 * value it read (instead of the one it wrote) shows up as {@link #STALE}.
	 *
	 * @param jdbcTemplate committed access to the test database
	 * @param table the table of the row
	 * @param id the row's id
	 */
	public static void makeStale(JdbcTemplate jdbcTemplate, String table, UUID id) {
		int updated = jdbcTemplate.update(
				"UPDATE " + table + " SET updated_at = ? WHERE id = ?", Timestamp.from(STALE), id);
		assertThat(updated).as("rows of %s with id %s", table, id).isEqualTo(1);
	}

	/**
	 * Asserts that the {@code createdAt} and {@code updatedAt} of a creation response are present and
	 * equal to the {@code created_at}/{@code updated_at} of the row it created.
	 *
	 * @param body the response body, with an {@code id}
	 * @param jdbcTemplate committed access to the test database
	 * @param table the table the response's row lives in
	 */
	public static void assertCreationTimestampsAreTheWrittenOnes(
			JsonNode body, JdbcTemplate jdbcTemplate, String table) {
		UUID id = UUID.fromString(body.get("id").asString());

		assertThat(inResponse(body, "createdAt"))
				.as("createdAt")
				.isEqualTo(inDatabase(jdbcTemplate, table, "created_at", id));
		assertThat(inResponse(body, "updatedAt"))
				.as("updatedAt")
				.isEqualTo(inDatabase(jdbcTemplate, table, "updated_at", id));
	}

	/**
	 * Asserts that the {@code updatedAt} of an update response is newer than {@link #STALE} and equal
	 * to the {@code updated_at} of its row.
	 *
	 * @param body the response body, with an {@code id}
	 * @param jdbcTemplate committed access to the test database
	 * @param table the table the response's row lives in
	 */
	public static void assertUpdatedAtIsTheNewlyWrittenOne(JsonNode body, JdbcTemplate jdbcTemplate, String table) {
		UUID id = UUID.fromString(body.get("id").asString());
		Instant answered = inResponse(body, "updatedAt");

		assertThat(answered).as("updatedAt").isAfter(STALE);
		assertThat(answered).as("updatedAt").isEqualTo(inDatabase(jdbcTemplate, table, "updated_at", id));
	}

	private static Instant inResponse(JsonNode body, String field) {
		JsonNode value = body.get(field);
		assertThat(value).as(field).isNotNull();
		assertThat(value.isNull()).as("%s is null", field).isFalse();
		return OffsetDateTime.parse(value.asString()).toInstant();
	}

	private static Instant inDatabase(JdbcTemplate jdbcTemplate, String table, String column, UUID id) {
		return jdbcTemplate
				.queryForObject("SELECT " + column + " FROM " + table + " WHERE id = ?", Timestamp.class, id)
				.toInstant();
	}
}
