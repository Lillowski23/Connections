package server.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.game.GameManager;
import server.game.GameState;
import server.game.PlayerGameResult;
import server.net.ChannelContext;
import server.session.PlayerGameState;
import server.session.PlayerSession;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/******************** Handler Layer ********************************

Entry point applicativo del protocollo lato server.
Riceve richieste gia parse/validate e coordina la logica sui componenti
/session, /game e /leaderboard, costruendo la risposta JSON finale.

Concorrenza:
- invocato in parallelo dai worker thread
- progettato per essere leggero e senza lock globali locali

*****************************************************************************/

public final class GameInfoHandler implements RequestRouter.Handler {

    private final GameManager gameManager;

    public GameInfoHandler(GameManager gameManager) {
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

        PlayerSession session = ctx.session();

        GameState current = gameManager.current();
        boolean askCurrent = reqGameId == Request.CURRENT_GAME;
        boolean askCurrentById = current != null && reqGameId == current.gameId();

        if (!askCurrent && !askCurrentById) {
            GameManager.GameStatsData saved = gameManager.stats(reqGameId);
            if (saved == null) {
                return Response.error(StatusCode.GAME_NOT_FOUND, reqId).build();
            }

            PlayerGameResult playerResult = gameManager.result(session.id(), reqGameId);

            GameState past = gameManager.byId(reqGameId);
            if (past == null) {
                return Response.error(StatusCode.GAME_NOT_FOUND, reqId).build();
            }

            Response response = Response.ok(reqId)
                .with(Response.KEY_GAME_ID, reqGameId)
                .with(Response.KEY_TIME_REMAINING, 0)
                .with(Response.KEY_GROUPS, groups(past, null));

            if (playerResult != null) {
                response.with(Response.KEY_CORRECT_GROUPS, playerResult.correct())
                    .with(Response.KEY_ERRORS, playerResult.mistakes())
                    .with(Response.KEY_SCORE, playerResult.score());
            }

            return response.build();
        }

        GameState game = current;
        if (game == null) {
            return Response.error(StatusCode.GAME_NOT_FOUND, reqId).build();
        }

        long delta = game.leftMs();
        boolean expired = delta <= 0;
        long remainingSeconds = expired ? 0 : (delta + 999) / 1000;

        PlayerGameState pgs = session.game();
        PlayerGameResult playerResult = gameManager.result(session.id(), game.gameId());
        Response resp = Response.ok(reqId)
            .with(Response.KEY_GAME_ID, game.gameId())
            .with(Response.KEY_TIME_REMAINING, remainingSeconds);

        if (playerResult != null && (expired || pgs == null || pgs.gameId() != game.gameId() || pgs.done())) {
            resp.with(Response.KEY_CORRECT_GROUPS, playerResult.correct())
                .with(Response.KEY_ERRORS, playerResult.mistakes())
                .with(Response.KEY_SCORE, playerResult.score())
                .with(Response.KEY_GROUPS, groups(game, null));
            return resp.build();
        }

        if (pgs != null && pgs.gameId() == game.gameId()) {
            resp.with(Response.KEY_CORRECT_GROUPS, pgs.correct())
                .with(Response.KEY_ERRORS, pgs.errors())
                .with(Response.KEY_SCORE, pgs.score());

            if (pgs.done() || expired) {
                resp.with(Response.KEY_GROUPS, groups(game, pgs));
            } else {
                resp.with(Response.KEY_REMAINING_WORDS, remaining(game, pgs));
            }
        } else if (!expired) {
            JsonArray words = new JsonArray();
            game.shuffle().forEach(words::add);
            resp.with(Response.KEY_REMAINING_WORDS, words);
        }

        return resp.build();
    }

    private static JsonArray groups(GameState game, PlayerGameState pgs) {
        JsonArray groupsArray = new JsonArray();
        for (int i = 0; i < 4; i++) {
            GameState.Group g = game.group(i);
            JsonObject groupObj = new JsonObject();
            groupObj.addProperty(Response.KEY_THEME, g.theme());
            JsonArray wordsArr = new JsonArray();

            List<String> tmp = new ArrayList<>(g.words());
            Collections.sort(tmp);
            tmp.forEach(wordsArr::add);
            groupObj.add(Response.KEY_WORDS, wordsArr);
            groupObj.addProperty(Response.KEY_FOUND, pgs == null || pgs.found(i));
            groupsArray.add(groupObj);
        }
        return groupsArray;
    }

    private static JsonArray remaining(GameState game, PlayerGameState pgs) {
        JsonArray tmp = new JsonArray();
        for (String word : game.shuffle()) {
            boolean inFoundGroup = false;
            for (int i = 0; i < 4; i++) {
                if (pgs.found(i) && game.group(i).words().contains(word)) {
                    inFoundGroup = true;
                    break;
                }
            }
            if (!inFoundGroup) {
                tmp.add(word);
            }
        }
        return tmp;
    }
}
