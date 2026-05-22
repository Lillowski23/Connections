package server.session;

import server.game.PlayerGameResult;

/******************** Player Game State ********************************

Stato runtime di un singolo player dentro la partita corrente.
Non e' lo storico persistente: vive nella PlayerSession mentre la partita e'
in corso e viene trasformato in PlayerGameResult solo alla fine.

Tiene solo quello che serve durante il gioco:
- gruppi gia' trovati
- errori
- score temporaneo
- esito finished/won/expired
- tempi di ingresso e chiusura

I metodi pubblici sono sincronizzati per serializzare le mutazioni del singolo
player anche se due worker ricevessero richieste quasi contemporanee.

La vittoria avviene a 3 gruppi corretti su 4, perche' il gruppo rimasto e'
implicito. Alla finalizzazione il risultato viene salvato usando playerId, non
username, cosi' lo storico non dipende da un nome modificabile.

*****************************************************************************/

public final class PlayerGameState {

    private final int gameId;

    private int errors;

    private final boolean[] foundGroups;

    private int correctCount;

    private boolean finished;

    private boolean won;

    private boolean expired;

    private int score;

    private final long joinedAt;

    private long finishedAt;

    public PlayerGameState(int gameId) {
        this.gameId = gameId;
        this.errors = 0;
        this.foundGroups = new boolean[4];
        this.correctCount = 0;
        this.finished = false;
        this.won = false;
        this.expired = false;
        this.score = 0;
        this.joinedAt = System.currentTimeMillis();
        this.finishedAt = 0L;
    }

    public synchronized boolean recordCorrectProposal(int groupIndex) {
        if (finished || foundGroups[groupIndex]) return false;

        foundGroups[groupIndex] = true;
        correctCount++;

        recalculateScore();

        if (correctCount >= 3) {

            finished = true;
            won = true;
            finishedAt = System.currentTimeMillis();
        }

        return won;
    }

    public synchronized boolean recordWrongProposal() {
        if (finished) return false;

        errors++;
        recalculateScore();

        if (errors >= 4) {
            finished = true;
            won = false;
            finishedAt = System.currentTimeMillis();
        }

        return errors >= 4;
    }

    public synchronized void onTimeExpired() {
        if (!finished) {
            finished = true;
            won = false;
            expired = true;
            finishedAt = System.currentTimeMillis();
        }
    }

    private void recalculateScore() {
        int bonus = correctCount * 6;
        int penalty = errors * (-4);
        this.score = bonus + penalty;
    }

    public int gameId() { return gameId; }
    public synchronized int errors() { return errors; }
    public synchronized int correct() { return correctCount; }
    public synchronized boolean done() { return finished; }
    public synchronized boolean won() { return won; }
    public synchronized int score() { return score; }
    public synchronized boolean expired() { return expired; }
    public long joinedAt() { return joinedAt; }
    public synchronized long finishedAt() { return finishedAt; }
    public synchronized boolean found(int index) { return foundGroups[index]; }

    public synchronized boolean perfect() {
        return won && errors == 0;
    }

    public synchronized PlayerGameResult toResult(long playerId) {
        long end = finishedAt == 0L ? System.currentTimeMillis() : finishedAt;
        return new PlayerGameResult(
            playerId,
            gameId,
            correctCount,
            errors,
            score,
            won,
            !won && errors >= 4,
            expired,
            joinedAt,
            end
        );
    }

    @Override
    public String toString() {
        return String.format("PlayerGameState{game=%d, correct=%d, errors=%d, score=%d, %s}",
            gameId, correctCount, errors, score,
            finished ? (won ? "WON" : "LOST") : "IN_PROGRESS");
    }
}
