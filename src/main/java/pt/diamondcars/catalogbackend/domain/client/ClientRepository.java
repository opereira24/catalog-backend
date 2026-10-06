package pt.diamondcars.catalogbackend.domain.client;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data repository for {@link Client}. Extends {@link JpaSpecificationExecutor} so the
 * back-office client listing can combine its optional free-text search over name/email/phone/NIF
 * without one derived-query method per field.
 */
public interface ClientRepository
		extends JpaRepository<Client, UUID>, JpaSpecificationExecutor<Client> {
}
