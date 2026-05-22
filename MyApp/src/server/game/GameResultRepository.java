package server.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/******************** Game Result Repository ********************************

Tiene in memoria i risultati consolidati delle partite giocate dai player.
La chiave logica non usa lo username ma la coppia playerId/gameId, cosi'
anche se il nome del giocatore viene aggiornato lo storico rimane agganciato
alla stessa identita' stabile.

Da questa struttura vengono ricavate le statistiche personali, le statistiche
per partita e i punteggi usati dalla leaderboard. Non conserva il log completo
delle proposte fatte durante la partita, ma solo il risultato sintetico necessario
per persistenza e ricostruzione dello stato derivato.

Concorrenza:
- ReadWriteLock: molte query storiche in parallelo, commit dei risultati esclusivi
- indici coerenti per chiave, player, partita, score e statistiche aggregate
- addResult impedisce doppi risultati dello stesso player nella stessa partita

*****************************************************************************/
public final class GameResultRepository {
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<ResultKey, PlayerGameResult> byKey = new HashMap<>();
    private final Map<Long, Map<Integer, PlayerGameResult>> byPlayer = new HashMap<>();
    private final Map<Integer, Map<Long, PlayerGameResult>> byGame = new HashMap<>();
    private final Map<Long, Integer> totalScoreByPlayer = new HashMap<>();
    private final Map<Integer, GameStatsCounter> gameStats = new HashMap<>();
    private int maxGameId = -1;

    private record ResultKey(long playerId, int gameId) {}

    public boolean addResult(PlayerGameResult result) {
        lock.writeLock().lock();
        try {
            return addUnlocked(result);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public PlayerGameResult one(long playerId, int gameId) {
        lock.readLock().lock();
        try {
            return byKey.get(new ResultKey(playerId, gameId));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PlayerGameResult> byPlayer(long playerId) {
        lock.readLock().lock();
        try {
            Map<Integer, PlayerGameResult> playerResults = byPlayer.get(playerId);
            if (playerResults == null) {
                return new ArrayList<>();
            }

            List<PlayerGameResult> out = new ArrayList<>(playerResults.values());
            out.sort(Comparator.comparingLong(PlayerGameResult::finishedAt));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<PlayerGameResult> byGame(int gameId) {
        lock.readLock().lock();
        try {
            Map<Long, PlayerGameResult> gameResults = byGame.get(gameId);
            if (gameResults == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(gameResults.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Collection<PlayerGameResult> snapshot() {
        lock.readLock().lock();
        try {
            return new ArrayList<>(byKey.values());
        } finally {
            lock.readLock().unlock();
        }
    }


    public int maxGameId() {
        lock.readLock().lock();
        try {
            return maxGameId;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void restore(Collection<PlayerGameResult> saved) {
        lock.writeLock().lock();
        try {
            clearUnlocked();
            if (saved == null) return;
            for (PlayerGameResult r : saved) {
                addUnlocked(r);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public int totalScore(long playerId) {
        lock.readLock().lock();
        try {
            return totalScoreByPlayer.getOrDefault(playerId, 0);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Map<Long, Integer> totalScoreByPlayerId() {
        lock.readLock().lock();
        try {
            return new HashMap<>(totalScoreByPlayer);
        } finally {
            lock.readLock().unlock();
        }
    }

    public GameStatsSummary gameStats(int gameId) {
        lock.readLock().lock();
        try {
            GameStatsCounter counter = gameStats.get(gameId);
            return counter == null ? null : counter.snapshot();
        } finally {
            lock.readLock().unlock();
        }
    }

    public PlayerStatsSummary playerStats(long playerId) {
        List<PlayerGameResult> list = byPlayer(playerId);
        int played = list.size();
        int won = 0;
        int lost = 0;
        int perfect = 0;
        int totalScore = 0;
        int currentStreak = 0;
        int maxStreak = 0;
        int[] histogram = new int[7];

        for (PlayerGameResult r : list) {
            totalScore += r.score();
            if (r.won()) {
                won++;
                currentStreak++;
                maxStreak = Math.max(maxStreak, currentStreak);
                if (r.mistakes() == 0) perfect++;
                int idx = Math.max(0, Math.min(4, r.mistakes()));
                histogram[idx]++;
            } else {
                if (r.expired()) {
                    histogram[6]++;
                } else {
                    lost++;
                    histogram[5]++;
                }
                currentStreak = 0;
            }
        }
        return new PlayerStatsSummary(played, won, lost, currentStreak, maxStreak,
            perfect, histogram, totalScore);
    }

    private boolean addUnlocked(PlayerGameResult result) {
        if (result == null || result.id() <= 0) {
            return false;
        }

        ResultKey key = new ResultKey(result.id(), result.gameId());
        if (byKey.containsKey(key)) {
            return false;
        }

        byKey.put(key, result);
        byPlayer.computeIfAbsent(result.id(), k -> new HashMap<>()).put(result.gameId(), result);
        byGame.computeIfAbsent(result.gameId(), k -> new HashMap<>()).put(result.id(), result);
        totalScoreByPlayer.merge(result.id(), result.score(), Integer::sum);
        gameStats.computeIfAbsent(result.gameId(), k -> new GameStatsCounter()).add(result);
        maxGameId = Math.max(maxGameId, result.gameId());
        return true;
    }

    private void clearUnlocked() {
        byKey.clear();
        byPlayer.clear();
        byGame.clear();
        totalScoreByPlayer.clear();
        gameStats.clear();
        maxGameId = -1;
    }

    private static final class GameStatsCounter {
        private int participants;
        private int finished;
        private int won;
        private int totalScore;

        private void add(PlayerGameResult result) {
            participants++;
            finished++;
            if (result.won()) {
                won++;
            }
            totalScore += result.score();
        }

        private GameStatsSummary snapshot() {
            return new GameStatsSummary(participants, finished, won, totalScore);
        }
    }

    public record GameStatsSummary(int participants, int finished, int won, int totalScore) {}

    public record PlayerStatsSummary(int puzzlesPlayed, int puzzlesWon, int puzzlesLost,
                                     int currentStreak, int maxStreak, int perfectPuzzles,
                                     int[] mistakeHistogram, int totalScore) {}
}
