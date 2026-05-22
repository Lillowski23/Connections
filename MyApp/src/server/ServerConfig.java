package server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;


/******************** Server Config ********************************
  
Contenitore unico della configurazione server caricata da JSON.
Qui tengo tutte le variabili operative: porte, pool, sicurezza,
parametri sessione, persistenza e timer.

Scelte:
    - config esterna per evitare hardcode
    - oggetto immutabile dopo il load
    - fallback automatico per pool size quando nel file c'è 0

Così ogni componente legge valori coerenti senza doversi parsare il file da solo.

************************************************************************************/


public final class ServerConfig {

    // --- Net ---
    public final int tcpPort;
    public final int tcpBacklog;

    // --- Game ---
    public final String gameDataPath;
    public final int gameDurationSeconds;
    public final int gameRestSeconds;

    // --- Core ---
    public final int poolCoreSize;
    public final int poolMaxSize;
    public final int poolKeepAliveSeconds;
    public final int poolQueueCapacity;

    // --- I/O ---
    public final int ioDispatcherCount;
    public final int ioBufferSize;
    public final int ioBufferPoolSize;

    // --- Persistence ---
    public final int persistenceIntervalSeconds;
    public final String persistenceUsersPath;
    public final String persistenceGamesPath;

    // --- Session Monitor ---
    public final int reconnectGraceSeconds;
    public final int evictInactiveHours;
    public final int sessionMonitorIntervalSeconds;

    // --- Security ---
    public final int maxRequestsPerSecond;
    public final int strikeBanShortSeconds;
    public final int strikeBanLongSeconds;
    public final int strikeThresholdShort;
    public final int strikeThresholdLong;
    public final int maxMessageBytes;

    private ServerConfig(JsonObject json) {
        // Networking
        this.tcpPort = json.get("server.tcp.port").getAsInt();
        this.tcpBacklog = json.get("server.tcp.backlog").getAsInt();

        // Game
        this.gameDataPath = json.get("game.data.path").getAsString();
        this.gameDurationSeconds = json.get("game.duration.seconds").getAsInt();
        this.gameRestSeconds = json.has("game.rest.seconds")
            ? Math.max(0, json.get("game.rest.seconds").getAsInt())
            : 10;

        // Worker Pool — 0 = auto-detect basato su CPU
        int cores = Runtime.getRuntime().availableProcessors();
        int rawCore = json.get("pool.core.size").getAsInt();
        int rawMax = json.get("pool.max.size").getAsInt();
        this.poolCoreSize = (rawCore > 0) ? rawCore : cores;
        this.poolMaxSize = (rawMax > 0) ? rawMax : cores * 4;
        this.poolKeepAliveSeconds = json.get("pool.keepalive.seconds").getAsInt();
        this.poolQueueCapacity = json.get("pool.queue.capacity").getAsInt();

        // I/O
        this.ioDispatcherCount = json.get("io.dispatcher.count").getAsInt();
        this.ioBufferSize = json.get("io.buffer.size").getAsInt();
        this.ioBufferPoolSize = json.get("io.buffer.pool.size").getAsInt();

        // Persistence
        this.persistenceIntervalSeconds = json.get("persistence.interval.seconds").getAsInt();
        this.persistenceUsersPath = json.get("persistence.users.path").getAsString();
        this.persistenceGamesPath = json.get("persistence.games.path").getAsString();

        // Session Monitor
        this.reconnectGraceSeconds = json.get("session.reconnect.grace.seconds").getAsInt();
        this.evictInactiveHours = json.get("session.evict.inactive.hours").getAsInt();
        this.sessionMonitorIntervalSeconds = json.get("session.monitor.interval.seconds").getAsInt();

        // Security
        this.maxRequestsPerSecond = json.get("security.max.requests.per.second").getAsInt();
        this.strikeBanShortSeconds = json.get("security.strike.ban.short.seconds").getAsInt();
        this.strikeBanLongSeconds = json.get("security.strike.ban.long.seconds").getAsInt();
        this.strikeThresholdShort = json.get("security.strike.threshold.short").getAsInt();
        this.strikeThresholdLong = json.get("security.strike.threshold.long").getAsInt();
        this.maxMessageBytes = json.get("security.max.message.bytes").getAsInt();
    }

    
    public static ServerConfig load(Path path) throws IOException {
        String tmp = Files.readString(path);
        JsonObject json = JsonParser.parseString(tmp).getAsJsonObject();
        return new ServerConfig(json);
    }

    @Override
    public String toString() {
        return String.format(
            "ServerConfig{tcp=%d, pool=[%d-%d], game=%ds, rest=%ds, persist=%ds}",
            tcpPort, poolCoreSize, poolMaxSize,
            gameDurationSeconds, gameRestSeconds, persistenceIntervalSeconds
        );
    }
}
