package pt.diamondcars.catalogbackend.service;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.config.AuthenticatedUserProvider;
import pt.diamondcars.catalogbackend.domain.user.AppUser;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUserRole;
import pt.diamondcars.catalogbackend.web.dto.AppUserActiveRequest;
import pt.diamondcars.catalogbackend.web.dto.AppUserRequest;
import pt.diamondcars.catalogbackend.web.dto.AppUserResponse;
import pt.diamondcars.catalogbackend.web.dto.AppUserUpdateRequest;
import pt.diamondcars.catalogbackend.web.exception.DuplicateAuthSubjectException;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;
import pt.diamondcars.catalogbackend.web.exception.SelfDeactivationException;
import pt.diamondcars.catalogbackend.web.exception.SelfDeletionException;

/**
 * Business logic of {@code /api/backoffice/users} and {@code GET /api/backoffice/me} (TASK-007),
 * ported from {@code dcbo-backend}'s {@code AppUserService}.
 *
 * <p>Every public method is {@code @Transactional} and maps to {@link AppUserResponse} before
 * returning ({@code spring.jpa.open-in-view: false}). Two changes from the port, each proven by a
 * test: the writes on an existing profile ({@link #update}, {@link #setActive}, {@link #delete})
 * lock its row first (AC C.6, {@code AppUserConcurrencyTest}), and {@link #create} flushes inside
 * the method so the 201 carries the timestamps (AC C.2).
 *
 * <p>{@code app_users.role} is informative: nothing here authorizes anything with it. The role that
 * authorizes is the one in the Auth0 token (AC B.3, {@code web/CLAUDE.md}).
 */
@Service
public class AppUserService {

	private final AppUserRepository appUserRepository;
	private final AuthenticatedUserProvider authenticatedUserProvider;

	/**
	 * Creates the service with its collaborators.
	 *
	 * @param appUserRepository persistence for {@link AppUser}
	 * @param authenticatedUserProvider resolves the Auth0 {@code sub} of the caller, for {@link
	 *     #currentProfile()} and to refuse self-deactivation and self-deletion
	 */
	public AppUserService(
			AppUserRepository appUserRepository, AuthenticatedUserProvider authenticatedUserProvider) {
		this.appUserRepository = appUserRepository;
		this.authenticatedUserProvider = authenticatedUserProvider;
	}

	/**
	 * Lists profiles, most recently created first by default.
	 *
	 * @param pageable pagination/sorting, defaulted by the controller to 50 per page by {@code
	 *     createdAt} descending; {@code id} DESC is appended when the sort lacks it ({@link
	 *     StableSort#withIdTiebreak}). No allow-list of {@code sort}: {@link AppUser} has no
	 *     association a sort could join through, and an unknown property is a 400 from the shared
	 *     {@code PropertyReferenceException} handler.
	 * @return the requested page
	 */
	@Transactional(readOnly = true)
	public Page<AppUserResponse> list(Pageable pageable) {
		return appUserRepository.findAll(StableSort.withIdTiebreak(pageable)).map(AppUserResponse::from);
	}

	/**
	 * Fetches one profile.
	 *
	 * @param id the profile's id
	 * @return the matching profile
	 * @throws ResourceNotFoundException if no profile has this id (404)
	 */
	@Transactional(readOnly = true)
	public AppUserResponse get(UUID id) {
		return AppUserResponse.from(
				appUserRepository.findById(id).orElseThrow(() -> notFound(id)));
	}

