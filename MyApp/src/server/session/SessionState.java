package server.session;

import java.util.Map;
import java.util.Set;

/******************** Session State ********************************

Rappresenta i possibili stati di una sessione utente.
Definisce le transizioni valide tra stati

*****************************************************************************/

public enum SessionState {

    OFFLINE,
    ONLINE,
    PLAYING,
    DISCONNECTED,
    FINISHED;

    private static final Map<SessionState, Set<SessionState>> ALLOWED_TRANSITIONS = Map.of(
        OFFLINE,      Set.of(ONLINE),
        ONLINE,       Set.of(OFFLINE, PLAYING, DISCONNECTED),
        PLAYING,      Set.of(FINISHED, DISCONNECTED, OFFLINE),
        DISCONNECTED, Set.of(ONLINE, PLAYING, FINISHED, OFFLINE),
        FINISHED,     Set.of(PLAYING, ONLINE, OFFLINE, DISCONNECTED)
    );

    public boolean canTransitionTo(SessionState target) {
        Set<SessionState> allowed = ALLOWED_TRANSITIONS.get(this);
        return allowed != null && allowed.contains(target);
    }
}
