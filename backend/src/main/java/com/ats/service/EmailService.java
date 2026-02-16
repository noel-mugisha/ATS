package com.ats.service;

import com.ats.dto.BulkEmailRequestDTO;
import com.ats.dto.BulkEmailResponseDTO;
import com.ats.model.EmailNotification;
import com.ats.model.User;
import com.ats.model.Application;
import com.ats.model.EmailEvent;
import com.ats.model.Job;
import com.ats.model.RecipientType;
import com.ats.model.Interview;
import com.ats.repository.EmailNotificationRepository;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * Service interface for handling email operations.
 *
 * <h2>Multi-Domain Support</h2>
 * <p>
 * Many email methods have overloaded versions that accept a {@code requestOrigin} parameter.
 * This supports deployments where the application is accessible from multiple domains
 * (e.g., ats.ist.com and ats.ist.africa). The requestOrigin ensures that email links
 * (verification, password reset, etc.) use the same domain the user is currently on,
 * rather than a hardcoded domain from environment variables.
 * </p>
 * <p>
 * If {@code requestOrigin} is null, the methods fall back to the configured frontend URL.
 * </p>
 */
public interface EmailService {
    
    /**
     * Sends a verification email and saves the notification in the database
     * @param to The recipient email address
     * @param token The verification token
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendVerificationEmail(String to, String token) throws MessagingException;

    /**
     * Sends a verification email using the requesting domain (for multi-domain support).
     *
     * @param to The recipient email address
     * @param token The verification token
     * @param requestOrigin The origin domain from the HTTP request (e.g., "https://ats.ist.com").
     *                      Used to generate verification links with the same domain the user is on.
     *                      If null, falls back to configured frontend URL.
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendVerificationEmail(String to, String token, String requestOrigin) throws MessagingException;
    
    /**
     * Sends a password reset email with a token
     * @param to The recipient email address
     * @param token The password reset token
     * @param user The user to send the email to
     * @return The created EmailNotification entity
     */
    EmailNotification sendPasswordResetEmail(String to, String token, User user) throws MessagingException;

    /**
     * Sends a password reset email with a token using the requesting domain (for multi-domain support).
     *
     * @param to The recipient email address
     * @param token The password reset token
     * @param user The user to send the email to
     * @param requestOrigin The origin domain from the HTTP request (e.g., "https://ats.ist.com").
     *                      Used to generate reset links with the same domain the user is on.
     *                      If null, falls back to configured frontend URL.
     * @return The created EmailNotification entity
     */
    EmailNotification sendPasswordResetEmail(String to, String token, User user, String requestOrigin) throws MessagingException;
    
    /**
     * Sends a verification email to a new user created by an admin
     * @param user The user to send the email to
     * @param token The verification token
     * @return The created EmailNotification entity
     */
    EmailNotification sendNewUserVerificationEmail(User user, String token) throws MessagingException;

    /**
     * Sends a verification email to a new user using the requesting domain (for multi-domain support).
     *
     * @param user The user to send the email to
     * @param token The verification token
     * @param requestOrigin The origin domain from the HTTP request (e.g., "https://ats.ist.com").
     *                      Used to generate verification links with the same domain used for signup.
     *                      If null, falls back to configured frontend URL.
     * @return The created EmailNotification entity
     */
    EmailNotification sendNewUserVerificationEmail(User user, String token, String requestOrigin) throws MessagingException;
    
    /**
     * Sends an invitation email to admin-created users with Connect consent link
     * @param user The user to send the email to
     * @param verificationToken The email verification token
     * @param connectConsentToken The Connect consent token
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendAdminCreatedUserInvitation(User user, String verificationToken, String connectConsentToken) throws MessagingException;

    /**
     * Sends an invitation email to admin-created users using the requesting domain (for multi-domain support).
     *
     * @param user The user to send the email to
     * @param verificationToken The email verification token
     * @param connectConsentToken The Connect consent token
     * @param requestOrigin The origin domain from the HTTP request (e.g., "https://ats.ist.com").
     *                      Used to generate invitation/consent links with the same domain the admin is using.
     *                      If null, falls back to configured frontend URL.
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendAdminCreatedUserInvitation(User user, String verificationToken, String connectConsentToken, String requestOrigin) throws MessagingException;

    /**
     * Sends an email based on an existing EmailNotification record
     * @param notification The EmailNotification to resend
     * @return The updated EmailNotification
     */
    EmailNotification sendEmailFromNotification(EmailNotification notification);

    /**
     * Sends an application-related email based on the event type
     * @param application The application involved in the event
     * @param event The type of email event
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendApplicationEmail(Application application, EmailEvent event) throws MessagingException;

    /**
     * Sends an interview-related email based on the event type
     * @param interview The interview involved in the event
     * @param event The type of email event
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendInterviewEmail(Interview interview, EmailEvent event) throws MessagingException;

    /**
     * Sends bulk emails to applicants based on filter criteria
     * @param request The bulk email request containing filters and content
     * @param senderUser The admin user sending the emails
     * @return The bulk email response with statistics
     */
    BulkEmailResponseDTO sendBulkEmailToApplicants(BulkEmailRequestDTO request, User senderUser);

    /**
     * Gets the list of applications that would be targeted by the bulk email filters
     * @param jobId Optional job ID filter
     * @param status Optional application status filter
     * @return List of applications that match the criteria
     */
    List<Application> getApplicantsForBulkEmail(Long jobId, com.ats.model.ApplicationStatus status);

    /**
     * Sends a custom email to a specific recipient
     * @param to Recipient email address
     * @param subject Email subject
     * @param content Email content
     * @param isHtml Whether the content is HTML
     * @param senderUser The user sending the email
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendCustomEmail(String to, String subject, String content, Boolean isHtml, User senderUser) throws MessagingException;
    
    /**
     * Sends an email with a calendar attachment
     * @param to Recipient email address
     * @param subject Email subject
     * @param content Email content
     * @param calendarContent ICS calendar content
     * @param attachmentName Name for the calendar attachment
     * @param job Related job (for region determination)
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendEmailWithCalendarAttachment(String to, String subject, String content, 
                                                    String calendarContent, String attachmentName, Job job) throws MessagingException;
    
    /**
     * Sends a custom job offer email to a candidate
     * @param application The application (for candidate info and job region)
     * @param customSubject Custom email subject
     * @param customContent Custom email content with {{candidateName}} placeholder
     * @return The created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    EmailNotification sendCustomJobOfferEmail(Application application, String customSubject, String customContent) throws MessagingException;
} 