	/**
	 * Registers the local profile of an Auth0 account that already exists in the tenant; always
	 * active.
	 *
	 * <p>{@code saveAndFlush}, not {@code save}: the {@code INSERT} runs inside this method, so the
	 * response carries the {@code createdAt}/{@code updatedAt} that were written. Two concurrent
	 * creations with the same {@code authSubject} both pass the lookup; the second fails on the
	 * unique index and leaves as {@code DataIntegrityViolationException} (409 by the shared handler).
	 *
	 * @param request the validated payload
	 * @return the created profile
	 * @throws DuplicateAuthSubjectException if a profile already exists for the {@code authSubject}
	 *     (409)
	 */
	@Transactional
	public AppUserResponse create(AppUserRequest request) {
		if (appUserRepository.findByAuthSubject(request.authSubject()).isPresent()) {
			throw new DuplicateAuthSubjectException(
					"Ja existe um perfil registado para este authSubject: " + request.authSubject());
		}
		AppUser appUser = AppUser.builder()
				.authSubject(request.authSubject())
				.email(request.email())
				.name(request.name())
				.role(AppUserRole.fromValue(request.role()))
				.build();
		return AppUserResponse.from(appUserRepository.saveAndFlush(appUser));
	}

	/**
	 * Replaces a profile's name and role; never touches {@code authSubject}, {@code email} or {@code
	 * active}.
	 *
	 * @param id the profile's id
	 * @param request the validated payload
	 * @return the updated profile
	 * @throws ResourceNotFoundException if no profile has this id (404)
	 */
	@Transactional
	public AppUserResponse update(UUID id, AppUserUpdateRequest request) {
		AppUser appUser = lockOrThrow(id);
		appUser.setName(request.name());
		appUser.setRole(AppUserRole.fromValue(request.role()));
		return AppUserResponse.from(appUser);
	}

	/**
	 * Activates or deactivates a profile. Refuses to deactivate the caller's own profile (see {@link
	 * SelfDeactivationException}). Reactivating oneself never gets here: an inactive caller is
	 * turned away by {@code ActiveUserInterceptor} first, and an active one activating themselves is
	 * a no-op.
	 *
	 * @param id the profile's id
	 * @param request the validated payload
	 * @return the updated profile
	 * @throws ResourceNotFoundException if no profile has this id (404)
	 * @throws SelfDeactivationException if {@code active} is {@code false} and the profile is the
	 *     caller's (409)
	 */
	@Transactional
	public AppUserResponse setActive(UUID id, AppUserActiveRequest request) {
		AppUser appUser = lockOrThrow(id);
		if (!request.active() && isCaller(appUser)) {
			throw new SelfDeactivationException("Nao e possivel desativar o proprio perfil: " + id);
		}
		appUser.setActive(request.active());
		return AppUserResponse.from(appUser);
	}

	/**
	 * Deletes a profile, refusing the caller's own (see {@link SelfDeletionException}). An admin
	 * without a local profile never hits that rule: there is no profile of theirs to compare.
	 *
	 * @param id the profile's id
	 * @throws ResourceNotFoundException if no profile has this id (404)
	 * @throws SelfDeletionException if the profile is the caller's (409)
	 */
	@Transactional
	public void delete(UUID id) {
		AppUser appUser = lockOrThrow(id);
		if (isCaller(appUser)) {
			throw new SelfDeletionException("Nao e possivel eliminar o proprio perfil: " + id);
		}
		appUserRepository.delete(appUser);
	}

	/**
	 * Resolves the profile of the authenticated caller, for {@code GET /api/backoffice/me}.
	 *
	 * @return the caller's profile, or empty when there is no authenticated subject or no local
	 *     profile for it yet (the controller answers 404 for both)
	 */
	@Transactional(readOnly = true)
	public Optional<AppUserResponse> currentProfile() {
		return authenticatedUserProvider
				.getCurrentUserSubject()
				.flatMap(appUserRepository::findByAuthSubject)
				.map(AppUserResponse::from);
	}

	private boolean isCaller(AppUser appUser) {
		return authenticatedUserProvider
				.getCurrentUserSubject()
				.filter(subject -> subject.equals(appUser.getAuthSubject()))
				.isPresent();
	}

	/**
	 * Loads the profile with its row locked until the end of the transaction (AC C.6). Always the
	 * first load of the profile in the calling method, before any check or write.
	 */
	private AppUser lockOrThrow(UUID id) {
		return appUserRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
	}

	private static ResourceNotFoundException notFound(UUID id) {
		return new ResourceNotFoundException("Utilizador nao encontrado: " + id);
	}
}
