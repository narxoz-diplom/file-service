package com.microservices.fileservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
@Slf4j
public class AuthController {

    @Value("${keycloak.url:http://localhost:8080}")
    private String keycloakUrl;

    @Value("${keycloak.realm:microservices-realm}")
    private String realm;

    @Value("${keycloak.admin.username:admin}")
    private String adminUsername;

    @Value("${keycloak.admin.password:admin}")
    private String adminPassword;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // Конструктор для инжекции ObjectMapper
    public AuthController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegistrationRequest request) {
        try {
            // Валидация входных данных
            if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Username is required"));
            }
            if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Email is required"));
            }
            if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Password is required"));
            }
            if (request.getRole() == null || request.getRole().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Role is required"));
            }
            
            // Валидация роли - только client или teacher
            String role = request.getRole().toLowerCase().trim();
            if (!role.equals("client") && !role.equals("teacher")) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Role must be 'client' or 'teacher'"));
            }
            
            // Обновляем роль в запросе
            request.setRole(role);

            // Получаем admin token
            String adminToken = getAdminToken();
            if (adminToken == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to authenticate with Keycloak admin"));
            }

            // Проверяем существование роли перед созданием пользователя
            if (!roleExists(adminToken, request.getRole())) {
                log.error("Role '{}' does not exist in realm '{}'. Please create it in Keycloak first.", 
                        request.getRole(), realm);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", 
                                "Role '" + request.getRole() + "' does not exist in Keycloak. " +
                                "Please contact administrator or create the role in Keycloak Admin Console."));
            }

            // Создаем пользователя
            String userId = createUser(adminToken, request);
            if (userId == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error", "Failed to create user. Username or email might already exist."));
            }

            // Назначаем роль
            boolean roleAssigned = assignRole(adminToken, userId, request.getRole());
            if (!roleAssigned) {
                log.warn("Failed to assign role {} to user {}, but user was created. " +
                        "Please assign the role manually in Keycloak Admin Console.", request.getRole(), userId);
                return ResponseEntity.status(HttpStatus.CREATED)
                        .body(Map.of(
                                "message", "User registered successfully, but role assignment failed. " +
                                        "Please contact administrator to assign the role manually.",
                                "username", request.getUsername(),
                                "roleAssigned", false,
                                "warning", "Role '" + request.getRole() + "' needs to be assigned manually in Keycloak"
                        ));
            }

            log.info("User registered: {} with role: {} (assigned: {})", request.getUsername(), request.getRole(), roleAssigned);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(Map.of("message", "User registered successfully", "username", request.getUsername(), "roleAssigned", roleAssigned));

        } catch (Exception e) {
            log.error("Error during registration", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Registration failed: " + e.getMessage()));
        }
    }

    private String getAdminToken() {
        try {
            String tokenUrl = keycloakUrl + "/realms/master/protocol/openid-connect/token";
            String body = "grant_type=password&client_id=admin-cli&username=" + adminUsername + "&password=" + adminPassword;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                // Парсим JSON ответ используя ObjectMapper
                Map<String, Object> tokenData = objectMapper.readValue(response.body(), Map.class);
                return (String) tokenData.get("access_token");
            } else {
                log.error("Failed to get admin token. Status: {}, Body: {}", response.statusCode(), response.body());
            }
        } catch (Exception e) {
            log.error("Error getting admin token", e);
        }
        return null;
    }

    private String createUser(String adminToken, RegistrationRequest request) {
        try {
            String usersUrl = keycloakUrl + "/admin/realms/" + realm + "/users";
            
            // Сначала создаем пользователя без пароля
            Map<String, Object> userData = new HashMap<>();
            userData.put("username", request.getUsername());
            userData.put("email", request.getEmail());
            userData.put("firstName", request.getFirstName());
            userData.put("lastName", request.getLastName());
            userData.put("enabled", true);
            userData.put("emailVerified", true); // Устанавливаем email как подтвержденный

            String jsonBody = objectMapper.writeValueAsString(userData);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(usersUrl))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            
            String userId = null;
            if (response.statusCode() == 201) {
                // Получаем ID пользователя из заголовка Location
                String location = response.headers().firstValue("Location").orElse(null);
                if (location != null) {
                    userId = location.substring(location.lastIndexOf("/") + 1);
                }
            } else if (response.statusCode() == 409) {
                log.warn("User already exists: {}", request.getUsername());
                return null;
            } else {
                log.error("Failed to create user. Status: {}, Body: {}", response.statusCode(), response.body());
                return null;
            }

            // Теперь устанавливаем пароль отдельным запросом
            if (userId != null) {
                String passwordUrl = keycloakUrl + "/admin/realms/" + realm + "/users/" + userId + "/reset-password";
                
                Map<String, Object> passwordData = new HashMap<>();
                passwordData.put("type", "password");
                passwordData.put("value", request.getPassword());
                passwordData.put("temporary", false); // Важно: пароль не временный!

                String passwordJson = objectMapper.writeValueAsString(passwordData);

                HttpRequest passwordRequest = HttpRequest.newBuilder()
                        .uri(URI.create(passwordUrl))
                        .header("Authorization", "Bearer " + adminToken)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(passwordJson))
                        .build();

                HttpResponse<String> passwordResponse = httpClient.send(passwordRequest, HttpResponse.BodyHandlers.ofString());
                
                if (passwordResponse.statusCode() == 204) {
                    log.info("Password set successfully for user: {}", request.getUsername());
                    return userId;
                } else {
                    log.error("Failed to set password. Status: {}, Body: {}", passwordResponse.statusCode(), passwordResponse.body());
                    // Удаляем пользователя, если не удалось установить пароль
                    deleteUser(adminToken, userId);
                    return null;
                }
            }
        } catch (Exception e) {
            log.error("Error creating user", e);
        }
        return null;
    }

    private void deleteUser(String adminToken, String userId) {
        try {
            String deleteUrl = keycloakUrl + "/admin/realms/" + realm + "/users/" + userId;
            HttpRequest deleteRequest = HttpRequest.newBuilder()
                    .uri(URI.create(deleteUrl))
                    .header("Authorization", "Bearer " + adminToken)
                    .DELETE()
                    .build();
            httpClient.send(deleteRequest, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.error("Error deleting user", e);
        }
    }

    private boolean assignRole(String adminToken, String userId, String roleName) {
        try {
            log.info("Attempting to assign role '{}' to user '{}'", roleName, userId);
            
            // Получаем роль из realm
            String rolesUrl = keycloakUrl + "/admin/realms/" + realm + "/roles/" + roleName;
            HttpRequest getRoleRequest = HttpRequest.newBuilder()
                    .uri(URI.create(rolesUrl))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();

            HttpResponse<String> roleResponse = httpClient.send(getRoleRequest, HttpResponse.BodyHandlers.ofString());
            if (roleResponse.statusCode() != 200) {
                log.error("Role '{}' not found in realm '{}'. Status: {}, Body: {}", 
                        roleName, realm, roleResponse.statusCode(), roleResponse.body());
                log.error("Please ensure the role '{}' exists in Keycloak realm '{}'", roleName, realm);
                return false;
            }

            String roleJson = roleResponse.body();
            log.debug("Retrieved role JSON: {}", roleJson);
            
            // Назначаем роль пользователю через realm role mappings
            String assignUrl = keycloakUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm";
            String requestBody = "[" + roleJson + "]";
            
            HttpRequest assignRequest = HttpRequest.newBuilder()
                    .uri(URI.create(assignUrl))
                    .header("Authorization", "Bearer " + adminToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> assignResponse = httpClient.send(assignRequest, HttpResponse.BodyHandlers.ofString());
            
            if (assignResponse.statusCode() == 204 || assignResponse.statusCode() == 200) {
                log.info("✅ Role '{}' successfully assigned to user '{}'", roleName, userId);
                
                // Проверяем, что роль действительно назначена
                if (verifyRoleAssignment(adminToken, userId, roleName)) {
                    log.info("✅ Verified: Role '{}' is assigned to user '{}'", roleName, userId);
                    return true;
                } else {
                    log.warn("⚠️ Role assignment reported success, but verification failed");
                    return false;
                }
            } else {
                log.error("❌ Failed to assign role '{}' to user '{}'. Status: {}, Body: {}", 
                        roleName, userId, assignResponse.statusCode(), assignResponse.body());
                return false;
            }
        } catch (Exception e) {
            log.error("❌ Exception while assigning role '{}' to user '{}': {}", roleName, userId, e.getMessage(), e);
            return false;
        }
    }
    
    private boolean roleExists(String adminToken, String roleName) {
        try {
            String rolesUrl = keycloakUrl + "/admin/realms/" + realm + "/roles/" + roleName;
            HttpRequest getRoleRequest = HttpRequest.newBuilder()
                    .uri(URI.create(rolesUrl))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();

            HttpResponse<String> roleResponse = httpClient.send(getRoleRequest, HttpResponse.BodyHandlers.ofString());
            return roleResponse.statusCode() == 200;
        } catch (Exception e) {
            log.error("Error checking if role exists", e);
            return false;
        }
    }
    
    private boolean verifyRoleAssignment(String adminToken, String userId, String roleName) {
        try {
            // Получаем список ролей пользователя
            String rolesUrl = keycloakUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm";
            HttpRequest getRolesRequest = HttpRequest.newBuilder()
                    .uri(URI.create(rolesUrl))
                    .header("Authorization", "Bearer " + adminToken)
                    .GET()
                    .build();

            HttpResponse<String> rolesResponse = httpClient.send(getRolesRequest, HttpResponse.BodyHandlers.ofString());
            if (rolesResponse.statusCode() == 200) {
                String rolesJson = rolesResponse.body();
                log.debug("User roles: {}", rolesJson);
                
                // Проверяем, содержит ли ответ нужную роль
                if (rolesJson != null && rolesJson.contains("\"name\":\"" + roleName + "\"")) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.error("Error verifying role assignment", e);
            return false;
        }
    }

    @Data
    public static class RegistrationRequest {
        private String username;
        private String email;
        private String password;
        private String firstName;
        private String lastName;
        private String role; // "client" or "teacher"
        
        // Геттеры и сеттеры генерируются через @Data
    }
}

