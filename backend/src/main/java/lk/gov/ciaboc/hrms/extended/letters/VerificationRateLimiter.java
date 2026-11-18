package lk.gov.ciaboc.hrms.extended.letters;

import lk.gov.ciaboc.hrms.masterdata.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple fixed-window, per-client-IP limiter for the public verification endpoint (in memory, per instance). Limit
 * and window are system settings. Behind the reverse proxy the client IP comes from the forwarded headers
 * (server.forward-headers-strategy=framework).
 */
@Component
@RequiredArgsConstructor
public class VerificationRateLimiter {
    private static final int MAX_TRACKED = 50_000;

    private final SettingsService settings;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        final long start;
        int count;

        Window(long start) {
            this.start = start;
        }
    }

    /** @return true if the request is allowed. */
    public boolean tryAcquire(String clientIp) {
        int limit = Math.max(1, settings.getInt("ext.verify.rate-limit", 30));
        long windowMs = Math.max(1, settings.getInt("ext.verify.rate-window-minutes", 10)) * 60_000L;
        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED) evict(now, windowMs);
        String key = clientIp == null ? "unknown" : clientIp;
        Window w = windows.compute(key, (k, cur) -> cur == null || now - cur.start >= windowMs ? new Window(now) : cur);
        synchronized (w) {
            if (w.count >= limit) return false;
            w.count++;
            return true;
        }
    }

    @Scheduled(fixedDelay = 600_000L, initialDelay = 600_000L)
    public void cleanup() {
        evict(System.currentTimeMillis(), Math.max(1, settings.getInt("ext.verify.rate-window-minutes", 10)) * 60_000L);
    }

    private void evict(long now, long windowMs) {
        windows.entrySet().removeIf(e -> now - e.getValue().start >= windowMs);
    }

    /** For tests. */
    void reset() {
        windows.clear();
    }
}
