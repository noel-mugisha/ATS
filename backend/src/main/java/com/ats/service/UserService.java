package com.ats.service;

import com.ats.dto.UserDTO;
import com.ats.dto.MfaSetupResponse;
import com.ats.model.User;
import com.ats.model.Role;
import org.springframework.security.core.Authentication;

import java.util.List;

public interface UserService {
    /**
     * Create a new user
     * @param userDTO The user data transfer object
     * @return The created user as DTO
     */
    UserDTO createUser(UserDTO userDTO);

    /**
     * Create a new user with multi-domain support for email links.
     *
     * This overloaded method accepts a requestOrigin parameter to support multi-domain deployments.
     * When creating users who will receive verification or invitation emails, the email links
     * (e.g., email verification, Connect consent) should use the domain the admin is currently on,
     * rather than a hardcoded domain. This ensures the new user gets emails with links to the
     * same domain the admin used to create their account.
     *
     * @param userDTO The user data transfer object
     * @param requestOrigin The origin domain from the HTTP request (e.g., "https://ats.ist.com").
     *                      If null, falls back to the configured frontend URL from environment variables.
     * @return The created user as DTO
     */
    UserDTO createUser(UserDTO userDTO, String requestOrigin);

    UserDTO updateUser(Long id, UserDTO userDTO);
    UserDTO getUserById(Long id);
    UserDTO getUserByEmail(String email);
    List<UserDTO> getAllUsers();
    void deleteUser(Long id);
    UserDTO updateUserStatus(Long id, boolean isActive);
    UserDTO updateUserStatus(Long id, boolean isActive, Authentication authentication);
    UserDTO updateUserRole(Long id, Role role);
    UserDTO assignRegion(Long id, String region);
    UserDTO deactivateAccount(Long id, String reason);
    UserDTO deactivateAccount(Long id, String reason, Authentication authentication);
    
    // 2FA methods
    MfaSetupResponse setupMfa(String email, String currentPassword);
    boolean verifyAndEnableMfa(String email, String code, String secret);
    boolean disableMfa(String email, String currentPassword);
    boolean validateMfaCode(String email, String code);
    boolean validateMfaRecoveryCode(String email, String recoveryCode);
    
    // Utility methods
    UserDTO convertToDTO(User user);
} 