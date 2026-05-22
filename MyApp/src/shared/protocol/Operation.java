package shared.protocol;


/********************    Specifica Protocollo  ************************************

  enum operation: Insieme delle operazioni nel protocollo di comunicazione.
  Dato che ogni valore corrisponde esattamente al campo "operation" nei messaggi JSON,
  ho incluso un campo "jsonKey" per mappare l'enum alla sequenza di caratteri JSON 
  corrispondente.
 
**************************************************************************************/

public enum Operation {

    
    REGISTER("register"),
    UPDATE_CREDENTIALS("updateCredentials"),
    LOGIN("login"),
    LOGOUT("logout"),
    SUBMIT_PROPOSAL("submitProposal"),
    REQUEST_GAME_INFO("requestGameInfo"),
    REQUEST_GAME_STATS("requestGameStats"),
    REQUEST_LEADERBOARD("requestLeaderboard"),
    REQUEST_PLAYER_STATS("requestPlayerStats");


    private final String jsonKey;

    Operation(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    public String key() {
        return jsonKey;
    }

    
    public static Operation from(String key) {
        if (key == null) return null;
        for (Operation op : values()) {
            if (op.jsonKey.equals(key)) {
                return op;
            }
        }
        return null;
    }
}
