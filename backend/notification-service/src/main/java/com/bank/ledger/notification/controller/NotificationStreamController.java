package com.bank.ledger.notification.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@RestController
@RequestMapping("/api/v1/notifications")
@CrossOrigin(origins = "*")
public class NotificationStreamController {

    // SSE emitters keyed by owner. Events are only ever delivered to the target user's own
    // streams; there is deliberately no broadcast path, so one customer's device and
    // transaction events can never reach another customer's session.
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> userEmitters = new ConcurrentHashMap<>();

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamNotifications(@RequestParam(value = "userId", required = false) String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        SseEmitter emitter = new SseEmitter(180_000L); // 3 minutes timeout
        userEmitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(emitter, userId));
        emitter.onTimeout(() -> removeEmitter(emitter, userId));
        emitter.onError((e) -> removeEmitter(emitter, userId));

        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data(Map.of("message", "Connected to Real-Time Notification Stream", "status", "ONLINE")));
        } catch (IOException e) {
            removeEmitter(emitter, userId);
        }

        log.info("Client connected to SSE stream. Total active: {}",
                userEmitters.values().stream().mapToInt(List::size).sum());
        return emitter;
    }

    public void pushToast(String userId, Object payload) {
        sendToUser(userId, "TRANSACTION_ALERT", payload);
    }

    public void pushSecurityAlert(String userId, Object payload) {
        log.info("Pushing SECURITY_ALERT event for userId: {}", userId);
        sendToUser(userId, "SECURITY_ALERT", payload);
    }

    private void sendToUser(String userId, String eventName, Object payload) {
        if (userId == null) return;
        for (SseEmitter emitter : userEmitters.getOrDefault(userId, new CopyOnWriteArrayList<>())) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(payload));
            } catch (Exception e) {
                removeEmitter(emitter, userId);
            }
        }
    }

    private void removeEmitter(SseEmitter emitter, String userId) {
        // Drop the user's entry once their last stream closes so the map doesn't grow forever.
        userEmitters.computeIfPresent(userId, (k, list) -> {
            list.remove(emitter);
            return list.isEmpty() ? null : list;
        });
    }
}