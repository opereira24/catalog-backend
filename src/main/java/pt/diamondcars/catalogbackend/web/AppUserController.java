package pt.diamondcars.catalogbackend.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.service.AppUserService;
import pt.diamondcars.catalogbackend.web.dto.AppUserActiveRequest;
import pt.diamondcars.catalogbackend.web.dto.AppUserRequest;
import pt.diamondcars.catalogbackend.web.dto.AppUserResponse;
import pt.diamondcars.catalogbackend.web.dto.AppUserUpdateRequest;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;

/**
 * Back-office user profiles (TASK-007): list, get, create, update, activate/deactivate and delete
 * under {@code /api/backoffice/users}, plus the caller's own profile at {@code
 * /api/backoffice/me}. Ported from {@code dcbo-backend}'s {@code AppUserController} (where they
 * were {@code /api/users} and {@code /api/me}); no public twin, so no {@code BackOffice} prefix.
 *
 * <p>The class is mapped on {@link BackOfficeApi#PREFIX} itself, not on {@code PREFIX + "/users"},
 * because {@code /me} does not live under {@code /users}.
 *
 * <p>Authorization (AC B):
 *
 * <ul>
 *   <li>{@code hasRole('ADMIN')} on the class: managing who uses the back-office is admin-only, and
 *       a method added later is admin-only by default. {@code USER} and a token without a role get
 *       403.
 *   <li>{@link #me()} overrides it with {@code isAuthenticated()} on the method: every valid token of
 *       the tenant reads its own profile, with or without a role (the first admin, a {@code USER},
 *       a signup without a role). It cannot be left without an annotation, as in {@code
 *       dcbo-backend}: it would inherit {@code ADMIN} and a {@code USER} would get 403 on their own
 *       profile. {@code config/BackOfficeSurfaceTest} rule (c) allows {@code isAuthenticated()} on
 *       this route and on no other.
 *   <li>The role that authorizes is always the token's, never {@code app_users.role}.
 * </ul>
 */
@RestController
@RequestMapping(BackOfficeApi.PREFIX)
@PreAuthorize("hasRole('ADMIN')")
public class AppUserController {

	private static final String USERS = "/users";

	private final AppUserService appUserService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param appUserService the service implementing every operation below
	 */
	public AppUserController(AppUserService appUserService) {
		this.appUserService = appUserService;
	}

	/**
	 * Lists profiles, most recently created first by default.
	 *
	 * @param pageable pagination/sorting; defaults to 50 per page by {@code createdAt} descending,
	 *     always ending in {@code id} DESC ({@code AppUserService#list})
	 * @return the requested page of profiles
	 */
	@GetMapping(USERS)
	public PagedModel<AppUserResponse> list(
			@PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(appUserService.list(pageable));
	}

	/**
	 * Fetches one profile.
	 *
	 * @param id the profile's id
	 * @return the matching profile (200), or a 404 {@code ApiError}
	 */
	@GetMapping(USERS + "/{id}")
	public AppUserResponse get(@PathVariable UUID id) {
		return appUserService.get(id);
	}

	/**
	 * Registers the local profile of an existing Auth0 account.
	 *
	 * @param request the validated payload
	 * @return 201 with {@code Location: /api/backoffice/users/<id>} and the created profile
	 * @throws pt.diamondcars.catalogbackend.web.exception.DuplicateAuthSubjectException if the {@code
	 *     authSubject} already has a profile (409)
	 */
	@PostMapping(USERS)
	public ResponseEntity<AppUserResponse> create(@Valid @RequestBody AppUserRequest request) {
		AppUserResponse created = appUserService.create(request);
		return ResponseEntity.created(URI.create(BackOfficeApi.PREFIX + USERS + "/" + created.id()))
				.body(created);
	}

	/**
	 * Replaces a profile's name and role.
	 *
	 * @param id the profile's id
	 * @param request the validated payload
	 * @return the updated profile (200), or a 404 {@code ApiError}
	 */
	@PutMapping(USERS + "/{id}")
	public AppUserResponse update(@PathVariable UUID id, @Valid @RequestBody AppUserUpdateRequest request) {
		return appUserService.update(id, request);
	}

	/**
	 * Activates or deactivates a profile.
	 *
	 * @param id the profile's id
	 * @param request the validated payload
	 * @return the updated profile (200), or a 404 {@code ApiError}
	 * @throws pt.diamondcars.catalogbackend.web.exception.SelfDeactivationException if the caller
	 *     deactivates their own profile (409)
	 */
	@PatchMapping(USERS + "/{id}/active")
	public AppUserResponse setActive(@PathVariable UUID id, @Valid @RequestBody AppUserActiveRequest request) {
		return appUserService.setActive(id, request);
	}

	/**
	 * Deletes a profile.
	 *
	 * @param id the profile's id
	 * @throws pt.diamondcars.catalogbackend.web.exception.SelfDeletionException if it is the caller's
	 *     own profile (409)
	 */
	@DeleteMapping(USERS + "/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		appUserService.delete(id);
	}

	/**
	 * Returns the caller's own profile, resolved from the {@code sub} of their token. Open to every
	 * authenticated caller, with or without a role (see the class Javadoc).
	 *
	 * @return the caller's profile (200)
	 * @throws ResourceNotFoundException if there is no local profile for the caller's {@code sub}
	 *     (404 in the usual {@code ApiError} envelope; {@code dcbo} reads it as "conta sem acesso
	 *     atribuido")
	 */
	@GetMapping("/me")
	@PreAuthorize("isAuthenticated()")
	public AppUserResponse me() {
		return appUserService
				.currentProfile()
				.orElseThrow(() -> new ResourceNotFoundException("Sem perfil local para o utilizador autenticado"));
	}
}
