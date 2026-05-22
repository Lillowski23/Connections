package server.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.google.gson.stream.MalformedJsonException;
import server.game.PlayerGameResult;
import server.session.PlayerSession;
import server.session.SessionRegistry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static server.storage.StorageField.*;

/******************** Storage Layer ********************************

Persistenza e supporto I/O del server.
Gestisce salvataggio/caricamento utenti in formato JSON e risultati sintetici
delle partite.

Gli utenti vengono salvati con playerId stabile e username corrente. I risultati
delle partite usano playerId/gameId, non lo username, cosi' il cambio nome non
rompe statistiche, game info storico o leaderboard. La leaderboard non viene
salvata come dato primario perche' si ricostruisce dai risultati.

Concorrenza e memoria:
- persistenza orchestrata da servizio dedicato
- lettura/scrittura JSON streaming, senza materializzare l'intero file in RAM
- scrittura atomica su file temporaneo e replace del file finale

*****************************************************************************/

public final class StorageManager {

    private static final Logger LOG = Logger.getLogger(StorageManager.class.getName());

    private final Path usersPath;
    private final Path gameHistoryPath;

    public StorageManager(String usersPath) {
        this(usersPath, null);
    }

    public StorageManager(String usersPath, String gameHistoryPath) {
        this.usersPath = Path.of(usersPath);
        if (gameHistoryPath == null || gameHistoryPath.isBlank()) {
            this.gameHistoryPath = this.usersPath.resolveSibling("game_history.json");
        } else {
            this.gameHistoryPath = Path.of(gameHistoryPath);
        }
    }

    public void saveUsers(SessionRegistry registry) throws IOException {
        final int[] count = {0};

        writeAtomicJson(usersPath, out -> {
            out.beginArray();

            for (PlayerSession session : registry.snapshot()) {
                writeUser(out, session);
                count[0]++;
            }

            out.endArray();
        });

        LOG.fine("Saved " + count[0] + " users to " + usersPath);
    }

    public int loadUsers(SessionRegistry registry) throws IOException {
        if (!Files.exists(usersPath)) {
            LOG.info("No users file found at " + usersPath + ", starting fresh");
            return 0;
        }

        if (Files.size(usersPath) == 0L) {
            LOG.info("Users file is empty at " + usersPath + ", starting fresh");
            return 0;
        }

        List<PlayerSession> loaded = new ArrayList<>();

        try (BufferedReader file = Files.newBufferedReader(usersPath, StandardCharsets.UTF_8);
             JsonReader in = new JsonReader(file)) {
            JsonToken tmp = in.peek();

            if (tmp == JsonToken.END_DOCUMENT) {
                LOG.info("Users file contains only whitespace at " + usersPath + ", starting fresh");
                return 0;
            }

            if (tmp == JsonToken.BEGIN_OBJECT) {
                in.beginObject();
                if (!in.hasNext()) {
                    in.endObject();
                    LOG.info("Users file contains empty JSON object at " + usersPath + ", starting fresh");
                    return 0;
                }
                LOG.warning("Users file must be a JSON array at " + usersPath + ", starting fresh");
                return 0;
            }

            if (tmp != JsonToken.BEGIN_ARRAY) {
                LOG.warning("Users file must be a JSON array at " + usersPath + ", starting fresh");
                return 0;
            }

            in.beginArray();
            while (in.hasNext()) {
                JsonObject obj = readNextObject(in, "user");
                try {
                    PlayerSession session = readUser(obj, registry);
                    if (session != null) {
                        loaded.add(session);
                    }
                } catch (RuntimeException ex) {
                    LOG.warning("Skipping invalid user entry: " + ex.getMessage());
                }
            }
            in.endArray();
        } catch (EOFException ex) {
            LOG.info("Users file contains only whitespace at " + usersPath + ", starting fresh");
            return 0;
        } catch (MalformedJsonException | RuntimeException ex) {
            LOG.warning("Users file is not valid JSON at " + usersPath + ", starting fresh");
            return 0;
        }

        int count = 0;
        for (PlayerSession session : loaded) {
            if (registry.register(session.name(), session)) {
                count++;
            } else {
                LOG.warning("Skipping duplicated user while loading: " + session.name());
            }
        }

        LOG.info("Loaded " + count + " users from " + usersPath);
        return count;
    }

