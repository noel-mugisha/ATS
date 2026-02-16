package com.ats.service;

import com.ats.dto.ForgotPasswordRequest;
import com.ats.dto.ResetPasswordRequest;

/**
 * Service interface for managing password reset operations
 */
public interface PasswordResetService {
    
    /**
     * Process a forgot password request and send a reset email if the user exists
     * @param request The forgot password request containing the email
     * @return true if the process completed successfully (email found or not)
     */
    boolean processForgotPasswordRequest(ForgotPasswordRequest request);

    /**
     * Process a forgot password request and send a reset email if the user exists.
     *
     * This overloaded method accepts a requestOrigin parameter to support multi-domain deployments.
     * When the application is accessible from multiple domains (e.g., ats.ist.com and ats.ist.africa),
     * the password reset link should use the same domain the user is currently on, rather than a
     * hardcoded domain from environment variables. This ensures a consistent user experience.
     *
     * @param request The forgot password request containing the email
     * @param requestOrigin The origin domain from the HTTP request (e.g., "https://ats.ist.com").
     *                      If null, falls back to the configured frontend URL from environment variables.
     * @return true if the process completed successfully (email found or not)
     */
    boolean processForgotPasswordRequest(ForgotPasswordRequest request, String requestOrigin);
    
    /**
     * Reset a user's password using a valid token
     * @param request The reset password request containing the token and new password
     * @return true if the password was reset successfully
     */
    boolean resetPassword(ResetPasswordRequest request);
} 