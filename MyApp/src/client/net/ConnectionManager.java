package client.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import client.ClientConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import shared.protocol.Request;
import shared.protocol.Response;

/******************** Connection Manager ********************************

Questa classe gestisce tutta la connessione TCP lato client.
Uso NIO con un thread selector dedicato per leggere le risposte,
mentre il thread della CLI continua a mandare richieste.

Scelte strategiche:
    - requestId progressivo per fare matching request/response
    - mappa concurrent di future pendenti
    - framing a newline per ricostruire i messaggi su stream TCP
    - close centralizzato

L'obbiettivo è quello di avere comandi semplici per la CLI (ask) ma con I/O asincrono.

************************************************************************************/

public final class ConnectionManager implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ConnectionManager.class.getName());

    private final AtomicLong requestIdCounter = new AtomicLong(1);
    private final ClientConfig config;
    private SocketChannel channel;
    private Selector selector;
    private Thread selectorThread;
    private volatile boolean running = false;
    private ByteBuffer readBuffer;
    private ByteBuffer currentWriteBuffer;
    private final StringBuilder accumulator = new StringBuilder();
    private final ConcurrentLinkedQueue<ByteBuffer> pendingWrites = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, CompletableFuture<JsonObject>> pendingRequests = new ConcurrentHashMap<>();

    
    public ConnectionManager(ClientConfig config) {
        this.config = config;
    }

    
    public void connect() throws IOException {
        this.channel = SocketChannel.open();
        this.channel.configureBlocking(false);
        this.channel.socket().setTcpNoDelay(true);

        this.selector = Selector.open();
        this.readBuffer = ByteBuffer.allocateDirect(config.ioBufferSize);

        channel.connect(new InetSocketAddress(config.serverHost, config.serverTcpPort));
        channel.register(selector, SelectionKey.OP_CONNECT);

        
        long delta = System.currentTimeMillis() + config.connectionTimeoutSeconds * 1000L;
        while (!channel.isConnected()) {
            long remaining = delta - System.currentTimeMillis();
            if (remaining <= 0) throw new IOException("Connection timeout");
            selector.select(remaining);

            for (SelectionKey key : selector.selectedKeys()) {
                if (key.isConnectable()) {
                    channel.finishConnect();
                }
            }
            selector.selectedKeys().clear();
        }

        // Connessione stabilita
        channel.keyFor(selector).interestOps(SelectionKey.OP_READ);

        //Avvio Selector thread
        running = true;
        selectorThread = new Thread(this::selectorLoop, "client-selector");
        selectorThread.setDaemon(true);
        selectorThread.start();

        LOG.info("Connected to " + config.serverHost + ":" + config.serverTcpPort);
    }

    
    public CompletableFuture<JsonObject> send(JsonObject request) throws IOException {
        if (!connected()) {
            throw new IOException("Not connected");
        }

        long reqId = requestIdCounter.getAndIncrement();
        request.addProperty(Request.KEY_REQUEST_ID, reqId);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pendingRequests.put(reqId, future);
        
        future.orTimeout(config.responseTimeoutSeconds, TimeUnit.SECONDS)
          .whenComplete((result, exception) -> {
              if (exception != null) {
                  pendingRequests.remove(reqId);
              }
          });
        
        String req = request.toString() + "\n";
        pendingWrites.offer(ByteBuffer.wrap(req.getBytes(StandardCharsets.UTF_8)));

        Selector tmp = selector;
        if (tmp != null) {
            tmp.wakeup();
        }

        return future;
    }

    public JsonObject ask(JsonObject request) throws IOException {
    try {
        // Nessun timeout specificato qui, logia spostata nel future di send()
        return send(request).get(); 
        
    } catch (java.util.concurrent.ExecutionException e) {
        if (e.getCause() instanceof TimeoutException) {
            throw new IOException("Server response timeout");
        }
        throw new IOException("Request failed: " + e.getCause().getMessage(), e.getCause());
        
    } catch (InterruptedException e) {
        // Cli riceve errore di interruzione, il ConnectionManager Thread deve chiudere la connessione non è solo un errore di timeout
        Thread.currentThread().interrupt(); 
        throw new IOException("Request interrupted", e);
    }
}

   

    // --- Selector loop ---

    private void selectorLoop() {
        while (running) {
            try {
                enableWriteIfNeeded();
                int ready = selector.select();
                if (!running) break;
                enableWriteIfNeeded();
                if (ready == 0) continue;

                var keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    if (key.isReadable()) {
                        read();
                    }

                    if (key.isValid() && key.isWritable()) {
                        write(key);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    LOG.log(Level.WARNING, "Selector error", e);
                    disconnect();
                }
            } catch (ClosedSelectorException e) {
                if (running) {
                    LOG.log(Level.WARNING, "Selector closed unexpectedly", e);
                    disconnect();
                }
            }
        }
    }

    private void enableWriteIfNeeded() {
        if (channel == null || selector == null) {
            return;
        }

        SelectionKey key = channel.keyFor(selector);
        if (key != null && key.isValid() && hasPendingWrite()) {
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }
    }

    
    private void read() throws IOException {
        readBuffer.clear();
        int bytesRead = channel.read(readBuffer);

        if (bytesRead == -1) {
            // Connesione chiusa lato server
            disconnect();
            return;
        }

        readBuffer.flip();
        String frame = StandardCharsets.UTF_8.decode(readBuffer).toString();
        accumulator.append(frame);

        
        String acc = accumulator.toString();
        int idx;
        while ((idx = acc.indexOf('\n')) >= 0) {
            String message = acc.substring(0, idx).trim();
            acc = acc.substring(idx + 1);

            if (!message.isEmpty()) {
                message(message);
            }
        }
        accumulator.setLength(0);
        accumulator.append(acc);
    }

    private void write(SelectionKey key) throws IOException {
        while (true) {
            if (currentWriteBuffer == null) {
                currentWriteBuffer = pendingWrites.poll();
            }

            if (currentWriteBuffer == null) {
                break;
            }

            channel.write(currentWriteBuffer);
            if (currentWriteBuffer.hasRemaining()) {
                break;
            }

            currentWriteBuffer = null;
        }

        if (!hasPendingWrite() && key.isValid()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private boolean hasPendingWrite() {
        return currentWriteBuffer != null || !pendingWrites.isEmpty();
    }

   
    private void message(String jsonStr) {
        try {
            JsonObject json = JsonParser.parseString(jsonStr).getAsJsonObject();

            // È una notifica?
            if (Response.note(json)) {
                return;
            }

            // È una risposta
            long reqId = Response.id(json);
            CompletableFuture<JsonObject> future = pendingRequests.remove(reqId);
            if (future != null) {
                future.complete(json);
            } else {
                LOG.warning("Received response for unknown requestId: " + reqId);
            }

        } catch (Exception e) {
            LOG.warning("Failed to parse server message: " + e.getMessage());
        }
    }

    private void disconnect() {
        running = false;
        // Completa tutti i future pendenti con errore
        pendingRequests.forEach((id, future) ->
            future.completeExceptionally(new IOException("Disconnected from server")));
        pendingRequests.clear();
        pendingWrites.clear();
        currentWriteBuffer = null;
        LOG.warning("Disconnected from server");
    }

    public boolean connected() {
        return running && channel != null && channel.isConnected();
    }

    @Override
    public void close() {
        running = false;
        pendingWrites.clear();
        currentWriteBuffer = null;
        try {
            if (selector != null) selector.wakeup();
            if (channel != null) channel.close();
            if (selector != null) selector.close();
        } catch (IOException ignored) {}
    }
}
