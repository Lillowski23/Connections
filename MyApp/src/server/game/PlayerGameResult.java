package server.game;

/**
 * Risultato persistente sintetico di una partita giocata da un utente.
 * Non salva le singole proposte: conserva solo i dati necessari a ricostruire
 * statistiche personali, leaderboard, statistiche partita e gameInfo storico.
 *
 * La chiave logica è playerId + gameId: lo username resta modificabile e non
 * viene usato per identificare stabilmente lo storico di gioco.
 */
public final class PlayerGameResult {
    private final long playerId;
    private final int gameId;
    private final int correctGroups;
    private final int mistakes;
    private final int score;
    private final boolean won;
    private final boolean lostByMistakes;
    private final boolean expired;
    private final long joinedAt;
    private final long finishedAt;

    public PlayerGameResult(long playerId, int gameId, int correctGroups, int mistakes,
                            int score, boolean won, boolean lostByMistakes, boolean expired,
                            long joinedAt, long finishedAt) {
        this.playerId = playerId;
        this.gameId = gameId;
        this.correctGroups = correctGroups;
        this.mistakes = mistakes;
        this.score = score;
        this.won = won;
        this.lostByMistakes = lostByMistakes;
        this.expired = expired;
        this.joinedAt = joinedAt;
        this.finishedAt = finishedAt;
    }

    public long id() { return playerId; }
    public int gameId() { return gameId; }
    public int correct() { return correctGroups; }
    public int mistakes() { return mistakes; }
    public int score() { return score; }
    public boolean won() { return won; }
    public boolean lostByMistakes() { return lostByMistakes; }
    public boolean expired() { return expired; }
    public long joinedAt() { return joinedAt; }
    public long finishedAt() { return finishedAt; }
}
