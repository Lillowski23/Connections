package server.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.leaderboard.Leaderboard;
import server.net.ChannelContext;
import server.session.SessionRegistry;
import server.session.PlayerSession;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.util.List;

/******************** Handler Layer ********************************

Entry point applicativo del protocollo lato server.
Riceve richieste gia parse/validate e coordina la logica sui componenti
/session, /game e /leaderboard, costruendo la risposta JSON finale.

Per la leaderboard il protocollo resta espresso con playerName/topPlayers, ma
la logica interna usa playerId: il nome richiesto viene risolto tramite
SessionRegistry e la risposta mostra sempre lo username corrente del player.

Concorrenza:
- invocato in parallelo dai worker thread
- progettato per essere leggero e senza lock globali locali

*****************************************************************************/

public final class LeaderboardHandler implements RequestRouter.Handler {

    private final Leaderboard leaderboard;
    private final SessionRegistry registry;

    public LeaderboardHandler(Leaderboard leaderboard, SessionRegistry registry) {
        this.leaderboard = leaderboard;
        this.registry = registry;
    }

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (!ctx.auth()) {
            return Response.error(StatusCode.UNAUTHORIZED, reqId).build();
        }

        if (request.has(Request.KEY_PLAYER_NAME) && request.has(Request.KEY_TOP_PLAYERS)) {
            return Response.error(StatusCode.BAD_REQUEST, reqId).build();
        }

        if (request.has(Request.KEY_PLAYER_NAME)) {
            String playerName = request.get(Request.KEY_PLAYER_NAME).getAsString();

            PlayerSession target = registry.get(playerName);
            if (target == null) {
                return Response.error(StatusCode.NOT_FOUND, reqId).build();
            }

            int rank = leaderboard.rank(target.id());
            Leaderboard.ScoreKey scoreKey = leaderboard.score(target.id());

            Response resp = Response.ok(reqId)
                .with(Response.KEY_USERNAME, target.name())
                .with(Response.KEY_RANK, rank)
                .with(Response.KEY_SCORE, scoreKey != null ? scoreKey.score() : 0);

            return resp.build();
        }

        List<Leaderboard.ScoreKey> entries;
        if (request.has(Request.KEY_TOP_PLAYERS)) {
            int topK;
            try {
                topK = request.get(Request.KEY_TOP_PLAYERS).getAsInt();
            } catch (Exception e) {
                return Response.error(StatusCode.BAD_REQUEST, reqId).build();
            }

            if (topK < 0) {
                return Response.error(StatusCode.BAD_REQUEST, reqId).build();
            }

            entries = topK == 0 ? leaderboard.all() : leaderboard.top(topK);
        } else {
            entries = leaderboard.all();
        }

        JsonArray leaderboardArray = new JsonArray();
        int rank = 1;
        for (Leaderboard.ScoreKey entry : entries) {
            JsonObject entryObj = new JsonObject();
            entryObj.addProperty(Response.KEY_RANK, rank++);
            PlayerSession player = registry.byId(entry.playerId());
            entryObj.addProperty(Response.KEY_USERNAME,
                player != null ? player.name() : String.valueOf(entry.playerId()));
            entryObj.addProperty(Response.KEY_SCORE, entry.score());
            leaderboardArray.add(entryObj);
        }

        return Response.ok(reqId)
            .with(Response.KEY_LEADERBOARD, leaderboardArray)
            .build();
    }
}
