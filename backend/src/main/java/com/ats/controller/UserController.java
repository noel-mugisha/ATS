package com.ats.controller;

import com.ats.dto.UserDTO;
import com.ats.service.UserService;
import com.ats.service.RegionalDataFilterService;
import com.ats.model.Role;
import com.ats.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/users")
@Tag(name = "User Management", description = "APIs for managing users in the ATS system")
public class UserController {

    private final UserService userService;
    private final RegionalDataFilterService regionalDataFilterService;

    @Autowired
    public UserController(UserService userService, RegionalDataFilterService regionalDataFilterService) {
        this.userService = userService;
        this.regionalDataFilterService = regionalDataFilterService;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Create a new user",
        description = "Creates a new user in the system. Email must be unique. Admin only."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "201",
            description = "User created successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class),
                examples = @ExampleObject(
                    value = "{\"id\": 1, \"email\": \"john.doe@example.com\", \"firstName\": \"John\", \"lastName\": \"Doe\", \"role\": \"RECRUITER\"}"
                )
            )
        ),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "409", description = "User already exists")
    })
    public ResponseEntity<UserDTO> createUser(
            @Parameter(
                description = "User details",
                required = true,
                schema = @Schema(implementation = UserDTO.class)
            )
            @RequestBody UserDTO userDTO,
            HttpServletRequest httpRequest) {
        String requestOrigin = extractOriginFromRequest(httpRequest);
        return ResponseEntity.ok(userService.createUser(userDTO, requestOrigin));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.id")
    @Operation(
        summary = "Update an existing user",
        description = "Updates the details of an existing user. Email must remain unique. Users can update their own profile."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "User updated successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class)
            )
        ),
        @ApiResponse(responseCode = "400", description = "Invalid input"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> updateUser(
            @Parameter(description = "User ID", example = "1")
            @PathVariable Long id,
            @Parameter(
                description = "Updated user details",
                required = true,
                schema = @Schema(implementation = UserDTO.class)
            )
            @RequestBody UserDTO userDTO) {
        return ResponseEntity.ok(userService.updateUser(id, userDTO));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.id")
    @Operation(
        summary = "Get user by ID",
        description = "Retrieves a user's details by their unique ID. Users can view their own profile."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "User found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class)
            )
        ),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> getUserById(
            @Parameter(description = "User ID", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Get user by email",
        description = "Retrieves a user's details by their email address. Admin only."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "User found",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class)
            )
        ),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> getUserByEmail(
            @Parameter(description = "User email", example = "john.doe@example.com")
            @PathVariable String email) {
        return ResponseEntity.ok(userService.getUserByEmail(email));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Get all users",
        description = "Retrieves a list of all users in the system. Admin only."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Users retrieved successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO[].class)
            )
        )
    })
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        return ResponseEntity.ok(userService.getAllUsers());
    }

    @GetMapping("/regional-access")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Get regional access information",
        description = "Returns information about the current user's regional data access permissions"
    )
    public ResponseEntity<Map<String, Object>> getRegionalAccess(Authentication authentication) {
        try {
            String userEmail = authentication.getName();
            UserDTO userDTO = userService.getUserByEmail(userEmail);
            // Convert UserDTO to User entity
            User currentUser = new User();
            currentUser.setId(userDTO.getId());
            currentUser.setEmail(userDTO.getEmail());
            currentUser.setFirstName(userDTO.getFirstName());
            currentUser.setLastName(userDTO.getLastName());
            currentUser.setRole(userDTO.getRole());
            currentUser.setRegion(userDTO.getRegion());
            
            Map<String, Object> response = new HashMap<>();
            response.put("accessibleRegion", regionalDataFilterService.getAccessibleRegion(currentUser));
            response.put("isEUAdmin", regionalDataFilterService.isEUAdmin(currentUser));
            response.put("isNonEUAdmin", regionalDataFilterService.isNonEUAdmin(currentUser));
            response.put("regionFilter", regionalDataFilterService.getRegionFilterCondition(currentUser));
            response.put("userRegion", currentUser.getRegion());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("error", "Failed to get regional access information: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    @PostMapping("/toggle-region-view")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Toggle region view mode for EU admins",
        description = "Allows EU admins to switch between viewing EU data and non-EU data. Non-EU admins cannot use this feature."
    )
    public ResponseEntity<Map<String, Object>> toggleRegionView(
            @RequestParam(required = false, defaultValue = "false") Boolean viewingAsNonEU,
            Authentication authentication,
            HttpServletRequest request) {
        try {
            String userEmail = authentication.getName();
            UserDTO userDTO = userService.getUserByEmail(userEmail);
            
            // Convert UserDTO to User entity
            User currentUser = new User();
            currentUser.setId(userDTO.getId());
            currentUser.setEmail(userDTO.getEmail());
            currentUser.setRole(userDTO.getRole());
            currentUser.setRegion(userDTO.getRegion());
            
            // Only EU admins can toggle view mode
            if (!regionalDataFilterService.isEUAdmin(currentUser)) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("error", "Only EU admins can toggle region view mode");
                return ResponseEntity.badRequest().body(response);
            }
            
            // Store view mode in session
            request.getSession().setAttribute("viewingAsNonEU_" + currentUser.getId(), viewingAsNonEU);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("viewingAsNonEU", viewingAsNonEU);
            response.put("message", viewingAsNonEU ? "Now viewing non-EU data" : "Now viewing EU data");
            response.put("effectiveFilter", regionalDataFilterService.getEffectiveRegionFilter(currentUser, viewingAsNonEU));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("error", "Failed to toggle region view: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
    
    @GetMapping("/region-view-mode")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Get current region view mode",
        description = "Returns the current region view mode for the authenticated admin"
    )
    public ResponseEntity<Map<String, Object>> getRegionViewMode(
            Authentication authentication,
            HttpServletRequest request) {
        try {
            String userEmail = authentication.getName();
            UserDTO userDTO = userService.getUserByEmail(userEmail);
            
            // Convert UserDTO to User entity
            User currentUser = new User();
            currentUser.setId(userDTO.getId());
            currentUser.setRole(userDTO.getRole());
            currentUser.setRegion(userDTO.getRegion());
            
            // Get view mode from session
            Boolean viewingAsNonEU = (Boolean) request.getSession().getAttribute("viewingAsNonEU_" + currentUser.getId());
            if (viewingAsNonEU == null) {
                viewingAsNonEU = false; // Default to EU view
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("isEUAdmin", regionalDataFilterService.isEUAdmin(currentUser));
            response.put("viewingAsNonEU", viewingAsNonEU);
            response.put("canToggle", regionalDataFilterService.isEUAdmin(currentUser));
            response.put("effectiveFilter", regionalDataFilterService.getEffectiveRegionFilter(currentUser, viewingAsNonEU));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("error", "Failed to get region view mode: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Delete a user",
        description = "Permanently deletes a user from the system. Admin only."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "204", description = "User deleted successfully"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<Void> deleteUser(
            @Parameter(description = "User ID", example = "1")
            @PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN') or #id == authentication.principal.id")
    @Operation(
        summary = "Deactivate user account",
        description = "Deactivates a user account with reason. Users can deactivate their own account or admins can deactivate any account. Admins cannot deactivate their own account."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Account deactivated successfully"),
        @ApiResponse(responseCode = "400", description = "Cannot deactivate your own admin account"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> deactivateAccount(
            @Parameter(description = "User ID", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Deactivation reason", required = true)
            @RequestBody Map<String, String> requestBody,
            Authentication authentication) {
        String reason = requestBody.get("reason");
        if (reason == null || reason.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(userService.deactivateAccount(id, reason, authentication));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Update user status",
        description = "Updates a user's active status. Admin only. Admins cannot deactivate their own account."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "User status updated successfully"),
        @ApiResponse(responseCode = "400", description = "Cannot deactivate your own admin account"),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> updateUserStatus(
            @Parameter(description = "User ID", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Active status", example = "true")
            @RequestParam Boolean isActive,
            Authentication authentication) {
        return ResponseEntity.ok(userService.updateUserStatus(id, isActive, authentication));
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Update user role",
        description = "Updates the role of a user. Admin only."
    )
    @ApiResponses(value = {
        @ApiResponse(
            responseCode = "200",
            description = "Role updated successfully",
            content = @Content(
                mediaType = "application/json",
                schema = @Schema(implementation = UserDTO.class)
            )
        ),
        @ApiResponse(responseCode = "404", description = "User not found")
    })
    public ResponseEntity<UserDTO> updateUserRole(
            @Parameter(description = "User ID", example = "1")
            @PathVariable Long id,
            @Parameter(description = "New role", example = "ADMIN")
            @RequestParam Role role) {
        return ResponseEntity.ok(userService.updateUserRole(id, role));
    }

    @PutMapping("/{id}/region")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Assign region to user",
        description = "Assigns a region to a user. Only admins can assign regions."
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Region assigned successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid region"),
        @ApiResponse(responseCode = "404", description = "User not found"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions")
    })
    public ResponseEntity<Map<String, Object>> assignRegion(
            @Parameter(description = "User ID", example = "1")
            @PathVariable Long id,
            @RequestBody Map<String, Object> requestBody) {
        try {
            String region = (String) requestBody.get("region");
            UserDTO updatedUser = userService.assignRegion(id, region);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Region assigned successfully");
            response.put("user", updatedUser);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "Failed to assign region: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Extracts the origin (scheme + host + port) from the HTTP request.
     * Checks X-Forwarded-Proto and X-Forwarded-Host headers first (for reverse proxy scenarios),
     * then falls back to the direct request values.
     *
     * @param request The HTTP servlet request
     * @return The origin URL (e.g., "https://ats.ist.com")
     */
    private String extractOriginFromRequest(HttpServletRequest request) {
        // Get scheme (http or https)
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isEmpty()) {
            scheme = request.getScheme();
        }

        // Get host
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isEmpty()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isEmpty()) {
            host = request.getServerName();
        }

        // Build origin URL
        StringBuilder origin = new StringBuilder();
        origin.append(scheme).append("://").append(host);

        // Add port if non-standard
        int port = request.getServerPort();
        if ((scheme.equals("https") && port != 443) || (scheme.equals("http") && port != 80)) {
            // Only add port if it's not already in the host header
            if (!host.contains(":")) {
                origin.append(":").append(port);
            }
        }

        return origin.toString();
    }
} 