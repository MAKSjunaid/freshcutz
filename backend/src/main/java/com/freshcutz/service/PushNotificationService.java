package com.freshcutz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshcutz.dto.PushSubscriptionRequest;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.PushSubscription;
import com.freshcutz.repository.PushSubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sends Web Push notifications — real OS-level notifications that reach
 * the admin's phone/desktop even when FreshCutz Admin isn't open — for two
 * events: a new order coming in, and an order being marked delivered.
 *
 * Every send is best-effort: a push failing (or VAPID keys not being
 * configured at all) never breaks placing or delivering an order, it just
 * logs and moves on. A subscription the push service reports as gone
 * (410) or not found (404) — the admin uninstalled/revoked it — is
 * deleted so we stop wasting a request on it. */
@Slf4j
@Service
public class PushNotificationService {

    private final PushSubscriptionRepository repository;
    private final ObjectMapper objectMapper;
    private final String vapidPublicKey;
    private final PushService pushService;

    public PushNotificationService(
            PushSubscriptionRepository repository,
            ObjectMapper objectMapper,
            @Value("${freshcutz.push.vapid-public-key:}") String publicKey,
            @Value("${freshcutz.push.vapid-private-key:}") String privateKey,
            @Value("${freshcutz.push.vapid-subject:mailto:admin@example.com}") String subject
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.vapidPublicKey = publicKey;
        Security.addProvider(new BouncyCastleProvider());

        PushService svc = null;
        if (!publicKey.isBlank() && !privateKey.isBlank()) {
            try {
                svc = new PushService(publicKey, privateKey, subject);
            } catch (Exception e) {
                log.warn("Push notifications disabled — couldn't init PushService: {}", e.getMessage());
            }
        } else {
            log.warn("Push notifications disabled — freshcutz.push.vapid-public-key/vapid-private-key not set");
        }
        this.pushService = svc;
    }

    public String getVapidPublicKey() {
        return vapidPublicKey;
    }

    @Transactional
    public void subscribe(PushSubscriptionRequest request) {
        if (request.getEndpoint() == null || request.getKeys() == null) return;
        PushSubscription sub = repository.findByEndpoint(request.getEndpoint()).orElseGet(PushSubscription::new);
        sub.setEndpoint(request.getEndpoint());
        sub.setP256dh(request.getKeys().getP256dh());
        sub.setAuth(request.getKeys().getAuth());
        repository.save(sub);
    }

    @Transactional
    public void unsubscribe(String endpoint) {
        if (endpoint == null) return;
        repository.deleteByEndpoint(endpoint);
    }

    /** Sends one test notification to the given device (or to every device
     * when endpoint is null) and reports exactly what the push service
     * answered, so the admin panel can say "works" or show the real reason it
     * doesn't. Runs synchronously on purpose — unlike the order alerts. */
    public Map<String, Object> sendTest(String endpoint) {
        Map<String, Object> result = new HashMap<>();
        result.put("configured", pushService != null);
        List<Map<String, Object>> details = new ArrayList<>();
        result.put("results", details);
        if (pushService == null) return result;

        List<PushSubscription> subscriptions = endpoint == null
                ? repository.findAll()
                : repository.findByEndpoint(endpoint).map(List::of).orElse(List.of());
        result.put("subscriptions", subscriptions.size());

        Map<String, Object> data = new HashMap<>();
        data.put("type", "test");
        data.put("title", "🔔 Order alerts are working");
        data.put("body", "You'll get a notification here for every new order.");
        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return result;
        }

        for (PushSubscription sub : subscriptions) {
            Map<String, Object> one = new HashMap<>();
            try {
                Subscription.Keys keys = new Subscription.Keys(sub.getP256dh(), sub.getAuth());
                Subscription subscription = new Subscription(sub.getEndpoint(), keys);
                HttpResponse response = pushService.send(new Notification(subscription, json));
                int status = response.getStatusLine().getStatusCode();
                one.put("status", status);
                if (status >= 300 && response.getEntity() != null) {
                    String body = EntityUtils.toString(response.getEntity());
                    one.put("detail", body.length() > 200 ? body.substring(0, 200) : body);
                }
                if (status == 404 || status == 410) repository.delete(sub);
            } catch (Exception e) {
                one.put("status", -1);
                one.put("detail", String.valueOf(e.getMessage()));
                log.warn("Test push failed: {}", e.getMessage());
            }
            details.add(one);
        }
        return result;
    }

    // @Async: runs on a background thread so a slow/unreachable push service
    // never adds latency to placing an order or marking one delivered — the
    // caller (OrderService) doesn't wait for this to finish.
    @Async
    public void notifyNewOrder(Order order) {
        sendToAll(payload(
                "new-order",
                "🐔 New Order — " + order.getOrderCode(),
                order.getCustomerName() + " · ₹" + order.getTotalAmount(),
                order
        ));
    }

    @Async
    public void notifyOrderDelivered(Order order) {
        sendToAll(payload(
                "order-delivered",
                "Order Delivered",
                order.getOrderCode() + " was marked delivered",
                order
        ));
    }

    private Map<String, Object> payload(String type, String title, String body, Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", type);
        data.put("title", title);
        data.put("body", body);
        data.put("orderId", order.getId());
        data.put("orderCode", order.getOrderCode());
        return data;
    }

    // Not @Transactional: this runs on the @Async thread, outside any
    // caller transaction anyway (self-invocation from notifyNewOrder/
    // notifyOrderDelivered wouldn't apply it regardless) — each
    // repository call below already gets its own transaction from Spring
    // Data JPA.
    void sendToAll(Map<String, Object> payload) {
        if (pushService == null) return;
        List<PushSubscription> subscriptions = repository.findAll();
        if (subscriptions.isEmpty()) return;

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("Couldn't serialize push payload: {}", e.getMessage());
            return;
        }

        for (PushSubscription sub : subscriptions) {
            try {
                Subscription.Keys keys = new Subscription.Keys(sub.getP256dh(), sub.getAuth());
                Subscription subscription = new Subscription(sub.getEndpoint(), keys);
                HttpResponse response = pushService.send(new Notification(subscription, json));
                int status = response.getStatusLine().getStatusCode();
                if (status == 404 || status == 410) {
                    repository.delete(sub);
                } else if (status >= 300) {
                    log.warn("Push to {} returned status {}", sub.getEndpoint(), status);
                }
            } catch (Exception e) {
                log.warn("Push to {} failed: {}", sub.getEndpoint(), e.getMessage());
            }
        }
    }
}
