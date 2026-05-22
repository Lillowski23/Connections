package server.leaderboard;

import server.session.SessionRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Logger;

/******************** Leaderboard ********************************

Indice ordinato concorrente della classifica globale.
Mantiene ranking per score/playerId e supporta query top/rank con costo basso.
Lo username non è usato come chiave interna perché può cambiare: viene risolto
solo quando si costruisce la risposta verso il client.

La leaderboard non e' lo storage primario del punteggio: e' una vista in memoria
ricostruibile dai risultati persistenti. In questo modo il cambio username non
richiede nessuna modifica allo storico, perche' i punteggi restano legati al playerId.

Concorrenza:
- ConcurrentSkipListMap per ordine + accessi concorrenti
- ConcurrentHashMap di supporto per lookup rapido delle chiavi correnti

*****************************************************************************/

public final class Leaderboard {

    private static final Logger LOG = Logger.getLogger(Leaderboard.class.getName());

    public record ScoreKey(int score, long playerId) implements Comparable<ScoreKey> {
        @Override
        public int compareTo(ScoreKey other) {

            int cmp = Integer.compare(other.score, this.score);
            if (cmp != 0) return cmp;

            return Long.compare(this.playerId, other.playerId);
        }
    }

    private final ConcurrentSkipListMap<ScoreKey, Long> rankings = new ConcurrentSkipListMap<>();

    private final java.util.concurrent.ConcurrentHashMap<Long, ScoreKey> currentKeys = new java.util.concurrent.ConcurrentHashMap<>();

    public void update(long playerId, int newTotalScore) {

        ScoreKey oldKey = currentKeys.get(playerId);
        if (oldKey != null) {
            rankings.remove(oldKey);
        }

        ScoreKey newKey = new ScoreKey(newTotalScore, playerId);
        rankings.put(newKey, playerId);
        currentKeys.put(playerId, newKey);
    }

    public void clear() {
        rankings.clear();
        currentKeys.clear();
    }

    public List<ScoreKey> all() {
        return new ArrayList<>(rankings.keySet());
    }

    public List<ScoreKey> top(int k) {
        List<ScoreKey> result = new ArrayList<>();
        int count = 0;
        for (ScoreKey key : rankings.keySet()) {
            if (count >= k) break;
            result.add(key);
            count++;
        }
        return result;
    }

    public int rank(long playerId) {
        ScoreKey key = currentKeys.get(playerId);
        if (key == null) return -1;

        return rankings.headMap(key).size() + 1;
    }

    public ScoreKey score(long playerId) {
        return currentKeys.get(playerId);
    }

    public int size() {
        return rankings.size();
    }

    public void rebuildFrom(SessionRegistry registry) {
        registry.forEach(session -> {
            update(session.id(), session.score());
        });
        LOG.info("Leaderboard rebuilt with " + size() + " players");
    }
}
