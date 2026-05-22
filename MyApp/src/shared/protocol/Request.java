package shared.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;


/******************** REQUEST BUILDER   ********************************

Viene utilizzato dal CLIENT per costruire le richieste da inviare al server,
e dal SERVER per estrarre i campi dalle richieste ricevute.

Ogni richiesta contiene SEMPRE:
- "requestId"  -> requestId della richiesta.
                  Serve per fare request/response matching lato client con CompletableFuture:
                  il client genera un id, il server lo rimanda indietro tale e quale,
                  e così la risposta viene associata alla richiesta giusta.

- "operation"  -> nome dell'operazione richiesto dal protocollo.
                  Non uso stringhe sparse nel codice ma l'enum Operation,
                  che poi viene tradotto nel corrispondente jsonKey.

- campi specifici dell'operazione -> username, psw, words, gameId, ecc.

Ho centralizzato tutta la costruzione delle richieste qui per evitare di creare
JsonObject a giro nel sorgente.

Inoltre ho usato gli stessi principi di factory methods come per response.

Request.login(...)
Request.proposal(...)
Request.gameInfo(...)

************************************************************************************/
public final class Request {

    // --- KEYS ---
    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_OPERATION = "operation";
    public static final String KEY_USERNAME = "username";
    public static final String KEY_PASSWORD = "psw";
    public static final String KEY_OLD_USERNAME = "oldUsername";
    public static final String KEY_NEW_USERNAME = "newUsername";
    public static final String KEY_OLD_PASSWORD = "oldPsw";
    public static final String KEY_NEW_PASSWORD = "newPsw";
    public static final String KEY_WORDS = "words";
    public static final String KEY_GAME_ID = "gameId";
    public static final String KEY_PLAYER_NAME = "playerName";
    public static final String KEY_TOP_PLAYERS = "topPlayers";
    public static final String KEY_UDP_PORT = "udpPort";

    // Valore speciale per "partita corrente" nel campo gameId
    public static final int CURRENT_GAME = -1;

    //Privata è una classe utility mia nessuno deve accederci 
    private Request() {}

    // --- Factory methods ---

    private static JsonObject base(Operation op, long requestId) {
        JsonObject tmp = new JsonObject();
        tmp.addProperty(KEY_REQUEST_ID, requestId);
        tmp.addProperty(KEY_OPERATION, op.key());
        return tmp;
    }

    // --- Registrazione, login, logout, updateCredentials ---
    public static JsonObject register(String username, String password, long requestId) {
        JsonObject tmp = base(Operation.REGISTER, requestId);
        tmp.addProperty(KEY_USERNAME, username);
        tmp.addProperty(KEY_PASSWORD, password);
        return tmp;
    }

    public static JsonObject login(String username, String password, long requestId) {
        JsonObject tmp = base(Operation.LOGIN, requestId);
        tmp.addProperty(KEY_USERNAME, username);
        tmp.addProperty(KEY_PASSWORD, password);
        return tmp;
    }

    public static JsonObject logout(long requestId) {
        return base(Operation.LOGOUT, requestId);
    }

    // specifica: si può aggiornare solo uno dei due o entrambi, gestito con parametri null

    public static JsonObject update(String oldUsername, String oldPassword,
                                                String newUsername, String newPassword,
                                                long requestId) {
        JsonObject tmp = base(Operation.UPDATE_CREDENTIALS, requestId);
        tmp.addProperty(KEY_OLD_USERNAME, oldUsername);
        tmp.addProperty(KEY_OLD_PASSWORD, oldPassword);
        if (newUsername != null) tmp.addProperty(KEY_NEW_USERNAME, newUsername);
        if (newPassword != null) tmp.addProperty(KEY_NEW_PASSWORD, newPassword);
        return tmp;
    }
    // ------------------------------------------------------------------------



    // --- Proposta parole e richieste informazioni ---

    public static JsonObject proposal(String[] words, long requestId) {
        JsonObject tmp = base(Operation.SUBMIT_PROPOSAL, requestId);
        JsonArray arr = new JsonArray();
        for (String e : words) {
            arr.add(e);
        }
        tmp.add(KEY_WORDS, arr);
        return tmp;
    }

    public static JsonObject gameInfo(int gameId, long requestId) {
        JsonObject tmp = base(Operation.REQUEST_GAME_INFO, requestId);
        tmp.addProperty(KEY_GAME_ID, gameId);
        return tmp;
    }

    
    public static JsonObject gameStats(int gameId, long requestId) {
        JsonObject tmp = base(Operation.REQUEST_GAME_STATS, requestId);
        tmp.addProperty(KEY_GAME_ID, gameId);
        return tmp;
    }

   
    public static JsonObject leaderboard(String playerName, int topPlayers,
                                                 long requestId) {
        JsonObject tmp = base(Operation.REQUEST_LEADERBOARD, requestId);
        if (playerName != null) tmp.addProperty(KEY_PLAYER_NAME, playerName);
        if (topPlayers > 0) tmp.addProperty(KEY_TOP_PLAYERS, topPlayers);
        return tmp;
    }

    
    public static JsonObject leaderboard(long requestId) {
        return leaderboard(null, 0, requestId);
    }

    
    public static JsonObject leaderboard(int topPlayers, long requestId) {
        return leaderboard(null, topPlayers, requestId);
    }

    
    public static JsonObject rank(String playerName, long requestId) {
        return leaderboard(playerName, 0, requestId);
    }

   
    public static JsonObject playerStats(long requestId) {
        return base(Operation.REQUEST_PLAYER_STATS, requestId);
    }

    // ------------------------------------------------------------------------

    // --- Parser lato sever ---

   
    public static Operation op(JsonObject json) {
        if (!json.has(KEY_OPERATION)) return null;
        return Operation.from(json.get(KEY_OPERATION).getAsString());
    }

   
    public static long id(JsonObject json) {
        if (!json.has(KEY_REQUEST_ID)) return -1;
        return json.get(KEY_REQUEST_ID).getAsLong();
    }

    
}
