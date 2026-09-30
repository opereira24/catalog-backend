package pt.diamondcars.catalogbackend.web.dto;

import java.util.UUID;

/**
 * Response payload for {@code POST /api/leads} (TASK-015 requirement 1): just the created lead's
 * id — the public site has no use for the rest of the lead back.
 *
 * @param id the created lead's id
 */
public record LeadResponse(UUID id) {}
