package server.handler;

import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.game.GameManager;
import server.game.GameState;
import server.net.ChannelContext;
import server.leaderboard.Leaderboard;
import server.security.PasswordHasher;
import server.session.PlayerSession;
import server.session.SessionRegistry;
import server.session.SessionState;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.net.InetSocketAddress;

/******************** Handler Layer ********************************

Entry point applicativo del protocollo lato server.
Riceve richieste gia parse/validate e coordina la logica sui componenti
/session, /game e /leaderboard, costruendo la risposta JSON finale.

Concorrenza:
- invocato in parallelo dai worker thread
- progettato per essere leggero e senza lock globali locali

*****************************************************************************/

public final class RegisterHandler implements RequestRouter.Handler {

    private final SessionRegistry registry;
    private final GameManager gameManager;
    private final Leaderboard leaderboard;

    public RegisterHandler(SessionRegistry registry, GameManager gameManager, Leaderboard leaderboard) {
        this.registry = registry;
        this.gameManager = gameManager;
        this.leaderboard = leaderboard;
    }

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (ctx.auth()) {
            return Response.error(StatusCode.ALREADY_LOGGED_IN, reqId).build();
        }

        String username = request.get(Request.KEY_USERNAME).getAsString();
        String password = request.get(Request.KEY_PASSWORD).getAsString();

        String hash = PasswordHasher.hash(password, PasswordHasher.generateSalt());

        PlayerSession session = new PlayerSession(registry.nextPlayerId(), username, hash);
        boolean success = registry.register(username, session);

        if (!success) {
            return Response.error(StatusCode.USERNAME_TAKEN, reqId).build();
        }

        leaderboard.update(session.id(), 0);

        if (!session.transition(SessionState.OFFLINE, SessionState.ONLINE)) {
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
            com.google.gson.JsonArray words = new com.google.gson.JsonArray();
            game.shuffle().forEach(words::add);
            response.with(Response.KEY_WORDS, words);
        }

        return response.build();
    }
}
