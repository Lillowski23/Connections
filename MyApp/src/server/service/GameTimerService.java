package server.service;

import server.game.GameManager;
import server.net.UdpNotifier;
import shared.protocol.Response;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/******************** Service Layer ********************************

Servizi schedulati del runtime server.
Orchestrano persistenza periodica, avanzamento partite e manutenzione
sessioni/sicurezza in thread dedicati e indipendenti.

GameTimerService gestisce il ciclo temporale della partita: avvio, scadenza,
intervallo breve fra due partite e notifica UDP ai client. Quando il timer
scade delega subito a GameManager la chiusura dei player non finiti, cosi' anche
i timeout diventano PlayerGameResult persistibili prima che il client chieda top,
stats o info.

Concorrenza:
- ScheduledExecutorService separato per ogni servizio
- coordinamento con /session, /game, /storage e /security

*****************************************************************************/

public final class GameTimerService {

    private static final Logger LOG = Logger.getLogger(GameTimerService.class.getName());

    private final ScheduledExecutorService scheduler;
    private final GameManager gameManager;
    private final UdpNotifier notifier;
    private final int gameDurationSeconds;
    private final int restSeconds;

    public GameTimerService(GameManager gameManager, UdpNotifier notifier,
                            int gameDurationSeconds, int restSeconds) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-timer");
            t.setDaemon(true);
            return t;
        });
        this.gameManager = gameManager;
        this.notifier = notifier;
        this.gameDurationSeconds = gameDurationSeconds;
        this.restSeconds = restSeconds;
    }

    public void start() {
        LOG.info("GameTimerService starting with duration=" + gameDurationSeconds
                 + "s, rest=" + restSeconds + "s");
        try {
            boolean started = gameManager.advanceGame();
            if (started) {
                var game = gameManager.current();
                if (game != null) {
                    LOG.info("First game started: id=" + game.gameId()
                             + " remaining=" + game.left() + "s");
                }
                notifyNewGame();
                scheduleNextAdvance();
            } else {
                LOG.severe("No games available at startup!");
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Failed to start first game", e);
        }
    }

    private void scheduleNextAdvance() {
        LOG.info("Next game transition scheduled in " + gameDurationSeconds + "s");
        scheduler.schedule(this::onGameExpired, gameDurationSeconds, TimeUnit.SECONDS);
    }

    private void onGameExpired() {
        var before = gameManager.current();
        LOG.info("Timer expired for game id=" + (before != null ? before.gameId() : -1));

        gameManager.expireCurrentGame();
        notifyGameEnd();
        LOG.info("Next game in " + restSeconds + "s");
        scheduler.schedule(this::advanceAfterIntermission, restSeconds, TimeUnit.SECONDS);
    }

    private void advanceAfterIntermission() {
        try {
            boolean hasNext = gameManager.advanceGame();
            if (hasNext) {
                var next = gameManager.current();
                if (next != null) {
                    LOG.info("Advanced to game id=" + next.gameId()
                             + " remaining=" + next.left() + "s");
                }
                notifyNewGame();
                scheduleNextAdvance();
            } else {
                LOG.info("All games completed! Server stays up for stats queries.");
                notifyAllGamesCompleted();
            }
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Error advancing game", e);
            scheduler.schedule(this::advanceAfterIntermission, 5, TimeUnit.SECONDS);
        }
    }

    private void notifyNewGame() {
        var game = gameManager.current();
        if (game != null) {
            notifier.send(
                Response.notification(Response.NOTICE_NEW_GAME)
                    .with(Response.KEY_GAME_ID, game.gameId())
                    .with(Response.KEY_TIME_REMAINING, game.left())
                    .build()
            );
        }
    }

    private void notifyGameEnd() {
        notifier.send(
            Response.notification(Response.NOTICE_GAME_ENDED)
                .with(Response.KEY_MESSAGE,
                      "Time's up! Type 'info' now; next game starts in "
                      + restSeconds + "s.")
                .build()
        );
    }

    private void notifyAllGamesCompleted() {
        notifier.send(
            Response.notification(Response.NOTICE_ALL_GAMES_COMPLETED)
                .with(Response.KEY_MESSAGE, "All puzzles have been played!")
                .build()
        );
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
