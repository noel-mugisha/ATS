package com.ats.service.impl;

import com.ats.dto.ForgotPasswordRequest;
import com.ats.dto.ResetPasswordRequest;
import com.ats.model.PasswordResetToken;
import com.ats.model.User;
import com.ats.repository.PasswordResetTokenRepository;
import com.ats.repository.UserRepository;
import com.ats.service.EmailService;
import com.ats.service.PasswordResetService;
import com.ats.util.TokenUtil;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetServiceImpl implements PasswordResetService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public boolean processForgotPasswordRequest(ForgotPasswordRequest request) {
        return processForgotPasswordRequest(request, null);
    }

    @Override
    @Transactional
    public boolean processForgotPasswordRequest(ForgotPasswordRequest request, String requestOrigin) {
        // Get user by email
        Optional<User> userOptional = userRepository.findByEmail(request.getEmail());

        // If user doesn't exist, return true (for security reasons, don't reveal if email exists)
        if (userOptional.isEmpty()) {
            log.info("Forgot password request for non-existent email: {}", request.getEmail());
            return true;
        }

        User user = userOptional.get();

        // If user signed up with LinkedIn and doesn't have password enabled yet, we'll enable it
        if (user.getIsEmailPasswordEnabled() == null || !user.getIsEmailPasswordEnabled()) {
            log.info("Enabling email/password auth for LinkedIn user: {}", user.getEmail());
            user.setIsEmailPasswordEnabled(true);
            user = userRepository.save(user);
        }

        // Create token
        PasswordResetToken resetToken = TokenUtil.createPasswordResetToken(user);
        resetToken = tokenRepository.save(resetToken);

        // Send email
        try {
            emailService.sendPasswordResetEmail(user.getEmail(), resetToken.getToken(), user, requestOrigin);
            log.info("Password reset email sent to: {} using origin: {}", user.getEmail(), requestOrigin);
            return true;
        } catch (MessagingException e) {
            log.error("Failed to send password reset email to: {}", user.getEmail(), e);
            return false;
        }
    }

    @Override
    @Transactional
    public boolean resetPassword(ResetPasswordRequest request) {
        // Validate token
        Optional<PasswordResetToken> tokenOptional = tokenRepository.findByToken(request.getToken());
        
        if (tokenOptional.isEmpty()) {
            log.warn("Password reset attempted with invalid token: {}", request.getToken());
            return false;
        }
        
        PasswordResetToken token = tokenOptional.get();
        
        // Check if token is expired or already used
        if (token.isExpired() || token.getIsUsed()) {
            log.warn("Password reset attempted with expired or already used token: {}", request.getToken());
            return false;
        }
        
        // Check if passwords match
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            log.warn("Password reset failed due to password mismatch for user: {}", token.getUser().getEmail());
            return false;
        }
        
        // Get user
        User user = token.getUser();
        
        // Update password
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        
        // Make sure email/password auth is enabled
        user.setIsEmailPasswordEnabled(true);
        
        // Save user
        userRepository.save(user);
        
        // Mark token as used
        token.setIsUsed(true);
        tokenRepository.save(token);
        
        log.info("Password successfully reset for user: {}", user.getEmail());
        return true;
    }
} 