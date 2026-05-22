package server.security;

import server.ServerConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/******************** Security Layer ********************************

Componenti di protezione della pipeline request.
Implementano anti-abuso per IP, validazione strutturale payload e gestione
credenziali, secondo il flusso documentato in UML_server (/security).

Concorrenza:
- strutture concorrenti per stato per-IP
- approccio lock-free per massimizzare throughput sotto carico

*****************************************************************************/

public final class RequestGuard {

    private final int maxRequestsPerWindow;
    private final long windowMillis;
    private final int maxStrikes;
    private final long banDurationMillis;

    private final ConcurrentHashMap<String, RateWindow> rateWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> strikes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> bannedUntil = new ConcurrentHashMap<>();

    public RequestGuard(ServerConfig config) {
        this.maxRequestsPerWindow = config.maxRequestsPerSecond;
        this.windowMillis = 1000L;
        this.maxStrikes = config.strikeThresholdShort;
        this.banDurationMillis = config.strikeBanShortSeconds * 1000L;
    }

    public CheckResult check(String ip) {
        long now = System.currentTimeMillis();

        Long banExpiry = bannedUntil.get(ip);
        if (banExpiry != null) {
            if (now < banExpiry) {
                return CheckResult.BANNED;
            }

            bannedUntil.remove(ip);
            strikes.remove(ip);
        }

        RateWindow window = rateWindows.computeIfAbsent(ip,
            k -> new RateWindow(now, new AtomicInteger(0)));

        if (now - window.windowStart > windowMillis) {

            RateWindow newWindow = new RateWindow(now, new AtomicInteger(1));
            rateWindows.put(ip, newWindow);
            return CheckResult.ALLOWED;
        }

        int count = window.requestCount.incrementAndGet();
        if (count > maxRequestsPerWindow) {
            addStrike(ip, now);
            return CheckResult.RATE_LIMITED;
        }

        return CheckResult.ALLOWED;
    }

    public boolean recordViolation(String ip) {
        return addStrike(ip, System.currentTimeMillis());
    }

    public boolean banned(String ip) {
        Long expiry = bannedUntil.get(ip);
        if (expiry == null) return false;
        if (System.currentTimeMillis() >= expiry) {
            bannedUntil.remove(ip);
            return false;
        }
        return true;
    }

    private boolean addStrike(String ip, long now) {
        AtomicInteger counter = strikes.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int total = counter.incrementAndGet();

        if (total >= maxStrikes) {
            bannedUntil.put(ip, now + banDurationMillis);
            return true;
        }
        return false;
    }

    public void cleanup() {
        long now = System.currentTimeMillis();

        bannedUntil.entrySet().removeIf(e -> now >= e.getValue());

        rateWindows.entrySet().removeIf(e ->
            now - e.getValue().windowStart > windowMillis * 2);

        strikes.keySet().removeIf(ip ->
            !bannedUntil.containsKey(ip) && !rateWindows.containsKey(ip));
    }

    public enum CheckResult {
        ALLOWED,
        RATE_LIMITED,
        BANNED
    }

    private static class RateWindow {
        final long windowStart;
        final AtomicInteger requestCount;

        RateWindow(long windowStart, AtomicInteger requestCount) {
            this.windowStart = windowStart;
            this.requestCount = requestCount;
        }
    }
}
