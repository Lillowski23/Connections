package server.net;

import server.session.PlayerSession;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;



/******************** ChannelContext ********************************

ChannelContext rappresenta lo stato di ogni TCP.

Ogni SocketChannel accettato dall'Acceptor viene associato dinamicamente a un'istanza di
ChannelContext per poi essere attaccata alla SelectionKey del relativo
IODispatcher.

Il contesto contiene:
    - canale TCP associato
    - indirizzo remoto 
    - timestamp di connessione
    - MessageAccumulator per ricostruire i messaggi dallo stream TCP
    - coda di output verso il client (producer: worker, consumer: dispatcher)
    - PlayerSession associata (null finché il client non fa login)

Ciclo di vita:
    1) creato dall'Acceptor alla nuova connessione
    2) usato dal dispatcher per read/write e framing
    3) bind della sessione al login
    4) unbind al logout esplicito
    5) cleanup alla chiusura canale (la PlayerSession sopravvive nel registry)

Thread-safety (scelta progettuale):
    - framer: usato dal solo thread I/O proprietario della connessione
    - writeQueue: ConcurrentLinkedQueue lock-free tra worker e dispatcher
    - session: volatile per garantire visibilità cross-thread dopo bind/unbind

In sintesi: ChannelContext è il "ponte" tra socket, stato utente e pipeline
di invio/ricezione, mantenendo il modello Reactor pulito e leggibile.

************************************************************************************/


public final class ChannelContext {

    
    private final SocketChannel channel;
    private final String remoteAddress;
    private final long connectedAt;
    private final MessageAccumulator framer;
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue;
    private ByteBuffer currentWriteBuffer;
     
    //La sessione è volatile perché viene scritto dal worker thread (durante login) e letto dall'IODispatcher e dal SessionMonitor
     //quindi devo garantire che gli altri thread vedano davvero l'aggiornamento della sessione dopo il login.
    private volatile PlayerSession session;

    
    public ChannelContext(SocketChannel channel, int maxMessageSize) {
        this.channel = channel;
        this.connectedAt = System.currentTimeMillis();
        this.framer = new MessageAccumulator(maxMessageSize);
        this.writeQueue = new ConcurrentLinkedQueue<>();
        this.currentWriteBuffer = null;

        String addr;
        try {
            addr = channel.getRemoteAddress().toString();
        } catch (Exception e) {
            addr = "unknown";
        }
        this.remoteAddress = addr;
        this.session = null;
    }

     public void bind(PlayerSession session) {
        this.session = session;
    }

    //la sessione viene disassociata ma non chiusa (rimane in stato DISCONNECTED)
    public void unbind() {
        this.session = null;
    }

    
    public boolean auth() {
        return session != null;
    }

    public void write(String jsonString) {
        byte[] tmp = (jsonString + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeQueue.offer(ByteBuffer.wrap(tmp));
    }

    public ByteBuffer next() {
        if (currentWriteBuffer != null) {
            return currentWriteBuffer;
        }
        currentWriteBuffer = writeQueue.poll();
        return currentWriteBuffer;
    }

    public void clearWrite() {
        currentWriteBuffer = null;
    }

    public boolean hasWrite() {
        return currentWriteBuffer != null || !writeQueue.isEmpty();
    }

    public void cleanup() {
        framer.reset();
        currentWriteBuffer = null;
        writeQueue.clear();
/* Non faccio unbind della sessione qui: in caso di disconnessione TCP
 la PlayerSession è già stata marcata DISCONNECTED dal dispatcher(!!!) e resta
 nel registry per permettere eventuale riconnessione. Questo metodo pulisce
 solo lo STATO LOCALE della connessione. */
    }
    
    public SocketChannel channel() {
        return channel;
    }

    public String remote() {
        return remoteAddress;
    }

    public long connectedAt() {
        return connectedAt;
    }

    public MessageAccumulator framer() {
        return framer;
    }

    public PlayerSession session() {
        return session;
    }

}
