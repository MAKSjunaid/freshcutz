package com.freshcutz.controller;

import com.freshcutz.dto.PushSubscriptionRequest;
import com.freshcutz.service.PushNotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Lets the admin panel register/unregister this device for Web Push
 * notifications. Protected by AdminAuthFilter — only reachable once
 * logged in, which is when admin-push.js offers to subscribe. */
@RestController
@RequestMapping("/api/admin/push")
public class AdminPushController {

    private final PushNotificationService pushNotificationService;

    public AdminPushController(PushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    @GetMapping("/vapid-public-key")
    public Map<String, String> vapidPublicKey() {
        return Map.of("publicKey", pushNotificationService.getVapidPublicKey());
    }

    @PostMapping("/subscribe")
    public void subscribe(@RequestBody PushSubscriptionRequest request) {
        pushNotificationService.subscribe(request);
    }

    /** "Send me a test notification" — called right after turning alerts on. */
    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody(required = false) Map<String, String> body) {
        return pushNotificationService.sendTest(body == null ? null : body.get("endpoint"));
    }

    @PostMapping("/unsubscribe")
    public void unsubscribe(@RequestBody Map<String, String> body) {
        pushNotificationService.unsubscribe(body.get("endpoint"));
    }
}
