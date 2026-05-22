package server.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.game.GameManager;
import server.leaderboard.Leaderboard;
import server.net.ChannelContext;
import server.session.PlayerSession;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.util.HashSet;
import java.util.Set;

/******************** Handler Layer ********************************

Entry point applicativo del protocollo lato server.
Riceve richieste gia parse/validate e coordina la logica sui componenti
/session, /game e /leaderboard, costruendo la risposta JSON finale.

Concorrenza:
- invocato in parallelo dai worker thread
- progettato per essere leggero e senza lock globali locali

*****************************************************************************/

public final class SubmitProposalHandler implements RequestRouter.Handler {

    private final GameManager gameManager;
    private final Leaderboard leaderboard;

    public SubmitProposalHandler(GameManager gameManager, Leaderboard leaderboard) {
        this.gameManager = gameManager;
        this.leaderboard = leaderboard;
    }

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (!ctx.auth()) {
            return Response.error(StatusCode.UNAUTHORIZED, reqId).build();
        }

        PlayerSession session = ctx.session();

        JsonArray wordsArray = request.getAsJsonArray(Request.KEY_WORDS);
        Set<String> words = new HashSet<>();
        for (int i = 0; i < wordsArray.size(); i++) {
            words.add(wordsArray.get(i).getAsString().toUpperCase());
        }

        GameManager.ProposalResult result = gameManager.check(session, words);

        switch (result.type()) {
            case CORRECT:
                Response resp = Response.base(
                    result.gameEnded() ? StatusCode.GAME_WON : StatusCode.PROPOSAL_CORRECT,
                    reqId
                );
                resp.with(Response.KEY_GROUP_INDEX, result.groupIndex())
                    .with(Response.KEY_THEME, result.theme())
                    .with(Response.KEY_CORRECT_GROUPS, result.state().correct())
                    .with(Response.KEY_ERRORS, result.state().errors())
                    .with(Response.KEY_SCORE, result.state().score());

                if (result.gameEnded()) {

                    leaderboard.update(session.id(), session.score());
                }

                return resp.build();

            case WRONG:
                Response wrongResp = Response.base(
                    result.gameEnded() ? StatusCode.GAME_LOST : StatusCode.PROPOSAL_WRONG,
                    reqId
                );
                wrongResp.with(Response.KEY_ERRORS, result.state().errors())
                         .with(Response.KEY_SCORE, result.state().score())
                         .with(Response.KEY_CORRECT_GROUPS, result.state().correct());

                if (result.gameEnded()) {
                    leaderboard.update(session.id(), session.score());
                }

                return wrongResp.build();

            case MALFORMED:

                return Response.error(StatusCode.INVALID_PROPOSAL, reqId)
                    .with(Response.KEY_MESSAGE, result.errorMessage())
                    .build();

            case ERROR:
                return Response.error(StatusCode.GAME_ALREADY_FINISHED, reqId)
                    .with(Response.KEY_MESSAGE, result.errorMessage())
                    .build();

            default:
                return Response.error(StatusCode.INTERNAL_ERROR, reqId).build();
        }
    }
}
