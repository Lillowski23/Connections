package server.service;

import server.session.PlayerSession;
import server.session.SessionRegistry;
import server.session.SessionState;
import server.security.RequestGuard;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/******************** Service Layer ********************************

Servizi schedulati del runtime server.
Orchestrano persistenza periodica, avanzamento partite e manutenzione
sessioni/sicurezza in thread dedicati e indipendenti.

Concorrenza:
- ScheduledExecutorService separato per ogni servizio
- coordinamento con /session, /game, /storage e /security

*****************************************************************************/

public final class SessionMonitorService {

    private static final Logger LOG = Logger.getLogger(SessionMonitorService.class.getName());

    private final ScheduledExecutorService scheduler;
    private final SessionRegistry registry;
    private final RequestGuard guard;
    private final long reconnectGraceMillis;
    private final long evictInactiveMillis;
    private final int intervalSeconds;

    public SessionMonitorService(SessionRegistry registry, RequestGuard guard,
                                  int reconnectGraceSeconds, int evictInactiveHours,
                                  int intervalSeconds) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-monitor");
            t.setDaemon(true);
            return t;
        });
        this.registry = registry;
        this.guard = guard;
        this.reconnectGraceMillis = reconnectGraceSeconds * 1000L;
        this.evictInactiveMillis = evictInactiveHours * 3600_000L;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(
            this::sweep,
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
        LOG.info("SessionMonitor started (interval=" + intervalSeconds + "s, "
                 + "grace=" + (reconnectGraceMillis / 1000) + "s, "
                 + "evict=" + (evictInactiveMillis / 3600_000) + "h)");
    }

    private void sweep() {
        long tmpNow = System.currentTimeMillis();
        int tmpDisconnected = 0;
        int tmpEvicted = 0;

        for (PlayerSession tmp : registry.snapshot()) {
            SessionState tmpState = tmp.state();

            switch (tmpState) {
                case DISCONNECTED:
                    if (tmpNow - tmp.disconnectedAt() > reconnectGraceMillis) {
                        tmp.clearGame();
                        tmp.go(SessionState.OFFLINE);
                        tmpDisconnected++;
                        LOG.fine("Grace expired for " + tmp.name());
                    }
                    break;

                case OFFLINE:
                    if (tmpNow - tmp.seenAt() > evictInactiveMillis) {
                        registry.remove(tmp.name());
                        tmpEvicted++;
                        LOG.info("Evicted inactive session: " + tmp.name());
                    }
                    break;

                default:
                    break;
            }
        }

        guard.cleanup();

        if (tmpDisconnected > 0 || tmpEvicted > 0) {
            LOG.info("SessionMonitor sweep: "
                     + tmpDisconnected + " disconnect-expired, "
                     + tmpEvicted + " evicted");
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
