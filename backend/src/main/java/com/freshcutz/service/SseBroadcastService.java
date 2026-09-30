package com.freshcutz.service;

import com.freshcutz.dto.ProductResponse;
import com.freshcutz.dto.StoreStatusResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes product-catalog and store-status changes to subscribed storefront
 * tabs over Server-Sent Events, replacing what used to be a 15-second
 * setInterval poll of /api/products and /api/store/status from every open
 * tab. A tab now opens one long-lived connection (ProductController's
 * /api/products/stream, StoreStatusController's /api/store/status/stream)
 * and simply waits — the backend only does any work, and only touches the
 * database, when an admin actually changes a product or the store's
 * online/offline status, via broadcastProducts()/broadcastStatus() below
 * (called from AdminProductController / AdminStoreController right after
 * a change is saved).
 */
@Service
public class SseBroadcastService {

    // A bounded lifetime per connection rather than none at all: if
    // Render's own proxy (or anything else in between) ever silently
    // drops a very long-lived idle connection, capping it here means the
    // browser's EventSource just reconnects on schedule instead of the
    // tab quietly going stale until the page is reloaded.
    private static final long EMITTER_TIMEOUT_MS = 25 * 60 * 1000L; // 25 minutes

    private final List<SseEmitter> productEmitters = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> statusEmitters = new CopyOnWriteArrayList<>();

    /** Registers a new subscriber and immediately sends it the current
     * catalog, so a tab that just connected does not have to wait for the
     * next admin edit to see anything. */
    public SseEmitter subscribeProducts(List<ProductResponse> initial) {
        SseEmitter emitter = register(productEmitters);
        send(emitter, "products", initial, productEmitters);
        return emitter;
    }

    /** Same idea as subscribeProducts(), for the store's online/offline
     * status. */
    public SseEmitter subscribeStatus(StoreStatusResponse initial) {
        SseEmitter emitter = register(statusEmitters);
        send(emitter, "status", initial, statusEmitters);
        return emitter;
    }

    public void broadcastProducts(List<ProductResponse> products) {
        for (SseEmitter emitter : productEmitters) {
            send(emitter, "products", products, productEmitters);
        }
    }

    public void broadcastStatus(StoreStatusResponse status) {
        for (SseEmitter emitter : statusEmitters) {
            send(emitter, "status", status, statusEmitters);
        }
    }

    /** Keeps intermediate proxies (Render's included) from treating a
     * quiet-but-healthy connection as idle and closing it, and doubles as
     * a cheap way to notice and drop connections the browser side has
     * already gone away on (a failed send here removes the emitter). */
    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        for (SseEmitter emitter : productEmitters) {
            sendComment(emitter, productEmitters);
        }
        for (SseEmitter emitter : statusEmitters) {
            sendComment(emitter, statusEmitters);
        }
    }

    private SseEmitter register(List<SseEmitter> emitters) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(emitter::complete);
        emitter.onError(e -> emitters.remove(emitter));
        emitters.add(emitter);
        return emitter;
    }

    private void send(SseEmitter emitter, String eventName, Object data, List<SseEmitter> emitters) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception e) {
            // The tab navigated away / lost its connection between us
            // iterating the list and writing to it — just stop tracking
            // it. Deliberately NOT calling emitter.complete() here: by
            // the time send() throws, the servlet container may already
            // have run its own AsyncListener.onError() for this exact
            // connection on ITS OWN thread (that's what onError() below,
            // in register(), is for) — calling complete() again from
            // here, a plain application thread, after that has already
            // happened is precisely what Tomcat's "a non-container
            // thread attempted to use the AsyncContext after an error"
            // guard exists to stop, and it threw straight out of this
            // method, which meant one dead connection could kill the
            // broadcast loop for every tab after it in the list. A
            // broad `catch (Exception e)` here (rather than just
            // IOException/IllegalStateException) is deliberate too, so
            // that whatever a given container throws for "this
            // connection is gone" can never again take the whole
            // broadcast down.
            emitters.remove(emitter);
        }
    }

    private void sendComment(SseEmitter emitter, List<SseEmitter> emitters) {
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
    }
}
