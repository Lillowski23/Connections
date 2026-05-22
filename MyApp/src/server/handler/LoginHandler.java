package server.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.game.GameManager;
import server.game.GameState;
import server.net.ChannelContext;
import server.security.PasswordHasher;
import server.session.PlayerGameState;
import server.session.PlayerSession;
import server.session.SessionRegistry;
import server.session.SessionState;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.net.InetSocketAddress;
import java.util.logging.Logger;

/******************** Handler Layer ********************************

Entry point applicativo del protocollo lato server.
Riceve richieste gia parse/validate e coordina la logica sui componenti
/session, /game e /leaderboard, costruendo la risposta JSON finale.

Concorrenza:
- invocato in parallelo dai worker thread
- progettato per essere leggero e senza lock globali locali

*****************************************************************************/

public final class LoginHandler implements RequestRouter.Handler {

    private static final Logger LOG = Logger.getLogger(LoginHandler.class.getName());

    private final SessionRegistry registry;
    private final GameManager gameManager;

    public LoginHandler(SessionRegistry registry, GameManager gameManager) {
        this.registry = registry;
        this.gameManager = gameManager;
    }

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (ctx.auth()) {
            return Response.error(StatusCode.ALREADY_LOGGED_IN, reqId).build();
        }

        String username = request.get(Request.KEY_USERNAME).getAsString();
        String password = request.get(Request.KEY_PASSWORD).getAsString();

        PlayerSession session = registry.get(username);
        if (session == null) {
            return Response.error(StatusCode.NOT_FOUND, reqId).build();
        }

        if (!PasswordHasher.verify(password, session.hash())) {
            return Response.error(StatusCode.AUTH_FAILED, reqId).build();
        }

        SessionState currentState = session.state();

        switch (currentState) {
            case OFFLINE:

                if (!session.transition(SessionState.OFFLINE, SessionState.ONLINE)) {
                    return Response.error(StatusCode.INTERNAL_ERROR, reqId).build();
                }
                break;

            case DISCONNECTED:

                if (gameManager.inCurrent(session)) {

                    session.go(SessionState.PLAYING);
                } else {

                    session.go(SessionState.ONLINE);
                }
                break;

            case ONLINE:
            case PLAYING:
            case FINISHED:

                return Response.error(StatusCode.ALREADY_LOGGED_IN, reqId).build();

            default:
                return Response.error(StatusCode.INTERNAL_ERROR, reqId).build();
        }

        ctx.bind(session);
        session.seen(System.currentTimeMillis());

        if (request.has(Request.KEY_UDP_PORT)) {
            int udpPort = request.get(Request.KEY_UDP_PORT).getAsInt();
            String ip = ctx.remote().split(":")[0].replace("/", "");
            session.udp(new InetSocketAddress(ip, udpPort));
        }

        Response response = Response.base(StatusCode.LOGGED_IN, reqId)
            .with(Response.KEY_USERNAME, username);

        GameState game = gameManager.current();
        if (game != null && game.running()) {
            response.with(Response.KEY_GAME_ID, game.gameId())
                    .with(Response.KEY_TIME_REMAINING, game.left());

                LOG.info("User '" + username + "' attached to game " + game.gameId()
                     + " with remaining=" + game.left() + "s");

            PlayerGameState pgs = session.game();
            if (pgs != null) {
                response.with(Response.KEY_ERRORS, pgs.errors())
                        .with(Response.KEY_SCORE, pgs.score())
                        .with(Response.KEY_CORRECT_GROUPS, pgs.correct());

                JsonArray remainingWords = new JsonArray();
                for (String word : game.shuffle()) {
                    boolean inFoundGroup = false;
                    for (int i = 0; i < 4; i++) {
                        if (pgs.found(i) && game.group(i).words().contains(word)) {
                            inFoundGroup = true;
                            break;
                        }
                    }
                    if (!inFoundGroup) {
                        remainingWords.add(word);
                    }
                }
                response.with(Response.KEY_REMAINING_WORDS, remainingWords);
            } else {

                JsonArray words = new JsonArray();
                game.shuffle().forEach(words::add);
                response.with(Response.KEY_WORDS, words);
            }
        }

        return response.build();
    }
}
