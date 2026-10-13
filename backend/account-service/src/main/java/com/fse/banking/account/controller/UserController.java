package com.fse.banking.account.controller;

import com.fse.banking.account.model.UserEntity;
import com.fse.banking.account.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;

    @GetMapping("/{userId}/location")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getUserLocation(@PathVariable("userId") String userId) {
        log.info("[USER CONTROLLER] GET location for userId: {}", userId);
        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.ok(Map.of(
                    "user_id", userId,
                    "location_name", "Manila, Philippines",
                    "latitude", 14.5995,
                    "longitude", 120.9842,
                    "ip_address", "112.198.45.10",
                    "first_name", "Customer",
                    "last_name", ""
            ));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("user_id", user.getUserId());
        response.put("first_name", user.getFirstName() != null ? user.getFirstName() : "");
        response.put("last_name", user.getLastName() != null ? user.getLastName() : "");
        response.put("location_name", user.getLastKnownLocationName() != null ? user.getLastKnownLocationName() : "Manila, Philippines");
        response.put("latitude", user.getLastKnownLatitude() != null ? user.getLastKnownLatitude() : 14.5995);
        response.put("longitude", user.getLastKnownLongitude() != null ? user.getLastKnownLongitude() : 120.9842);
        response.put("ip_address", user.getLastKnownIp() != null ? user.getLastKnownIp() : "112.198.45.10");

        return ResponseEntity.ok(response);
    }

    @RequestMapping(value = "/{userId}/location", method = {RequestMethod.PATCH, RequestMethod.PUT, RequestMethod.POST})
    @Transactional
    public ResponseEntity<Map<String, Object>> updateUserLocation(
            @PathVariable("userId") String userId,
            @RequestBody Map<String, Object> locationPayload) {
        log.info("[USER CONTROLLER] UPDATE location for userId {}: {}", userId, locationPayload);

        UserEntity user = userRepository.findById(userId).orElse(null);
        if (user != null) {
            if (locationPayload.containsKey("latitude") && locationPayload.get("latitude") != null) {
                user.setLastKnownLatitude(Double.valueOf(String.valueOf(locationPayload.get("latitude"))));
            }
            if (locationPayload.containsKey("longitude") && locationPayload.get("longitude") != null) {
                user.setLastKnownLongitude(Double.valueOf(String.valueOf(locationPayload.get("longitude"))));
            }
            if (locationPayload.containsKey("location_name") && locationPayload.get("location_name") != null) {
                user.setLastKnownLocationName(String.valueOf(locationPayload.get("location_name")));
            }
            if (locationPayload.containsKey("ip_address") && locationPayload.get("ip_address") != null) {
                user.setLastKnownIp(String.valueOf(locationPayload.get("ip_address")));
            }
            user.setLastGeoUpdatedAt(Instant.now());
            userRepository.save(user);
        }

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "user_id", userId,
                "location", locationPayload,
                "timestamp", Instant.now()
        ));
    }
}
