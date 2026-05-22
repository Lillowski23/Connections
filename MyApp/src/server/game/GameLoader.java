package server.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import static server.storage.StorageField.*;


/******************** Game Loader ********************************

Carica le partite dal file JSON.

Dato che il file può essere di grandi dimensioni sfruttiamo un JsonReader 
per tokenizzare dinamicamente senza parsare tutto l'albero di sintassi in memoria.


Il file rimane aperto tra una partita e l'altra: GameManager
chiama loadNext() solo quando serve la prossima partita.

************************************************************************************/



public final class GameLoader implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(GameLoader.class.getName());

    private final JsonReader reader;
    private final Path sourcePath;
    private final long gameDurationMs;
    private boolean started = false;
    private boolean finished = false;
    private int counter = 0;

    
    public GameLoader(Path path, long gameDurationMillis) throws IOException {
        this.sourcePath = path;
        BufferedReader fileReader = Files.newBufferedReader(path);
        this.reader = new JsonReader(fileReader);
        this.gameDurationMs = gameDurationMillis;
        LOG.info("GameLoader online: " + path + " | duration="
                 + gameDurationMillis + "ms (~" + (gameDurationMillis / 1000) + "s)");
    }


    public GameState loadNext() throws IOException {
        if (finished) return null;

        if (!started) {
            reader.beginArray(); 
            started = true;
        }

        if (!reader.hasNext()) {
            reader.endArray();
            finished = true;
            LOG.info("Database partite esaurito, totale partite caricate: " + counter);
            return null;
        }

        JsonObject gameObj = JsonParser.parseReader(reader).getAsJsonObject();
        GameState parsed = parseGame(gameObj);
        int gameId = parsed.gameId();

        counter++;
        LOG.fine("Game loaded successfully " + gameId + " (total: " + counter + ")");

        return parsed;
    }

    public GameState loadById(int wantedGameId) throws IOException {
        try (BufferedReader fileReader = Files.newBufferedReader(sourcePath);
             JsonReader scanReader = new JsonReader(fileReader)) {
            scanReader.beginArray();
            while (scanReader.hasNext()) {
                JsonObject gameObj = JsonParser.parseReader(scanReader).getAsJsonObject();
                int gameId = gameObj.get(GAME_ID.key()).getAsInt();
                if (gameId == wantedGameId) {
                    return parseGame(gameObj);
                }
            }
            scanReader.endArray();
        }
        return null;
    }

    private GameState parseGame(JsonObject gameObj) {
        int gameId = gameObj.get(GAME_ID.key()).getAsInt();
        JsonArray groupsArray = gameObj.getAsJsonArray(GROUPS.key());

        GameState.Group[] groups = new GameState.Group[4];
        for (int i = 0; i < 4; i++) {
            JsonObject groupObj = groupsArray.get(i).getAsJsonObject();
            String theme = groupObj.get(THEME.key()).getAsString();
            JsonArray wordsArray = groupObj.getAsJsonArray(WORDS.key());

            Set<String> words = new HashSet<>();
            for (JsonElement wordElem : wordsArray) {
                words.add(wordElem.getAsString().toUpperCase());
            }
            groups[i] = new GameState.Group(theme, Set.copyOf(words));
        }

        return new GameState(gameId, groups, gameDurationMs);
    }

    
    public int loaded() {
        return counter;
    }

    
    public boolean hasMore() {
        return !finished;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
