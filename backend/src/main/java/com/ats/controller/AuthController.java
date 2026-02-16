package com.ats.controller;

import com.ats.dto.AuthRequest;
import com.ats.dto.AuthResponse;
import com.ats.dto.UserDTO;
import com.ats.dto.ChangePasswordRequest;
import com.ats.dto.ForgotPasswordRequest;
import com.ats.dto.LoginRequest;
import com.ats.dto.ResetPasswordRequest;
import com.ats.dto.MfaSetupRequest;
import com.ats.dto.MfaSetupResponse;
import com.ats.dto.MfaVerifyRequest;
import com.ats.dto.MfaLoginRequest;
import com.ats.model.User;
import com.ats.model.Role;
import com.ats.repository.UserRepository;
import com.ats.security.JwtTokenProvider;
import com.ats.exception.ResourceAlreadyExistsException;
import com.ats.exception.AtsCustomExceptions.BadRequestException;
import com.ats.service.EmailService;
import com.ats.service.PasswordResetService;
import com.ats.service.UserService;
import com.ats.util.TokenUtil;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.MessagingException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import com.ats.annotation.RequiresAuthentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication & Profile", description = "APIs for user authentication, registration, and profile management")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final EmailService emailService;
    private final PasswordResetService passwordResetService;
    private final UserService userService;

    @PostMapping("/signup")
    @Operation(
        summary = "Register a new user",
        description = "Creates a new user account and sends a verification email"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "User registered successfully",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"message\": \"Registration successful. Please check your email for verification.\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid input or email already in use",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-02-20T10:00:00\", \"message\": \"Email is already in use\", \"status\": 400, \"error\": \"Bad Request\"}"
                )
            )
        )
    })
    public ResponseEntity<?> signup(@Valid @RequestBody AuthRequest authRequest, HttpServletRequest httpRequest) {
        if (userRepository.existsByEmail(authRequest.getEmail())) {
            throw new ResourceAlreadyExistsException("Email is already in use");
        }

        // Validate Privacy Policy acceptance (required for all users)
        if (authRequest.getPrivacyPolicyAccepted() == null || !authRequest.getPrivacyPolicyAccepted()) {
            throw new BadRequestException("You must accept the Privacy Policy to create an account");
        }
        
        User user = new User();
        user.setEmail(authRequest.getEmail());
        user.setPasswordHash(passwordEncoder.encode(authRequest.getPassword()));
        user.setFirstName(authRequest.getFirstName());
        user.setLastName(authRequest.getLastName());
        user.setRole(Role.CANDIDATE);
        user.setIsActive(true);
        user.setIsEmailPasswordEnabled(true);
        
        // Set privacy policy acceptance
        user.setPrivacyPolicyAccepted(authRequest.getPrivacyPolicyAccepted());
        user.setPrivacyPolicyAcceptedAt(LocalDateTime.now());
        
        // Generate verification token using TokenUtil
        String verificationToken = TokenUtil.generateVerificationToken(user);

        user = userRepository.save(user);

        try {
            String requestOrigin = extractOriginFromRequest(httpRequest);
            emailService.sendNewUserVerificationEmail(user, verificationToken, requestOrigin);
            return ResponseEntity.ok(new HashMap<String, String>() {{
                put("message", "Registration successful. Please check your email for verification.");
            }});
        } catch (MessagingException e) {
            // Log the error but return success since user was created
            e.printStackTrace();
            return ResponseEntity.ok(new HashMap<String, String>() {{
                put("message", "Registration successful. However, we couldn't send the verification email. Please contact support.");
                put("verificationToken", verificationToken); // Include token in response for testing
            }});
        }
    }

    @PostMapping("/login")
    @Operation(
        summary = "Login user",
        description = "Authenticates a user with email and password"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Authentication successful",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AuthResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Authentication failed, invalid credentials or email not verified"
        ),
        @ApiResponse(
            responseCode = "202",
            description = "2FA required",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"message\": \"2FA verification required\", \"email\": \"user@example.com\", \"requires2FA\": true}"
                )
            )
        )
    })
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest authRequest) {
        User user = userRepository.findByEmail(authRequest.getEmail())
            .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        if (!user.getIsEmailVerified()) {
            throw new RuntimeException("Please verify your email before logging in");
        }
        
        if (user.getIsActive() == null || !user.getIsActive()) {
            throw new RuntimeException("This account has been deactivated. Please contact an administrator.");
        }

        // Authenticate with password
        Authentication authentication = authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(authRequest.getEmail(), authRequest.getPassword())
        );

        // Check if user has 2FA enabled
        if (user.getMfaEnabled() != null && user.getMfaEnabled()) {
            // Return a response indicating 2FA is required
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of(
                    "message", "2FA verification required",
                    "email", user.getEmail(),
                    "requires2FA", true
                ));
        }

        // If 2FA is not enabled, proceed with normal login
        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = tokenProvider.generateToken(authentication);
        
        // Update last login time
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        return ResponseEntity.ok(new AuthResponse(jwt, convertToDTO(user)));
    }

    @PostMapping("/logout")
    @Operation(
        summary = "Logout user",
        description = "Logs out the current user by clearing the security context"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "User logged out successfully",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"message\": \"Logged out successfully\"}"
                )
            )
        )
    })
    public ResponseEntity<?> logout() {
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok().body(new HashMap<String, String>() {{
            put("message", "Logged out successfully");
        }});
    }

    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(@RequestParam String token) {
        User user = userRepository.findByEmailVerificationToken(token)
            .orElseThrow(() -> new RuntimeException("Invalid verification token"));

        if (user.getEmailVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
            throw new RuntimeException("Verification token has expired");
        }

        // For admin-created CANDIDATE users, Connect consent must be accepted before email verification
        // Check if this is an admin-created candidate (has connectConsentToken) and consent not given
        if (user.getRole() == Role.CANDIDATE) {
            // If user has a connectConsentToken, they were created by admin and MUST accept Connect consent
            if (user.getConnectConsentToken() != null && !user.getConnectConsentToken().isEmpty()) {
                // This is an admin-created candidate - Connect consent is mandatory
                if (user.getConnectConsentGiven() == null || !Boolean.TRUE.equals(user.getConnectConsentGiven())) {
                    logger.warn("Email verification blocked for admin-created candidate {} - Connect consent not accepted", user.getEmail());
                    throw new BadRequestException("You must accept Connect consent before verifying your email. Please check your email for the Connect consent link and accept it first.");
                }
            }
        }

        user.setIsEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationTokenExpiry(null);
        
        // Clear Connect consent token if it exists (admin-created candidates)
        if (user.getConnectConsentToken() != null) {
            user.setConnectConsentToken(null);
            user.setConnectConsentTokenExpiry(null);
            logger.info("Cleared Connect consent token for admin-created candidate {} after email verification", user.getEmail());
        }
        
        userRepository.save(user);

        return ResponseEntity.ok().body(new HashMap<String, String>() {{
            put("message", "Email verified successfully");
        }});
    }
    
    @GetMapping("/me")
    @Operation(
        summary = "Get current user profile",
        description = "Retrieves detailed information about the currently authenticated user's profile. This endpoint returns all profile information including personal details, contact information, and account status. Authorization header with Bearer token is required.",
        tags = {"Authentication & Profile"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "User profile retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class),
                examples = @ExampleObject(
                    value = "{\n" +
                           "  \"id\": 1,\n" +
                           "  \"email\": \"john.doe@example.com\",\n" +
                           "  \"firstName\": \"John\",\n" +
                           "  \"lastName\": \"Doe\",\n" +
                           "  \"role\": \"CANDIDATE\",\n" +
                           "  \"department\": \"Engineering\",\n" +
                           "  \"linkedinProfileUrl\": \"https://linkedin.com/in/johndoe\",\n" +
                           "  \"profilePictureUrl\": \"/api/files/profile-pictures/1bb1d8f6-649f-490d-85b5-621b16b5d2f7.jpg\",\n" +
                           "  \"birthDate\": \"1990-01-15\",\n" +
                           "  \"phoneNumber\": \"+1 (555) 123-4567\",\n" +
                           "  \"addressLine1\": \"123 Main Street\",\n" +
                           "  \"addressLine2\": \"Apt 4B\",\n" +
                           "  \"city\": \"New York\",\n" +
                           "  \"state\": \"NY\",\n" +
                           "  \"country\": \"United States\",\n" +
                           "  \"postalCode\": \"10001\",\n" +
                           "  \"bio\": \"Full-stack developer with 5 years of experience...\",\n" +
                           "  \"isActive\": true,\n" +
                           "  \"isEmailVerified\": true,\n" +
                           "  \"isEmailPasswordEnabled\": true\n" +
                           "}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Invalid or expired JWT token",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 401, \"error\": \"Unauthorized\", \"message\": \"Invalid JWT token\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 404, \"error\": \"Not Found\", \"message\": \"User not found\"}"
                )
            )
        )
    })
    @RequiresAuthentication
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        return ResponseEntity.ok(convertToDTO(user));
    }
    
    @PutMapping("/me")
    @Operation(
        summary = "Update current user profile",
        description = "Updates information for the currently authenticated user. This endpoint allows users to update their profile information including personal details, contact information, and profile picture. Authorization header with Bearer token is required. Null values will clear the corresponding fields in the database.",
        tags = {"Authentication & Profile"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Profile updated successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class),
                examples = @ExampleObject(
                    value = "{\n" +
                           "  \"id\": 1,\n" +
                           "  \"email\": \"john.doe@example.com\",\n" +
                           "  \"firstName\": \"John\",\n" +
                           "  \"lastName\": \"Smith\",\n" +
                           "  \"role\": \"CANDIDATE\",\n" +
                           "  \"department\": \"Product\",\n" +
                           "  \"linkedinProfileUrl\": \"https://linkedin.com/in/johnsmith\",\n" +
                           "  \"profilePictureUrl\": \"/api/files/profile-pictures/1bb1d8f6-649f-490d-85b5-621b16b5d2f7.jpg\",\n" +
                           "  \"birthDate\": \"1990-01-15\",\n" +
                           "  \"phoneNumber\": \"+1 (555) 123-4567\",\n" +
                           "  \"addressLine1\": \"456 Park Avenue\",\n" +
                           "  \"addressLine2\": null,\n" +
                           "  \"city\": \"New York\",\n" +
                           "  \"state\": \"NY\",\n" +
                           "  \"country\": \"United States\",\n" +
                           "  \"postalCode\": \"10022\",\n" +
                           "  \"bio\": \"Senior developer with expertise in React and Spring Boot\",\n" +
                           "  \"isActive\": true,\n" +
                           "  \"isEmailVerified\": true,\n" +
                           "  \"isEmailPasswordEnabled\": true\n" +
                           "}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request body",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 400, \"error\": \"Bad Request\", \"message\": \"Invalid request body\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - Invalid or expired JWT token",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 401, \"error\": \"Unauthorized\", \"message\": \"Invalid JWT token\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 404, \"error\": \"Not Found\", \"message\": \"User not found\"}"
                )
            )
        )
    })
    @RequiresAuthentication
    public ResponseEntity<?> updateCurrentUser(
            Authentication authentication,
            @Valid @RequestBody UserDTO userDTO) {
        String email = authentication.getName();
        System.out.println("Updating profile for user: " + email);
        System.out.println("Received update data: " + userDTO);
        
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Update user information (set fields regardless of null status to allow clearing fields)
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setDepartment(userDTO.getDepartment());
        user.setLinkedinProfileUrl(userDTO.getLinkedinProfileUrl());
        user.setProfilePictureUrl(userDTO.getProfilePictureUrl());
        
        // Update profile fields
        user.setBirthDate(userDTO.getBirthDate());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setAddressLine1(userDTO.getAddressLine1());
        user.setAddressLine2(userDTO.getAddressLine2());
        user.setCity(userDTO.getCity());
        user.setState(userDTO.getState());
        user.setCountry(userDTO.getCountry());
        user.setPostalCode(userDTO.getPostalCode());
        user.setBio(userDTO.getBio());
        
        // Don't update sensitive fields like email, role, active status, etc.
        
        // Save the updated user
        user = userRepository.save(user);
        System.out.println("Profile updated successfully for user: " + email);
        
        UserDTO updatedUserDTO = convertToDTO(user);
        System.out.println("Returning updated user data: " + updatedUserDTO);
        return ResponseEntity.ok(updatedUserDTO);
    }
    
    @PostMapping("/accept-privacy-policy")
    @Operation(
        summary = "Accept Privacy Policy",
        description = "Marks the current user as having accepted the Privacy Policy. Required for submitting job applications."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Privacy Policy accepted successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class)
            )
        ),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @RequiresAuthentication
    public ResponseEntity<?> acceptPrivacyPolicy(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Set Privacy Policy acceptance
        user.setPrivacyPolicyAccepted(true);
        user.setPrivacyPolicyAcceptedAt(LocalDateTime.now());
        userRepository.save(user);
        
        logger.info("User {} accepted Privacy Policy", email);
        return ResponseEntity.ok(convertToDTO(user));
    }
    
    @PostMapping("/accept-application-consent")
    @Operation(
        summary = "Accept Application Consent",
        description = "Marks the current user as having accepted the application consent terms. Required for submitting job applications."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Application consent accepted successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class)
            )
        ),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    @RequiresAuthentication
    public ResponseEntity<?> acceptApplicationConsent(Authentication authentication) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Set Application Consent acceptance
        user.setApplicationConsentGiven(true);
        user.setApplicationConsentGivenAt(LocalDateTime.now());
        userRepository.save(user);
        
        logger.info("User {} accepted Application Consent", email);
        return ResponseEntity.ok(convertToDTO(user));
    }
    
    @PostMapping("/accept-connect-consent")
    @Operation(
        summary = "Accept Connect Consent via Token",
        description = "Accepts Connect consent using a token (for admin-created users). Also supports authenticated users accepting Connect consent."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Connect consent accepted successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class)
            )
        ),
        @ApiResponse(responseCode = "400", description = "Invalid or expired token"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<?> acceptConnectConsent(
            @RequestParam(required = false) String token,
            Authentication authentication) {
        
        User user;
        
        // If token is provided, use token-based flow (for admin-created users)
        if (token != null && !token.isEmpty()) {
            user = userRepository.findByConnectConsentToken(token)
                .orElseThrow(() -> new BadRequestException("Invalid or expired Connect consent token"));
            
            // Validate token expiry
            if (user.getConnectConsentTokenExpiry() == null || 
                user.getConnectConsentTokenExpiry().isBefore(LocalDateTime.now())) {
                throw new BadRequestException("Connect consent token has expired");
            }
        } else if (authentication != null && authentication.isAuthenticated()) {
            // Authenticated user flow (for profile settings)
            String email = authentication.getName();
            user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        } else {
            throw new BadRequestException("Either a valid token or authentication is required");
        }
        
        // Set Connect Consent acceptance
        user.setConnectConsentGiven(true);
        user.setConnectConsentGivenAt(LocalDateTime.now());
        
        // NOTE: We do NOT clear connectConsentToken here - it will be cleared during email verification
        // This allows the verify-email endpoint to know this was an admin-created candidate
        userRepository.save(user);
        
        logger.info("User {} accepted Connect Consent. Email verification still required.", user.getEmail());
        return ResponseEntity.ok(convertToDTO(user));
    }
    
    @PostMapping("/deactivate")
    @Operation(
        summary = "Deactivate current user account",
        description = "Deactivates the currently authenticated user's account. Requires a reason for deactivation. The account will be marked as inactive but data will be preserved for future reactivation by an administrator.",
        tags = {"Authentication & Profile", "Security"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "Account deactivated successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class),
                examples = @ExampleObject(
                    value = "{\n" +
                           "  \"id\": 1,\n" +
                           "  \"email\": \"john.doe@example.com\",\n" +
                           "  \"firstName\": \"John\",\n" +
                           "  \"lastName\": \"Doe\",\n" +
                           "  \"role\": \"CANDIDATE\",\n" +
                           "  \"isActive\": false,\n" +
                           "  \"deactivationReason\": \"Moving to a different platform\",\n" +
                           "  \"deactivationDate\": \"2024-05-20T10:00:00\"\n" +
                           "}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "400", 
            description = "Invalid request - Missing deactivation reason",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 400, \"error\": \"Bad Request\", \"message\": \"Deactivation reason is required\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "401", 
            description = "Unauthorized - Invalid or expired JWT token",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 401, \"error\": \"Unauthorized\", \"message\": \"Invalid JWT token\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "404", 
            description = "User not found",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 404, \"error\": \"Not Found\", \"message\": \"User not found\"}"
                )
            )
        )
    })
    @RequiresAuthentication(message = "Authentication required to deactivate your account")
    public ResponseEntity<?> deactivateCurrentUser(
            Authentication authentication,
            @Schema(description = "Deactivation request with reason", required = true, example = "{\"reason\": \"Moving to a different platform\"}")
            @RequestBody Map<String, String> request) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Prevent admin from deactivating their own account
        if (user.getRole() == Role.ADMIN) {
            throw new IllegalArgumentException("Cannot deactivate your own admin account. Please ask another admin to do this.");
        }
        
        String reason = request.get("reason");
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Deactivation reason is required");
        }
        
        user.setIsActive(false);
        user.setDeactivationReason(reason);
        user.setDeactivationDate(LocalDateTime.now());
        
        user = userRepository.save(user);
        
        return ResponseEntity.ok(convertToDTO(user));
    }

    @PostMapping("/change-password")
    @Operation(
        summary = "Change user password",
        description = "Allows authenticated users to change their password. Requires current password for verification and the new password. The password must meet complexity requirements (minimum 8 characters, including upper and lowercase letters, numbers, and special characters).",
        tags = {"Authentication & Profile", "Security"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200", 
            description = "Password changed successfully",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"message\": \"Password changed successfully\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "400", 
            description = "Invalid request - Current password incorrect or new password invalid",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 400, \"error\": \"Bad Request\", \"message\": \"Current password is incorrect\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "401", 
            description = "Unauthorized - Invalid or expired JWT token",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 401, \"error\": \"Unauthorized\", \"message\": \"Invalid JWT token\"}"
                )
            )
        )
    })
    @RequiresAuthentication(message = "Authentication required to change your password")
    public ResponseEntity<?> changePassword(
            Authentication authentication,
            @Schema(description = "Password change request with current and new password", required = true,
                    example = "{\"currentPassword\": \"oldPassword123\", \"newPassword\": \"newPassword456\", \"confirmPassword\": \"newPassword456\"}")
            @Valid @RequestBody ChangePasswordRequest request) {
        String email = authentication.getName();
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Verify current password
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            return ResponseEntity.badRequest().body(Map.of("message", "Current password is incorrect"));
        }
        
        // Validate new password
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            return ResponseEntity.badRequest().body(Map.of("message", "New password and confirmation do not match"));
        }
        
        // Password strength validation could be added here
        
        // Update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    @PostMapping("/forgot-password")
    @Operation(
        summary = "Request password reset",
        description = "Initiates a password reset for the provided email address. If the email exists in the system, a password reset link will be sent to that address. This endpoint can be used by both email/password users and social login users. For social login users, this will enable email/password authentication.",
        tags = {"Authentication & Profile", "Security"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Request processed successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    type = "object",
                    example = "{\"message\": \"If the email exists in our system, a password reset link has been sent.\"}"
                ),
                examples = @ExampleObject(
                    value = "{\"message\": \"If the email exists in our system, a password reset link has been sent.\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request - Email format is invalid",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"message\": \"Email is required\", \"status\": 400, \"error\": \"Bad Request\"}"
                )
            )
        )
    })
    public ResponseEntity<?> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        // Extract the origin from the request to use in the password reset link
        // This allows the reset link to use the same domain the user is accessing from
        String requestOrigin = extractOriginFromRequest(httpRequest);

        passwordResetService.processForgotPasswordRequest(request, requestOrigin);

        // Always return success to prevent email enumeration attacks
        return ResponseEntity.ok(Map.of(
            "message", "If the email exists in our system, a password reset link has been sent."
        ));
    }

    /**
     * Extracts the origin (scheme + host + port) from the HTTP request
     * @param request The HTTP servlet request
     * @return The origin URL (e.g., "https://ats.ist.com")
     */
    private String extractOriginFromRequest(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isEmpty()) {
            scheme = request.getScheme();
        }

        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isEmpty()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isEmpty()) {
            host = request.getServerName();
        }

        // Build the origin URL
        StringBuilder origin = new StringBuilder();
        origin.append(scheme).append("://").append(host);

        // Only add port if it's not the default for the scheme
        int port = request.getServerPort();
        if ((scheme.equals("https") && port != 443) || (scheme.equals("http") && port != 80)) {
            if (!host.contains(":")) {  // Only add if not already in Host header
                origin.append(":").append(port);
            }
        }

        return origin.toString();
    }
    
    @PostMapping("/reset-password")
    @Operation(
        summary = "Reset password",
        description = "Resets a user's password using a valid reset token. The token must be valid, not expired, and not previously used. The password must meet complexity requirements. This endpoint requires no authentication as it's used in the password reset flow.",
        tags = {"Authentication & Profile", "Security"}
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Password reset successful",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    type = "object",
                    example = "{\"message\": \"Password has been reset successfully. You can now log in with your new password.\"}"
                ),
                examples = @ExampleObject(
                    value = "{\"message\": \"Password has been reset successfully. You can now log in with your new password.\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid request or token",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(
                    type = "object",
                    example = "{\"message\": \"Invalid or expired token. Please request a new password reset link.\"}"
                ),
                examples = @ExampleObject(
                    value = "{\"message\": \"Invalid or expired token. Please request a new password reset link.\"}"
                )
            )
        ),
        @ApiResponse(
            responseCode = "422",
            description = "Validation error - Password does not meet requirements",
            content = @Content(
                mediaType = "application/json",
                examples = @ExampleObject(
                    value = "{\"timestamp\": \"2024-05-20T10:00:00\", \"status\": 422, \"error\": \"Unprocessable Entity\", \"message\": \"Password must contain at least one uppercase letter, one lowercase letter, one number, and one special character\"}"
                )
            )
        )
    })
    public ResponseEntity<?> resetPassword(
        @Schema(description = "Reset password request containing the token and new password", required = true)
        @Valid @RequestBody ResetPasswordRequest request) {
        boolean success = passwordResetService.resetPassword(request);
        
        if (success) {
            return ResponseEntity.ok(Map.of(
                "message", "Password has been reset successfully. You can now log in with your new password."
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "Invalid or expired token. Please request a new password reset link."
            ));
        }
    }

    @PostMapping("/mfa/setup")
    @Operation(
        summary = "Set up MFA",
        description = "Initiates the setup of Multi-Factor Authentication for a user"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "MFA setup initiated successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = MfaSetupResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - invalid password"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found"
        )
    })
    @RequiresAuthentication(message = "You must be logged in to set up MFA")
    public ResponseEntity<?> setupMfa(@Valid @RequestBody MfaSetupRequest request, Authentication authentication) {
        String email = authentication.getName();
        MfaSetupResponse response = userService.setupMfa(email, request.getCurrentPassword());
        return ResponseEntity.ok(response);
    }
    
    @PostMapping("/mfa/verify")
    @Operation(
        summary = "Verify and enable MFA",
        description = "Verify a MFA code and enable MFA for the user"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "MFA enabled successfully"
        ),
        @ApiResponse(
            responseCode = "400",
            description = "Invalid verification code"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found"
        )
    })
    @RequiresAuthentication(message = "You must be logged in to verify MFA")
    public ResponseEntity<?> verifyAndEnableMfa(@Valid @RequestBody MfaVerifyRequest request, Authentication authentication) {
        String email = authentication.getName();
        boolean success = userService.verifyAndEnableMfa(email, request.getCode(), request.getSecret());
        
        if (success) {
            return ResponseEntity.ok().body(Map.of("message", "MFA enabled successfully"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid verification code"));
        }
    }
    
    @PostMapping("/mfa/disable")
    @Operation(
        summary = "Disable MFA",
        description = "Disable Multi-Factor Authentication for a user"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "MFA disabled successfully"
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized - invalid password"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found"
        )
    })
    @RequiresAuthentication(message = "You must be logged in to disable MFA")
    public ResponseEntity<?> disableMfa(@Valid @RequestBody MfaSetupRequest request, Authentication authentication) {
        String email = authentication.getName();
        boolean success = userService.disableMfa(email, request.getCurrentPassword());
        
        if (success) {
            return ResponseEntity.ok().body(Map.of("message", "MFA disabled successfully"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("message", "Failed to disable MFA"));
        }
    }
    
    @PostMapping("/mfa/login")
    @Operation(
        summary = "Login with MFA",
        description = "Complete the login process by providing a MFA code"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Login successful",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = AuthResponse.class)
            )
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Invalid MFA code"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found"
        )
    })
    public ResponseEntity<?> loginWithMfa(@Valid @RequestBody MfaLoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found"));
                
        boolean isValid = false;
        
        // Try verification code first
        if (request.getCode() != null && !request.getCode().isEmpty()) {
            isValid = userService.validateMfaCode(request.getEmail(), request.getCode());
        }
        
        // If verification code is invalid, try recovery code
        if (!isValid && request.getRecoveryCode() != null && !request.getRecoveryCode().isEmpty()) {
            isValid = userService.validateMfaRecoveryCode(request.getEmail(), request.getRecoveryCode());
        }
        
        if (!isValid) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Invalid verification code or recovery code"));
        }
        
        // Setup authentication
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))
        );
        
        SecurityContextHolder.getContext().setAuthentication(authentication);
        
        // Generate JWT
        String jwt = tokenProvider.generateToken(authentication);
        
        // Update last login
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);
        
        return ResponseEntity.ok(new AuthResponse(jwt, convertToDTO(user)));
    }

    @GetMapping("/2fa/status")
    @Operation(
        summary = "Get 2FA status",
        description = "Get the current 2FA status for the authenticated user"
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "2FA status retrieved successfully"
        ),
        @ApiResponse(
            responseCode = "401",
            description = "Unauthorized"
        ),
        @ApiResponse(
            responseCode = "404",
            description = "User not found"
        )
    })
    @RequiresAuthentication(message = "You must be logged in to check 2FA status")
    public ResponseEntity<?> get2FAStatus(Authentication authentication) {
        String email = authentication.getName();
        
        // Use a direct database query to bypass entity mapping issues
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        // Check if the user has a 2FA secret (which indicates 2FA is enabled)
        boolean isEnabled = user.getMfaSecret() != null && !user.getMfaSecret().isEmpty();
        
        return ResponseEntity.ok().body(Map.of("enabled", isEnabled));
    }

    private UserDTO convertToDTO(User user) {
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setEmail(user.getEmail());
        userDTO.setFirstName(user.getFirstName());
        userDTO.setLastName(user.getLastName());
        userDTO.setRole(user.getRole());
        userDTO.setDepartment(user.getDepartment());
        userDTO.setLinkedinId(user.getLinkedinId());
        userDTO.setLinkedinProfileUrl(user.getLinkedinProfileUrl());
        userDTO.setProfilePictureUrl(user.getProfilePictureUrl());
        userDTO.setIsActive(user.getIsActive());
        userDTO.setIsEmailVerified(user.getIsEmailVerified());
        userDTO.setIsEmailPasswordEnabled(user.getIsEmailPasswordEnabled());
        userDTO.setLastLogin(user.getLastLogin());
        
        // Include additional profile fields
        userDTO.setBirthDate(user.getBirthDate());
        userDTO.setPhoneNumber(user.getPhoneNumber());
        userDTO.setAddressLine1(user.getAddressLine1());
        userDTO.setAddressLine2(user.getAddressLine2());
        userDTO.setCity(user.getCity());
        userDTO.setState(user.getState());
        userDTO.setCountry(user.getCountry());
        userDTO.setPostalCode(user.getPostalCode());
        userDTO.setBio(user.getBio());
        userDTO.setDeactivationReason(user.getDeactivationReason());
        userDTO.setDeactivationDate(user.getDeactivationDate());
        userDTO.setMfaEnabled(user.getMfaEnabled());
        userDTO.setIsSubscribed(user.getIsSubscribed());
        userDTO.setPrivacyPolicyAccepted(user.getPrivacyPolicyAccepted());
        userDTO.setApplicationConsentGiven(user.getApplicationConsentGiven());
        userDTO.setApplicationConsentGivenAt(user.getApplicationConsentGivenAt());
        userDTO.setConnectConsentGiven(user.getConnectConsentGiven());
        userDTO.setConnectConsentGivenAt(user.getConnectConsentGivenAt());
        
        return userDTO;
    }
} 