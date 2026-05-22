package server.game;

import server.session.PlayerGameState;
import server.session.PlayerSession;
import server.session.SessionRegistry;
import server.session.SessionState;
import server.leaderboard.Leaderboard;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;


/******************** Game Manager ********************************
Compiti principali:
        - Mantiene la partita corrente
        - Timer scade => chiude i player non finiti e passa alla prossima partita
        - Gestisce le proposte e aggiorna lo stato dei giocatori
        - Consolida i risultati in GameResultRepository usando playerId/gameId
        - Aggiorna la leaderboard in memoria quando un risultato viene chiuso
        - Fornisce info sulla partita corrente e sullo storico
 
Regola di gioco:
        Il player vince quando trova 3 gruppi su 4. Il quarto gruppo rimasto e'
        determinato per esclusione, quindi non viene richiesto come proposta finale.

Persistenza/resume:
        Lo storico persistito vive nei PlayerGameResult. All'avvio, dopo il load,
        GameManager si allinea al massimo gameId gia' salvato in modo da ripartire
        dalla partita successiva e non riproporre una partita gia' conclusa.
 
Concorrenza:
        La partita corrente viene letta da un sacco di thread (worker che rispondono
        a requestGameInfo, requestGameStats) e modificata da un solo componente. 
        Questo è il pattern ReadWriteLock:
        - ReadLock: le read sono progettate per funzionare in parallelo
        - WriteLock: write esclusive, ha senso bloccare tutta la struttura
 
 Le proposte dei giocatori sono completamente separate:
        la proposta risiede solo in PlayerGameState (uno per giocatore),
        per quanto riguarda la partita corrente,
        possono esserci anche un miliardo di richieste in parallelo.

************************************************************************************/


public final class GameManager {

    private static final Logger LOG = Logger.getLogger(GameManager.class.getName());
    private final GameLoader gL;
    private final SessionRegistry sessionRegistry;
    private final GameResultRepository resultRepository;
    private final Leaderboard leaderboard;

    private final ReadWriteLock gameLock = new ReentrantReadWriteLock();
    private volatile GameState currentGame;

    
    private final AtomicInteger playersActive = new AtomicInteger(0);
    private final AtomicInteger playersFinished = new AtomicInteger(0);
    private final AtomicInteger playersWon = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, StatsCounter> statsPerGame = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<Long>> joinedPlayersPerGame = new ConcurrentHashMap<>();
    private volatile int lastSavedGameId = -1;
    private volatile boolean resumeAligned = false;

    public static final class GameStatsData {
        public final int participants;
        public final int finished;
        public final int won;
        public final int totalScore;

        public GameStatsData(int participants, int finished, int won, int totalScore) {
            this.participants = participants;
            this.finished = finished;
            this.won = won;
            this.totalScore = totalScore;
        }
    }

    private static final class StatsCounter {
        private final AtomicInteger participants = new AtomicInteger(0);
        private final AtomicInteger finished = new AtomicInteger(0);
        private final AtomicInteger won = new AtomicInteger(0);
        private final AtomicInteger totalScore = new AtomicInteger(0);

        private StatsCounter() {}

        private StatsCounter(GameStatsData d) {
            participants.set(d.participants);
            finished.set(d.finished);
            won.set(d.won);
            totalScore.set(d.totalScore);
        }

        private GameStatsData snapshot() {
            return new GameStatsData(participants.get(), finished.get(), won.get(), totalScore.get());
        }
    }

    public record ProposalResult(
        Type type,
        int groupIndex,        // -1 se non applicabile
        String theme,          // null se non applicabile
        boolean gameEnded,     // true se il giocatore ha vinto o perso
        PlayerGameState state, 
        String errorMessage    
    ) {
        public enum Type { CORRECT, WRONG, MALFORMED, ERROR }

        static ProposalResult correct(int groupIndex, String theme, boolean won, PlayerGameState state) {
            return new ProposalResult(Type.CORRECT, groupIndex, theme, won, state, null);
        }

        static ProposalResult wrong(boolean lost, PlayerGameState state) {
            return new ProposalResult(Type.WRONG, -1, null, lost, state, null);
        }

        static ProposalResult malformed(String msg) {
            return new ProposalResult(Type.MALFORMED, -1, null, false, null, msg);
        }

        static ProposalResult error(String msg) {
            return new ProposalResult(Type.ERROR, -1, null, false, null, msg);
        }
    }

    
    public GameManager(GameLoader gL, SessionRegistry sessionRegistry) {
        this(gL, sessionRegistry, new GameResultRepository(), null);
    }

