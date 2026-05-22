package shared.protocol;


/******************** Codici di response del server  ********************************

codice numerico + descrizione leggibile.

codice:     2xx    |     4xx          |   5xx
            successo|   errore client  | errore server

messaggio:  OK                  |   Bad Request           | Internal Server Error
            Registered.         |   Unauthorized          | Service Unavailable
            Credentials Updated |   Username Taken        |
            Logged In           |   Auth Failed           |
            Logged Out          |   Not Found             |
            Proposal Correct    |   Already Logged In     | 
            Proposal Wrong      |   Game Not Found        |
            Game Won            |   Invalid Proposal      | 
            Game Lost           |   Game Already Finished |
                                |   Unknown Operation     |
                                |   Rate Limited          |

************************************************************************************/


public enum StatusCode {

    
    OK(200, "OK"),
    REGISTERED(201, "User registered successfully"),
    CREDENTIALS_UPDATED(202, "Credentials updated successfully"),
    LOGGED_IN(203, "Login successful"),
    LOGGED_OUT(204, "Logout successful"),
    PROPOSAL_CORRECT(210, "Proposal is correct"),
    PROPOSAL_WRONG(211, "Proposal is wrong"),
    GAME_WON(220, "You won the game"),
    GAME_LOST(221, "You lost the game — too many mistakes"),

    
    BAD_REQUEST(400, "Malformed request"),
    UNAUTHORIZED(401, "Not logged in"),
    USERNAME_TAKEN(402, "Username already registered"),
    AUTH_FAILED(403, "Wrong username or password"),
    NOT_FOUND(404, "Resource not found"),
    ALREADY_LOGGED_IN(405, "User is already logged in"),
    GAME_NOT_FOUND(406, "Game ID does not exist"),
    INVALID_PROPOSAL(407, "Proposal contains invalid words"),
    GAME_ALREADY_FINISHED(408, "Your game is already finished"),
    UNKNOWN_OPERATION(409, "Unrecognized operation"),
    RATE_LIMITED(429, "Too many requests — slow down"),

    
    INTERNAL_ERROR(500, "Internal server error"),
    SERVICE_UNAVAILABLE(503, "Server is shutting down");

    
    private final int code;
    private final String message;

    StatusCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    
    public static StatusCode from(int code) {
        for (StatusCode tmp : values()) {
            if (tmp.code == code) {
                return tmp;
            }
        }
        return null;
    }
}
