package server.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/******************** Session Registry ********************************

Modello runtime delle sessioni attive dei giocatori.
- Mappa primaria thread-safe playerId -> PlayerSession per identita stabile interna
- Indice thread-safe username -> playerId per login e protocollo esterno
- Scritture rare serializzate per tenere coerenti mappa primaria e indice
- Supporto per iterazione su sessioni
- Snapshot coerente del registry per monitor/persistenza

*****************************************************************************/

public final class SessionRegistry {

    private static final Logger LOG = Logger.getLogger(SessionRegistry.class.getName());
    private final ConcurrentHashMap<Long, PlayerSession> byId;
    private final ConcurrentHashMap<String, Long> idByName;
    private final AtomicLong nextPlayerId;

    public SessionRegistry() {
        this.byId = new ConcurrentHashMap<>();
        this.idByName = new ConcurrentHashMap<>();
        this.nextPlayerId = new AtomicLong(1L);
    }

    public long nextPlayerId() {
        return nextPlayerId.getAndIncrement();
    }

    private void observePlayerId(long playerId) {
        nextPlayerId.updateAndGet(cur -> Math.max(cur, playerId + 1));
    }

    public synchronized boolean register(String username, PlayerSession session) {
        if (idByName.containsKey(username) || byId.containsKey(session.id())) {
            return false;
        }

        byId.put(session.id(), session);
        idByName.put(username, session.id());

        observePlayerId(session.id());
        LOG.info("Registered new player: " + username + " (id=" + session.id() + ")");
        return true;
    }

    public PlayerSession get(String username) {
        Long tmp = idByName.get(username);
        return tmp == null ? null : byId.get(tmp);
    }

    public PlayerSession byId(long playerId) {
        return byId.get(playerId);
    }

    public boolean exists(String username) {
        return idByName.containsKey(username);
    }

    public boolean existsId(long playerId) {
        return byId.containsKey(playerId);
    }

    public synchronized boolean rename(String oldUsername, String newUsername, PlayerSession session) {

        Long tmp = idByName.get(oldUsername);
        if (tmp == null || tmp.longValue() != session.id() || idByName.containsKey(newUsername)) {
            return false;
        }

        idByName.put(newUsername, session.id());
        idByName.remove(oldUsername);
        byId.put(session.id(), session);
        LOG.info("Renamed player: " + oldUsername + " → " + newUsername
            + " (id=" + session.id() + ")");
        return true;
    }

    public synchronized void remove(String username) {
        Long tmp = idByName.remove(username);
        if (tmp != null) {
            byId.remove(tmp);
        }
    }

    public void forEachConnected(Consumer<PlayerSession> action) {
        snapshot().forEach(session -> {
            SessionState state = session.state();
            if (state != SessionState.OFFLINE) {
                action.accept(session);
            }
        });
    }

    public void forEach(Consumer<PlayerSession> action) {
        snapshot().forEach(action);
    }

    public synchronized int size() {
        return byId.size();
    }

    public synchronized java.util.Collection<PlayerSession> snapshot() {
        return new java.util.ArrayList<>(byId.values());
    }
}
