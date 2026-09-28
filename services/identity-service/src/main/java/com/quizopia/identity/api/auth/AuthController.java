package com.quizopia.identity.api.auth;

import com.quizopia.identity.application.currentuser.CurrentUserService;
import com.quizopia.identity.application.emailverification.EmailVerificationStatus;
import com.quizopia.identity.application.emailverification.UsernameEmailVerificationService;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationInput;
import com.quizopia.identity.application.login.InitialLoginService;
import com.quizopia.identity.application.login.InitialLoginStatus;
import com.quizopia.identity.application.logout.CurrentSessionLogoutService;
import com.quizopia.identity.application.refresh.RefreshAccessService;
import com.quizopia.identity.application.refresh.RefreshAccessStatus;
import com.quizopia.identity.application.registration.LocalRegistrationInput;
import com.quizopia.identity.application.registration.LocalRegistrationService;
import com.quizopia.identity.application.registration.LocalRegistrationStatus;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import com.quizopia.identity.security.password.RawLocalPassword;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AuthController.AUTH_ROOT)
@Tag(name = "Identity authentication")
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@Profile("!test")
public class AuthController {
    public static final String AUTH_ROOT = "/api/auth";
    public static final String REGISTER_PATH = AUTH_ROOT + "/register";
    public static final String LOGIN_PATH = AUTH_ROOT + "/login";
    public static final String REFRESH_PATH = AUTH_ROOT + "/refresh";
    public static final String LOGOUT_PATH = AUTH_ROOT + "/logout";
    public static final String ME_PATH = AUTH_ROOT + "/me";
    public static final String REQUEST_PATH = AUTH_ROOT + "/email-verification/request";
    public static final String CONFIRM_PATH = AUTH_ROOT + "/email-verification/confirm";

    private static final String AUTH_USERNAME_UNAVAILABLE = "AUTH_USERNAME_UNAVAILABLE";
    private static final String AUTH_REGISTRATION_FAILED = "AUTH_REGISTRATION_FAILED";
    private static final String AUTH_VERIFICATION_FAILED = "AUTH_VERIFICATION_FAILED";
    private static final String AUTH_INVALID_CREDENTIALS = "AUTH_INVALID_CREDENTIALS";
    private static final String AUTH_REFRESH_FAILED = "AUTH_REFRESH_FAILED";

    private final LocalRegistrationService registrationService;
    private final UsernameEmailVerificationService verificationService;
    private final InitialLoginService loginService;
    private final RefreshAccessService refreshAccessService;
    private final CurrentSessionLogoutService logoutService;
    private final CurrentUserService currentUserService;
    private final RefreshCookieCredentialResolver refreshCredentialResolver;
    private final RefreshCookieFactory refreshCookieFactory;
    private final Clock clock;

