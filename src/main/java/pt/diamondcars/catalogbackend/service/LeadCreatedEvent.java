package pt.diamondcars.catalogbackend.service;

import java.util.UUID;

/**
 * Published by {@link LeadService} immediately after a new lead is saved (TASK-015 requirement
 * 6), so {@link LeadForwarder} can attempt to forward it to {@code dcbo-backend} strictly after
 * the creating transaction commits — never from inside it, which would hold a database connection
 * open for the duration of a network call.
 *
 * @param leadId the id of the newly created lead
 */
record LeadCreatedEvent(UUID leadId) {}
