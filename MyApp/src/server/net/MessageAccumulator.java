package server.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;



/******************** Message Accumulator ********************************

Questa classe esiste perché TCP invia stream di byte, ho bisogno di ricostrurie il messaggio.

Quando il server fa una read() su un SocketChannel, non ha nessuna garanzia
di ricevere un messaggio completo.

Per semplicità mi sono interamente appoggiato al delimitatore \n
di ogni messaggio JSON che viene serializzato su una sola riga e termina con \n.
  
L'accumulatore mette insieme i byte letti, li decodifica in UTF-8,
li inserisce in uno StringBuilder (evitando di creare oggetti intermedi)
ogni volta che trova il delimitatore 
estrae un messaggio completo da inviare al livello superiore.


Avrei potuto imporre delle regole sulla dimensione del frame
ma dato che il protocollo ha questa caratteristica ho adottato questa strategia.

Il che però introduce delle vulnerabilità:

•   un client può tentare di inviare un payload massiccio senza delimitatore
•   un client può mandare input preparati per raggirare il parser.
•   se il canale non fosse protetto da TLS, chiunque potrebbe iniettare dati malevoli nel flusso
    o peggio ricostruirre con reverse-engenerring messaggi validi per attaccare il server.

Dato che trovo molto interessante il lato di sicurezza, 
un primo step per ridurre il rischio di attacco è stato
imporre un limite massimo di messaggi di accumulo.

(maxMessageSize): se arrivano troppi dati senza mai trovare '\n',
                  la connessione viene considerata malevola e viene segnalata.
                  Per lo meno ci evitiamo attacchi Dos.


Notare che: questa classe NON è thread-safe.
            Ogni ChannelContext possiede
            il proprio MessageAccumulator e lo usa un solo thread I/O alla volta.
            
************************************************************************************/


public final class MessageAccumulator {

   
    private static final char del = '\n';
    private final StringBuilder accumulator = new StringBuilder(256);
    private final int maxMessageSize;

    
    public MessageAccumulator(int maxMessageSize) {
        this.maxMessageSize = maxMessageSize;
    }

    
    public String[] feed(ByteBuffer buffer) throws MessageOverflowException {
        String tmp = StandardCharsets.UTF_8.decode(buffer).toString();

        if (accumulator.length() == 0 && tmp.endsWith("\n")) {
            String[] messages = tmp.split("\n", -1);
            String[] res = new String[messages.length - 1];
            for (int i = 0; i < res.length; i++) {
                if (messages[i].length() > maxMessageSize) {
                    throw new MessageOverflowException(
                        "Messaggio malevolo identificato: (length)" + messages[i].length());
                }
                res[i] = messages[i];
            }
            return res;
        }

        accumulator.append(tmp);

        if (accumulator.length() > maxMessageSize && accumulator.indexOf("\n") == -1) {
            accumulator.setLength(0);
            throw new MessageOverflowException(
                "Accumulati" + accumulator.length() + " bytets senza delimitatore... possibile attacco DoS");
        }

        List<String> messages = new ArrayList<>();
        int idx;
        while ((idx = accumulator.indexOf(String.valueOf(del))) != -1) {
            String msg = accumulator.substring(0, idx);
            accumulator.delete(0, idx + 1);
            if (!msg.isEmpty()) {
                messages.add(msg);
            }
        }

        return messages.toArray(new String[0]);
    }

    
    public void reset() {
        accumulator.setLength(0);
    }

    public static class MessageOverflowException extends Exception {
        public MessageOverflowException(String message) {
            super(message);
        }
    }
}