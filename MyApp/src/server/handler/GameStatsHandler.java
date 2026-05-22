package server.handler;

import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.game.GameManager;
import server.game.GameState;
import server.net.ChannelContext;
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

public final class GameStatsHandler implements RequestRouter.Handler {

    private final GameManager gameManager;

    public GameStatsHandler(GameManager gameManager) {
        this.gameManager = gameManager;
    }

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (!ctx.auth()) {
            return Response.error(StatusCode.UNAUTHORIZED, reqId).build();
        }

        int reqGameId = Request.CURRENT_GAME;
        if (request.has(Request.KEY_GAME_ID) && !request.get(Request.KEY_GAME_ID).isJsonNull()) {
            reqGameId = request.get(Request.KEY_GAME_ID).getAsInt();
        }

        GameState current = gameManager.current();
        boolean askCurrent = reqGameId == Request.CURRENT_GAME; //Flag -1 da protocollo
        boolean askCurrentById = current != null && reqGameId == current.gameId(); //Flag id esplicito uguale alla partita corrente

        // ramo partita corrente 
        if (askCurrent || askCurrentById) {
            if (current == null) {
                return Response.error(StatusCode.GAME_NOT_FOUND, reqId).build();
            }

            int active = gameManager.active();
            int finished = gameManager.finished();
            int won = gameManager.won();
            int total = active + finished;
            double avg = 0.0;
            if (total > 0) {
                GameManager.GameStatsData curStats = gameManager.stats(current.gameId());
                if (curStats != null) {
                    avg = (double) curStats.totalScore / (double) total;
                }
            }

            return Response.ok(reqId)
                .with(Response.KEY_GAME_ID, current.gameId())
                .with(Response.KEY_TIME_REMAINING, current.left())
                .with(Response.KEY_PLAYERS_ACTIVE, active)
                .with(Response.KEY_PLAYERS_FINISHED, finished)
                .with(Response.KEY_PLAYERS_WON, won)
                .with(Response.KEY_TOTAL_PLAYERS, total)
                .with(Response.KEY_AVERAGE_SCORE, avg)
                .build();
        }

        // ramo storico: partita non corrente, letta dagli aggregati persistiti in GameManager
        GameManager.GameStatsData savedStats = gameManager.stats(reqGameId);
        if (savedStats == null) {
            return Response.error(StatusCode.GAME_NOT_FOUND, reqId).build();
        }

        double avg = 0.0;
        if (savedStats.participants > 0) {
            avg = (double) savedStats.totalScore / (double) savedStats.participants;
        }

        return Response.ok(reqId)
            .with(Response.KEY_GAME_ID, reqGameId)
            .with(Response.KEY_TIME_REMAINING, 0)
            .with(Response.KEY_PLAYERS_ACTIVE, 0)
            .with(Response.KEY_PLAYERS_FINISHED, savedStats.finished)
            .with(Response.KEY_PLAYERS_WON, savedStats.won)
            .with(Response.KEY_TOTAL_PLAYERS, savedStats.participants)
            .with(Response.KEY_AVERAGE_SCORE, avg)
            .build();
    }
}
