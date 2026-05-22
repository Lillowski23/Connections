package client.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import client.ClientConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;

/******************** Notification Receiver ********************************

Listener UDP del client per notifiche asincrone dal server.
La porta locale viene aperta all'avvio e inviata durante il login,
così il server sa dove spedire eventi di gioco.

Scelte:
    - thread dedicato e daemon
    - ricezione separata dal canale TCP principale
    - callback semplice verso la CLI

************************************************************************************/

public final class UdpListener implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(UdpListener.class.getName());

    private DatagramChannel channel;
    private Thread listenerThread;
    private volatile boolean running = false;
    private volatile Consumer<JsonObject> handler;
    private int localPort;
    private final int bufferSize;

    public UdpListener(ClientConfig config) {
        this.bufferSize = config.ioBufferSize;
    }

    public void start(Consumer<JsonObject> notificationHandler) throws IOException {
        this.handler = notificationHandler;
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(true); // sospeso dal sistema operativo finché non arriva un datagramma UDP o socket/canale chiusa
        this.channel.bind(new InetSocketAddress(0));
        this.localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();

        running = true;
        listenerThread = new Thread(this::listenLoop, "udp-listener");
        listenerThread.setDaemon(true);
        listenerThread.start();

        LOG.info("UDP NotificationReceiver listening on port " + localPort);
    }

    
    public int port() {
        return localPort;
    }

    
    private void listenLoop() {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

        while (running) {
            try {
                buffer.clear();
                channel.receive(buffer); // Blocking
                buffer.flip();

                String message = StandardCharsets.UTF_8.decode(buffer).toString().trim();
                if (!message.isEmpty()) {
                    try {
                        JsonObject json = JsonParser.parseString(message).getAsJsonObject();
                        Consumer<JsonObject> h = handler;
                        if (h != null) {
                            h.accept(json);
                        }
                    } catch (Exception e) {
                        LOG.fine("Invalid UDP notification: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (running) {
                    LOG.fine("UDP receive error: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (channel != null) channel.close(); // Sveglia il thread bloccato su receive()
        } catch (IOException ignored) {}
    }
}
