package server.net;

import server.ServerConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;


/******************** Acceptor  ********************************

La classe Acceptor è il punto di ingresso del server.
Serve ad accettare le connessioni TCP, configurarle in modalità non bloccante 
e quindi smistarle circolarmente ai dispatcher I/O. 

 
Dato che l'accept è un'operazione poco frequente e rapidissima, mi basta un singolo thread Acceptor che 
gestisce SOLO OP_ACCEPT. 

Quando arriva una nuova connessione:
   1. Accetta il SocketChannel
   2. Lo configura come non-bloccante
   3. Crea il ChannelContext associato
   4. Lo passa all'IODispatcher per la registrazione OP_READ

Separare la logica dagli I/O dispatcher evita che un accept venga ritardato
perché il dispatcher è ipegnato a leggere dati. Inoltre grazie a Selector,
garantisco che il thread non consumi CPU quando non ci sono connessioni.

************************************************************************************/


public final class Acceptor implements Runnable {

    private static final Logger LOG = Logger.getLogger(Acceptor.class.getName());

    private final ServerConfig config;
    private final IODispatcher[] dispatchers;
    private volatile boolean running = true;
    private Selector selector; 

    private int nextDispatcher = 0;

    public Acceptor(ServerConfig config, IODispatcher[] dispatchers) {
        this.config = config;
        this.dispatchers = dispatchers;
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup(); // Chicca: sveglia immediatamente il thead bloccato su select()
        }
    }

    @Override
    public void run() {
        Thread.currentThread().setName("acceptor");

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             Selector tmp = Selector.open()) {
             
            this.selector = tmp; 

            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress(config.tcpPort), config.tcpBacklog);
            serverChannel.register(tmp, SelectionKey.OP_ACCEPT);

            LOG.info("Acceptor  port: " + config.tcpPort);

            while (running) {
                int ready = tmp.select();
                if (!running) break;
                if (ready == 0) continue;

                Iterator<SelectionKey> keys = tmp.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (key.isAcceptable()) {
                        accept(serverChannel);
                    }
                }
            }

        } catch (IOException e) {
            if (running) {
                LOG.log(Level.SEVERE, "Acceptor error", e);
            }
        }

        LOG.info("Acceptor stopped");
    }

    
    private void accept(ServerSocketChannel serverChannel) throws IOException {
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel == null) return; 

        clientChannel.configureBlocking(false);
        clientChannel.socket().setTcpNoDelay(true); //Serve per fowardare immediatamente i pacchetti
        ChannelContext con = new ChannelContext(clientChannel, config.maxMessageBytes); 
        IODispatcher dispatcher = dispatchers[nextDispatcher];
        nextDispatcher = (nextDispatcher + 1) % dispatchers.length;
        dispatcher.add(clientChannel, con);// Registra su una concurrent linked queue e gestione thread safe compito del dispatcher
        
        int tmp = (nextDispatcher == 0) ? dispatchers.length - 1 : nextDispatcher - 1;
        LOG.fine("Accepted connection from: " + con.remote()
                 + " -> dispatcher-" + tmp);
    }

}
