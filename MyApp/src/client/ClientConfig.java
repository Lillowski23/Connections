package client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/******************** Client Config ********************************

Configurazione del client letta da JSON all'avvio.


Scelte:
    - campi immutabili 
    - nessuna dipendenza dal lato server

************************************************************************************/


public final class ClientConfig {

    
    public final String serverHost;
    public final int serverTcpPort;
    public final int connectionTimeoutSeconds;
    public final int responseTimeoutSeconds;
    public final int ioBufferSize;

    private ClientConfig(JsonObject json) {
        this.serverHost = json.get("server.host").getAsString();
        this.serverTcpPort = json.get("server.tcp.port").getAsInt();
        this.connectionTimeoutSeconds = json.get("connection.timeout.seconds").getAsInt();
        this.responseTimeoutSeconds = json.get("response.timeout.seconds").getAsInt();
        this.ioBufferSize = json.get("io.buffer.size").getAsInt();
    }

    public static ClientConfig load(Path path) throws IOException {
        String tmp = Files.readString(path);
        JsonObject json = JsonParser.parseString(tmp).getAsJsonObject();
        return new ClientConfig(json);
    }

    @Override
    public String toString() {
        return String.format("ClientConfig{server=%s:%d, timeout=%ds}",
            serverHost, serverTcpPort, responseTimeoutSeconds);
    }
}
