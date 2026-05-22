package server.handler;

import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.net.ChannelContext;
import server.session.PlayerSession;
import server.session.SessionState;
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

public final class LogoutHandler implements RequestRouter.Handler {

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (!ctx.auth()) {
            return Response.error(StatusCode.UNAUTHORIZED, reqId).build();
        }

        PlayerSession session = ctx.session();

        session.go(SessionState.OFFLINE);

        ctx.unbind();

        return Response.base(StatusCode.LOGGED_OUT, reqId).build();
    }
}