    public void saveGameResults(Collection<PlayerGameResult> data) throws IOException {
        writeAtomicJson(gameHistoryPath, out -> {
            out.beginArray();
            for (PlayerGameResult result : data) {
                writeGameResult(out, result);
            }
            out.endArray();
        });
    }

    public List<PlayerGameResult> loadGameResults(SessionRegistry registry) throws IOException {
        List<PlayerGameResult> out = new ArrayList<>();

        if (!Files.exists(gameHistoryPath) || Files.size(gameHistoryPath) == 0L) {
            return out;
        }

        try (BufferedReader file = Files.newBufferedReader(gameHistoryPath, StandardCharsets.UTF_8);
             JsonReader in = new JsonReader(file)) {
            JsonToken tmp = in.peek();

            if (tmp == JsonToken.END_DOCUMENT) {
                return out;
            }

            if (tmp != JsonToken.BEGIN_ARRAY) {
                LOG.warning("Game results must be JSON array at " + gameHistoryPath + ", starting fresh");
                return out;
            }

            in.beginArray();
            while (in.hasNext()) {
                JsonObject obj = readNextObject(in, "game result");
                try {
                    PlayerGameResult result = readGameResult(obj, registry);
                    if (result != null) {
                        out.add(result);
                    }
                } catch (RuntimeException ex) {
                    LOG.warning("Skipping invalid game result entry: " + ex.getMessage());
                }
            }
            in.endArray();
        } catch (EOFException ex) {
            return out;
        } catch (MalformedJsonException | RuntimeException ex) {
            LOG.warning("Game results JSON invalid at " + gameHistoryPath + ", starting fresh");
            return new ArrayList<>();
        }

        return out;
    }

    @FunctionalInterface
    private interface JsonWriteTask {
        void write(JsonWriter out) throws IOException;
    }

