package server.storage;

/**
 * Chiavi JSON usate nei file persistenti e nei file delle partite.
 *
 * Non sono chiavi del protocollo client/server: quelle restano in
 * shared.protocol.Request e shared.protocol.Response. Questa enum evita
 * stringhe duplicate in StorageManager e GameLoader mantenendo esplicito
 * il contratto dei file su disco.
 */
public enum StorageField {
    PLAYER_ID("playerId"),
    USERNAME("username"),
    PASSWORD_HASH("passwordHash"),
    TOTAL_SCORE("totalScore"),
    PUZZLES_PLAYED("puzzlesPlayed"),
    PUZZLES_WON("puzzlesWon"),
    PUZZLES_LOST("puzzlesLost"),
    CURRENT_STREAK("currentStreak"),
    MAX_STREAK("maxStreak"),
    PERFECT_PUZZLES("perfectPuzzles"),
    MISTAKE_HISTOGRAM("mistakeHistogram"),

    GAME_ID("gameId"),
    CORRECT_GROUPS("correctGroups"),
    MISTAKES("mistakes"),
    SCORE("score"),
    WON("won"),
    LOST_BY_MISTAKES("lostByMistakes"),
    EXPIRED("expired"),
    JOINED_AT("joinedAt"),
    FINISHED_AT("finishedAt"),

    GROUPS("groups"),
    THEME("theme"),
    WORDS("words");

    private final String key;

    StorageField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}
