package server.session;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/******************** Player Session ********************************

Rappresenta lo stato di un giocatore connesso o disconnesso.
- Identità stabile: playerId, usato dalla logica interna e dai risultati persistiti
- Identità applicativa: username, passwordHash. Lo username puo' cambiare, il playerId no
- Stato sessione: ONLINE, PLAYING, DISCONNECTED, etc.
- Statistiche di gioco: punteggio totale, partite giocate/vinte/perse, streak, istogramma errori
- Stato partita corrente: gameId, errori, tempo, etc.
- Informazioni di rete: indirizzo UDP per notifiche
- Timestamp per gestione timeout e riconnessioni

Le statistiche aggregate presenti qui servono come stato runtime/cache e vengono
ricostruite dai risultati salvati quando il server riparte. La sorgente stabile
per partite concluse rimane quindi il risultato playerId/gameId salvato nello storage.

*****************************************************************************/

public final class PlayerSession {

    private static final Logger LOG = Logger.getLogger(PlayerSession.class.getName());

    private final long playerId;
    private volatile String username;
    private volatile String passwordHash;
    private final AtomicReference<SessionState> state;

    private int puzzlesPlayed;
    private int puzzlesWon;
    private int puzzlesLost;
    private int currentStreak;
    private int maxStreak;
    private int perfectPuzzles;
    private final int[] mistakeHistogram = new int[7];

    private int totalScore;

    private volatile long disconnectedAt;

    private volatile long lastSeenAt;

    private volatile InetSocketAddress udpAddress;

    private final Object gameLock = new Object();
    private PlayerGameState currentGameState;

    public record Stats(int played, int wins, int losses, int streak,
                        int bestStreak, int perfect, int[] histogram, int score) {
        public Stats {
            histogram = histogram.clone();
        }

        @Override
        public int[] histogram() {
            return histogram.clone();
        }

        public double winRate() {
            return played == 0 ? 0.0 : (wins * 100.0) / played;
        }

        public double lossRate() {
            return played == 0 ? 0.0 : (losses * 100.0) / played;
        }
    }

    public PlayerSession(long playerId, String username, String passwordHash) {
        this.playerId = playerId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.state = new AtomicReference<>(SessionState.OFFLINE);
        this.lastSeenAt = System.currentTimeMillis();
        this.totalScore = 0;
    }

    public PlayerSession(String username, String passwordHash) {
        this(-1L, username, passwordHash);
    }

    public boolean transition(SessionState a, SessionState b) {
        if (!a.canTransitionTo(b)) {
            LOG.warning("Invalid transition attempted: " + a + " → " + b
                        + " for user " + username);
            return false;
        }
        return state.compareAndSet(a, b);
    }

    public boolean go(SessionState target) {
        SessionState current = state.get();
        if (!current.canTransitionTo(target)) {
            LOG.warning("Invalid transition: " + current + " → " + target
                        + " for user " + username);
            return false;
        }
        return state.compareAndSet(current, target);
    }

    public void onDisconnect() {
        SessionState current = state.get();
        if (current == SessionState.ONLINE || current == SessionState.PLAYING
            || current == SessionState.FINISHED) {
            if (state.compareAndSet(current, SessionState.DISCONNECTED)) {
                this.disconnectedAt = System.currentTimeMillis();
                LOG.info("Player " + username + " disconnected from state " + current);
            }
        }
    }

    public void update(String newUsername, String newPasswordHash) {
        if (newUsername != null) this.username = newUsername;
        if (newPasswordHash != null) this.passwordHash = newPasswordHash;
    }

    public long id() { return playerId; }
    public String name() { return username; }
    public String hash() { return passwordHash; }
    public SessionState state() { return state.get(); }
    public long disconnectedAt() { return disconnectedAt; }
    public long seenAt() { return lastSeenAt; }
    public InetSocketAddress udp() { return udpAddress; }
    public synchronized int score() { return totalScore; }
    public synchronized int played() { return puzzlesPlayed; }
    public synchronized int wins() { return puzzlesWon; }
    public synchronized int losses() { return puzzlesLost; }
    public synchronized int streak() { return currentStreak; }
    public synchronized int bestStreak() { return maxStreak; }
    public synchronized int perfect() { return perfectPuzzles; }
    public synchronized int[] histogram() { return mistakeHistogram.clone(); }

    public synchronized double winRate() {
        return puzzlesPlayed == 0 ? 0.0 : (puzzlesWon * 100.0) / puzzlesPlayed;
    }

    public synchronized double lossRate() {
        return puzzlesPlayed == 0 ? 0.0 : (puzzlesLost * 100.0) / puzzlesPlayed;
    }

    public synchronized Stats stats() {
        return new Stats(
            puzzlesPlayed,
            puzzlesWon,
            puzzlesLost,
            currentStreak,
            maxStreak,
            perfectPuzzles,
            mistakeHistogram,
            totalScore
        );
    }

    public void udp(InetSocketAddress addr) { this.udpAddress = addr; }
    public void seen(long ts) { this.lastSeenAt = ts; }

    public void startGame(int gameId) {
        synchronized (gameLock) {
            if (currentGameState == null || currentGameState.gameId() != gameId || currentGameState.done()) {
                currentGameState = new PlayerGameState(gameId);
            }
        }
    }

    public PlayerGameState game() {
        synchronized (gameLock) {
            return currentGameState;
        }
    }

    public PlayerGameState clearGame() {
        synchronized (gameLock) {
            PlayerGameState old = currentGameState;
            currentGameState = null;
            return old;
        }
    }

    public synchronized void updateStats(PlayerGameState pgs) {
        if (pgs == null || !pgs.done()) return;

        puzzlesPlayed++;
        totalScore += pgs.score();

        if (pgs.won()) {
            puzzlesWon++;
            currentStreak++;
            maxStreak = Math.max(maxStreak, currentStreak);
            if (pgs.perfect()) perfectPuzzles++;

            int idx = Math.max(0, Math.min(4, pgs.errors()));
            mistakeHistogram[idx]++;
        } else {
            currentStreak = 0;
            if (pgs.expired()) {
                mistakeHistogram[6]++;
            } else {
                puzzlesLost++;
                mistakeHistogram[5]++;
            }
        }
    }

    public synchronized void restoreStats(int puzzlesPlayed, int puzzlesWon, int puzzlesLost,
                              int currentStreak, int maxStreak, int perfectPuzzles,
                              int[] histogram, int totalScore) {
        this.puzzlesPlayed = puzzlesPlayed;
        this.puzzlesWon = puzzlesWon;
        this.puzzlesLost = puzzlesLost;
        this.currentStreak = currentStreak;
        this.maxStreak = maxStreak;
        this.perfectPuzzles = perfectPuzzles;
        System.arraycopy(histogram, 0, this.mistakeHistogram, 0,
                         Math.min(histogram.length, this.mistakeHistogram.length));
        this.totalScore = totalScore;
    }

    @Override
    public String toString() {
        return String.format("PlayerSession{id=%d, user=%s, state=%s, score=%d}",
            playerId, username, state.get(), totalScore);
    }
}