    public GameManager(GameLoader gL, SessionRegistry sessionRegistry, Leaderboard leaderboard) {
        this(gL, sessionRegistry, new GameResultRepository(), leaderboard);
    }

    public GameManager(GameLoader gL, SessionRegistry sessionRegistry, GameResultRepository resultRepository) {
        this(gL, sessionRegistry, resultRepository, null);
    }

    public GameManager(GameLoader gL, SessionRegistry sessionRegistry,
                       GameResultRepository resultRepository, Leaderboard leaderboard) {
        this.gL = gL;
        this.sessionRegistry = sessionRegistry;
        this.resultRepository = resultRepository;
        this.leaderboard = leaderboard;
    }

    private StatsCounter realStatsCounter(int gameId) {
        return statsPerGame.computeIfAbsent(gameId, k -> new StatsCounter());
    }

    private boolean join(int gameId, long playerId) {
        joinedPlayersPerGame.computeIfAbsent(gameId, k -> ConcurrentHashMap.newKeySet());
        Set<Long> joined = joinedPlayersPerGame.get(gameId);
        if (joined.add(playerId)) {
            realStatsCounter(gameId).participants.incrementAndGet();
            return true;
        }
        return false;
    }

    private void finish(int gameId, boolean wonGame, int score) {
        StatsCounter s = realStatsCounter(gameId);
        s.finished.incrementAndGet();
        if (wonGame) {
            s.won.incrementAndGet();
        }
        s.totalScore.addAndGet(score);
    }

    
    public boolean advanceGame() throws IOException {
        gameLock.writeLock().lock();
        try {
            if (currentGame != null) {
                endCurrentGame();
            }

            GameState nextGame;
            if (!resumeAligned && currentGame == null && lastSavedGameId >= 0) {
                LOG.info("resume: last saved gameId=" + lastSavedGameId + ", searching next game");
                nextGame = null;
                while (true) {
                    GameState candidate = gL.loadNext();
                    if (candidate == null) {
                        LOG.warning("resume: no game found after saved gameId=" + lastSavedGameId);
                        break;
                    }
                    if (candidate.gameId() > lastSavedGameId) {
                        nextGame = candidate;
                        LOG.info("resume: next game selected=" + candidate.gameId());
                        break;
                    }
                }
                resumeAligned = true;
            } else {
                nextGame = gL.loadNext();
                resumeAligned = true;
            }

            if (nextGame == null) {
                currentGame = null;
                LOG.warning("Game not found!");
                return false;
            }

            currentGame = nextGame;
            playersActive.set(0);
            playersFinished.set(0);
            playersWon.set(0);
            realStatsCounter(currentGame.gameId());

            LOG.info("Game started: " + currentGame.gameId());

            return true;

        } finally {
            gameLock.writeLock().unlock();
        }
    }


    public void expireCurrentGame() {
        gameLock.writeLock().lock();
        try {
            if (currentGame != null) {
                endCurrentGame();
            }
        } finally {
            gameLock.writeLock().unlock();
        }
    }
    
    private void endCurrentGame() {
        sessionRegistry.forEachConnected(session -> {
            PlayerGameState pgs = session.game();
            if (pgs != null && currentGame != null && pgs.gameId() == currentGame.gameId()) {
                if (!pgs.done()) {
                    pgs.onTimeExpired(); // partita non finita entro il tempo
                }
                finalizePlayerGame(session, pgs);
                session.clearGame();
            }
        });
    }

