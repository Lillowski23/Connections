package server.security;

import com.google.gson.JsonObject;
import shared.protocol.Operation;
import shared.protocol.Request;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/******************** Security Layer ********************************

Componenti di protezione della pipeline request.
Implementano anti-abuso per IP, validazione strutturale payload e gestione
credenziali, secondo il flusso documentato in UML_server (/security).

Concorrenza:
- strutture concorrenti per stato per-IP
- approccio lock-free per massimizzare throughput sotto carico

*****************************************************************************/

public final class RequestValidator {

    private static final Map<Operation, Set<String>> REQUIRED_FIELDS;

    static {
        EnumMap<Operation, Set<String>> map = new EnumMap<>(Operation.class);

        map.put(Operation.REGISTER, Set.of(
            Request.KEY_USERNAME, Request.KEY_PASSWORD
        ));

        map.put(Operation.LOGIN, Set.of(
            Request.KEY_USERNAME, Request.KEY_PASSWORD
        ));

        map.put(Operation.LOGOUT, Set.of());

        map.put(Operation.UPDATE_CREDENTIALS, Set.of(
            Request.KEY_OLD_USERNAME, Request.KEY_OLD_PASSWORD

        ));

        map.put(Operation.SUBMIT_PROPOSAL, Set.of(
            Request.KEY_WORDS
        ));

        map.put(Operation.REQUEST_GAME_INFO, Set.of());

        map.put(Operation.REQUEST_GAME_STATS, Set.of());

        map.put(Operation.REQUEST_LEADERBOARD, Set.of());

        map.put(Operation.REQUEST_PLAYER_STATS, Set.of());

        REQUIRED_FIELDS = Map.copyOf(map);
    }

    private RequestValidator() {}

    public static String check(Operation operation, JsonObject request) {
        if (operation == null) {
            return "Unknown operation";
        }

        Set<String> required = REQUIRED_FIELDS.get(operation);
        if (required == null) {
            return "No validation rule for operation: " + operation;
        }

        for (String field : required) {
            if (!request.has(field) || request.get(field).isJsonNull()) {
                return "Missing required field: " + field;
            }
        }

        switch (operation) {
            case REGISTER:
            case LOGIN:
                if (!validateStringField(request, Request.KEY_USERNAME, 1, 32)) {
                    return "Invalid username (1-32 chars)";
                }
                if (!validateStringField(request, Request.KEY_PASSWORD, 4, 128)) {
                    return "Invalid password (4-128 chars)";
                }
                break;

            case SUBMIT_PROPOSAL:
                if (!request.get(Request.KEY_WORDS).isJsonArray()
                        || request.getAsJsonArray(Request.KEY_WORDS).size() != 4) {
                    return "Proposal must contain exactly 4 words";
                }
                for (int i = 0; i < request.getAsJsonArray(Request.KEY_WORDS).size(); i++) {
                    if (!request.getAsJsonArray(Request.KEY_WORDS).get(i).isJsonPrimitive()) {
                        return "Proposal words must be strings";
                    }
                }
                break;

            case UPDATE_CREDENTIALS:

                if (!request.has(Request.KEY_NEW_USERNAME)
                        && !request.has(Request.KEY_NEW_PASSWORD)) {
                    return "Must specify newUsername and/or newPassword";
                }

                if (request.has(Request.KEY_NEW_USERNAME)
                        && !validateStringField(request, Request.KEY_NEW_USERNAME, 1, 32)) {
                    return "Invalid new username (1-32 chars)";
                }
                if (request.has(Request.KEY_NEW_PASSWORD)
                        && !validateStringField(request, Request.KEY_NEW_PASSWORD, 4, 128)) {
                    return "Invalid new password (4-128 chars)";
                }
                break;

            case REQUEST_GAME_INFO:
            case REQUEST_GAME_STATS:

                if (request.has(Request.KEY_GAME_ID) && !request.get(Request.KEY_GAME_ID).isJsonNull()) {
                    try {
                        request.get(Request.KEY_GAME_ID).getAsInt();
                    } catch (Exception e) {
                        return "gameId must be an integer";
                    }
                }
                break;

            case REQUEST_LEADERBOARD:

                if (request.has(Request.KEY_PLAYER_NAME)
                        && request.has(Request.KEY_TOP_PLAYERS)) {
                    return "Use either playerName or topPlayers, not both";
                }

                if (request.has(Request.KEY_PLAYER_NAME)
                        && !validateStringField(request, Request.KEY_PLAYER_NAME, 1, 32)) {
                    return "Invalid playerName (1-32 chars)";
                }

                if (request.has(Request.KEY_TOP_PLAYERS)) {
                    try {
                        int top = request.get(Request.KEY_TOP_PLAYERS).getAsInt();

                        if (top < 0) {
                            return "topPlayers must be >= 0";
                        }
                    } catch (Exception e) {
                        return "topPlayers must be an integer";
                    }
                }
                break;

            default:
                break;
        }

        return null;
    }

    private static boolean validateStringField(JsonObject obj, String field, int minLen, int maxLen) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()) return false;
        String val = obj.get(field).getAsString();
        return val.length() >= minLen && val.length() <= maxLen;
    }
}