    public AuthController(
            LocalRegistrationService registrationService,
            UsernameEmailVerificationService verificationService,
            InitialLoginService loginService,
            RefreshAccessService refreshAccessService,
            CurrentSessionLogoutService logoutService,
            CurrentUserService currentUserService,
            RefreshCookieCredentialResolver refreshCredentialResolver,
            RefreshCookieFactory refreshCookieFactory,
            @Qualifier("identityClock") Clock clock) {
        this.registrationService = registrationService;
        this.verificationService = verificationService;
        this.loginService = loginService;
        this.refreshAccessService = refreshAccessService;
        this.logoutService = logoutService;
        this.currentUserService = currentUserService;
        this.refreshCredentialResolver = refreshCredentialResolver;
        this.refreshCookieFactory = refreshCookieFactory;
        this.clock = clock;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a pending local account", description = "Anonymous public operation.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Registration accepted; email verification is required",
                content =
                        @Content(
                                schema = @Schema(implementation = AuthStatusResponse.class),
                                examples = @ExampleObject(value = "{\"status\":\"VERIFICATION_REQUIRED\"}"))),
        @ApiResponse(
                responseCode = "400",
                description = "Request validation failed",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(
                responseCode = "409",
                description = "AUTH_USERNAME_UNAVAILABLE or AUTH_REGISTRATION_FAILED",
                content =
                        @Content(
                                schema = @Schema(implementation = ApiErrorResponse.class),
                                examples = {
                                    @ExampleObject(
                                            name = "usernameUnavailable",
                                            value =
                                                    "{\"code\":\"AUTH_USERNAME_UNAVAILABLE\",\"message\":\"Username is unavailable.\",\"status\":409,\"path\":\"/api/auth/register\"}"),
                                    @ExampleObject(
                                            name = "registrationFailed",
                                            value =
                                                    "{\"code\":\"AUTH_REGISTRATION_FAILED\",\"message\":\"Registration failed.\",\"status\":409,\"path\":\"/api/auth/register\"}")
                                }))
    })
    public ResponseEntity<AuthStatusResponse> register(@Valid @RequestBody RegistrationRequest request) {
        var result = registrationService.register(new LocalRegistrationInput(
                request.username(), request.email(), RawLocalPassword.from(request.password())));
        if (result.status() == LocalRegistrationStatus.USERNAME_CONFLICT) {
            throw new AuthApiException(AUTH_USERNAME_UNAVAILABLE, "Username is unavailable.", HttpStatus.CONFLICT);
        }
        if (result.status() != LocalRegistrationStatus.CREATED_PENDING_VERIFICATION) {
            throw new AuthApiException(AUTH_REGISTRATION_FAILED, "Registration failed.", HttpStatus.CONFLICT);
        }
        return ResponseEntity.accepted().body(new AuthStatusResponse(AuthStatusResponse.Status.VERIFICATION_REQUIRED));
    }

    @PostMapping("/email-verification/request")
    @Operation(
            summary = "Request an email verification OTP",
            description = "Anonymous public operation. The response does not reveal whether an OTP was issued.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "202",
                description = "Verification request accepted without an account-state distinction",
                content =
                        @Content(
                                schema = @Schema(implementation = AuthStatusResponse.class),
                                examples = @ExampleObject(value = "{\"status\":\"VERIFICATION_REQUEST_ACCEPTED\"}"))),
        @ApiResponse(
                responseCode = "400",
                description = "Request validation failed",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<AuthStatusResponse> requestVerification(
            @Valid @RequestBody EmailVerificationRequest request) {
        verificationService.request(request.username());
        return ResponseEntity.accepted()
                .body(new AuthStatusResponse(AuthStatusResponse.Status.VERIFICATION_REQUEST_ACCEPTED));
    }

    @PostMapping("/email-verification/confirm")
    @Operation(summary = "Confirm an email verification OTP", description = "Anonymous public operation.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Email verified and account activated"),
        @ApiResponse(
                responseCode = "400",
                description = "AUTH_VERIFICATION_FAILED",
                content =
                        @Content(
                                schema = @Schema(implementation = ApiErrorResponse.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"code\":\"AUTH_VERIFICATION_FAILED\",\"message\":\"Email verification failed.\",\"status\":400,\"path\":\"/api/auth/email-verification/confirm\"}")))
    })
    public ResponseEntity<Void> confirmVerification(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        EmailVerificationStatus status =
                verificationService.confirm(request.username(), RawEmailVerificationOtp.from(request.otp()));
        if (status != EmailVerificationStatus.VERIFIED) {
            throw new AuthApiException(AUTH_VERIFICATION_FAILED, "Email verification failed.", HttpStatus.BAD_REQUEST);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    @Operation(
            summary = "Authenticate a local user",
            description =
                    "Authenticates an exact username or exact verified email and establishes the refresh session in an HttpOnly cookie.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Authentication succeeded",
                content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Request validation failed",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "AUTH_INVALID_CREDENTIALS",
                content =
                        @Content(
                                schema = @Schema(implementation = ApiErrorResponse.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"code\":\"AUTH_INVALID_CREDENTIALS\",\"message\":\"Invalid credentials.\",\"status\":401,\"path\":\"/api/auth/login\"}")))
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        var result = loginService.login(
                new LocalAuthenticationInput(request.identifier(), RawLocalPassword.from(request.password())));
        if (result.status() != InitialLoginStatus.AUTHENTICATED) {
            throw new AuthApiException(AUTH_INVALID_CREDENTIALS, "Invalid credentials.", HttpStatus.UNAUTHORIZED);
        }
        var session = result.session().orElseThrow();
        var cookie = refreshCookieFactory.create(session);
        var response = LoginResponse.from(session.accessToken(), clock.instant());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate a browser refresh session",
            description =
                    "Uses only the quizopia_refresh HttpOnly cookie and requires an explicitly trusted Origin. A successful response rotates the cookie.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Refresh succeeded and the HttpOnly refresh cookie was rotated",
                content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(
                responseCode = "401",
                description = "AUTH_REFRESH_FAILED",
                content =
                        @Content(
                                schema = @Schema(implementation = ApiErrorResponse.class),
                                examples =
                                        @ExampleObject(
                                                value =
                                                        "{\"code\":\"AUTH_REFRESH_FAILED\",\"message\":\"Refresh failed.\",\"status\":401,\"path\":\"/api/auth/refresh\"}"))),
        @ApiResponse(
                responseCode = "403",
                description = "ACCESS_DENIED when Origin is missing or untrusted",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> refresh(HttpServletRequest request) {
        var credential = refreshCredentialResolver.resolve(request).orElse(null);
        if (credential == null) {
            throw refreshFailed();
        }
        var result = refreshAccessService.refresh(credential);
        if (result.status() != RefreshAccessStatus.REFRESHED) {
            throw refreshFailed();
        }
        var session = result.session().orElseThrow();
        var cookie = refreshCookieFactory.create(session);
        var response = LoginResponse.from(session.accessToken(), clock.instant());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(response);
    }

    @PostMapping("/logout")
    @Operation(
            summary = "Log out the current browser refresh session",
            description =
                    "Idempotently revokes the current refresh family when recognized, clears the refresh cookie, and requires an explicitly trusted Origin.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Logout completed and the refresh cookie was cleared"),
        @ApiResponse(
                responseCode = "403",
                description = "ACCESS_DENIED when Origin is missing or untrusted",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        refreshCredentialResolver.resolve(request).ifPresent(logoutService::logout);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookieFactory.clear().toString())
                .build();
    }

    @GetMapping("/me")
    @Operation(
            summary = "Get the authoritative current user profile",
            description =
                    "Requires a Quizopia USER bearer token and reloads current account eligibility, revocation state, and roles from Identity.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Current authoritative user profile",
                content = @Content(schema = @Schema(implementation = CurrentUserResponse.class))),
        @ApiResponse(responseCode = "401", description = "Missing, invalid, or expired bearer token"),
        @ApiResponse(
                responseCode = "403",
                description = "Authenticated principal is not an eligible current user",
                content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public CurrentUserResponse me(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null || jwt.getIssuedAt() == null) {
            throw accessDenied();
        }
        return currentUserService
                .findEligible(UUID.fromString(jwt.getSubject()), jwt.getIssuedAt())
                .map(CurrentUserResponse::from)
                .orElseThrow(AuthController::accessDenied);
    }

    private static AuthApiException refreshFailed() {
        return new AuthApiException(AUTH_REFRESH_FAILED, "Refresh failed.", HttpStatus.UNAUTHORIZED);
    }

    private static AuthApiException accessDenied() {
        return new AuthApiException("ACCESS_DENIED", "Access is denied", HttpStatus.FORBIDDEN);
    }
}
