package server.storage;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;

/******************** Storage Layer ********************************

Persistenza e supporto I/O del server.
Gestisce salvataggio/caricamento utenti in formato JSON e riuso di buffer
NIO, allineandosi al modello di robustezza descritto in UML_server (/storage).

Concorrenza:
- persistenza orchestrata da servizio dedicato
- pool buffer concorrente usato dal piano I/O

*****************************************************************************/

public final class ByteBufferPool {

    private final ConcurrentLinkedQueue<ByteBuffer> pool;
    private final int bufferSize;

    public ByteBufferPool(int poolSize, int bufferSize) {
        this.bufferSize = bufferSize;
        this.pool = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < poolSize; i++) {
            pool.offer(ByteBuffer.allocateDirect(bufferSize));
        }
    }

    public ByteBuffer get() {
        ByteBuffer tmp = pool.poll();
        if (tmp == null)
            tmp = ByteBuffer.allocateDirect(bufferSize);

        tmp.clear();
        return tmp;
    }

    public void put(ByteBuffer b) {
        if (b != null && b.isDirect() && b.capacity() == bufferSize) {
            pool.offer(b);
        }

    }

    public int available() {
        return pool.size();
    }
}
