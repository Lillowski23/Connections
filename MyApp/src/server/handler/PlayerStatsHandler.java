package server.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.net.ChannelContext;
import server.session.PlayerSession;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

/******************** Handler Layer ********************************

Entry point applicativo del protocollo lato server.
Riceve richieste gia parse/validate e coordina la logica sui componenti
/session, /game e /leaderboard, costruendo la risposta JSON finale.

Concorrenza:
- invocato in parallelo dai worker thread
- progettato per essere leggero e senza lock globali locali

*****************************************************************************/

public final class PlayerStatsHandler implements RequestRouter.Handler {

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (!ctx.auth()) {
            return Response.error(StatusCode.UNAUTHORIZED, reqId).build();
        }

        PlayerSession session = ctx.session();
        PlayerSession.Stats stats = session.stats();

        int[] histogram = stats.histogram();
        JsonArray histArr = new JsonArray();
        for (int count : histogram) {
            histArr.add(count);
        }

        return Response.ok(reqId)
            .with(Response.KEY_USERNAME, session.name())
            .with(Response.KEY_PUZZLES_COMPLETED, stats.played())
            .with(Response.KEY_WIN_RATE, stats.winRate())
            .with(Response.KEY_LOSS_RATE, stats.lossRate())
            .with(Response.KEY_CURRENT_STREAK, stats.streak())
            .with(Response.KEY_MAX_STREAK, stats.bestStreak())
            .with(Response.KEY_PERFECT_PUZZLES, stats.perfect())
            .with(Response.KEY_MISTAKE_HISTOGRAM, histArr)
            .with(Response.KEY_SCORE, stats.score())
            .build();
    }
}
