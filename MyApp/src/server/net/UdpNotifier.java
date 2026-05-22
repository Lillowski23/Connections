package server.net;

import com.google.gson.JsonObject;
import server.session.PlayerSession;
import server.session.SessionRegistry;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;


/******************** UDP Manager ********************************

Notifica avvisi asincroni UDP ai client tramite singolo DatagramChannel 
in modalità non-bloccante.

Il client, al login, comunica la sua porta UDP. 
L'indirizzo viene salvato nella PlayerSession.

************************************************************************************/

public final class UdpNotifier {

    private static final Logger LOG = Logger.getLogger(UdpNotifier.class.getName());

    private final DatagramChannel channel;
    private final SessionRegistry sessionRegistry;

    public UdpNotifier(SessionRegistry sessionRegistry) throws IOException {
        this.sessionRegistry = sessionRegistry;
        this.channel = DatagramChannel.open();
        this.channel.configureBlocking(false);
        LOG.info("UdpNotifier online");
    }

    
    public void send(JsonObject notification) {
        byte[] data = (notification.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(data);

        //Notifica broadcast 
        sessionRegistry.forEachConnected(session -> {
            InetSocketAddress udpAddr = session.udp();
            if (udpAddr != null) {
                try {
                    buffer.rewind(); 
                    channel.send(buffer, udpAddr);
                } catch (IOException e) {
                    LOG.fine("Ipossiible inviare notifica UDP a " + udpAddr + ": " + e.getMessage());
                }
            }
        });
    }

    
    public void sendTo(PlayerSession session, JsonObject notification) {
        InetSocketAddress udpAddr = session.udp();
        if (udpAddr == null) return;

        byte[] data = (notification.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            channel.send(buffer, udpAddr);
        } catch (IOException e) {
            LOG.fine("Ipossiible inviare notifica UDP a " + udpAddr);
        }
    }

    
    public void close() {
        try {
            channel.close();
        } catch (IOException ignored) {}
    }
}
