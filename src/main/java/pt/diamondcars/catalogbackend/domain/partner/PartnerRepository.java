package pt.diamondcars.catalogbackend.domain.partner;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Partner}. No derived queries beyond CRUD: the partner-related
 * lookups the back-office needs live on {@code CarRepository} ({@code existsByPartnerId}, {@code
 * findByPartnerIdOrderByCreatedAtDesc}).
 */
public interface PartnerRepository extends JpaRepository<Partner, UUID> {
}
