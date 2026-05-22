package server.core;

import server.ServerConfig;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;




/******************** ThreadPool Custom ********************************

Il WorkerPool si occupa di gestire la logica di gioco con thread differenti dagli I/O thread.
I thread sono task Runnable perché non restituiscono un valore.
La risposta viene serializzata e inviata nel pipeline I/O. 

Potendo essere configurato con parametri da file la scelta del ThreadPoolExecutor custom è stata implicita, 
ma è stata fatta anche per i seguenti motivi:

queue:                      ArrayBlockingQueue  con capacità limitata.
                            Quando il core è occupato, le task si accodano.
                            Quando la coda è piena, si creano thread fino a maxPoolSize.
                            Inoltre ArrayBlockingQueue è cache friendly e concorrente di default.
 
rejectionPolicy:                         CallerRunsPolicy
                            Se il pool ha già raggiunto maxPoolSize e la coda è piena, 
                            il thread che ha inviato il task (l’IODispatcher) lo esegue
                            direttamente, senza quindi rifiutarlo. Così farà per il prossimo ecc.
                            e per quello dopo ancora reallentando le read del Selector 
                            e l’ingresso di nuovi dati dai client.
                            Il tutto quindi senza perdere nenache un task.

Leggendo la documentazione del costruttore di ThreadPoolExecutor, ho scoperto che 
è possibile passare una ThreadFactory, cioè un componente che crea i thread worker secondo il pattern Factory. 
In questo modo ho potuto configurarli come daemon, garantendo così la loro morte assieme al processo principale. 
Inoltre ho usato un contatore atomico per fare da dispenser di numeri assegnando quindi
nomi progressivi ai thread (worker-1, …, worker-n) per debugging e monitoraggio.
************************************************************************************/



public final class WorkerPool {

    private final ThreadPoolExecutor executor;

    public WorkerPool(ServerConfig config) {
        this.executor = new ThreadPoolExecutor(
            config.poolCoreSize,
            config.poolMaxSize,
            config.poolKeepAliveSeconds,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(config.poolQueueCapacity),
            new WorkerThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        this.executor.allowCoreThreadTimeOut(true); //Per tante ragioni ha senso poter far morire anche i thread core se inattivi
    }

  
    public void run(Runnable task) {
        executor.execute(task);
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    
    public int active() {
        return executor.getActiveCount();
    }

    
    public int queue() {
        return executor.getQueue().size();
    }

    
    private static class WorkerThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