    private boolean finalizePlayerGame(PlayerSession session, PlayerGameState pgs) {
        if (session == null || pgs == null || !pgs.done()) {
            return false;
        }

        PlayerGameResult result = pgs.toResult(session.id());
        boolean added = resultRepository.addResult(result);
        if (added) {
            playersActive.updateAndGet(v -> Math.max(0, v - 1));
            playersFinished.incrementAndGet();
            if (pgs.won()) {
                playersWon.incrementAndGet();
            }
            finish(pgs.gameId(), pgs.won(), pgs.score());
            session.updateStats(pgs);
            if (leaderboard != null) {
                leaderboard.update(session.id(), resultRepository.totalScore(session.id()));
            }
        }

        if (session.state() != SessionState.FINISHED) {
            session.go(SessionState.FINISHED);
        }
        return added;
    }

   
    public ProposalResult check(PlayerSession session, java.util.Set<String> proposedWords) {
        gameLock.readLock().lock();
        try {
            if (currentGame == null) {
                return ProposalResult.error("No active game");
            }

            if (currentGame.expired()) {
                return ProposalResult.error("Game is no longer active");
            }

            PlayerGameState pgs = session.game();
            if (pgs != null && pgs.gameId() == currentGame.gameId() && pgs.done()) {
                return ProposalResult.error("Game already finished");
            }

            if (!currentGame.areAllWordsValid(proposedWords)) {
                return ProposalResult.malformed("Invalid words");
            }

            if (pgs == null || pgs.gameId() != currentGame.gameId()) {
                session.startGame(currentGame.gameId());
                session.go(SessionState.PLAYING);
                if (join(currentGame.gameId(), session.id())) {
                    playersActive.incrementAndGet();
                }
                pgs = session.game();
            }

            for (String word : proposedWords) {
                for (int i = 0; i < 4; i++) {
                    if (pgs.found(i) && currentGame.group(i).words().contains(word)) {
                        return ProposalResult.malformed("Error: " + word + " already matched");
                    }
                }
            }

            int idx = currentGame.check(proposedWords);

            if (idx >= 0) {
                // Proposta corretta
                boolean won = pgs.recordCorrectProposal(idx);
                String theme = currentGame.group(idx).theme();

                if (won) {
                    finalizePlayerGame(session, pgs);
                }

                return ProposalResult.correct(idx, theme, won, pgs);
            } else {
                // Proposta sbagliata
                boolean lost = pgs.recordWrongProposal();

                if (lost) {
                    finalizePlayerGame(session, pgs);
                }

                return ProposalResult.wrong(lost, pgs);
            }

        } finally {
            gameLock.readLock().unlock();
        }
    }

    
    public void joinCurrentGame(PlayerSession session) {
        gameLock.readLock().lock();
        try {
            if (currentGame != null && currentGame.running()) {
                session.startGame(currentGame.gameId());
                session.go(SessionState.PLAYING);
                if (join(currentGame.gameId(), session.id())) {
                    playersActive.incrementAndGet();
                }
            }
        } finally {
            gameLock.readLock().unlock();
        }
    }

    
    public boolean inCurrent(PlayerSession session) {
        gameLock.readLock().lock();
        try {
            if (currentGame == null) return false;
            PlayerGameState pgs = session.game();
            return pgs != null && pgs.gameId() == currentGame.gameId();
        } finally {
            gameLock.readLock().unlock();
        }
    }


    public GameState byId(int gameId) {
        try {
            return gL.loadById(gameId);
        } catch (IOException e) {
            LOG.warning("Cannot load game " + gameId + ": " + e.getMessage());
            return null;
        }
    }

    public PlayerGameResult result(long playerId, int gameId) {
        return resultRepository.one(playerId, gameId);
    }

    public GameResultRepository repo() {
        return resultRepository;
    }

    public void alignResumeToStoredResults() {
        int tmp = resultRepository.maxGameId();
        if (tmp >= 0) {
            lastSavedGameId = tmp;
            resumeAligned = false;
        }
    }

    public GameState current() {
        gameLock.readLock().lock();
        try {
            return currentGame;
        } finally {
            gameLock.readLock().unlock();
        }
    }

    public boolean running() {
        gameLock.readLock().lock();
        try {
            return currentGame != null && currentGame.running();
        } finally {
            gameLock.readLock().unlock();
        }
    }

    public int active() { return playersActive.get(); }
    public int finished() { return playersFinished.get(); }
    public int won() { return playersWon.get(); }

    public GameStatsData stats(int gameId) {
        StatsCounter s = statsPerGame.get(gameId);
        if (s != null) {
            return s.snapshot();
        }

        GameResultRepository.GameStatsSummary derived = resultRepository.gameStats(gameId);
        if (derived == null) {
            return null;
        }
        return new GameStatsData(
            derived.participants(),
            derived.finished(),
            derived.won(),
            derived.totalScore()
        );
    }

    public Map<Integer, GameStatsData> snapshotGameStats() {
        Map<Integer, GameStatsData> out = new HashMap<>();
        for (Map.Entry<Integer, StatsCounter> e : statsPerGame.entrySet()) {
            out.put(e.getKey(), e.getValue().snapshot());
        }
        return out;
    }

    public void restoreGameStats(Map<Integer, GameStatsData> saved) {
        if (saved == null) {
            return;
        }
        statsPerGame.clear();
        joinedPlayersPerGame.clear();
        int maxId = -1;
        for (Map.Entry<Integer, GameStatsData> e : saved.entrySet()) {
            statsPerGame.put(e.getKey(), new StatsCounter(e.getValue()));
            joinedPlayersPerGame.put(e.getKey(), ConcurrentHashMap.newKeySet());
            if (e.getKey() > maxId) {
                maxId = e.getKey();
            }
        }
        lastSavedGameId = maxId;
        resumeAligned = false;
    }

}
