package server.service;

import server.session.PlayerSession;
import server.session.SessionRegistry;
import server.storage.StorageManager;
import server.leaderboard.Leaderboard;
import server.game.GameManager;
import server.game.GameResultRepository;
import server.game.PlayerGameResult;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/******************** Service Layer ********************************

Servizi schedulati del runtime server.
Orchestrano persistenza periodica, avanzamento partite e manutenzione
sessioni/sicurezza in thread dedicati e indipendenti.

Questo servizio carica utenti e risultati consolidati all'avvio, poi ricostruisce
lo stato derivato: statistiche aggregate nelle sessioni e leaderboard in memoria.
Durante l'esecuzione salva periodicamente utenti e risultati playerId/gameId,
lasciando fuori dallo storage le viste ricostruibili come la classifica ordinata.

*****************************************************************************/

public final class PersistenceService {

    private static final Logger LOG = Logger.getLogger(PersistenceService.class.getName());

    private final ScheduledExecutorService scheduler;
    private final StorageManager storageManager;
    private final SessionRegistry registry;
    private final Leaderboard leaderboard;
    private final GameManager gameManager;
    private final int intervalSeconds;

    public PersistenceService(StorageManager storageManager, SessionRegistry registry,
                               Leaderboard leaderboard, GameManager gameManager,
                               int intervalSeconds) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "persistence");
            t.setDaemon(true);
            return t;
        });
        this.storageManager = storageManager;
        this.registry = registry;
        this.leaderboard = leaderboard;
        this.gameManager = gameManager;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() throws IOException {
        int loadedUsers = storageManager.loadUsers(registry);
        LOG.info("Loaded " + loadedUsers + " users from persistence");

        List<PlayerGameResult> results = storageManager.loadGameResults(registry);
        gameManager.repo().restore(results);
        gameManager.alignResumeToStoredResults();
        rebuildDerivedState(gameManager.repo());
        LOG.info("Loaded " + results.size() + " game results from persistence");

        scheduler.scheduleAtFixedRate(
            this::persist,
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
        LOG.info("PersistenceService started (interval=" + intervalSeconds + "s)");
    }

    private void rebuildDerivedState(GameResultRepository repo) {
        for (PlayerSession session : registry.snapshot()) {
            GameResultRepository.PlayerStatsSummary stats = repo.playerStats(session.id());
            session.restoreStats(
                stats.puzzlesPlayed(),
                stats.puzzlesWon(),
                stats.puzzlesLost(),
                stats.currentStreak(),
                stats.maxStreak(),
                stats.perfectPuzzles(),
                stats.mistakeHistogram(),
                stats.totalScore()
            );
        }

        leaderboard.clear();
        for (Map.Entry<Long, Integer> e : repo.totalScoreByPlayerId().entrySet()) {
            leaderboard.update(e.getKey(), e.getValue());
        }

        // Utenti registrati ma senza partite devono comunque comparire con score 0.
        registry.forEach(session -> {
            if (leaderboard.score(session.id()) == null) {
                leaderboard.update(session.id(), 0);
            }
        });
    }

    private void persist() {
        try {
            storageManager.saveUsers(registry);
            storageManager.saveGameResults(gameManager.repo().snapshot());
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Persistence failed, will retry next cycle", e);
        }
    }

    public void saveNow() {
        try {
            storageManager.saveUsers(registry);
            storageManager.saveGameResults(gameManager.repo().snapshot());
            LOG.info("Final persistence save completed");
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Final persistence save FAILED", e);
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

        saveNow();
    }
}
