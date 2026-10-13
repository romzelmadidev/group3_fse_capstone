package com.fse.banking.account.service;

import com.fse.banking.account.dto.DeviceInfoDto;
import com.fse.banking.account.dto.LoginRequest;
import com.fse.banking.account.dto.LoginResponse;
import com.fse.banking.account.dto.RegisterRequest;
import com.fse.banking.account.dto.RegisterResponse;
import com.fse.banking.account.dto.VerifyLoginOtpRequest;
import com.fse.banking.account.exception.TooManyRequestsException;
import com.fse.banking.account.model.AdminUserEntity;
import com.fse.banking.account.model.UserEntity;
import com.fse.banking.account.repository.AdminUserRepository;
import com.fse.banking.account.repository.UserRepository;
import com.fse.banking.account.repository.AccountRepository;
import com.fse.banking.account.repository.BalanceMasterRepository;
import com.fse.banking.account.security.JwtProvider;
import com.fse.banking.account.security.RedisSessionStore;
import com.fse.banking.account.security.model.RefreshTokenMetadata;
import com.fse.banking.account.security.model.SessionMetadata;
import com.fse.banking.common.enums.UserRole;
import com.fse.banking.common.enums.UserStatus;
import com.fse.banking.common.exception.ConflictException;
import com.fse.banking.common.exception.DeviceCompromisedException;
import com.fse.banking.common.exception.ForbiddenException;
import com.fse.banking.common.exception.LockedUserException;
import com.fse.banking.common.exception.UnauthorizedException;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final AdminUserRepository adminUserRepository;
    private final AccountRepository accountRepository;
    private final BalanceMasterRepository balanceMasterRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RedisSessionStore redisSessionStore;

    @Value("${app.services.notification-service.url:${NOTIFICATION_SERVICE_URL:http://notification-service:8083}}")
    private String notificationServiceUrl;

    private static final int MAX_FAILED_ATTEMPTS = 5;
    /** Both the failure-counting window and the lock length. */
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(7);
    private static final Duration OTP_TTL = Duration.ofMinutes(5);
    private static final Duration OTP_RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_OTP_ATTEMPTS = 5;
    private static final SecureRandom OTP_RANDOM = new SecureRandom();

    @Data
    @Builder
    public static class LoginResult {
        private LoginResponse response;
        private String refreshTokenId;
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        log.info("Processing registration for email: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ConflictException("Email " + request.getEmail() + " is already registered.");
        }
        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new ConflictException("Phone number " + request.getPhoneNumber() + " is already registered.");
        }
        String formattedGovId = request.getGovernmentIdType() + "-" + request.getGovernmentIdNumber();
        if (userRepository.existsByGovernmentId(formattedGovId) || userRepository.existsByGovernmentId(request.getGovernmentIdNumber())) {
            throw new ConflictException("Government ID " + request.getGovernmentIdNumber() + " is already registered.");
        }

        String userId = "USR-" + (100000 + ThreadLocalRandom.current().nextInt(900000));

        UserEntity user = UserEntity.builder()
                .userId(userId)
                .firstName(request.getFirstName())
                .middleName(request.getMiddleName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .phoneNumber(request.getPhoneNumber())
                .dob(request.getDateOfBirth())
                .governmentId(request.getGovernmentIdType() + "-" + request.getGovernmentIdNumber())
                .role(UserRole.CUSTOMER)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .maxConcurrentSessions(3)
                .failedLoginAttempts(0)
                .status(UserStatus.ACTIVE)
                .build();

        UserEntity saved = userRepository.save(user);
        log.info("User registered successfully: userId={}", saved.getUserId());

        // lastLoginAt stays null: the account cannot get tokens until this email code is verified.
        sendLoginOtp(saved);

        return RegisterResponse.builder()
                .userId(saved.getUserId())
                .email(saved.getEmail())
                .maskedEmail(maskEmail(saved.getEmail()))
                .kycStatus("PENDING")
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional
    public LoginResult login(LoginRequest request, String clientIp, String userAgent) {
        log.info("Processing login attempt for: {}", request.getEmail());

        // Zero-tolerance device integrity gate
        if (Boolean.TRUE.equals(request.getIsDeviceCompromised())) {
            String reasons = request.getCompromiseReasons() != null && !request.getCompromiseReasons().isBlank()
                    ? request.getCompromiseReasons()
                    : "Root, Unlocked Bootloader, or Developer Options detected";
            log.error("SECURITY ALERT: Blocked login attempt from compromised device! email={}, deviceId={}, reasons={}",
                    request.getEmail(), request.getDeviceId(), reasons);
            throw new DeviceCompromisedException("Access Denied: Your device failed security integrity checks (" + reasons + ").");
        }

        boolean isConsoleRequest = (request.getDeviceName() != null && request.getDeviceName().toLowerCase().contains("console"))
                || "Aura Console".equalsIgnoreCase(request.getDeviceName());

        if (isConsoleRequest && adminUserRepository != null) {
            Optional<AdminUserEntity> adminOpt = adminUserRepository.findByEmail(request.getEmail());
            if (adminOpt.isPresent()) {
                return loginAdmin(adminOpt.get(), request, clientIp, userAgent);
            }
        }

        Optional<UserEntity> userOpt = userRepository.findByEmail(request.getEmail());
        if (userOpt.isPresent()) {
            return loginCustomer(userOpt.get(), request, clientIp, userAgent);
        }

        if (adminUserRepository != null) {
            Optional<AdminUserEntity> adminOpt = adminUserRepository.findByEmail(request.getEmail());
            if (adminOpt.isPresent()) {
                return loginAdmin(adminOpt.get(), request, clientIp, userAgent);
            }
        }

        throw new UnauthorizedException("Invalid email or password.");
    }

    private LoginResult loginCustomer(UserEntity user, LoginRequest request, String clientIp, String userAgent) {
        if (user.getStatus() == UserStatus.LOCKED) {
            throw new LockedUserException("User account is locked due to security policy. Contact customer support.");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new ForbiddenException("User account is suspended. Contact customer support.");
        }

        long lockedSeconds = redisSessionStore.getLoginLockSeconds(user.getUserId());
        if (lockedSeconds > 0) {
            long minutes = (lockedSeconds + 59) / 60;
            throw new LockedUserException("Too many failed sign-in attempts. Try again in " + minutes
                    + (minutes == 1 ? " minute." : " minutes."));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            long attempts = redisSessionStore.recordFailedLogin(user.getUserId(), LOCKOUT_DURATION);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                redisSessionStore.lockLogin(user.getUserId(), LOCKOUT_DURATION);
                log.warn("Account {} temporarily locked for {} after {} consecutive failed login attempts",
                        user.getUserId(), LOCKOUT_DURATION, attempts);
            }
            throw new UnauthorizedException("Invalid email or password.");
        }

        redisSessionStore.clearFailedLogins(user.getUserId());
        if (user.getFailedLoginAttempts() > 0) {
            user.setFailedLoginAttempts(0);
            userRepository.save(user);
        }

        String resolvedDeviceId = resolveDeviceId(request.getDeviceId(), clientIp, userAgent);
        String resolvedDeviceName = resolveDeviceName(request.getDeviceName(), userAgent);
        String resolvedDeviceType = resolveDeviceType(request.getDeviceType(), resolvedDeviceId, resolvedDeviceName, userAgent);
        boolean isWeb = "WEB".equalsIgnoreCase(resolvedDeviceType);

        String primaryDeviceId = redisSessionStore.getPrimaryDeviceId(user.getUserId());
        Optional<DeviceInfoDto> existingDevice = redisSessionStore.getDevice(user.getUserId(), resolvedDeviceId);
        boolean isKnownApprovedDevice = existingDevice.isPresent() && existingDevice.get().isApproved();

        boolean isFirstTimeLogin = user.getLastLoginAt() == null;
        // OTP binds only the first (primary) mobile; later devices skip OTP and need primary-device approval.
        boolean noPrimaryYet = primaryDeviceId == null || primaryDeviceId.isBlank();
        boolean requiresDeviceBindingMfa = !isWeb && !isKnownApprovedDevice && noPrimaryYet;

        if (isFirstTimeLogin || requiresDeviceBindingMfa) {
            String otp = redisSessionStore.getLoginOtp(user.getUserId());
            if (otp == null || otp.isBlank()) {
                long wait = sendLoginOtp(user);
                if (wait > 0) {
                    throw new TooManyRequestsException("Please wait " + wait + " seconds before requesting a new code.");
                }
            }

            String fullName = (user.getFirstName() + " " + (user.getLastName() != null ? user.getLastName() : "")).trim();
            LoginResponse mfaChallenge = LoginResponse.builder()
                    .status("MFA_REQUIRED")
                    .userId(user.getUserId())
                    .maskedEmail(maskEmail(user.getEmail()))
                    .role(user.getRole().name())
                    .fullName(fullName)
                    .email(user.getEmail())
                    .phoneNumber(user.getPhoneNumber())
                    .deviceType(resolvedDeviceType)
                    .build();

            return LoginResult.builder()
                    .response(mfaChallenge)
                    .refreshTokenId(null)
                    .build();
        }

        return createAuthenticatedSession(user, clientIp, userAgent, request.getDeviceId(), request.getDeviceName(), request.getDeviceType());
    }

    private LoginResult loginAdmin(AdminUserEntity admin, LoginRequest request, String clientIp, String userAgent) {
        if (admin.getStatus() == UserStatus.LOCKED) {
            throw new LockedUserException("User account is locked due to security policy. Contact customer support.");
        }
        if (admin.getStatus() == UserStatus.SUSPENDED) {
            throw new ForbiddenException("User account is suspended. Contact customer support.");
        }

        long lockedSeconds = redisSessionStore.getLoginLockSeconds(admin.getAdminId());
        if (lockedSeconds > 0) {
            long minutes = (lockedSeconds + 59) / 60;
            throw new LockedUserException("Too many failed sign-in attempts. Try again in " + minutes
                    + (minutes == 1 ? " minute." : " minutes."));
        }

        if (!passwordEncoder.matches(request.getPassword(), admin.getPasswordHash())) {
            long attempts = redisSessionStore.recordFailedLogin(admin.getAdminId(), LOCKOUT_DURATION);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                redisSessionStore.lockLogin(admin.getAdminId(), LOCKOUT_DURATION);
                log.warn("Admin account {} temporarily locked for {} after {} consecutive failed login attempts",
                        admin.getAdminId(), LOCKOUT_DURATION, attempts);
            }
            throw new UnauthorizedException("Invalid email or password.");
        }

        redisSessionStore.clearFailedLogins(admin.getAdminId());
        if (admin.getFailedLoginAttempts() > 0) {
            admin.setFailedLoginAttempts(0);
            adminUserRepository.save(admin);
        }

        String resolvedDeviceId = resolveDeviceId(request.getDeviceId(), clientIp, userAgent);
        String resolvedDeviceName = resolveDeviceName(request.getDeviceName(), userAgent);
        String resolvedDeviceType = resolveDeviceType(request.getDeviceType(), resolvedDeviceId, resolvedDeviceName, userAgent);
        boolean isWeb = "WEB".equalsIgnoreCase(resolvedDeviceType);

        String primaryDeviceId = redisSessionStore.getPrimaryDeviceId(admin.getAdminId());
        Optional<DeviceInfoDto> existingDevice = redisSessionStore.getDevice(admin.getAdminId(), resolvedDeviceId);
        boolean isKnownApprovedDevice = existingDevice.isPresent() && existingDevice.get().isApproved();

        boolean isFirstTimeLogin = admin.getLastLoginAt() == null;
        boolean noPrimaryYet = primaryDeviceId == null || primaryDeviceId.isBlank();
        boolean requiresDeviceBindingMfa = !isWeb && !isKnownApprovedDevice && noPrimaryYet;

        // Web admin portal logins for staff/admin roles bypass OTP/MFA challenge
        if (!isWeb && (isFirstTimeLogin || requiresDeviceBindingMfa)) {
            String otp = redisSessionStore.getLoginOtp(admin.getAdminId());
            if (otp == null || otp.isBlank()) {
                long wait = sendAdminLoginOtp(admin);
                if (wait > 0) {
                    throw new TooManyRequestsException("Please wait " + wait + " seconds before requesting a new code.");
                }
            }

            String fullName = (admin.getFirstName() + " " + (admin.getLastName() != null ? admin.getLastName() : "")).trim();
            LoginResponse mfaChallenge = LoginResponse.builder()
                    .status("MFA_REQUIRED")
                    .userId(admin.getAdminId())
                    .maskedEmail(maskEmail(admin.getEmail()))
                    .role(admin.getRole().name())
                    .fullName(fullName)
                    .email(admin.getEmail())
                    .phoneNumber(admin.getPhoneNumber())
                    .deviceType(resolvedDeviceType)
                    .build();

            return LoginResult.builder()
                    .response(mfaChallenge)
                    .refreshTokenId(null)
                    .build();
        }

        return createAuthenticatedAdminSession(admin, clientIp, userAgent, request.getDeviceId(), request.getDeviceName(), request.getDeviceType());
    }


    @Transactional
    public LoginResult verifyLoginOtp(VerifyLoginOtpRequest request, String clientIp, String userAgent) {
        log.info("Processing login OTP verification for userId: {}", request.getUserId());

        if (adminUserRepository != null) {
            Optional<AdminUserEntity> adminOpt = adminUserRepository.findById(request.getUserId());
            if (adminOpt.isPresent()) {
                AdminUserEntity admin = adminOpt.get();
                if (admin.getStatus() == UserStatus.LOCKED || admin.getStatus() == UserStatus.SUSPENDED) {
                    throw new UnauthorizedException("Account is inactive.");
                }
                validateOtpCode(admin.getAdminId(), request.getOtp());
                admin.setLastLoginAt(Instant.now());
                adminUserRepository.save(admin);
                return createAuthenticatedAdminSession(admin, clientIp, userAgent, request.getDeviceId(), request.getDeviceName(), request.getDeviceType());
            }
        }

        UserEntity user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new UnauthorizedException("User account not found."));

        if (user.getStatus() == UserStatus.LOCKED || user.getStatus() == UserStatus.SUSPENDED) {
            throw new UnauthorizedException("Account is inactive.");
        }

        validateOtpCode(user.getUserId(), request.getOtp());

        // Mark first-time login as complete
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return createAuthenticatedSession(user, clientIp, userAgent, request.getDeviceId(), request.getDeviceName(), request.getDeviceType());
    }

    private void validateOtpCode(String userId, String rawOtp) {
        String cachedOtp = redisSessionStore.getLoginOtp(userId);
        if (cachedOtp == null) {
            throw new UnauthorizedException("Verification code has expired. Request a new code.");
        }
        if (!cachedOtp.equals(rawOtp != null ? rawOtp.trim() : "")) {
            long attempts = redisSessionStore.incrementLoginOtpAttempts(userId, OTP_TTL);
            if (attempts >= MAX_OTP_ATTEMPTS) {
                redisSessionStore.clearLoginOtp(userId);
                throw new TooManyRequestsException("Too many incorrect codes. Request a new code.");
            }
            long left = MAX_OTP_ATTEMPTS - attempts;
            throw new UnauthorizedException("Incorrect verification code. " + left + (left == 1 ? " attempt" : " attempts") + " left.");
        }

        // Invalidate OTP immediately to prevent replay attacks
        redisSessionStore.clearLoginOtp(userId);
        // A verified code must not leave a cooldown that blocks the next device's challenge.
        redisSessionStore.clearLoginOtpCooldown(userId);
    }

    /** Emails a new code to an account that has not verified its email yet or is in an active login MFA challenge. Returns the masked address. */
    public String resendLoginOtp(String userId) {
        if (adminUserRepository != null) {
            Optional<AdminUserEntity> adminOpt = adminUserRepository.findById(userId);
            if (adminOpt.isPresent()) {
                AdminUserEntity admin = adminOpt.get();
                if (admin.getLastLoginAt() != null) {
                    String pendingOtp = redisSessionStore.getLoginOtp(admin.getAdminId());
                    if (pendingOtp == null || pendingOtp.isBlank()) {
                        throw new ConflictException("This email is already verified. Sign in with your password.");
                    }
                }
                long wait = sendAdminLoginOtp(admin);
                if (wait > 0) {
                    throw new TooManyRequestsException("Please wait " + wait + " seconds before requesting a new code.");
                }
                return maskEmail(admin.getEmail());
            }
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User account not found."));
        if (user.getLastLoginAt() != null) {
            String pendingOtp = redisSessionStore.getLoginOtp(user.getUserId());
            if (pendingOtp == null || pendingOtp.isBlank()) {
                throw new ConflictException("This email is already verified. Sign in with your password.");
            }
        }
        long wait = sendLoginOtp(user);
        if (wait > 0) {
            throw new TooManyRequestsException("Please wait " + wait + " seconds before requesting a new code.");
        }
        return maskEmail(user.getEmail());
    }

    /** Generates, stores and emails a fresh code unless one went out inside the cooldown. Returns seconds left to wait (0 = sent). */
    private long sendLoginOtp(UserEntity user) {
        long wait = redisSessionStore.startLoginOtpCooldown(user.getUserId(), OTP_RESEND_COOLDOWN);
        if (wait > 0) {
            return wait;
        }
        String otp = String.format("%06d", OTP_RANDOM.nextInt(1_000_000));
        redisSessionStore.storeLoginOtp(user.getUserId(), otp, OTP_TTL);
        dispatchOtpEmail(user.getEmail(), user.getFirstName() + " " + user.getLastName(), otp);
        return 0;
    }

    private long sendAdminLoginOtp(AdminUserEntity admin) {
        long wait = redisSessionStore.startLoginOtpCooldown(admin.getAdminId(), OTP_RESEND_COOLDOWN);
        if (wait > 0) {
            return wait;
        }
        String otp = String.format("%06d", OTP_RANDOM.nextInt(1_000_000));
        redisSessionStore.storeLoginOtp(admin.getAdminId(), otp, OTP_TTL);
        dispatchOtpEmail(admin.getEmail(), admin.getFirstName() + " " + admin.getLastName(), otp);
        return 0;
    }


    public LoginResult createAuthenticatedSession(UserEntity user, String clientIp, String userAgent) {
        return createAuthenticatedSession(user, clientIp, userAgent, null, null, null);
    }

    public LoginResult createAuthenticatedSession(UserEntity user, String clientIp, String userAgent, String rawDeviceId, String rawDeviceName) {
        return createAuthenticatedSession(user, clientIp, userAgent, rawDeviceId, rawDeviceName, null);
    }

    public LoginResult createAuthenticatedSession(UserEntity user, String clientIp, String userAgent, String rawDeviceId, String rawDeviceName, String rawDeviceType) {
        return createAuthenticatedSession(
                user.getUserId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhoneNumber(),
                user.getRole().getAuthority(),
                user.getMaxConcurrentSessions(),
                clientIp,
                userAgent,
                rawDeviceId,
                rawDeviceName,
                rawDeviceType
        );
    }

    public LoginResult createAuthenticatedAdminSession(AdminUserEntity admin, String clientIp, String userAgent, String rawDeviceId, String rawDeviceName, String rawDeviceType) {
        return createAuthenticatedSession(
                admin.getAdminId(),
                admin.getEmail(),
                admin.getFirstName(),
                admin.getLastName(),
                admin.getPhoneNumber(),
                admin.getRole().getAuthority(),
                admin.getMaxConcurrentSessions(),
                clientIp,
                userAgent,
                rawDeviceId,
                rawDeviceName,
                rawDeviceType
        );
    }

    public LoginResult createAuthenticatedSession(
            String userId,
            String email,
            String firstName,
            String lastName,
            String phoneNumber,
            String roleAuthority,
            int maxConcurrentSessions,
            String clientIp,
            String userAgent,
            String rawDeviceId,
            String rawDeviceName,
            String rawDeviceType) {
        String resolvedDeviceId = resolveDeviceId(rawDeviceId, clientIp, userAgent);
        String resolvedDeviceName = resolveDeviceName(rawDeviceName, userAgent);
        String resolvedDeviceType = resolveDeviceType(rawDeviceType, resolvedDeviceId, resolvedDeviceName, userAgent);
        boolean isWeb = "WEB".equalsIgnoreCase(resolvedDeviceType);

        String primaryDeviceId = redisSessionStore.getPrimaryDeviceId(userId);
        boolean isPrimary = false;
        boolean isApproved = false;
        boolean isThirdDevice = false;
        String replacedDeviceId = null;
        String replacedDeviceName = null;
        boolean isStaffRole = roleAuthority != null && (
                roleAuthority.contains("ADMIN") ||
                roleAuthority.contains("TELLER") ||
                roleAuthority.contains("MANAGER") ||
                roleAuthority.contains("COMPLIANCE"));

        if (isWeb) {
            // Configuration: 1 Web session allowed
            // A desktop/web session cannot be the primary mobile device
            isPrimary = false;

            if (isStaffRole || primaryDeviceId == null || primaryDeviceId.isBlank()) {
                // Staff web portal logins or accounts without a primary mobile are auto-approved without mobile push alerts
                isApproved = true;
                deregisterOtherWebSessions(userId, resolvedDeviceId);
            } else {
                Optional<DeviceInfoDto> existingWeb = redisSessionStore.getDevice(userId, resolvedDeviceId);
                if (existingWeb.isPresent() && existingWeb.get().isApproved()) {
                    isApproved = true;
                    deregisterOtherWebSessions(userId, resolvedDeviceId);
                } else {
                    isApproved = false;
                    dispatchDesktopSessionAlert(userId, resolvedDeviceName, clientIp, primaryDeviceId, resolvedDeviceId);
                }
            }
        } else {
            // Mobile device: 2 Mobiles allowed (1 Primary Mobile + 1 Secondary Mobile)
            if (primaryDeviceId == null || primaryDeviceId.isBlank()) {
                redisSessionStore.setPrimaryDeviceId(userId, resolvedDeviceId);
                primaryDeviceId = resolvedDeviceId;
                isPrimary = true;
                isApproved = true;
                log.info("Registered initial primary mobile device {} for user {}", resolvedDeviceId, userId);
            } else {
                isPrimary = resolvedDeviceId.equals(primaryDeviceId);
            }

            final String effectivePrimaryDeviceId = primaryDeviceId;
            if (isPrimary) {
                isApproved = true;
            } else {
                List<DeviceInfoDto> allDevices = redisSessionStore.getUserDevices(userId);
                List<DeviceInfoDto> existingMobileSecondaries = allDevices.stream()
                        .filter(d -> !"WEB".equalsIgnoreCase(d.getDeviceType()))
                        .filter(d -> !d.getDeviceId().equals(effectivePrimaryDeviceId) && !d.getDeviceId().equals(resolvedDeviceId))
                        .toList();

                if (!existingMobileSecondaries.isEmpty()) {
                    isThirdDevice = true;
                    replacedDeviceId = existingMobileSecondaries.get(0).getDeviceId();
                    replacedDeviceName = existingMobileSecondaries.get(0).getDeviceName();
                    log.info("User {} attempting login from 3rd mobile device {}. Existing secondary mobile is {} ({}) — will be replaced only upon approval",
                            userId, resolvedDeviceId, replacedDeviceId, replacedDeviceName);
                }

                Optional<DeviceInfoDto> existing = redisSessionStore.getDevice(userId, resolvedDeviceId);
                if (existing.isPresent() && existing.get().isApproved() && !isThirdDevice) {
                    isApproved = true;
                } else {
                    isApproved = false;
                }
            }

            if (!isPrimary && !isApproved) {
                dispatchSecondaryDeviceLoginAlert(
                        userId,
                        resolvedDeviceName,
                        clientIp,
                        effectivePrimaryDeviceId,
                        resolvedDeviceId,
                        isThirdDevice,
                        replacedDeviceId,
                        replacedDeviceName,
                        "MOBILE"
                );
            }
        }

        DeviceInfoDto deviceInfoDto = DeviceInfoDto.builder()
                .deviceId(resolvedDeviceId)
                .deviceName(resolvedDeviceName)
                .deviceType(resolvedDeviceType)
                .isPrimary(isPrimary)
                .isApproved(isApproved)
                .status(isApproved ? "APPROVED" : (isThirdDevice ? "PENDING_CONFIRMATION" : "PENDING_APPROVAL"))
                .clientIp(clientIp)
                .userAgent(userAgent)
                .registeredAt(Instant.now())
                .lastLoginAt(Instant.now())
                .build();
        redisSessionStore.saveUserDevice(userId, deviceInfoDto);

        String jti = UUID.randomUUID().toString();
        String accessToken = jwtProvider.generateAccessToken(userId, email, roleAuthority, jti, resolvedDeviceId);

        String sessionId = "SESS-" + UUID.randomUUID();

        // Enforce max concurrent sessions in Redis (evicts the oldest live session, never a random one)
        redisSessionStore.registerSessionToken(userId, sessionId, jti,
                Instant.now().getEpochSecond() + jwtProvider.getAccessTokenExpirationSeconds(),
                maxConcurrentSessions);

        String refreshTokenId = "rt_" + UUID.randomUUID().toString().replace("-", "");

        SessionMetadata sessionMetadata = SessionMetadata.builder()
                .sessionId(sessionId)
                .userId(userId)
                .activeRefreshTokenId(refreshTokenId)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .deviceId(resolvedDeviceId)
                .deviceName(resolvedDeviceName)
                .createdAt(Instant.now())
                .build();
        redisSessionStore.saveSession(userId, sessionId, sessionMetadata, REFRESH_TOKEN_TTL);

        RefreshTokenMetadata tokenMetadata = RefreshTokenMetadata.builder()
                .tokenId(refreshTokenId)
                .userId(userId)
                .sessionId(sessionId)
                .deviceId(resolvedDeviceId)
                .role(roleAuthority)
                .status("ACTIVE")
                .parentTokenId(null)
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(REFRESH_TOKEN_TTL))
                .build();
        redisSessionStore.saveRefreshToken(tokenMetadata, REFRESH_TOKEN_TTL);
        redisSessionStore.addToTokenFamily(sessionId, refreshTokenId);

        String fullName = (firstName + " " + (lastName != null ? lastName : "")).trim();
        String primaryAccId = null;
        String primaryAccNum = null;
        java.math.BigDecimal availBalance = java.math.BigDecimal.ZERO;
        String currency = "PHP";

        try {
            List<com.fse.banking.account.model.AccountEntity> userAccounts = accountRepository.findByUserId(userId);
            if (userAccounts != null && !userAccounts.isEmpty()) {
                com.fse.banking.account.model.AccountEntity acc = userAccounts.get(0);
                primaryAccId = acc.getAccountId();
                primaryAccNum = acc.getAccountNumber();
                currency = acc.getCurrency() != null ? acc.getCurrency() : "PHP";
                Optional<com.fse.banking.account.model.BalanceMasterEntity> bm = balanceMasterRepository.findByAccountId(primaryAccId);
                if (bm.isPresent()) {
                    availBalance = bm.get().getAvailableBalance();
                }
            }
        } catch (Exception ex) {
            log.warn("Could not query account/balance for user {}: {}", userId, ex.getMessage());
        }

        LoginResponse loginResponse = LoginResponse.builder()
                .status(isApproved ? "AUTHENTICATED" : (isThirdDevice ? "PENDING_CONFIRMATION" : "PENDING_APPROVAL"))
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresInSeconds(jwtProvider.getAccessTokenExpirationSeconds())
                .role(roleAuthority)
                .userId(userId)
                .fullName(fullName)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .phoneNumber(phoneNumber)
                .primaryAccountId(primaryAccId)
                .accountNumber(primaryAccNum)
                .availableBalance(availBalance)
                .currency(currency)
                .deviceId(resolvedDeviceId)
                .deviceName(resolvedDeviceName)
                .deviceType(resolvedDeviceType)
                .isPrimaryDevice(isPrimary)
                .isApproved(isApproved)
                .primaryDeviceId(primaryDeviceId)
                .build();

        return LoginResult.builder()
                .response(loginResponse)
                .refreshTokenId(refreshTokenId)
                .build();
    }

    public List<DeviceInfoDto> getUserDevices(String userId) {
        return redisSessionStore.getUserDevices(userId);
    }

    public void setPrimaryDevice(String userId, String deviceId) {
        String existingPrimary = redisSessionStore.getPrimaryDeviceId(userId);
        if (existingPrimary != null && !existingPrimary.isBlank() && !existingPrimary.equals(deviceId)) {
            log.warn("Attempt to change immutable primary device for user {} from {} to {} rejected", userId, existingPrimary, deviceId);
            throw new IllegalArgumentException("Primary device is permanent and cannot be changed once established.");
        }
        Optional<DeviceInfoDto> dev = redisSessionStore.getDevice(userId, deviceId);
        if (dev.isPresent() && "WEB".equalsIgnoreCase(dev.get().getDeviceType())) {
            throw new IllegalArgumentException("Desktop/Web sessions cannot be designated as the primary device.");
        }
        redisSessionStore.setPrimaryDeviceId(userId, deviceId);
        log.info("Primary device for user {} set to {}", userId, deviceId);
    }

    public void approveDevice(String userId, String deviceId, String requesterDeviceId) {
        String primaryDeviceId = redisSessionStore.getPrimaryDeviceId(userId);
        if (primaryDeviceId == null || !primaryDeviceId.equals(requesterDeviceId)) {
            log.warn("Device {} attempted to approve {} for user {} but is not the primary device", requesterDeviceId, deviceId, userId);
            throw new IllegalArgumentException("Only the primary device can approve access requests.");
        }
        List<DeviceInfoDto> allDevices = redisSessionStore.getUserDevices(userId);
        String targetDeviceType = redisSessionStore.getDevice(userId, deviceId)
                .map(DeviceInfoDto::getDeviceType)
                .orElse("MOBILE");

        if ("WEB".equalsIgnoreCase(targetDeviceType)) {
            // Configuration: 1 Web session allowed
            for (DeviceInfoDto d : allDevices) {
                if ("WEB".equalsIgnoreCase(d.getDeviceType()) && !d.getDeviceId().equals(deviceId)) {
                    log.info("Deregistering older web session {} for user {}", d.getDeviceId(), userId);
                    redisSessionStore.deleteUserDevice(userId, d.getDeviceId());
                    redisSessionStore.deleteSessionsForDevice(userId, d.getDeviceId());
                    dispatchDeviceApprovalEvent(userId, d.getDeviceId(), "device-revoked");
                }
            }
        } else {
            // Configuration: 2 Mobiles allowed (1 Primary, 1 Secondary).
            // Approving a secondary mobile deregisters any other secondary mobile!
            for (DeviceInfoDto d : allDevices) {
                if (!"WEB".equalsIgnoreCase(d.getDeviceType())
                        && !d.getDeviceId().equals(primaryDeviceId)
                        && !d.getDeviceId().equals(deviceId)) {
                    log.info("Deregistering replaced secondary mobile device {} for user {} to maintain 2-mobile policy", d.getDeviceId(), userId);
                    redisSessionStore.deleteUserDevice(userId, d.getDeviceId());
                    redisSessionStore.deleteSessionsForDevice(userId, d.getDeviceId());
                    dispatchDeviceApprovalEvent(userId, d.getDeviceId(), "device-revoked");
                }
            }
        }

        redisSessionStore.getDevice(userId, deviceId).ifPresent(device -> {
            device.setApproved(true);
            device.setStatus("APPROVED");
            redisSessionStore.saveUserDevice(userId, device);
            log.info("Device {} for user {} marked as APPROVED", deviceId, userId);
            dispatchDeviceApprovalEvent(userId, deviceId, "device-approved");
        });
    }

    public void revokeDevice(String userId, String deviceId) {
        revokeDevice(userId, deviceId, null);
    }

    public void revokeDevice(String userId, String deviceId, String requesterDeviceId) {
        String primaryDeviceId = redisSessionStore.getPrimaryDeviceId(userId);
        if (deviceId != null && deviceId.equals(primaryDeviceId)) {
            if (requesterDeviceId != null && requesterDeviceId.equals(primaryDeviceId)) {
                log.info("Primary device {} unlinked itself for user {}", deviceId, userId);
                redisSessionStore.setPrimaryDeviceId(userId, "");
            } else {
                log.warn("Attempt to revoke primary device {} for user {} by requester {} rejected", deviceId, userId, requesterDeviceId);
                throw new IllegalArgumentException("Cannot revoke the primary device's access.");
            }
        }
        redisSessionStore.deleteUserDevice(userId, deviceId);
        redisSessionStore.deleteSessionsForDevice(userId, deviceId);
        log.info("Device {} for user {} deleted/revoked by requester {}", deviceId, userId, requesterDeviceId);
        dispatchDeviceApprovalEvent(userId, deviceId, "device-revoked");
    }

    private void dispatchDeviceApprovalEvent(String userId, String deviceId, String endpoint) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(3000);
            restTemplate.setRequestFactory(factory);

            java.util.Map<String, Object> payload = java.util.Map.of(
                    "user_id", userId,
                    "device_id", deviceId
            );
            restTemplate.postForEntity(notificationServiceUrl + "/api/v1/notifications/" + endpoint, payload, java.util.Map.class);
        } catch (Exception e) {
            log.warn("Could not dispatch {} to notification-service: {}", endpoint, e.getMessage());
        }
    }

    private String resolveDeviceId(String requestedId, String clientIp, String userAgent) {
        if (requestedId != null && !requestedId.isBlank()) {
            return requestedId.trim();
        }
        String seed = (clientIp != null ? clientIp : "127.0.0.1") + ":" + (userAgent != null ? userAgent : "app");
        return "dev_" + UUID.nameUUIDFromBytes(seed.getBytes()).toString().substring(0, 12);
    }

    private String resolveDeviceName(String requestedName, String userAgent) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        if (userAgent == null || userAgent.isBlank()) {
            return "Primary Mobile Device";
        }
        if (userAgent.contains("iPhone")) return "iPhone";
        if (userAgent.contains("iPad")) return "iPad";
        if (userAgent.contains("Android")) return "Android Device";
        if (userAgent.contains("Macintosh")) return "MacBook Pro";
        if (userAgent.contains("Windows")) return "Windows PC";
        if (userAgent.contains("Chrome")) return "Chrome Browser";
        if (userAgent.contains("Dart") || userAgent.contains("Flutter")) return "Mobile Device";
        return "Mobile Device";
    }

    public String resolveDeviceType(String rawDeviceType, String deviceId, String deviceName, String userAgent) {
        if (rawDeviceType != null && !rawDeviceType.isBlank()) {
            String t = rawDeviceType.trim().toUpperCase();
            if (t.contains("WEB") || t.contains("DESKTOP")) return "WEB";
            if (t.contains("MOBILE")) return "MOBILE";
        }
        String combined = ((deviceId != null ? deviceId : "") + " "
                + (deviceName != null ? deviceName : "") + " "
                + (userAgent != null ? userAgent : "")).toLowerCase();
        if (combined.contains("android") || combined.contains("iphone") || combined.contains("ipad")
                || combined.contains("mobile") || combined.contains("dart") || combined.contains("flutter")) {
            return "MOBILE";
        }
        if (combined.contains("web") || combined.contains("desktop") || combined.contains("laptop")
                || combined.contains("chrome") || combined.contains("firefox") || combined.contains("safari")
                || combined.contains("windows") || combined.contains("macintosh") || combined.contains("linux")) {
            return "WEB";
        }
        return "MOBILE";
    }

    private void deregisterOtherWebSessions(String userId, String currentDeviceId) {
        List<DeviceInfoDto> allDevices = redisSessionStore.getUserDevices(userId);
        List<DeviceInfoDto> existingWebDevices = allDevices.stream()
                .filter(d -> "WEB".equalsIgnoreCase(d.getDeviceType()))
                .filter(d -> !d.getDeviceId().equals(currentDeviceId))
                .toList();
        for (DeviceInfoDto oldWeb : existingWebDevices) {
            log.info("Enforcing 1-web limit: deregistering previous web session {} for user {}", oldWeb.getDeviceId(), userId);
            redisSessionStore.deleteUserDevice(userId, oldWeb.getDeviceId());
            redisSessionStore.deleteSessionsForDevice(userId, oldWeb.getDeviceId());
            dispatchDeviceApprovalEvent(userId, oldWeb.getDeviceId(), "device-revoked");
        }
    }

    private void dispatchDesktopSessionAlert(
            String userId,
            String deviceName,
            String clientIp,
            String targetPrimaryDeviceId,
            String deviceId) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(3000);
            restTemplate.setRequestFactory(factory);

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("user_id", userId);
            payload.put("title", "Security Alert: Desktop Login Request");
            payload.put("message", "A desktop/web session (" + deviceName + ") is requesting access to your account from IP " + clientIp + ". Confirm if you wish to grant this device access.");
            payload.put("type", "SECURITY_ALERT");
            payload.put("device_type", "WEB");
            payload.put("device_name", deviceName);
            payload.put("device_id", deviceId != null ? deviceId : "");
            payload.put("client_ip", clientIp);
            payload.put("target_device_id", targetPrimaryDeviceId != null ? targetPrimaryDeviceId : "");
            payload.put("status", "PENDING_APPROVAL");
            payload.put("is_third_device", false);
            payload.put("replaced_device_id", "");
            payload.put("replaced_device_name", "");

            String targetUrl = notificationServiceUrl + "/api/v1/notifications/security-alert";
            restTemplate.postForEntity(targetUrl, payload, java.util.Map.class);
            log.info("Successfully dispatched desktop session alert to primary device {} for user {}", targetPrimaryDeviceId, userId);
        } catch (Exception e) {
            log.warn("Could not dispatch desktop session alert to notification-service: {}", e.getMessage());
        }
    }

    private void dispatchSecondaryDeviceLoginAlert(
            String userId,
            String newDeviceName,
            String clientIp,
            String targetPrimaryDeviceId,
            String newDeviceId,
            boolean isThirdDevice,
            String replacedDeviceId,
            String replacedDeviceName) {
        dispatchSecondaryDeviceLoginAlert(userId, newDeviceName, clientIp, targetPrimaryDeviceId, newDeviceId, isThirdDevice, replacedDeviceId, replacedDeviceName, "MOBILE");
    }

    private void dispatchSecondaryDeviceLoginAlert(
            String userId,
            String newDeviceName,
            String clientIp,
            String targetPrimaryDeviceId,
            String newDeviceId,
            boolean isThirdDevice,
            String replacedDeviceId,
            String replacedDeviceName,
            String deviceType) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(3000);
            restTemplate.setRequestFactory(factory);

            String title = isThirdDevice
                    ? "Security Alert: 3rd Mobile Device Login Attempt"
                    : "Security Alert: New Mobile Device Login";
            String message = isThirdDevice
                    ? "A 3rd mobile device (" + newDeviceName + ") has logged in. Since AuraBank only allows 2 mobile devices (1 Primary, 1 Secondary), " + (replacedDeviceName != null ? replacedDeviceName : "the other secondary mobile") + " was automatically logged out. Confirm if you wish to grant this device access to your account."
                    : "A new mobile device (" + newDeviceName + ") just logged into your account from IP " + clientIp + ".";

            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("user_id", userId);
            payload.put("title", title);
            payload.put("message", message);
            payload.put("type", "SECURITY_ALERT");
            payload.put("device_type", deviceType != null ? deviceType : "MOBILE");
            payload.put("device_name", newDeviceName);
            payload.put("device_id", newDeviceId != null ? newDeviceId : "");
            payload.put("client_ip", clientIp);
            payload.put("target_device_id", targetPrimaryDeviceId != null ? targetPrimaryDeviceId : "");
            payload.put("status", isThirdDevice ? "PENDING_CONFIRMATION" : "PENDING_APPROVAL");
            payload.put("is_third_device", isThirdDevice);
            payload.put("replaced_device_id", replacedDeviceId != null ? replacedDeviceId : "");
            payload.put("replaced_device_name", replacedDeviceName != null ? replacedDeviceName : "");

            String targetUrl = notificationServiceUrl + "/api/v1/notifications/security-alert";
            restTemplate.postForEntity(targetUrl, payload, java.util.Map.class);
            log.info("Dispatched {} mobile device login alert for user {} (device: {}) to notification-service",
                    isThirdDevice ? "3rd" : "secondary", userId, newDeviceName);
        } catch (Exception e) {
            log.warn("Could not dispatch device alert to notification-service: {}", e.getMessage());
        }
    }

    private void dispatchOtpEmail(String email, String recipientName, String otp) {
        try {
            org.springframework.web.client.RestTemplate restTemplate = new org.springframework.web.client.RestTemplate();
            org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(3000);
            restTemplate.setRequestFactory(factory);

            java.util.Map<String, Object> payload = java.util.Map.of(
                    "recipient_email", email,
                    "recipient_name", recipientName,
                    "verification_code", otp,
                    "type", "LOGIN_OTP"
            );
            String targetUrl = notificationServiceUrl + "/api/v1/notifications/send-otp";
            restTemplate.postForEntity(targetUrl, payload, java.util.Map.class);
            log.info("Dispatched first-time login OTP email via notification-service to {}", email);
        } catch (Exception e) {
            log.warn("Could not dispatch OTP email via notification-service: {}", e.getMessage());
        }
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) return email;
        String[] parts = email.split("@");
        String username = parts[0];
        String domain = parts[1];
        if (username.length() <= 2) {
            return username.charAt(0) + "***@" + domain;
        }
        return username.charAt(0) + "***" + username.charAt(username.length() - 1) + "@" + domain;
    }

    public void logout(String authHeader, String refreshTokenCookie) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtProvider.validateToken(token)) {
                String jti = jwtProvider.getJti(token);
                String userId = jwtProvider.getUserId(token);
                long remainingTtl = jwtProvider.getRemainingTtlSeconds(token);
                redisSessionStore.blacklistToken(jti, remainingTtl);
                redisSessionStore.removeSessionToken(userId, jti);
                log.info("Access token jti {} blacklisted on logout for user {}", jti, userId);
            }
        }

        if (refreshTokenCookie != null && !refreshTokenCookie.isBlank()) {
            redisSessionStore.getRefreshToken(refreshTokenCookie).ifPresent(metadata -> {
                redisSessionStore.purgeEntireTokenFamily(metadata.getSessionId(), metadata.getUserId());
                log.info("Token family for session {} purged on logout", metadata.getSessionId());
            });
        }
    }

    public void logoutAll(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtProvider.validateToken(token)) {
                String userId = jwtProvider.getUserId(token);
                logoutAll(userId, authHeader);
            }
        }
    }

    public void logoutAll(String userId, String authHeader) {
        if (userId == null || userId.isBlank()) return;
        String primaryDeviceId = redisSessionStore.getPrimaryDeviceId(userId);
        List<DeviceInfoDto> allDevices = redisSessionStore.getUserDevices(userId);
        for (DeviceInfoDto d : allDevices) {
            if (primaryDeviceId == null || !d.getDeviceId().equals(primaryDeviceId)) {
                redisSessionStore.deleteUserDevice(userId, d.getDeviceId());
                redisSessionStore.deleteSessionsForDevice(userId, d.getDeviceId());
                dispatchDeviceApprovalEvent(userId, d.getDeviceId(), "device-revoked");
            }
        }
        log.info("Logged out all non-primary sessions and devices for user {}", userId);
    }

    public void logoutAllWebSessions(String userId, String authHeader) {
        if (userId == null || userId.isBlank()) return;
        List<DeviceInfoDto> allDevices = redisSessionStore.getUserDevices(userId);
        for (DeviceInfoDto d : allDevices) {
            if ("WEB".equalsIgnoreCase(d.getDeviceType())) {
                redisSessionStore.deleteUserDevice(userId, d.getDeviceId());
                redisSessionStore.deleteSessionsForDevice(userId, d.getDeviceId());
                dispatchDeviceApprovalEvent(userId, d.getDeviceId(), "device-revoked");
                log.info("Logged out web session {} for user {}", d.getDeviceId(), userId);
            }
        }
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtProvider.validateToken(token)) {
                String jti = jwtProvider.getJti(token);
                long remainingTtl = jwtProvider.getRemainingTtlSeconds(token);
                redisSessionStore.blacklistToken(jti, remainingTtl);
                redisSessionStore.removeSessionToken(userId, jti);
            }
        }
        log.info("Logged out all web sessions for user {}", userId);
    }
}