    private void writeAtomicJson(Path path, JsonWriteTask task) throws IOException {
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (BufferedWriter file = Files.newBufferedWriter(tmpPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
             JsonWriter out = new JsonWriter(file)) {
            out.setIndent("  ");
            task.write(out);
        }

        forceFile(tmpPath);

        Files.move(tmpPath, path,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    private void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void writeUser(JsonWriter out, PlayerSession session) throws IOException {
        PlayerSession.Stats stats = session.stats();

        out.beginObject();
        out.name(PLAYER_ID.key()).value(session.id());
        out.name(USERNAME.key()).value(session.name());
        out.name(PASSWORD_HASH.key()).value(session.hash());
        out.name(TOTAL_SCORE.key()).value(stats.score());
        out.name(PUZZLES_PLAYED.key()).value(stats.played());
        out.name(PUZZLES_WON.key()).value(stats.wins());
        out.name(PUZZLES_LOST.key()).value(stats.losses());
        out.name(CURRENT_STREAK.key()).value(stats.streak());
        out.name(MAX_STREAK.key()).value(stats.bestStreak());
        out.name(PERFECT_PUZZLES.key()).value(stats.perfect());

        out.name(MISTAKE_HISTOGRAM.key());
        out.beginArray();
        for (int tmp : stats.histogram()) {
            out.value(tmp);
        }
        out.endArray();

        out.endObject();
    }

    private PlayerSession readUser(JsonObject obj, SessionRegistry registry) {
        String username = stringValue(obj, USERNAME.key(), null);
        String passwordHash = stringValue(obj, PASSWORD_HASH.key(), null);

        if (username == null || username.isBlank() || passwordHash == null) {
            return null;
        }

        long playerId = longValue(obj, PLAYER_ID.key(), -1L);
        if (playerId <= 0) {
            playerId = registry.nextPlayerId();
        }

        PlayerSession session = new PlayerSession(playerId, username, passwordHash);
        session.restoreStats(
            intValue(obj, PUZZLES_PLAYED.key(), 0),
            intValue(obj, PUZZLES_WON.key(), 0),
            intValue(obj, PUZZLES_LOST.key(), 0),
            intValue(obj, CURRENT_STREAK.key(), 0),
            intValue(obj, MAX_STREAK.key(), 0),
            intValue(obj, PERFECT_PUZZLES.key(), 0),
            histogramValue(obj),
            intValue(obj, TOTAL_SCORE.key(), 0)
        );
        return session;
    }

    private void writeGameResult(JsonWriter out, PlayerGameResult result) throws IOException {
        out.beginObject();
        out.name(PLAYER_ID.key()).value(result.id());
        out.name(GAME_ID.key()).value(result.gameId());
        out.name(CORRECT_GROUPS.key()).value(result.correct());
        out.name(MISTAKES.key()).value(result.mistakes());
        out.name(SCORE.key()).value(result.score());
        out.name(WON.key()).value(result.won());
        out.name(LOST_BY_MISTAKES.key()).value(result.lostByMistakes());
        out.name(EXPIRED.key()).value(result.expired());
        out.name(JOINED_AT.key()).value(result.joinedAt());
        out.name(FINISHED_AT.key()).value(result.finishedAt());
        out.endObject();
    }

    private PlayerGameResult readGameResult(JsonObject obj, SessionRegistry registry) {
        long playerId = longValue(obj, PLAYER_ID.key(), -1L);
        if (playerId <= 0) {
            String username = stringValue(obj, USERNAME.key(), null);
            if (username != null) {
                PlayerSession session = registry.get(username);
                if (session != null) {
                    playerId = session.id();
                }
            }
        }

        if (playerId <= 0) {
            LOG.warning("Skipping game result without valid playerId");
            return null;
        }

        return new PlayerGameResult(
            playerId,
            requiredInt(obj, GAME_ID.key()),
            requiredInt(obj, CORRECT_GROUPS.key()),
            requiredInt(obj, MISTAKES.key()),
            requiredInt(obj, SCORE.key()),
            requiredBoolean(obj, WON.key()),
            requiredBoolean(obj, LOST_BY_MISTAKES.key()),
            requiredBoolean(obj, EXPIRED.key()),
            requiredLong(obj, JOINED_AT.key()),
            requiredLong(obj, FINISHED_AT.key())
        );
    }

    private JsonObject readNextObject(JsonReader in, String label) {
        JsonElement tmp = JsonParser.parseReader(in);
        if (!tmp.isJsonObject()) {
            throw new IllegalArgumentException("Expected " + label + " object");
        }
        return tmp.getAsJsonObject();
    }

    private int[] histogramValue(JsonObject obj) {
        int[] histogram = new int[7];
        JsonElement tmp = obj.get(MISTAKE_HISTOGRAM.key());
        if (tmp == null || !tmp.isJsonArray()) {
            return histogram;
        }

        JsonArray values = tmp.getAsJsonArray();
        for (int i = 0; i < Math.min(values.size(), histogram.length); i++) {
            histogram[i] = values.get(i).getAsInt();
        }
        return histogram;
    }

    private String stringValue(JsonObject obj, String name, String fallback) {
        JsonElement tmp = obj.get(name);
        if (tmp == null || tmp.isJsonNull()) {
            return fallback;
        }
        return tmp.getAsString();
    }

    private int intValue(JsonObject obj, String name, int fallback) {
        JsonElement tmp = obj.get(name);
        if (tmp == null || tmp.isJsonNull()) {
            return fallback;
        }
        return tmp.getAsInt();
    }

    private long longValue(JsonObject obj, String name, long fallback) {
        JsonElement tmp = obj.get(name);
        if (tmp == null || tmp.isJsonNull()) {
            return fallback;
        }
        return tmp.getAsLong();
    }

    private int requiredInt(JsonObject obj, String name) {
        JsonElement tmp = requiredValue(obj, name);
        return tmp.getAsInt();
    }

    private long requiredLong(JsonObject obj, String name) {
        JsonElement tmp = requiredValue(obj, name);
        return tmp.getAsLong();
    }

    private boolean requiredBoolean(JsonObject obj, String name) {
        JsonElement tmp = requiredValue(obj, name);
        return tmp.getAsBoolean();
    }

    private JsonElement requiredValue(JsonObject obj, String name) {
        JsonElement tmp = obj.get(name);
        if (tmp == null || tmp.isJsonNull()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return tmp;
    }
}
