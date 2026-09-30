package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request payload for {@code POST /api/leads} (TASK-015 requirement 1), the public,
 * unauthenticated entry point for every sales lead the {@code dc} site submits — mirrors the
 * shape {@code dc/src/services/firebaseService.js:91,118} already sends for {@code createLead}
 * (car-specific) and {@code createGeneralContact} (general contact, no {@link #carroId}).
 *
 * @param nome full name, required
 * @param telefone contact phone number, required
 * @param email contact email, optional, but must be a valid address when present
 * @param mensagem free-text message, optional, capped at the same length {@code dcbo-backend}'s
 *     own {@code InternalLeadRequest#mensagem} accepts, so a valid submission here can never be
 *     rejected by the forwarding call (requirement 5)
 * @param carroId id of the car this lead is about, or {@code null} for a general contact lead —
 *     presence/absence decides {@link pt.diamondcars.catalogbackend.domain.lead.LeadOrigin}
 *     (requirement 2)
 * @param carroMarca denormalized brand snapshot of {@link #carroId}, or {@code null}
 * @param carroModelo denormalized model snapshot of {@link #carroId}, or {@code null}
 */
public record LeadRequest(
		@NotBlank @Size(max = 255) String nome,
		@NotBlank @Size(max = 50) String telefone,
		@Email @Size(max = 255) String email,
		@Size(max = 1000) String mensagem,
		UUID carroId,
		@Size(max = 100) String carroMarca,
		@Size(max = 100) String carroModelo) {}
