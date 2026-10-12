package com.fse.banking.account.controller;

import com.fse.banking.account.model.UserEntity;
import com.fse.banking.account.repository.UserRepository;
import com.fse.banking.account.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Last reported customer location, used by the back-office location simulator. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserLocationController {

    private final UserRepository userRepository;
    private final JwtProvider jwtProvider;

    @GetMapping("/{userId}/location")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getLocation(
            @PathVariable("userId") String userId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        ResponseEntity<Map<String, Object>> denied = authorize(userId, authHeader);
        if (denied != null) return denied;

        return userRepository.findById(userId)
                .map(u -> ResponseEntity.ok(toBody(u)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @PatchMapping("/{userId}/location")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateLocation(
            @PathVariable("userId") String userId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {
        ResponseEntity<Map<String, Object>> denied = authorize(userId, authHeader);
        if (denied != null) return denied;

        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        if (body.get("latitude") != null) user.setLastKnownLatitude(Double.valueOf(String.valueOf(body.get("latitude"))));
        if (body.get("longitude") != null) user.setLastKnownLongitude(Double.valueOf(String.valueOf(body.get("longitude"))));
        if (body.get("location_name") != null) user.setLastKnownLocationName(String.valueOf(body.get("location_name")));
        if (body.get("ip_address") != null) user.setLastKnownIp(String.valueOf(body.get("ip_address")));
        user.setLastGeoUpdatedAt(Instant.now());
        userRepository.save(user);

        return ResponseEntity.ok(toBody(user));
    }

    private static Map<String, Object> toBody(UserEntity u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", u.getUserId());
        m.put("first_name", u.getFirstName());
        m.put("last_name", u.getLastName());
        m.put("location_name", u.getLastKnownLocationName());
        m.put("latitude", u.getLastKnownLatitude());
        m.put("longitude", u.getLastKnownLongitude());
        m.put("ip_address", u.getLastKnownIp());
        m.put("last_geo_updated_at", u.getLastGeoUpdatedAt());
        return m;
    }

    /** Null when allowed. Readable by staff or the customer it belongs to. */
    private ResponseEntity<Map<String, Object>> authorize(String userId, String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String token = authHeader.substring(7);
        if (!jwtProvider.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String role = String.valueOf(jwtProvider.getRole(token)).replace("ROLE_", "");
        boolean staff = "ADMIN".equals(role) || "TELLER".equals(role) || "MANAGER".equals(role);
        if (!staff && !userId.equals(jwtProvider.getUserId(token))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return null;
    }
}
