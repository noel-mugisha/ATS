package com.ats.service.impl;

import com.ats.model.EmailNotification;
import com.ats.model.User;
import com.ats.model.Application;
import com.ats.model.EmailEvent;
import com.ats.model.Interview;
import com.ats.model.Job;
import com.ats.model.LocationType;
import com.ats.model.RecipientType;
import com.ats.repository.EmailNotificationRepository;
import com.ats.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.activation.DataSource;
import jakarta.mail.util.ByteArrayDataSource;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.time.ZonedDateTime;

import com.ats.dto.BulkEmailRequestDTO;
import com.ats.dto.BulkEmailResponseDTO;
import com.ats.model.ApplicationStatus;
import com.ats.repository.ApplicationRepository;
import com.ats.service.SubscriptionService;
import com.ats.service.mail.MailProvider;
import com.ats.service.mail.MailProviderFactory;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {
    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final EmailNotificationRepository emailNotificationRepository;
    private final ApplicationRepository applicationRepository;
    private final SubscriptionService subscriptionService;
    private final MailProviderFactory mailProviderFactory;

    @Value("${app.frontend.url}")
    private String frontendUrl;

    /**
     * Email event configuration
     */
    private static class EventConfig {
        final RecipientType recipientType;
        final String subjectTemplate;

        EventConfig(RecipientType recipientType, String subjectTemplate) {
            this.recipientType = recipientType;
            this.subjectTemplate = subjectTemplate;
        }
    }

    // Event configuration mapping
    private final Map<EmailEvent, EventConfig> eventConfigs = Map.of(
        EmailEvent.APPLICATION_RECEIVED, new EventConfig(RecipientType.CANDIDATE, "Application Received - %s"),
        EmailEvent.APPLICATION_REVIEWED, new EventConfig(RecipientType.CANDIDATE, "Application Status Update - %s"),
        EmailEvent.APPLICATION_SHORTLISTED, new EventConfig(RecipientType.CANDIDATE, "Congratulations! You've Been Shortlisted - %s"),
        EmailEvent.INTERVIEW_ASSIGNED_TO_INTERVIEWER, new EventConfig(RecipientType.INTERVIEWER, "New Interview Assignment - %s"),
        EmailEvent.INTERVIEW_ASSIGNED_TO_CANDIDATE, new EventConfig(RecipientType.CANDIDATE, "Interview Scheduled - %s"),
        EmailEvent.INTERVIEW_CANCELLED_TO_CANDIDATE, new EventConfig(RecipientType.CANDIDATE, "Interview Cancelled - %s"),
        EmailEvent.INTERVIEW_CANCELLED_TO_INTERVIEWER, new EventConfig(RecipientType.INTERVIEWER, "Interview Assignment Cancelled - %s"),
        EmailEvent.JOB_OFFER, new EventConfig(RecipientType.CANDIDATE, "Job Offer - %s")
    );

    /**
     * Determines the region for job-related emails based on the job's region.
     * For job-related emails, this ensures we use the correct regional mail provider.
     * 
     * @param job The job to get region from (can be null)
     * @return The region string or null for default provider
     */
    private String determineRegionFromJob(Job job) {
        if (job != null && job.getRegion() != null) {
            return job.getRegion();
        }
        return "DEFAULT_JOB_REGION"; // Will use default provider (AWS SES with no-reply.ist.africa)
    }

    /**
     * Generic method to send an email with a template and save notification.
     * This version uses the DEFAULT provider (no-reply.ats.ist.com) for user-related emails.
     * 
     * @param to Recipient email address
     * @param subject Email subject
     * @param templateName Template name to use
     * @param templateVariables Variables to pass to the template
     * @param user Related user (optional)
     * @return Created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    @Transactional
    private EmailNotification sendTemplateEmail(String to, String subject, String templateName, 
            Map<String, Object> templateVariables, User user) throws MessagingException {
        // For user-related emails, use null region (default provider with no-reply.ats.ist.com)
        return sendTemplateEmailWithRegion(to, subject, templateName, templateVariables, user, null);
    }
    
    /**
     * Generic method to send an email with a template and save notification.
     * This version uses the JOB's region for determining the mail provider.
     * 
     * @param to Recipient email address
     * @param subject Email subject
     * @param templateName Template name to use
     * @param templateVariables Variables to pass to the template
     * @param user Related user (optional)
     * @param job Related job (for region determination)
     * @return Created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    @Transactional
    private EmailNotification sendTemplateEmailWithJob(String to, String subject, String templateName, 
            Map<String, Object> templateVariables, User user, Job job) throws MessagingException {
        // For job-related emails, use job's region
        String region = determineRegionFromJob(job);
        return sendTemplateEmailWithRegion(to, subject, templateName, templateVariables, user, region);
    }
    
    /**
     * Core method to send an email with a template and save notification.
     * 
     * @param to Recipient email address
     * @param subject Email subject
     * @param templateName Template name to use
     * @param templateVariables Variables to pass to the template
     * @param user Related user (optional)
     * @param region Region for mail provider selection (null for default)
     * @return Created EmailNotification entity
     * @throws MessagingException If there's an error sending the email
     */
    @Transactional
    private EmailNotification sendTemplateEmailWithRegion(String to, String subject, String templateName, 
            Map<String, Object> templateVariables, User user, String region) throws MessagingException {
        
        // Create Thymeleaf context and add variables
        Context context = new Context();
        templateVariables.forEach(context::setVariable);
        
        // Process template
        String emailContent = templateEngine.process(templateName, context);

        // Select mail provider based on region
        MailProvider mailProvider = mailProviderFactory.getProvider(region);
        String from = mailProvider.getDefaultFromAddress();
        
        // Create email notification record
        EmailNotification.EmailNotificationBuilder builder = EmailNotification.builder()
                .recipientEmail(to)
                .subject(subject)
                .body(emailContent)
                .templateName(templateName)
                .status(EmailNotification.EmailStatus.PENDING)
                .retryCount(0);
                
        if (user != null) {
            builder.relatedUser(user);
        }
        
        EmailNotification notification = builder.build();
        
        // Save notification first
        notification = emailNotificationRepository.save(notification);

        try {
            // Try to send the email with use of region-aware mail provider
            mailProvider.sendEmail(to, from, subject, emailContent);
            
            // Update status to SENT
            notification.setStatus(EmailNotification.EmailStatus.SENT);
            return emailNotificationRepository.save(notification);
        } catch (MessagingException e) {
            // Update status to FAILED and save error message
            notification.setStatus(EmailNotification.EmailStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            notification = emailNotificationRepository.save(notification);
            
            // Rethrow the exception for the caller to handle if needed
            throw e;
        } catch (Exception e) {
            // Update status to FAILED and save error message for non-MessagingException
            notification.setStatus(EmailNotification.EmailStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            notification = emailNotificationRepository.save(notification);
            
            // Convert to MessagingException to maintain compatibility with existing method signatures
            MessagingException messagingException = new MessagingException("Failed to send email", e);
            throw messagingException;
        }
    }

    @Override
    @Transactional
    public EmailNotification sendVerificationEmail(String to, String token) throws MessagingException {
        return sendVerificationEmail(to, token, null);
    }

    @Override
    @Transactional
    public EmailNotification sendVerificationEmail(String to, String token, String requestOrigin) throws MessagingException {
        // Use requestOrigin if provided, otherwise fall back to configured frontendUrl
        String baseUrl = (requestOrigin != null && !requestOrigin.isEmpty()) ? requestOrigin : frontendUrl;

        Map<String, Object> templateVars = Map.of(
            "verificationLink", baseUrl + "/verify-email?token=" + token
        );

        return sendTemplateEmail(to, "Verify your email address", "verification-email", templateVars, null);
    }
    
    @Override
    @Transactional
    public EmailNotification sendPasswordResetEmail(String to, String token, User user) throws MessagingException {
        return sendPasswordResetEmail(to, token, user, null);
    }

    @Override
    @Transactional
    public EmailNotification sendPasswordResetEmail(String to, String token, User user, String requestOrigin) throws MessagingException {
        // Use requestOrigin if provided, otherwise fall back to configured frontendUrl
        String baseUrl = (requestOrigin != null && !requestOrigin.isEmpty()) ? requestOrigin : frontendUrl;

        Map<String, Object> templateVars = Map.of(
            "resetLink", baseUrl + "/reset-password?token=" + token
        );

        return sendTemplateEmail(to, "Reset Your Password", "password-reset-email", templateVars, user);
    }
    
    @Override
    @Transactional
    public EmailNotification sendNewUserVerificationEmail(User user, String token) throws MessagingException {
        return sendNewUserVerificationEmail(user, token, null);
    }

    @Override
    @Transactional
    public EmailNotification sendNewUserVerificationEmail(User user, String token, String requestOrigin) throws MessagingException {
        // Use requestOrigin if provided, otherwise fall back to configured frontendUrl
        String baseUrl = (requestOrigin != null && !requestOrigin.isEmpty()) ? requestOrigin : frontendUrl;

        Map<String, Object> templateVars = Map.of(
            "verificationLink", baseUrl + "/verify-email?token=" + token,
            "userName", user.getFirstName()
        );

        return sendTemplateEmail(user.getEmail(), "Verify your email address - IST", "new-user-email", templateVars, user);
    }

    @Override
    @Transactional
    public EmailNotification sendAdminCreatedUserInvitation(User user, String verificationToken, String connectConsentToken) throws MessagingException {
        return sendAdminCreatedUserInvitation(user, verificationToken, connectConsentToken, null);
    }

    @Override
    @Transactional
    public EmailNotification sendAdminCreatedUserInvitation(User user, String verificationToken, String connectConsentToken, String requestOrigin) throws MessagingException {
        // Use requestOrigin if provided, otherwise fall back to configured frontendUrl
        String baseUrl = (requestOrigin != null && !requestOrigin.isEmpty()) ? requestOrigin : frontendUrl;

        Map<String, Object> templateVars = new HashMap<>();
        // Single link with both tokens - user will accept consent first, then verify email
        templateVars.put("setupLink", baseUrl + "/accept-connect-consent?token=" + connectConsentToken + "&verificationToken=" + verificationToken);
        templateVars.put("userName", user.getFirstName());
        templateVars.put("privacyPolicyUrl", baseUrl + "/privacy-policy");

        return sendTemplateEmail(user.getEmail(), "Welcome to IST - Complete Your Account Setup", "admin-created-user-invitation", templateVars, user);
    }

    @Override
    @Transactional
    public EmailNotification sendEmailFromNotification(EmailNotification notification) {
        try {
            // Create a MimeMessage from the notification
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            
            helper.setTo(notification.getRecipientEmail());
            helper.setSubject(notification.getSubject());
            helper.setText(notification.getBody(), true);
            
            // Try to send the email
            mailSender.send(message);
            
            // Update status to SENT
            notification.setStatus(EmailNotification.EmailStatus.SENT);
            return emailNotificationRepository.save(notification);
        } catch (Exception e) {
            // Update status to FAILED and save error message
            notification.setStatus(EmailNotification.EmailStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            return emailNotificationRepository.save(notification);
        }
    }

    @Override
    @Transactional
    public EmailNotification sendApplicationEmail(Application application, EmailEvent event) throws MessagingException {
        EventConfig config = eventConfigs.get(event);
        if (config == null) {
            throw new IllegalArgumentException("Unsupported email event: " + event);
        }

        // Determine recipient based on event configuration
        String recipientEmail = getRecipientEmail(application, null, config.recipientType);
        User relatedUser = getRelatedUser(application, null, config.recipientType);
        
        // Generate subject
        String subject = String.format(config.subjectTemplate, application.getJob().getTitle());
        
        // Generate template variables based on event
        Map<String, Object> templateVars = buildApplicationTemplateVariables(application, event);
        
        // Use job's region for job-related emails
        return sendTemplateEmailWithJob(recipientEmail, subject, event.getTemplateName(), templateVars, relatedUser, application.getJob());
    }

    @Override
    @Transactional
    public EmailNotification sendInterviewEmail(Interview interview, EmailEvent event) throws MessagingException {
        EventConfig config = eventConfigs.get(event);
        if (config == null) {
            throw new IllegalArgumentException("Unsupported email event: " + event);
        }

        // Determine recipient based on event configuration
        String recipientEmail = getRecipientEmail(interview.getApplication(), interview, config.recipientType);
        User relatedUser = getRelatedUser(interview.getApplication(), interview, config.recipientType);
        
        // Generate subject
        String subject = String.format(config.subjectTemplate, interview.getApplication().getJob().getTitle());
        
        // Generate template variables based on event
        Map<String, Object> templateVars = buildInterviewTemplateVariables(interview, event);
        
        // Use job's region for job-related emails
        return sendTemplateEmailWithJob(recipientEmail, subject, event.getTemplateName(), templateVars, relatedUser, interview.getApplication().getJob());
    }

    /**
     * Determines the recipient email based on the recipient type
     */
    private String getRecipientEmail(Application application, Interview interview, RecipientType recipientType) {
        switch (recipientType) {
            case CANDIDATE:
                return application.getCandidate().getEmail();
            case INTERVIEWER:
                if (interview == null) {
                    throw new IllegalArgumentException("Interview is required for INTERVIEWER recipient type");
                }
                return interview.getInterviewer().getEmail();
            case ADMIN:
                // For now, return the admin who shortlisted (if available)
                return application.getShortlistedBy() != null ? 
                       application.getShortlistedBy().getEmail() : 
                       application.getCandidate().getEmail(); // fallback
            default:
                throw new IllegalArgumentException("Unsupported recipient type: " + recipientType);
        }
    }

    /**
     * Determines the related user based on the recipient type
     */
    private User getRelatedUser(Application application, Interview interview, RecipientType recipientType) {
        switch (recipientType) {
            case CANDIDATE:
                return application.getCandidate();
            case INTERVIEWER:
                if (interview == null) {
                    throw new IllegalArgumentException("Interview is required for INTERVIEWER recipient type");
                }
                return interview.getInterviewer();
            case ADMIN:
                return application.getShortlistedBy();
            default:
                return null;
        }
    }

    /**
     * Builds template variables for application-related emails
     */
    private Map<String, Object> buildApplicationTemplateVariables(Application application, EmailEvent event) {
        Map<String, Object> templateVars = new HashMap<>();
        
        // Common variables for all application emails
        templateVars.put("candidateName", application.getCandidate().getFirstName() + " " + application.getCandidate().getLastName());
        templateVars.put("jobTitle", application.getJob().getTitle());
        templateVars.put("applicationId", application.getId().toString());
        
        // Event-specific variables
        switch (event) {
            case APPLICATION_RECEIVED:
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy 'at' hh:mm a");
                templateVars.put("applicationDate", application.getCreatedAt().format(formatter));
                break;
            case APPLICATION_REVIEWED:
            case APPLICATION_SHORTLISTED:
                // No additional variables needed for these events
                break;
        }
        
        return templateVars;
    }

    /**
     * Builds template variables for interview-related emails
     */
    private Map<String, Object> buildInterviewTemplateVariables(Interview interview, EmailEvent event) {
        Map<String, Object> templateVars = new HashMap<>();
        Application application = interview.getApplication();
        
        // Common variables for all interview emails
        templateVars.put("candidateName", application.getCandidate().getFirstName() + " " + application.getCandidate().getLastName());
        templateVars.put("jobTitle", application.getJob().getTitle());
        templateVars.put("applicationId", application.getId().toString());
        
        // Add scheduled date if available
        if (interview.getScheduledAt() != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a");
            templateVars.put("scheduledDate", interview.getScheduledAt().format(formatter));
        }
        
        // Add duration if available
        if (interview.getDurationMinutes() != null) {
            templateVars.put("durationMinutes", interview.getDurationMinutes());
        }
        
        // Add location information
        if (interview.getLocationType() != null) {
            templateVars.put("locationType", interview.getLocationType().getDisplayName());
            if (interview.getLocationType() == LocationType.OFFICE && interview.getLocationAddress() != null) {
                templateVars.put("locationAddress", interview.getLocationAddress());
                templateVars.put("locationInfo", interview.getLocationAddress());
            } else if (interview.getLocationType() == LocationType.ONLINE) {
                templateVars.put("locationInfo", "Online Interview (Meeting link will be provided)");
            } else {
                templateVars.put("locationInfo", " IST Interview Room");
            }
        } else {
            templateVars.put("locationInfo", " IST Interview Room");
        }
        
        // Event-specific variables
        switch (event) {
            case INTERVIEW_ASSIGNED_TO_INTERVIEWER:
                templateVars.put("interviewerName", interview.getInterviewer().getFirstName() + " " + interview.getInterviewer().getLastName());
                templateVars.put("candidateEmail", application.getCandidate().getEmail());
                templateVars.put("interviewTemplate", interview.getSkeleton().getName());
                templateVars.put("interviewerPortalLink", frontendUrl + "/interviewer/dashboard");
                break;
            case INTERVIEW_ASSIGNED_TO_CANDIDATE:
                templateVars.put("candidatePortalLink", frontendUrl + "/candidate/dashboard");
                break;
            case INTERVIEW_CANCELLED_TO_CANDIDATE:
                templateVars.put("interviewTemplate", interview.getSkeleton().getName());
                templateVars.put("candidatePortalLink", frontendUrl + "/candidate/dashboard");
                if (interview.getScheduledAt() != null) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a");
                    templateVars.put("scheduledAt", interview.getScheduledAt().format(formatter));
                }
                break;
            case INTERVIEW_CANCELLED_TO_INTERVIEWER:
                templateVars.put("interviewerName", interview.getInterviewer().getFirstName() + " " + interview.getInterviewer().getLastName());
                templateVars.put("interviewTemplate", interview.getSkeleton().getName());
                templateVars.put("interviewerPortalLink", frontendUrl + "/interviewer/dashboard");
                if (interview.getScheduledAt() != null) {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, MMMM dd, yyyy 'at' hh:mm a");
                    templateVars.put("scheduledAt", interview.getScheduledAt().format(formatter));
                }
                break;
        }
        
        return templateVars;
    }
    
    /**
     * Generate a unique campaign ID for bulk email tracking
     */
    private String generateBulkEmailCampaignId(BulkEmailRequestDTO request, User senderUser, ZonedDateTime startTime) {
        StringBuilder campaignId = new StringBuilder();
        campaignId.append("bulk-").append(startTime.toEpochSecond());
        
        if (request.getJobId() != null) {
            campaignId.append("-job").append(request.getJobId());
        }
        
        if (request.getStatus() != null) {
            campaignId.append("-").append(request.getStatus().toString().toLowerCase());
        }
        
        campaignId.append("-").append(senderUser.getId());
        
        return campaignId.toString();
    }

    @Override
    @Transactional
    public BulkEmailResponseDTO sendBulkEmailToApplicants(BulkEmailRequestDTO request, User senderUser) {
        ZonedDateTime startTime = ZonedDateTime.now();
        
        // Generate a unique campaign ID for this bulk email
        String campaignId = generateBulkEmailCampaignId(request, senderUser, startTime);
        
        List<Long> emailNotificationIds = new ArrayList<>();
        List<BulkEmailResponseDTO.FailedEmailDetail> failures = new ArrayList<>();
        int successCount = 0;
        
        // Check if sending to subscribed users
        if (Boolean.TRUE.equals(request.getSendToSubscribedUsers())) {
            // Send to subscribed users
            List<User> subscribedUsers = subscriptionService.getAllSubscribedUsers();
            
            for (User user : subscribedUsers) {
                // Check if user wants bulk emails
                try {
                    Map<String, Boolean> preferences = subscriptionService.getSubscriptionPreferences(user.getId());
                    if (!preferences.getOrDefault("bulkEmails", true)) {
                        continue; // Skip if user has disabled bulk emails
                    }
                } catch (Exception e) {
                    // Continue with default behavior (send email)
                }
                
                try {
                    String personalizedContent = personalizeEmailContentForUser(
                        request.getContent(),
                        user
                    );
                    
                    EmailNotification notification = sendEmailWithNotification(
                        user.getEmail(),
                        request.getSubject(),
                        personalizedContent,
                        request.getIsHtml(),
                        senderUser,
                        "bulk-email-subscribed",
                        campaignId
                    );
                    
                    emailNotificationIds.add(notification.getId());
                    successCount++;
                } catch (Exception e) {
                    failures.add(BulkEmailResponseDTO.FailedEmailDetail.builder()
                        .candidateEmail(user.getEmail())
                        .candidateName(user.getFirstName() + " " + user.getLastName())
                        .errorMessage("Failed to send email: " + e.getMessage())
                        .build());
                }
            }
            
            // Calculate final stats
            ZonedDateTime completedTime = ZonedDateTime.now();
            int totalAttempted = subscribedUsers.size() + (request.getSendTest() ? 1 : 0);
            int failureCount = failures.size();
            
            String status;
            if (failureCount == 0) {
                status = "SUCCESS";
            } else if (successCount > 0) {
                status = "PARTIAL_SUCCESS";
            } else {
                status = "FAILED";
            }
            
            return BulkEmailResponseDTO.builder()
                .totalAttempted(totalAttempted)
                .successCount(successCount)
                .failureCount(failureCount)
                .emailNotificationIds(emailNotificationIds)
                .failures(failures)
                .startedAt(startTime)
                .completedAt(completedTime)
                .status(status)
                .build();
        }
        
        // Get the list of applications to send emails to (original behavior)
        List<Application> applications = getApplicationsForBulkEmail(request);
        
        // Send test email first if requested
        if (request.getSendTest() && request.getTestEmailRecipient() != null) {
            try {
                // For test emails, use job's region from first application if available
                Job testJob = applications.isEmpty() ? null : applications.get(0).getJob();
                EmailNotification testEmail = sendEmailWithNotificationForJob(
                    request.getTestEmailRecipient(),
                    "[TEST] " + request.getSubject(),
                    request.getContent(),
                    request.getIsHtml(),
                    senderUser,
                    "bulk-email-test",
                    campaignId,
                    testJob
                );
                emailNotificationIds.add(testEmail.getId());
                successCount++;
            } catch (MessagingException e) {
                failures.add(BulkEmailResponseDTO.FailedEmailDetail.builder()
                    .candidateEmail(request.getTestEmailRecipient())
                    .candidateName("Test Recipient")
                    .errorMessage("Test email failed: " + e.getMessage())
                    .build());
            }
        }
        
        // Send emails to all matching applicants
        for (Application application : applications) {
            try {
                // Null checks for safety
                if (application.getCandidate() == null) {
                    failures.add(BulkEmailResponseDTO.FailedEmailDetail.builder()
                        .applicationId(application.getId())
                        .candidateEmail("Unknown")
                        .candidateName("Unknown")
                        .errorMessage("Candidate information is missing for application ID: " + application.getId())
                        .build());
                    continue;
                }
                
                if (application.getJob() == null) {
                    failures.add(BulkEmailResponseDTO.FailedEmailDetail.builder()
                        .applicationId(application.getId())
                        .candidateEmail(application.getCandidate().getEmail())
                        .candidateName(application.getCandidate().getFirstName() + " " + application.getCandidate().getLastName())
                        .errorMessage("Job information is missing for application ID: " + application.getId())
                        .build());
                    continue;
                }
                
                String candidateEmail = application.getCandidate().getEmail();
                if (candidateEmail == null || candidateEmail.trim().isEmpty()) {
                    failures.add(BulkEmailResponseDTO.FailedEmailDetail.builder()
                        .applicationId(application.getId())
                        .candidateEmail("No email")
                        .candidateName(application.getCandidate().getFirstName() + " " + application.getCandidate().getLastName())
                        .errorMessage("Candidate email is missing or empty")
                        .build());
                    continue;
                }
                
                String candidateName = (application.getCandidate().getFirstName() != null ? application.getCandidate().getFirstName() : "") + 
                                     " " + (application.getCandidate().getLastName() != null ? application.getCandidate().getLastName() : "");
                candidateName = candidateName.trim();
                
                // Personalize the content with candidate and job information
                String personalizedContent = personalizeEmailContent(
                    request.getContent(), 
                    application,
                    candidateName
                );
                
                // Use job's region for bulk emails to applicants (job-related)
                EmailNotification notification = sendEmailWithNotificationForJob(
                    candidateEmail,
                    request.getSubject(),
                    personalizedContent,
                    request.getIsHtml(),
                    senderUser,
                    "bulk-email",
                    campaignId,
                    application.getJob()
                );
                
                emailNotificationIds.add(notification.getId());
                successCount++;
                
            } catch (Exception e) {
                String candidateEmail = "Unknown";
                String candidateName = "Unknown";
                
                try {
                    if (application.getCandidate() != null) {
                        candidateEmail = application.getCandidate().getEmail() != null ? application.getCandidate().getEmail() : "No email";
                        candidateName = (application.getCandidate().getFirstName() != null ? application.getCandidate().getFirstName() : "") + 
                                      " " + (application.getCandidate().getLastName() != null ? application.getCandidate().getLastName() : "");
                        candidateName = candidateName.trim();
                    }
                } catch (Exception ignored) {
                    // If we can't get candidate info, use defaults
                }
                
                failures.add(BulkEmailResponseDTO.FailedEmailDetail.builder()
                    .applicationId(application.getId())
                    .candidateEmail(candidateEmail)
                    .candidateName(candidateName)
                    .errorMessage(e.getMessage())
                    .build());
            }
        }
        
        ZonedDateTime completedTime = ZonedDateTime.now();
        int totalAttempted = applications.size() + (request.getSendTest() ? 1 : 0);
        int failureCount = failures.size();
        
        String status;
        if (failureCount == 0) {
            status = "SUCCESS";
        } else if (successCount > 0) {
            status = "PARTIAL_SUCCESS";
        } else {
            status = "FAILED";
        }
        
        return BulkEmailResponseDTO.builder()
            .totalAttempted(totalAttempted)
            .successCount(successCount)
            .failureCount(failureCount)
            .emailNotificationIds(emailNotificationIds)
            .failures(failures)
            .startedAt(startTime)
            .completedAt(completedTime)
            .status(status)
            .build();
    }
    
    @Override
    public List<Application> getApplicantsForBulkEmail(Long jobId, ApplicationStatus status) {
        if (jobId != null && status != null) {
            return applicationRepository.findByJobIdAndStatusWithCandidateAndJob(jobId, status);
        } else if (jobId != null) {
            return applicationRepository.findByJobIdWithCandidateAndJob(jobId);
        } else if (status != null) {
            return applicationRepository.findByStatusWithCandidateAndJob(status);
        } else {
            return applicationRepository.findAllWithCandidateAndJob();
        }
    }
    
    @Override
    @Transactional
    public EmailNotification sendCustomEmail(String to, String subject, String content, Boolean isHtml, User senderUser) throws MessagingException {
        return sendEmailWithNotification(to, subject, content, isHtml, senderUser, "custom-email", null);
    }
    
    /**
     * Reusable utility method to send email and record notification with status tracking.
     * This version uses the DEFAULT provider (no-reply.ats.ist.com) for user-related emails.
     * Can be used for both individual emails and bulk emails with campaign tracking.
     */
    private EmailNotification sendEmailWithNotification(String to, String subject, String content, Boolean isHtml, 
                                                       User senderUser, String templateName, String campaignId) throws MessagingException {
        // For user-related emails, use null region (default provider with no-reply.ats.ist.com)
        return sendEmailWithNotificationAndRegion(to, subject, content, isHtml, senderUser, templateName, campaignId, null);
    }
    
    /**
     * Reusable utility method to send email and record notification with status tracking.
     * This version uses the JOB's region for determining the mail provider.
     * Can be used for both individual emails and bulk emails with campaign tracking.
     */
    private EmailNotification sendEmailWithNotificationForJob(String to, String subject, String content, Boolean isHtml, 
                                                       User senderUser, String templateName, String campaignId, Job job) throws MessagingException {
        // For job-related emails, use job's region
        String region = determineRegionFromJob(job);
        return sendEmailWithNotificationAndRegion(to, subject, content, isHtml, senderUser, templateName, campaignId, region);
    }
    
    /**
     * Core utility method to send email and record notification with status tracking.
     * This method handles the common pattern of creating notification, sending email, and updating status.
     * Can be used for both individual emails and bulk emails with campaign tracking.
     */
    private EmailNotification sendEmailWithNotificationAndRegion(String to, String subject, String content, Boolean isHtml, 
                                                       User senderUser, String templateName, String campaignId, String region) throws MessagingException {

        // Select mail provider based on region
        MailProvider mailProvider = mailProviderFactory.getProvider(region);
        String from = mailProvider.getDefaultFromAddress();
        // Create email notification record
        EmailNotification notification = EmailNotification.builder()
            .recipientEmail(to)
            .subject(subject)
            .body(content)
            .status(EmailNotification.EmailStatus.PENDING)
            .templateName(templateName)
            .relatedUser(senderUser)
            .bulkEmailCampaignId(campaignId)
            .build();
        
        notification = emailNotificationRepository.save(notification);

        try {
            // Send the email
            mailProvider.sendEmail(to, from, subject, content);
            
            // Update status to SENT
            notification.setStatus(EmailNotification.EmailStatus.SENT);
            return emailNotificationRepository.save(notification);
        } catch (MessagingException e) {
            // Update status to FAILED and save error message
            notification.setStatus(EmailNotification.EmailStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            notification = emailNotificationRepository.save(notification);
            
            throw e;
        }
    }
    
    private List<Application> getApplicationsForBulkEmail(BulkEmailRequestDTO request) {
        // If specific application IDs are provided, use those
        if (request.getApplicationIds() != null && !request.getApplicationIds().isEmpty()) {
            return applicationRepository.findAllById(request.getApplicationIds())
                .stream()
                .filter(app -> app.getCandidate() != null && app.getJob() != null)
                .toList();
        }
        
        // Otherwise use job and status filters
        return getApplicantsForBulkEmail(request.getJobId(), request.getStatus());
    }
    
    private String personalizeEmailContent(String content, Application application, String candidateName) {
        if (content == null) return content;
        
        // Replace common placeholders with actual values
        String personalizedContent = content
            .replace("{{candidateName}}", candidateName != null ? candidateName : "")
            .replace("{{firstName}}", application.getCandidate().getFirstName() != null ? application.getCandidate().getFirstName() : "")
            .replace("{{lastName}}", application.getCandidate().getLastName() != null ? application.getCandidate().getLastName() : "")
            .replace("{{jobTitle}}", application.getJob().getTitle() != null ? application.getJob().getTitle() : "")
            .replace("{{jobDepartment}}", application.getJob().getDepartment() != null ? application.getJob().getDepartment() : "")
            .replace("{{applicationStatus}}", application.getStatus() != null ? application.getStatus().toString() : "");
        
        return personalizedContent;
    }
    
    /**
     * Personalize email content for a subscribed user
     */
    private String personalizeEmailContentForUser(String content, User user) {
        if (content == null) return content;
        
        // Replace common placeholders with actual values
        String personalizedContent = content
            .replace("{{firstName}}", user.getFirstName() != null ? user.getFirstName() : "")
            .replace("{{lastName}}", user.getLastName() != null ? user.getLastName() : "")
            .replace("{{fullName}}", (user.getFirstName() != null ? user.getFirstName() : "") + " " + (user.getLastName() != null ? user.getLastName() : ""))
            .replace("{{email}}", user.getEmail() != null ? user.getEmail() : "");
        
        return personalizedContent;
    }
    
    @Override
    @Transactional
    public EmailNotification sendEmailWithCalendarAttachment(String to, String subject, String content, 
                                                           String calendarContent, String attachmentName, Job job) throws MessagingException {
        // Use job's region for calendar invites (job-related emails)
        String region = determineRegionFromJob(job);
        MailProvider mailProvider = mailProviderFactory.getProvider(region);
        String from = mailProvider.getDefaultFromAddress();
        // Create email notification record
        EmailNotification notification = EmailNotification.builder()
            .recipientEmail(to)
            .subject(subject)
            .body(content)
            .status(EmailNotification.EmailStatus.PENDING)
            .templateName("calendar-invite")
            .retryCount(0)
            .build();
        
        notification = emailNotificationRepository.save(notification);

        try {
            // Create the email message
           mailProvider.sendEmailWithAttachment(
                to,
                from,
                subject,
                content,
                attachmentName,
                calendarContent.getBytes(StandardCharsets.UTF_8),
                "text/calendar; method=REQUEST"
            );
            
            // Update status to SENT
            notification.setStatus(EmailNotification.EmailStatus.SENT);
            return emailNotificationRepository.save(notification);
            
        } catch (Exception e) {
            // Update status to FAILED and save error message
            notification.setStatus(EmailNotification.EmailStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            notification = emailNotificationRepository.save(notification);
            
            throw new MessagingException("Failed to send email with calendar attachment", e);
        }
    }
    
    @Override
    @Transactional
    public EmailNotification sendCustomJobOfferEmail(Application application, String customSubject, String customContent) 
            throws MessagingException {
        
        String candidateName = application.getCandidate().getFirstName() + " " + application.getCandidate().getLastName();
        String candidateEmail = application.getCandidate().getEmail();
        
        // Replace {{candidateName}} placeholder with actual candidate name
        String personalizedContent = customContent.replace("{{candidateName}}", candidateName);
        
        // Convert plain text to HTML (preserve line breaks and basic formatting)
        String htmlContent = convertPlainTextToHtml(personalizedContent);
        
        // Use job's region for email provider selection
        String region = determineRegionFromJob(application.getJob());
        MailProvider mailProvider = mailProviderFactory.getProvider(region);
        String from = mailProvider.getDefaultFromAddress();
        
        // Create email notification record
        EmailNotification notification = EmailNotification.builder()
            .recipientEmail(candidateEmail)
            .subject(customSubject)
            .body(htmlContent)
            .status(EmailNotification.EmailStatus.PENDING)
            .templateName("custom-job-offer")
            .relatedUser(application.getCandidate())
            .build();
        
        notification = emailNotificationRepository.save(notification);

        try {
            // Send the email
            mailProvider.sendEmail(candidateEmail, from, customSubject, htmlContent);
            
            // Update status to SENT
            notification.setStatus(EmailNotification.EmailStatus.SENT);
            return emailNotificationRepository.save(notification);
        } catch (MessagingException e) {
            // Update status to FAILED and save error message
            notification.setStatus(EmailNotification.EmailStatus.FAILED);
            notification.setErrorMessage(e.getMessage());
            notification = emailNotificationRepository.save(notification);
            
            throw e;
        }
    }
    
    /**
     * Convert plain text to HTML with professional styling matching the job-offer template
     */
    private String convertPlainTextToHtml(String plainText) {
        if (plainText == null) return "";
        
        // Escape HTML special characters first
        String html = plainText
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
        
        // Convert markdown-style bold (**text**) to HTML
        html = html.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        
        StringBuilder result = new StringBuilder();
        result.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"></head>");
        result.append("<body style=\"font-family: Arial, sans-serif; line-height: 1.6; color: #333;\">");
        result.append("<div style=\"max-width: 600px; margin: 0 auto; padding: 20px;\">");
        
        // Add header
        result.append("<h2 style=\"color: #2563eb;\">Congratulations! Job Offer from IST Africa</h2>");
        
        // Parse the content into sections
        String[] paragraphs = html.split("\n\n");
        boolean inNextSteps = false;
        StringBuilder nextStepsContent = new StringBuilder();
        
        for (String para : paragraphs) {
            String trimmedPara = para.trim();
            if (trimmedPara.isEmpty()) continue;
            
            // Check if this is the job title/info section (contains "Application ID")
            if (trimmedPara.contains("Application ID:")) {
                String[] lines = trimmedPara.split("\n");
                result.append("<div style=\"background-color: #f3f4f6; padding: 15px; border-radius: 5px; margin: 20px 0;\">");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("<strong>") || (line.length() > 0 && !line.contains("Application ID"))) {
                        result.append("<h3 style=\"margin: 0; color: #1f2937;\">").append(line).append("</h3>");
                    } else if (line.contains("Application ID")) {
                        result.append("<p style=\"margin: 5px 0 0 0; color: #6b7280;\">").append(line).append("</p>");
                    }
                }
                result.append("</div>");
            }
            // Check if this is the congratulations section (contains emoji or "Congratulations!")
            else if (trimmedPara.contains("🎉") || (trimmedPara.contains("Congratulations!") && !trimmedPara.contains("Job Offer"))) {
                result.append("<div style=\"background-color: #ecfdf5; border-left: 4px solid #10b981; padding: 15px; margin: 20px 0;\">");
                result.append("<p style=\"margin: 0; color: #047857;\">").append(trimmedPara.replace("\n", "<br>")).append("</p>");
                result.append("</div>");
            }
            // Check if this starts the Next Steps section
            else if (trimmedPara.contains("<strong>Next Steps:</strong>") || trimmedPara.contains("Next Steps:")) {
                inNextSteps = true;
                nextStepsContent = new StringBuilder();
                // Process the current paragraph for bullet points
                String[] lines = trimmedPara.split("\n");
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("•") || line.startsWith("*")) {
                        nextStepsContent.append("<li>").append(line.substring(1).trim()).append("</li>");
                    }
                }
            }
            // Check if this is a bullet point list (part of Next Steps or standalone)
            else if (trimmedPara.startsWith("•") || trimmedPara.startsWith("*")) {
                if (inNextSteps) {
                    String[] lines = trimmedPara.split("\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (line.startsWith("•") || line.startsWith("*")) {
                            nextStepsContent.append("<li>").append(line.substring(1).trim()).append("</li>");
                        }
                    }
                } else {
                    // Standalone bullet list
                    result.append("<ul style=\"margin: 10px 0; padding-left: 20px;\">");
                    String[] lines = trimmedPara.split("\n");
                    for (String line : lines) {
                        line = line.trim();
                        if (line.startsWith("•") || line.startsWith("*")) {
                            result.append("<li>").append(line.substring(1).trim()).append("</li>");
                        }
                    }
                    result.append("</ul>");
                }
            }
            // Check if this is sign-off (contains "Best regards" or "Sincerely")
            else if (trimmedPara.toLowerCase().contains("best regards") || 
                     trimmedPara.toLowerCase().contains("sincerely") ||
                     trimmedPara.contains("Recruiting Team")) {
                // First, close Next Steps if it was open
                if (inNextSteps && nextStepsContent.length() > 0) {
                    result.append("<div style=\"background-color: #eff6ff; border-left: 4px solid #2563eb; padding: 15px; margin: 20px 0;\">");
                    result.append("<p style=\"margin: 0; color: #1e40af;\"><strong>Next Steps:</strong></p>");
                    result.append("<ul style=\"margin: 10px 0 0 0; padding-left: 20px; color: #1e40af;\">");
                    result.append(nextStepsContent);
                    result.append("</ul></div>");
                    inNextSteps = false;
                }
                
                result.append("<p style=\"margin-top: 30px;\">");
                String[] lines = trimmedPara.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.contains("Recruiting Team") || line.contains("Team")) {
                        result.append("<strong>").append(line).append("</strong>");
                    } else {
                        result.append(line);
                    }
                    if (i < lines.length - 1) {
                        result.append("<br>");
                    }
                }
                result.append("</p>");
            }
            // Regular paragraph
            else {
                // Close Next Steps if it was open and we hit a regular paragraph
                if (inNextSteps && nextStepsContent.length() > 0) {
                    result.append("<div style=\"background-color: #eff6ff; border-left: 4px solid #2563eb; padding: 15px; margin: 20px 0;\">");
                    result.append("<p style=\"margin: 0; color: #1e40af;\"><strong>Next Steps:</strong></p>");
                    result.append("<ul style=\"margin: 10px 0 0 0; padding-left: 20px; color: #1e40af;\">");
                    result.append(nextStepsContent);
                    result.append("</ul></div>");
                    inNextSteps = false;
                }
                
                String formattedPara = trimmedPara.replace("\n", "<br>");
                result.append("<p style=\"margin: 10px 0;\">").append(formattedPara).append("</p>");
            }
        }
        
        // Close Next Steps if still open at the end
        if (inNextSteps && nextStepsContent.length() > 0) {
            result.append("<div style=\"background-color: #eff6ff; border-left: 4px solid #2563eb; padding: 15px; margin: 20px 0;\">");
            result.append("<p style=\"margin: 0; color: #1e40af;\"><strong>Next Steps:</strong></p>");
            result.append("<ul style=\"margin: 10px 0 0 0; padding-left: 20px; color: #1e40af;\">");
            result.append(nextStepsContent);
            result.append("</ul></div>");
        }
        
        // Add footer
        result.append("<hr style=\"border: none; border-top: 1px solid #e5e7eb; margin: 30px 0;\">");
        result.append("<p style=\"font-size: 0.9em; color: #666;\">");
        result.append("If you have any questions about the offer, please contact us at ");
        result.append("<a href=\"mailto:denis.niwemugisha@ist.com\" style=\"color: #2563eb;\">denis.niwemugisha@ist.com</a>");
        result.append("</p>");
        
        result.append("</div></body></html>");
        return result.toString();
    }
} 