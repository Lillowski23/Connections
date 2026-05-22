package server.core;

import com.google.gson.JsonObject;
import server.net.ChannelContext;
import server.security.RequestValidator;
import shared.protocol.Operation;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.util.EnumMap;
import java.util.Map;

/******************** RequestRouter ********************************

Il router (ispirato a Node.js con il meccanismo di request handling) 
viene chiamato dai worker thread,
smistando ogni richiesta json validata all'handler corrispondente. 
Ogni invocazione è indipendente e il fatto che non ci sia
uno stato condiviso nel router stesso lo rende thread safe per costruzione.

Prima di invocare l'handler viene usato RequestValidator, cosi' gli handler
ricevono richieste gia' controllate nella struttura dei campi principali.
Se la validazione fallisce il router risponde direttamente con BAD_REQUEST,
senza far arrivare payload malformati alla logica applicativa.

Dichiarando una functional interface "Handler"
dichiaro che il router ha uno e un solo metodo astratto "handle" che riceve una richiesta e restituisce una risposta.
Ogni handler specifico (RegisterHandler, LoginHandler, ecc.) implementa questa interfaccia ma grazie alla firma del metodo astratto
garantisce che riceva JsonObject request e ChannelContext ctx e che restituisca JsonObject response).

L'ultima chicca architetturale sta nella scelta di usare Operation come campo chiave della mappa ovvero un enum 
piuttosto che una stringa perché risulta anche più efficente di un hashmap e soprattuto disambigua il campo "operation" da altri campi stringa 
che potrebbero essere presenti nella richiesta. 
************************************************************************************/


public final class RequestRouter {

    @FunctionalInterface
    public interface Handler {
        JsonObject handle(JsonObject request, ChannelContext ctx);
    }

    
    private final Map<Operation, Handler> handlers;

    
    public RequestRouter(Handler registerHandler,
                         Handler loginHandler,
                         Handler logoutHandler,
                         Handler updateCredentialsHandler,
                         Handler submitProposalHandler,
                         Handler gameInfoHandler,
                         Handler gameStatsHandler,
                         Handler leaderboardHandler,
                         Handler playerStatsHandler) {

        this.handlers = new EnumMap<Operation, Handler>(Operation.class);
        handlers.put(Operation.REGISTER, registerHandler);
        handlers.put(Operation.LOGIN, loginHandler);
        handlers.put(Operation.LOGOUT, logoutHandler);
        handlers.put(Operation.UPDATE_CREDENTIALS, updateCredentialsHandler);
        handlers.put(Operation.SUBMIT_PROPOSAL, submitProposalHandler);
        handlers.put(Operation.REQUEST_GAME_INFO, gameInfoHandler);
        handlers.put(Operation.REQUEST_GAME_STATS, gameStatsHandler);
        handlers.put(Operation.REQUEST_LEADERBOARD, leaderboardHandler);
        handlers.put(Operation.REQUEST_PLAYER_STATS, playerStatsHandler);
    }

   
    public JsonObject route(JsonObject request, ChannelContext ctx) {
        long requestId;
        try {
            requestId = Request.id(request);
        } catch (Exception e) {
            requestId = -1;
        }

        Operation op;
        try {
            op = Request.op(request);
        } catch (Exception e) {
            return Response.error(StatusCode.UNKNOWN_OPERATION, requestId).build();
        }

        if (op == null) {
            return Response.error(StatusCode.UNKNOWN_OPERATION, requestId).build();
        }

        String validationError = RequestValidator.check(op, request);
        if (validationError != null) {
            return Response.error(StatusCode.BAD_REQUEST, requestId)
                .with(Response.KEY_MESSAGE, validationError)
                .build();
        }

        Handler handler = handlers.get(op);
        if (handler == null) {
            //Non succede ma se succede...
            return Response.error(StatusCode.INTERNAL_ERROR, requestId).build();
        }

        return handler.handle(request, ctx);
    }
}
