package shared.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;


/******************** RESPONSE BUILDER   ********************************

Viene utilizzato dal SERVER per costruire le risposte da inviare ai client,
e dai CLIENT per parsare le risposte ricevute.

Ogni risposta contiene SEMPRE:
- "requestId"  -> requestId della richiesta per fare matching lato client con CompletableFuture
- "status"     -> numero € StatusCode
- "message"    -> messaggio leggibile da StatusCode
- campi specifici per operazione (dati partita, classifica, statistiche, ecc.)

Struttura del builder:
  
Response.ok(requestId)                → risposta base successo
          .withGameData(...)            → aggiunge dati partita
          .build()                      → restituisce JsonObject


Ho seguito il design pattern factory method 
per evitare di dover costruire JsonObject a mano in ogni handler.

************************************************************************************/


public final class Response {

    // Chiavi JSON per i campi base
    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_STATUS = "status";
    public static final String KEY_MESSAGE = "message";

    // Chiavi JSON partita in corso
    public static final String KEY_GAME_ID = "gameId";
    public static final String KEY_WORDS = "words";
    public static final String KEY_TIME_REMAINING = "timeRemaining";
    public static final String KEY_ERRORS = "errors";
    public static final String KEY_SCORE = "score";
    public static final String KEY_CORRECT_GROUPS = "correctGroups";
    public static final String KEY_REMAINING_WORDS = "remainingWords";
    public static final String KEY_GROUPS = "groups";
    public static final String KEY_GROUP_INDEX = "groupIndex";
    public static final String KEY_THEME = "theme";
    public static final String KEY_FOUND = "found";

    // Chiavi JSON statistiche partita
    public static final String KEY_PLAYERS_ACTIVE = "playersActive";
    public static final String KEY_PLAYERS_FINISHED = "playersFinished";
    public static final String KEY_PLAYERS_WON = "playersWon";
    public static final String KEY_AVERAGE_SCORE = "averageScore";
    public static final String KEY_TOTAL_PLAYERS = "totalPlayers";

    // Chiavi JSON statistiche giocatore
    public static final String KEY_PUZZLES_COMPLETED = "puzzlesCompleted";
    public static final String KEY_WIN_RATE = "winRate";
    public static final String KEY_LOSS_RATE = "lossRate";
    public static final String KEY_CURRENT_STREAK = "currentStreak";
    public static final String KEY_MAX_STREAK = "maxStreak";
    public static final String KEY_PERFECT_PUZZLES = "perfectPuzzles";
    public static final String KEY_MISTAKE_HISTOGRAM = "mistakeHistogram";

    // Chiavi JSON leaderboard
    public static final String KEY_LEADERBOARD = "leaderboard";
    public static final String KEY_RANK = "rank";
    public static final String KEY_USERNAME = "username";

    // Chiave per notifiche UDP
    public static final String KEY_NOTIFICATION = "notification";
    public static final String KEY_NOTIFICATION_TYPE = "type";

    public static final String NOTICE_NEW_GAME = "new_game";
    public static final String NOTICE_GAME_ENDED = "game_ended";
    public static final String NOTICE_ALL_GAMES_COMPLETED = "all_games_completed";

    private final JsonObject json;


    //Privata è una classe utility nessuno deve accederci 
    private Response(JsonObject json) {
        this.json = json;
    }

    // --- Factory methods ---

    public static Response base(StatusCode status, long requestId) {
        JsonObject tmp = new JsonObject();
        tmp.addProperty(KEY_REQUEST_ID, requestId);
        tmp.addProperty(KEY_STATUS, status.code());
        tmp.addProperty(KEY_MESSAGE, status.message());
        return new Response(tmp);
    }

    // --- OK ---
    public static Response ok(long requestId) {
        return base(StatusCode.OK, requestId);
    }

    /// --- Error ---
    public static Response error(StatusCode status, long requestId) {
        return base(status, requestId);
    }

    
    // --- + String --- 
    public Response with(String key, String value) {
        json.addProperty(key, value);
        return this;
    }

    // --- + Int --- 
    public Response with(String key, int value) {
        json.addProperty(key, value);
        return this;
    }

    // --- + Long --- 
    public Response with(String key, long value) {
        json.addProperty(key, value);
        return this;
    }

    // --- + Double --- 
    public Response with(String key, double value) {
        json.addProperty(key, value);
        return this;
    }

    // --- + Bool --- 
    public Response with(String key, boolean value) {
        json.addProperty(key, value);
        return this;
    }

    // --- + Array --- 
    public Response with(String key, JsonArray value) {
        json.add(key, value);
        return this;
    }

    // --- + Object --- 
    public Response with(String key, JsonObject value) {
        json.add(key, value);
        return this;
    }

    // Notifica UDP: non è una risposta a una richiesta specifica, quindi non include requestId, status o message. 
    // infatti si usa solo per notificare timout fine partita o nuova partita disponibile.
     
    public static Response notification(String type) {
        JsonObject tmp = new JsonObject();
        tmp.addProperty(KEY_NOTIFICATION, true);
        tmp.addProperty(KEY_NOTIFICATION_TYPE, type);
        return new Response(tmp);
    }

    // --- Build Finale ---
    public JsonObject build() {
        return json;
    }

    // --- Parsing helpers (usati dal client) ---


    // --- Estrarre stato della risposta ---
    public static StatusCode status(JsonObject json) {
        if (!json.has(KEY_STATUS)) return null;
        return StatusCode.from(json.get(KEY_STATUS).getAsInt());
    }

    // --- Estrarre requestId dalla risposta ---
    public static long id(JsonObject json) {
        if (!json.has(KEY_REQUEST_ID)) return -1;
        return json.get(KEY_REQUEST_ID).getAsLong();
    }

    // --- Controlla se il messaggio è una notifica UDP ---
    public static boolean note(JsonObject json) {
        return json.has(KEY_NOTIFICATION) && json.get(KEY_NOTIFICATION).getAsBoolean();
    }

    // --- Estrae il tipo di notifica UDP ---
    public static String notice(JsonObject json) {
        if (!json.has(KEY_NOTIFICATION_TYPE)) return null;
        return json.get(KEY_NOTIFICATION_TYPE).getAsString();
    }
}
