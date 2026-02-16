package com.ats.service.impl;

import com.ats.dto.UserDTO;
import com.ats.exception.ResourceAlreadyExistsException;
import com.ats.exception.ResourceNotFoundException;
import com.ats.model.User;
import com.ats.model.Role;
import com.ats.model.Region;
import com.ats.repository.UserRepository;
import com.ats.service.EmailService;
import com.ats.service.UserService;
import com.ats.service.RegionalDataFilterService;
import com.ats.util.TokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ats.dto.MfaSetupResponse;
import com.ats.service.TotpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TotpService totpService;
    private final RegionalDataFilterService regionalDataFilterService;

    @Autowired
    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, EmailService emailService, TotpService totpService, RegionalDataFilterService regionalDataFilterService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.totpService = totpService;
        this.regionalDataFilterService = regionalDataFilterService;
    }

    @Override
    @Transactional
    public UserDTO createUser(UserDTO userDTO) {
        return createUser(userDTO, null);
    }

    @Override
    @Transactional
    public UserDTO createUser(UserDTO userDTO, String requestOrigin) {
        if (userRepository.existsByEmail(userDTO.getEmail())) {
            throw new ResourceAlreadyExistsException("Email already exists");
        }
        if (userDTO.getLinkedinId() != null && userRepository.existsByLinkedinId(userDTO.getLinkedinId())) {
            throw new ResourceAlreadyExistsException("LinkedIn ID already exists");
        }

        User user = new User();
        user.setEmail(userDTO.getEmail());
        
        if (userDTO.getPassword() != null && !userDTO.getPassword().isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(userDTO.getPassword()));
        }
        
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setRole(userDTO.getRole());
        user.setDepartment(userDTO.getDepartment());
        user.setRegion(userDTO.getRegion());
        user.setLinkedinId(userDTO.getLinkedinId());
        user.setLinkedinProfileUrl(userDTO.getLinkedinProfileUrl());
        user.setProfilePictureUrl(userDTO.getProfilePictureUrl());
        user.setIsEmailPasswordEnabled(userDTO.getIsEmailPasswordEnabled() != null ? userDTO.getIsEmailPasswordEnabled() : true);
        user.setIsActive(userDTO.getIsActive() != null ? userDTO.getIsActive() : true);
        
        // For admin-created CANDIDATE users, set up Connect consent flow
        // Note: This method is called when admin creates users, NOT for self-signup
        boolean isAdminCreatedCandidate = userDTO.getRole() == Role.CANDIDATE;
        if (isAdminCreatedCandidate) {
            // Admin-created candidates need to accept privacy policy and Connect consent
            user.setPrivacyPolicyAccepted(false);
            user.setConnectConsentGiven(false);
            // Generate Connect consent token
            TokenUtil.generateConnectConsentToken(user);
        } else {
            // For other roles created by admin, privacy policy is handled differently
            user.setPrivacyPolicyAccepted(userDTO.getPrivacyPolicyAccepted() != null ? userDTO.getPrivacyPolicyAccepted() : false);
        }
        
        // Handle email verification based on request
        boolean shouldSendVerificationEmail = userDTO.getSendVerificationEmail() != null ? userDTO.getSendVerificationEmail() : false;
        if (shouldSendVerificationEmail) {
            // Use TokenUtil to generate verification token
            TokenUtil.generateVerificationToken(user);
        } else {
            // Otherwise, use the value from the DTO or default to false
            user.setIsEmailVerified(userDTO.getIsEmailVerified() != null ? userDTO.getIsEmailVerified() : false);
        }
        
        User savedUser = userRepository.save(user);
        
        // Send appropriate email based on user type
        if (shouldSendVerificationEmail && savedUser.getEmailVerificationToken() != null) {
            if (isAdminCreatedCandidate && savedUser.getConnectConsentToken() != null) {
                // Admin-created CANDIDATE: Use invitation email with Connect consent link
                try {
                    emailService.sendAdminCreatedUserInvitation(savedUser, savedUser.getEmailVerificationToken(), savedUser.getConnectConsentToken(), requestOrigin);
                    logger.info("Sent admin-created user invitation email to: {}", savedUser.getEmail());
                } catch (Exception e) {
                    logger.error("Failed to send invitation email to " + savedUser.getEmail(), e);
                }
            } else {
                // Admin-created non-CANDIDATE roles: Use regular verification email
                // (This should rarely happen, but covers cases like admin creating INTERVIEWER, etc.)
                try {
                    emailService.sendNewUserVerificationEmail(savedUser, savedUser.getEmailVerificationToken(), requestOrigin);
                    logger.info("Sent verification email to admin-created user: {}", savedUser.getEmail());
                } catch (Exception e) {
                    logger.error("Failed to send verification email to " + savedUser.getEmail(), e);
                }
            }
        }
        
        return convertToDTO(savedUser);
    }

    @Override
    @Transactional
    public UserDTO updateUser(Long id, UserDTO userDTO) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Do not allow changing email address
        if (!user.getEmail().equals(userDTO.getEmail())) {
            throw new IllegalArgumentException("Email address cannot be changed");
        }

        // Update basic information
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        
        // Update address information
        user.setBirthDate(userDTO.getBirthDate());
        user.setPhoneNumber(userDTO.getPhoneNumber());
        user.setAddressLine1(userDTO.getAddressLine1());
        user.setAddressLine2(userDTO.getAddressLine2());
        user.setCity(userDTO.getCity());
        user.setState(userDTO.getState());
        user.setCountry(userDTO.getCountry());
        user.setPostalCode(userDTO.getPostalCode());
        user.setBio(userDTO.getBio());
        
        // Only allow admins to change these fields
        if (isCurrentUserAdmin()) {
            user.setDepartment(userDTO.getDepartment());
            user.setRole(userDTO.getRole());
            user.setIsActive(userDTO.getIsActive() != null ? userDTO.getIsActive() : user.getIsActive());
        }

        // Update LinkedIn information if provided
        if (userDTO.getLinkedinProfileUrl() != null) {
            user.setLinkedinProfileUrl(userDTO.getLinkedinProfileUrl());
        }
        
        // Update profile picture if provided
        if (userDTO.getProfilePictureUrl() != null) {
            user.setProfilePictureUrl(userDTO.getProfilePictureUrl());
        }

        // Handle password update if provided
        if (userDTO.getPassword() != null && !userDTO.getPassword().isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(userDTO.getPassword()));
        }

        User updatedUser = userRepository.save(user);
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO getUserById(Long id) {
        return userRepository.findById(id)
            .map(this::convertToDTO)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDTO getUserByEmail(String email) {
        return userRepository.findByEmail(email)
            .map(this::convertToDTO)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDTO> getAllUsers() {
        // Get current user for regional filtering
        User currentUser = getCurrentUser();
        
        if (currentUser == null) {
            logger.warn("No current user found for regional filtering");
            return userRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        }
        
        // Get view mode from session (for EU admins who can switch)
        Boolean viewingAsNonEU = regionalDataFilterService.getViewModeFromSession(currentUser);
        String effectiveFilter = regionalDataFilterService.getEffectiveRegionFilter(currentUser, viewingAsNonEU);
        
        if (effectiveFilter == null) {
            // No regional restrictions
            return userRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        }
        
        // Apply regional filtering
        return userRepository.findAll().stream()
            .filter(user -> {
                String userRegion = user.getRegion();
                
                // EU admin viewing as non-EU: show non-EU users
                if (regionalDataFilterService.isEUAdmin(currentUser) && Boolean.TRUE.equals(viewingAsNonEU)) {
                    return !"EU".equals(userRegion);
                }
                
                // EU admin in default mode: show only EU users
                if (regionalDataFilterService.isEUAdmin(currentUser)) {
                    return "EU".equals(userRegion);
                }
                
                // Non-EU admins can only see non-EU users
                if (regionalDataFilterService.isNonEUAdmin(currentUser)) {
                    return !"EU".equals(userRegion);
                }
                
                return true; // Fallback
            })
            .map(this::convertToDTO)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User not found");
        }
        userRepository.deleteById(id);
    }

    @Override
    @Transactional
    public UserDTO updateUserStatus(Long id, boolean isActive) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setIsActive(isActive);
        User updatedUser = userRepository.save(user);
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional
    public UserDTO updateUserStatus(Long id, boolean isActive, Authentication authentication) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        
        // Get current user from authentication
        String currentUserEmail = authentication.getName();
        User currentUser = userRepository.findByEmail(currentUserEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
        
        // Prevent admin from deactivating their own account
        if (currentUser.getId().equals(id) && currentUser.getRole() == Role.ADMIN && !isActive) {
            throw new IllegalArgumentException("Cannot deactivate your own admin account. Please ask another admin to do this.");
        }
        
        user.setIsActive(isActive);
        User updatedUser = userRepository.save(user);
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional
    public UserDTO updateUserRole(Long id, Role role) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setRole(role);
        User updatedUser = userRepository.save(user);
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional
    public UserDTO assignRegion(Long id, String region) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        
        // Get current user for permission checking
        User currentUser = getCurrentUser();
        if (currentUser == null) {
            throw new IllegalArgumentException("Current user not found");
        }
        
        // Validate region if provided
        if (region != null && !region.trim().isEmpty()) {
            if (!Region.isValid(region)) {
                throw new IllegalArgumentException("Invalid region. Must be EU, RW, OTHER, or null.");
            }
            
            // EU region assignment restriction: Only EU admins can assign EU region
            if ("EU".equals(region)) {
                if (!regionalDataFilterService.isEUAdmin(currentUser)) {
                    throw new IllegalArgumentException("Only EU administrators can assign EU region to users.");
                }
            }
            
            user.setRegion(region);
        } else {
            user.setRegion(null);
        }
        
        User updatedUser = userRepository.save(user);
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional
    public UserDTO deactivateAccount(Long id, String reason) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setIsActive(false);
        user.setDeactivationReason(reason);
        user.setDeactivationDate(LocalDateTime.now());
        User updatedUser = userRepository.save(user);
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional
    public UserDTO deactivateAccount(Long id, String reason, Authentication authentication) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        
        // Get current user from authentication
        String currentUserEmail = authentication.getName();
        User currentUser = userRepository.findByEmail(currentUserEmail)
            .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
        
        // Prevent admin from deactivating their own account
        if (currentUser.getId().equals(id) && currentUser.getRole() == Role.ADMIN) {
            throw new IllegalArgumentException("Cannot deactivate your own admin account. Please ask another admin to do this.");
        }
        
        user.setIsActive(false);
        user.setDeactivationReason(reason);
        user.setDeactivationDate(LocalDateTime.now());
        User updatedUser = userRepository.save(user);
        return convertToDTO(updatedUser);
    }

    @Override
    @Transactional
    public MfaSetupResponse setupMfa(String email, String currentPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                
        // Verify the user's password
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RuntimeException("Invalid password");
        }
        
        // Generate MFA secret and QR code
        String secret = totpService.generateSecret();
        String qrCodeImageUrl = totpService.generateQrCodeImageUrl(email, secret);
        String[] recoveryCodes = totpService.generateRecoveryCodes();
        
        // Return the setup information without saving to DB yet
        // Will be saved only after user confirms by verifying a code
        return new MfaSetupResponse(secret, qrCodeImageUrl, recoveryCodes);
    }
    
    @Override
    @Transactional
    public boolean verifyAndEnableMfa(String email, String code, String secret) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                
        // Verify the provided code is valid
        if (!totpService.validateCode(code, secret)) {
            return false;
        }
        
        // Generate recovery codes
        String[] recoveryCodes = totpService.generateRecoveryCodes();
        
        // Save MFA settings to user
        user.setMfaEnabled(true);
        user.setMfaSecret(secret);
        user.setMfaRecoveryCodes(recoveryCodes);
        userRepository.save(user);
        
        return true;
    }
    
    @Override
    @Transactional
    public boolean disableMfa(String email, String currentPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                
        // Verify the user's password
        if (user.getPasswordHash() == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new RuntimeException("Invalid password");
        }
        
        // Disable MFA
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaRecoveryCodes(null);
        userRepository.save(user);
        
        return true;
    }
    
    @Override
    public boolean validateMfaCode(String email, String code) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                
        if (!user.getMfaEnabled() || user.getMfaSecret() == null) {
            throw new RuntimeException("MFA is not enabled for this user");
        }
        
        return totpService.validateCode(code, user.getMfaSecret());
    }
    
    @Override
    @Transactional
    public boolean validateMfaRecoveryCode(String email, String recoveryCode) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
                
        if (!user.getMfaEnabled() || user.getMfaRecoveryCodes() == null) {
            throw new RuntimeException("MFA is not enabled for this user");
        }
        
        String[] updatedCodes = totpService.validateAndRemoveRecoveryCode(recoveryCode, user.getMfaRecoveryCodes());
        
        if (updatedCodes != null) {
            user.setMfaRecoveryCodes(updatedCodes);
            userRepository.save(user);
            return true;
        }
        
        return false;
    }

    @Override
    public UserDTO convertToDTO(User user) {
        logger.debug("=== MFA DEBUG START ===");
        logger.debug("Converting user to DTO for email: {}", user.getEmail());
        logger.debug("user.getMfaEnabled() = {}", user.getMfaEnabled());
        logger.debug("user.getMfaSecret() = {}", (user.getMfaSecret() != null ? "NOT NULL (length: " + user.getMfaSecret().length() + ")" : "NULL"));
        logger.debug("=== MFA DEBUG END ===");
        
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setEmail(user.getEmail());
        dto.setFirstName(user.getFirstName());
        dto.setLastName(user.getLastName());
        dto.setRole(user.getRole());
        dto.setDepartment(user.getDepartment());
        dto.setRegion(user.getRegion());
        dto.setLinkedinId(user.getLinkedinId());
        dto.setLinkedinProfileUrl(user.getLinkedinProfileUrl());
        dto.setProfilePictureUrl(user.getProfilePictureUrl());
        dto.setBirthDate(user.getBirthDate());
        dto.setPhoneNumber(user.getPhoneNumber());
        dto.setAddressLine1(user.getAddressLine1());
        dto.setAddressLine2(user.getAddressLine2());
        dto.setCity(user.getCity());
        dto.setState(user.getState());
        dto.setCountry(user.getCountry());
        dto.setPostalCode(user.getPostalCode());
        dto.setBio(user.getBio());
        dto.setDeactivationReason(user.getDeactivationReason());
        dto.setDeactivationDate(user.getDeactivationDate());
        dto.setIsEmailPasswordEnabled(user.getIsEmailPasswordEnabled());
        dto.setLastLogin(user.getLastLogin());
        dto.setIsActive(user.getIsActive());
        dto.setIsEmailVerified(user.getIsEmailVerified());
        dto.setMfaEnabled(user.getMfaEnabled());
        dto.setIsSubscribed(user.getIsSubscribed());
        dto.setPrivacyPolicyAccepted(user.getPrivacyPolicyAccepted());
        return dto;
    }
    
    /**
     * Get the current authenticated user
     */
    private User getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) {
                return null;
            }
            
            String email = authentication.getName();
            return userRepository.findByEmail(email).orElse(null);
        } catch (Exception e) {
            logger.error("Error getting current user", e);
            return null;
        }
    }
    
    /**
     * Check if the current user is an admin
     */
    private boolean isCurrentUserAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && 
               authentication.getAuthorities().stream()
                   .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
} 