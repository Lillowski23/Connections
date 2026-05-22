package client;

import client.cli.AnsiCodes;
import client.cli.CliRenderer;
import client.cli.CommandLoop;
import client.net.ConnectionManager;
import client.net.UdpListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

/******************** Client Main ********************************

Questo file è il punto di ingresso del client.
Qui faccio solo orchestrazione: carico config, apro TCP, apro listener UDP,
avvio il command loop e chiudo tutto in ordine.

Scelte pratiche:
    - Main thread per input da tastiera (più semplice da gestire)
    - Thread selector separato per la socket TCP
    - Thread UDP separato per notifiche asincrone
    - Chiusura pulita delle risorse anche in caso di errore

************************************************************************************/
public final class ClientMain {

    private static final Logger LOG = Logger.getLogger(ClientMain.class.getName());

    public static void main(String[] args) {
        try {
            // --- Configurazione ---
            String configPath = args.length > 0 ? args[0] : "config/client_config.json";
            ClientConfig config = ClientConfig.load(Path.of(configPath));
            LOG.info("Client config loaded: " + config);

            // --- UDP Notifier (ascolto notifiche) -----
            UdpListener notificationReceiver = new UdpListener(config);

            // --- Connessione TCP -----
            ConnectionManager connection = new ConnectionManager(config);
            try {
                connection.connect();
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Failed to connect to server", e);
                System.err.println(AnsiCodes.color(
                    "Cannot connect to " + config.serverHost + ":" + config.serverTcpPort,
                    AnsiCodes.RED));
                System.exit(1);
            }

            // --- Command loop (main thread) ---
            CommandLoop loop = new CommandLoop(connection, notificationReceiver);
            notificationReceiver.start(loop::notification);
            loop.run();

            // --- Cleanup ---
            synchronized (CliRenderer.outputLock()) {
                System.out.println("Closing connection...");
            }
            connection.close();
            notificationReceiver.close();
            LOG.info("Client shutdown.");

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Unexpected error", e);
            System.err.println(AnsiCodes.color("Fatal error: " + e.getMessage(), AnsiCodes.RED));
            System.exit(1);
        }
    }
}
