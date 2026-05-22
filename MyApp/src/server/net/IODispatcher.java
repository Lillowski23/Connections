package server.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import server.core.RequestRouter;
import server.core.WorkerPool;
import server.security.RequestGuard;
import server.storage.ByteBufferPool;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/******************** IODispatcher ********************************

Thread Reactor I/O del server per canali TCP assegnati.
Gestisce OP_READ/OP_WRITE, framing messaggi e delega la logica cpu bound
al WorkerPool.

Concorrenza:
- un dispatcher per thread
- thread-safe con queue pending + selector.wakeup()

*****************************************************************************/

public final class IODispatcher implements Runnable {

    private static final Logger LOG = Logger.getLogger(IODispatcher.class.getName());

    private final int id;
    private final ByteBufferPool bufferPool;
    private final WorkerPool workerPool;
    private final RequestRouter router;
    private final RequestGuard guard;

    private Selector selector;
    private volatile boolean running = true;
    private final ConcurrentLinkedQueue<Object[]> pendingRegistrations = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SelectionKey> pendingWriteKeys = new ConcurrentLinkedQueue<>();

    public IODispatcher(int id, ByteBufferPool bufferPool, WorkerPool workerPool,
                        RequestRouter router, RequestGuard guard) {
        this.id = id;
        this.bufferPool = bufferPool;
        this.workerPool = workerPool;
        this.router = router;
        this.guard = guard;
    }

    public void add(SocketChannel channel, ChannelContext ctx) {
        pendingRegistrations.offer(new Object[]{channel, ctx});
        if (selector != null) {
            selector.wakeup();
        }
    }

    @Override
    public void run() {
        Thread.currentThread().setName("io-dispatcher-" + id);

        try {
            this.selector = Selector.open();
            LOG.info("IODispatcher-" + id + " running");

            while (running) {
                regs();
                writes();

                int ready = selector.select();
                if (!running) break;
                regs();
                writes();
                if (ready == 0) continue;

                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    try {
                        if (key.isReadable()) {
                            read(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            write(key);
                        }
                    } catch (IOException e) {
                        disconnect(key, e);
                    }
                }
            }

        } catch (IOException e) {
            if (running) {
                LOG.log(Level.SEVERE, "IODispatcher-" + id + " error", e);
            }
        }

        LOG.info("IODispatcher-" + id + " arrestato");
    }

    private void regs() {
        Object[] tmp;
        while ((tmp = pendingRegistrations.poll()) != null) {
            SocketChannel channel = (SocketChannel) tmp[0];
            ChannelContext ctx = (ChannelContext) tmp[1];
            try {
                channel.register(selector, SelectionKey.OP_READ, ctx);
                LOG.fine("Canale registrato da " + ctx.remote());
            } catch (IOException e) {
                LOG.warning("Channel registration error: " + e.getMessage());
                try { channel.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void read(SelectionKey key) throws IOException {
        ChannelContext ctx = (ChannelContext) key.attachment();
        SocketChannel channel = ctx.channel();
        ByteBuffer tmp = bufferPool.get();

        try {
            int bytesRead = channel.read(tmp);

            if (bytesRead == -1) {

                disconnect(key, null);
                return;
            }

            if (bytesRead == 0) return;

            tmp.flip();
            String[] messages;

            try {
                messages = ctx.framer().feed(tmp);
            } catch (MessageAccumulator.MessageOverflowException e) {

                LOG.warning("Possible hacking attempt from " + ctx.remote() + ": " + e.getMessage());
                disconnect(key, e);
                return;
            }

            for (String rawMessage : messages) {
                message(rawMessage, ctx, key);
            }

        } finally {

            bufferPool.put(tmp);
        }
    }

    private void message(String rawByte, ChannelContext ctx, SelectionKey key) {

        JsonObject json;
        try {
            json = JsonParser.parseString(rawByte).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {

            guard.recordViolation(ctx.remote());
            long reqId = -1;
            ctx.write(
                Response.error(StatusCode.BAD_REQUEST, reqId).build().toString()
            );
            requestWrite(key);
            return;
        }

        RequestGuard.CheckResult res = guard.check(ctx.remote());
        if (res != RequestGuard.CheckResult.ALLOWED) {
            long reqId = Request.id(json);
            ctx.write(
                Response.error(StatusCode.RATE_LIMITED, reqId).build().toString()
            );
            requestWrite(key);
            return;
        }

        workerPool.run(() -> {
            JsonObject response = router.route(json, ctx);
            ctx.write(response.toString());

            requestWrite(key);
        });
    }

    private void write(SelectionKey key) throws IOException {
        ChannelContext ctx = (ChannelContext) key.attachment();
        SocketChannel channel = ctx.channel();

        ByteBuffer tmp;
        while ((tmp = ctx.next()) != null) {
            channel.write(tmp);
            if (tmp.hasRemaining()) {

                break;
            }

            ctx.clearWrite();
        }

        if (!ctx.hasWrite()) {
            key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
        }
    }

    private void disconnect(SelectionKey key, Exception cause) {
        ChannelContext ctx = (ChannelContext) key.attachment();
        String addr = ctx.remote();

        if (cause != null) {
            LOG.fine("Client disconnected: " + addr + " (" + cause.getMessage() + ")");
        } else {
            LOG.fine("Client disconnected: " + addr + " (EOF)");
        }

        if (ctx.auth()) {
            ctx.session().onDisconnect();
        }

        ctx.cleanup();
        close(key);
    }

    private void writes() {
        SelectionKey key;
        while ((key = pendingWriteKeys.poll()) != null) {
            try {
                if (!key.isValid()) {
                    continue;
                }

                ChannelContext ctx = (ChannelContext) key.attachment();
                if (ctx == null || !ctx.hasWrite()) {
                    continue;
                }

                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            } catch (CancelledKeyException ignored) {
                // La connessione puo' essere stata chiusa mentre un worker produceva la risposta.
            }
        }
    }

    private void requestWrite(SelectionKey key) {
        pendingWriteKeys.offer(key);
        if (selector != null) {
            selector.wakeup();
        }
    }

    private void close(SelectionKey key) {
        try {
            key.cancel();
            key.channel().close();
        } catch (IOException ignored) {}
    }

    public void stop() {
        running = false;
        if (selector != null) {
            selector.wakeup();
        }
    }
}
