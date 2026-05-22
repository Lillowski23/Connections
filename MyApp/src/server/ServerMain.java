package server;

import server.core.RequestRouter;
import server.core.WorkerPool;
import server.game.GameLoader;
import server.game.GameManager;
import server.handler.*;
import server.leaderboard.Leaderboard;
import server.net.Acceptor;
import server.net.IODispatcher;
import server.net.UdpNotifier;
import server.security.RequestGuard;
import server.service.GameTimerService;
import server.service.PersistenceService;
import server.service.SessionMonitorService;
import server.session.SessionRegistry;
import server.storage.ByteBufferPool;
import server.storage.StorageManager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/******************** Server Main ********************************

Entry point del server: 
Qui avviene esclusivamnte il wiring dei componenti.
La logica di gioco vera è nei manager/handler, non nel main.

Scelte organizzative:
    - init in ordine di dipendenze
    - separazione tra I/O, regole del gioco e servizi schedulati
    - shutdown ordinato per evitare perdita dati e thread zombie


************************************************************************************/

public final class ServerMain {

    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) {
        try {
            // --- Configurazione ---
            String configPath = args.length > 0 ? args[0] : "config/server_config.json";
            ServerConfig config = ServerConfig.load(Path.of(configPath));
            LOG.info("Config loaded: " + config);

            // --- Stato condiviso ---
            SessionRegistry registry = new SessionRegistry();
            Leaderboard leaderboard = new Leaderboard();
            StorageManager storage = new StorageManager(config.persistenceUsersPath, config.persistenceGamesPath);

            // --- Sicurezza ---
            RequestGuard guard = new RequestGuard(config);

            // --- Worker Pool ---
            WorkerPool workerPool = new WorkerPool(config);
            LOG.info("WorkerPool created: core=" + config.poolCoreSize
                     + ", max=" + config.poolMaxSize);

            // --- Buffer Pool ---
            ByteBufferPool bufferPool = new ByteBufferPool(
                config.ioBufferPoolSize, config.ioBufferSize);

            // --- Game Data + Manager ---
            long gameDurationMs = config.gameDurationSeconds * 1000L;
            LOG.info("Game duration configured: " + config.gameDurationSeconds
                     + "s (" + gameDurationMs + "ms), rest="
                     + config.gameRestSeconds + "s");
            GameLoader dataProvider = new GameLoader(
                Path.of(config.gameDataPath), gameDurationMs);
            GameManager gameManager = new GameManager(dataProvider, registry, leaderboard);

            // --- UDP Notifier ---
            UdpNotifier udpNotifier = new UdpNotifier(registry);

            // --- Handlers ---
            RegisterHandler registerHandler = new RegisterHandler(registry, gameManager, leaderboard);
            LoginHandler loginHandler = new LoginHandler(registry, gameManager);
            LogoutHandler logoutHandler = new LogoutHandler();
            UpdateCredentialsHandler updateHandler = new UpdateCredentialsHandler(registry);
            SubmitProposalHandler proposalHandler = new SubmitProposalHandler(
                gameManager, leaderboard);
            GameInfoHandler gameInfoHandler = new GameInfoHandler(gameManager);
            GameStatsHandler gameStatsHandler = new GameStatsHandler(gameManager);
            LeaderboardHandler leaderboardHandler = new LeaderboardHandler(
                leaderboard, registry);
            PlayerStatsHandler playerStatsHandler = new PlayerStatsHandler();

            // --- Router ---
            RequestRouter router = new RequestRouter(
                registerHandler, loginHandler, logoutHandler, updateHandler,
                proposalHandler, gameInfoHandler, gameStatsHandler,
                leaderboardHandler, playerStatsHandler
            );

            // --- Services ---
            PersistenceService persistenceService = new PersistenceService(
                storage, registry, leaderboard, gameManager, config.persistenceIntervalSeconds);
            persistenceService.start(); // carica utenti, rebuild leaderboard, avvia scheduler

            GameTimerService gameTimerService = new GameTimerService(
                gameManager, udpNotifier, config.gameDurationSeconds,
                config.gameRestSeconds);
            gameTimerService.start();

            SessionMonitorService sessionMonitor = new SessionMonitorService(
                registry, guard,
                config.reconnectGraceSeconds,
                config.evictInactiveHours,
                config.sessionMonitorIntervalSeconds);
            sessionMonitor.start();

            // --- IO Dispatchers ---
            int dispatcherCount = config.ioDispatcherCount > 0
                ? config.ioDispatcherCount
                : Runtime.getRuntime().availableProcessors();

            IODispatcher[] dispatchers = new IODispatcher[dispatcherCount];
            Thread[] dispatcherThreads = new Thread[dispatcherCount];

            for (int i = 0; i < dispatcherCount; i++) {
                dispatchers[i] = new IODispatcher(i, bufferPool, workerPool, router, guard);
                dispatcherThreads[i] = new Thread(dispatchers[i], "io-dispatcher-" + i);
                dispatcherThreads[i].setDaemon(true);
            }

            // --- Acceptor ---
            Acceptor acceptor = new Acceptor(config, dispatchers);
            Thread acceptorThread = new Thread(acceptor, "acceptor");
            acceptorThread.setDaemon(true);

            // ===== Shutdown Hook =====
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutdown initiated...");

                // 1. Ferma l'accettazione di nuove connessioni
                acceptor.stop();

                // 2. Ferma i servizi
                gameTimerService.shutdown();
                sessionMonitor.shutdown();

                // 3. Ferma il worker pool (completa i task in coda)
                workerPool.shutdown();

                // 4. Salvataggio finale
                persistenceService.shutdown();

                // 5. Chiudi le risorse
                try { dataProvider.close(); } catch (Exception e) { /* ignore */ }
                try { udpNotifier.close(); } catch (Exception e) { /* ignore */ }

                LOG.info("Server shutdown complete.");
            }, "shutdown-hook"));

            // --- Network Startup ---
            for (Thread dispatcherThread : dispatcherThreads) {
                dispatcherThread.start();
            }
            LOG.info(dispatcherCount + " IODispatchers started");

            acceptorThread.start();

            LOG.info("=== CONNECTIONS Server ready ===");
            LOG.info("TCP port: " + config.tcpPort);

            // Il main thread attende (i daemon thread tengono vivo il processo
            // grazie allo shutdown hook che è un non-daemon thread)
            Thread.currentThread().join();

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Server failed to start", e);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
