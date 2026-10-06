package pt.diamondcars.catalogbackend.domain.user;

import jakarta.persistence.Converter;
import pt.diamondcars.catalogbackend.domain.support.AbstractPersistentEnumConverter;

/**
 * Persists {@link AppUserRole} to/from the {@code app_users.role} column.
 */
@Converter(autoApply = true)
public class AppUserRoleConverter extends AbstractPersistentEnumConverter<AppUserRole> {

	/** Creates the converter bound to {@link AppUserRole}. */
	public AppUserRoleConverter() {
		super(AppUserRole.class);
	}
}
