package server.handler;

import com.google.gson.JsonObject;
import server.core.RequestRouter;
import server.net.ChannelContext;
import server.security.PasswordHasher;
import server.session.PlayerSession;
import server.session.SessionRegistry;
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

public final class UpdateCredentialsHandler implements RequestRouter.Handler {

    private final SessionRegistry registry;

    public UpdateCredentialsHandler(SessionRegistry registry) {
        this.registry = registry;
    }

    @Override
    public JsonObject handle(JsonObject request, ChannelContext ctx) {
        long reqId = Request.id(request);

        if (!ctx.auth()) {
            return Response.error(StatusCode.UNAUTHORIZED, reqId).build();
        }

        String oldUsername = request.get(Request.KEY_OLD_USERNAME).getAsString();
        String oldPassword = request.get(Request.KEY_OLD_PASSWORD).getAsString();

        PlayerSession session = ctx.session();
        if (!session.name().equals(oldUsername)) {
            return Response.error(StatusCode.AUTH_FAILED, reqId).build();
        }

        if (!PasswordHasher.verify(oldPassword, session.hash())) {
            return Response.error(StatusCode.AUTH_FAILED, reqId).build();
        }

        String newUsername = request.has(Request.KEY_NEW_USERNAME)
            ? request.get(Request.KEY_NEW_USERNAME).getAsString() : null;
        String newPassword = request.has(Request.KEY_NEW_PASSWORD)
            ? request.get(Request.KEY_NEW_PASSWORD).getAsString() : null;

        if (newUsername == null && newPassword == null) {
            return Response.error(StatusCode.BAD_REQUEST, reqId).build();
        }

        if (newUsername != null) {
            if (!registry.rename(oldUsername, newUsername, session)) {
                return Response.error(StatusCode.USERNAME_TAKEN, reqId).build();
            }
        }

        String newHash = newPassword != null
            ? PasswordHasher.hash(newPassword, PasswordHasher.generateSalt())
            : null;

        session.update(newUsername, newHash);

        return Response.base(StatusCode.CREDENTIALS_UPDATED, reqId)
            .build();
    }
}
