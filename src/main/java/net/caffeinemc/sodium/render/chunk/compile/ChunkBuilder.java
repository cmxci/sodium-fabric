package net.caffeinemc.sodium.render.chunk.compile;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import net.caffeinemc.sodium.SodiumClientMod;
import net.caffeinemc.sodium.render.chunk.compile.tasks.AbstractBuilderTask;
import net.caffeinemc.sodium.render.chunk.compile.tasks.SectionBuildResult;
import net.caffeinemc.sodium.render.chunk.passes.ChunkRenderPassManager;
import net.caffeinemc.sodium.render.terrain.TerrainBuildContext;
import net.caffeinemc.sodium.render.terrain.format.TerrainVertexType;
import net.caffeinemc.sodium.util.tasks.CancellationSource;
import net.caffeinemc.sodium.util.tasks.QueueDrainingIterator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ChunkBuilder {
    private static final Logger LOGGER = LogManager.getLogger("ChunkBuilder");

    private final Deque<WrappedTask> buildQueue = new ConcurrentLinkedDeque<>();

    private final Object jobNotifier = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> threads = new ArrayList<>();

    private World world;
    private ChunkRenderPassManager renderPassManager;

    private final int limitThreads;
    private final TerrainVertexType vertexType;

    private final Queue<SectionBuildResult> deferredResultQueue = new ConcurrentLinkedDeque<>();
    private final ThreadLocal<TerrainBuildContext> localContexts = new ThreadLocal<>();
    
    private static final int BUILD_SAMPLE_COUNT = 64;
    private static final int SCHEDULE_SAMPLE_COUNT = 64;
    private static final double WORK_FACTOR = .5;
    
    private final AtomicLong sumBuildTime = new AtomicLong();
    private final AtomicLongArray buildTimeSamples = new AtomicLongArray(BUILD_SAMPLE_COUNT);
    private final AtomicInteger buildSamplesPointer = new AtomicInteger();
    
    private long sumScheduleTime;
    private final long[] scheduleTimeSamples = new long[SCHEDULE_SAMPLE_COUNT];
    private int scheduleSamplesPointer;
    private long currentScheduleTime;

    public ChunkBuilder(TerrainVertexType vertexType) {
        this.vertexType = vertexType;
        this.limitThreads = getThreadCount();
    }

    /**
     * Returns the remaining number of build tasks which should be scheduled this frame. If an attempt is made to
     * spawn more tasks than the budget allows, it will block until resources become available.
     */
    public int getSchedulingBudget() {
        this.addScheduleSample();
        
        int currentBuilds = this.buildQueue.size();
        
        int budgetFast = 0;
        int buildSampleCount = Math.min(this.buildSamplesPointer.get(), BUILD_SAMPLE_COUNT);
        int scheduleSampleCount = Math.min(this.scheduleSamplesPointer, SCHEDULE_SAMPLE_COUNT);
        
        if (buildSampleCount != 0 && scheduleSampleCount != 0) {
            long avgBuildTime = this.sumBuildTime.get() / buildSampleCount;
            long avgScheduleTime = this.sumScheduleTime / scheduleSampleCount;
            if (avgBuildTime != 0) {
                budgetFast = (int) ((double) avgScheduleTime * this.limitThreads * WORK_FACTOR / avgBuildTime) - currentBuilds;
            }
        }
        
        int budgetSlow = this.limitThreads - currentBuilds;
        
        return Math.max(0, Math.max(budgetFast, budgetSlow));
    }
    
    private void addScheduleSample() {
        long nextTime = System.nanoTime();
        long currentTime = this.currentScheduleTime;
        if (currentTime != 0) {
            long nanosElapsed = nextTime - currentTime;
            int rawPtr = this.scheduleSamplesPointer++;
            if (this.scheduleSamplesPointer >= BUILD_SAMPLE_COUNT * 2) {
                this.scheduleSamplesPointer = BUILD_SAMPLE_COUNT;
            }
            int ptr = rawPtr % BUILD_SAMPLE_COUNT;
            long oldSample = this.scheduleTimeSamples[ptr];
            this.scheduleTimeSamples[ptr] = nanosElapsed;
            this.sumScheduleTime += nanosElapsed - oldSample;
        }
        this.currentScheduleTime = nextTime;
    }
    
    private void addBuildSample(long nanosElapsed) {
        int rawPtr = this.buildSamplesPointer.getAndIncrement();
        this.buildSamplesPointer.compareAndSet(BUILD_SAMPLE_COUNT * 2, BUILD_SAMPLE_COUNT);
        int ptr = rawPtr % BUILD_SAMPLE_COUNT;
        long oldSample = this.buildTimeSamples.getAndSet(ptr, nanosElapsed);
        this.sumBuildTime.getAndAdd(nanosElapsed - oldSample);
    }

    /**
     * Spawns a number of work-stealing threads to process results in the build queue. If the builder is already
     * running, this method does nothing and exits.
     */
    public void startWorkers() {
        if (this.running.getAndSet(true)) {
            return;
        }

        if (!this.threads.isEmpty()) {
            throw new IllegalStateException("Threads are still alive while in the STOPPED state");
        }

        for (int i = 0; i < this.limitThreads; i++) {
            TerrainBuildContext context = new TerrainBuildContext(this.world, this.vertexType, this.renderPassManager);
            WorkerRunnable worker = new WorkerRunnable(context);

            Thread thread = new Thread(worker, "Chunk Render Task Executor #" + i);
            thread.setPriority(Math.max(0, Thread.NORM_PRIORITY - 2));
            thread.start();

            this.threads.add(thread);
        }

        LOGGER.info("Started {} worker threads", this.threads.size());
    }

    /**
     * Notifies all worker threads to stop and blocks until all workers terminate. After the workers have been shut
     * down, all tasks are cancelled and the pending queues are cleared. If the builder is already stopped, this
     * method does nothing and exits. This method implicitly calls {@link ChunkBuilder#doneStealingTasks()} on the
     * calling thread.
     */
    public void stopWorkers() {
        if (!this.running.getAndSet(false)) {
            return;
        }

        if (this.threads.isEmpty()) {
            throw new IllegalStateException("No threads are alive but the executor is in the RUNNING state");
        }

        LOGGER.info("Stopping worker threads");

        // Notify all worker threads to wake up, where they will then terminate
        synchronized (this.jobNotifier) {
            this.jobNotifier.notifyAll();
        }

        // Wait for every remaining thread to terminate
        for (Thread thread : this.threads) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
            }
        }

        this.threads.clear();

        // Delete any queued tasks and resources attached to them
        for (WrappedTask job : this.buildQueue) {
            job.future.cancel(true);
        }

        // Delete any results in the deferred queue
        while (!this.deferredResultQueue.isEmpty()) {
            this.deferredResultQueue.remove()
                    .delete();
        }

        this.buildQueue.clear();

        this.world = null;
    
        this.doneStealingTasks();
    }
    
    /**
     * Cleans up resources allocated on the currently calling thread for the {@link ChunkBuilder#stealTask()} method.
     * This method should be called on a thread that has stolen tasks when it is done stealing to prevent resource
     * leaks.
     */
    public void doneStealingTasks() {
        this.localContexts.remove();
    }

    public CompletableFuture<SectionBuildResult> schedule(AbstractBuilderTask task) {
        if (!this.running.get()) {
            throw new IllegalStateException("Executor is stopped");
        }

        WrappedTask job = new WrappedTask(task);

        this.buildQueue.add(job);

        synchronized (this.jobNotifier) {
            this.jobNotifier.notify();
        }

        return job.future;
    }

    /**
     * @return True if the build queue is empty
     */
    public boolean isBuildQueueEmpty() {
        return this.buildQueue.isEmpty();
    }

    /**
     * Initializes this chunk builder for the given world. If the builder is already running (which can happen during
     * a world teleportation event), the worker threads will first be stopped and all pending tasks will be discarded
     * before being started again.
     * @param world The world instance
     * @param renderPassManager The render pass manager used for the world
     */
    public void init(ClientWorld world, ChunkRenderPassManager renderPassManager) {
        if (world == null) {
            throw new NullPointerException("World is null");
        }

        this.stopWorkers();

        this.world = world;
        this.renderPassManager = renderPassManager;

        this.startWorkers();
    }

    /**
     * Returns the "optimal" number of threads to be used for chunk build tasks. This will always return at least one
     * thread.
     */
    private static int getOptimalThreadCount() {
        return MathHelper.clamp(Math.max(getMaxThreadCount() / 3, getMaxThreadCount() - 6), 1, 10);
    }

    private static int getThreadCount() {
        int requested = SodiumClientMod.options().performance.chunkBuilderThreads;
        return requested == 0 ? getOptimalThreadCount() : Math.min(requested, getMaxThreadCount());
    }

    private static int getMaxThreadCount() {
        return Runtime.getRuntime().availableProcessors();
    }

    public CompletableFuture<Void> scheduleDeferred(AbstractBuilderTask task) {
        return this.schedule(task)
                .thenAccept(this.deferredResultQueue::add);
    }

    public Iterator<SectionBuildResult> createDeferredBuildResultDrain() {
        return new QueueDrainingIterator<>(this.deferredResultQueue);
    }

    /**
     * "Steals" a task on the queue and allows the currently calling thread to execute it using locally-allocated
     * resources instead. While this function returns true, the caller should continually execute it so that additional
     * tasks can be processed.
     *
     * @return True if it was able to steal a task, otherwise false
     */
    public boolean stealTask() {
        WrappedTask task = this.getNextJob(false);

        if (task == null) {
            return false;
        }

        TerrainBuildContext context = this.localContexts.get();

        if (context == null) {
            this.localContexts.set(context = new TerrainBuildContext(this.world, this.vertexType, this.renderPassManager));
        }

        try {
            processJob(task, context);
        } finally {
            context.release();
        }

        return true;
    }

    /**
     * Returns the next task which this worker can work on or blocks until one becomes available. If no tasks are
     * currently available and {@param block} is true, it will wait on the {@link ChunkBuilder#jobNotifier} field
     * until it is notified of an incoming task.
     */
    private WrappedTask getNextJob(boolean block) {
        WrappedTask job = ChunkBuilder.this.buildQueue.poll();

        if (job == null && block) {
            synchronized (ChunkBuilder.this.jobNotifier) {
                try {
                    ChunkBuilder.this.jobNotifier.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }

        return job;
    }

    private static void processJob(WrappedTask job, TerrainBuildContext context) {
        if (job.isCancelled()) {
            return;
        }

        SectionBuildResult result;

        try {
            // Perform the build task with this worker's local resources and obtain the result
            result = job.task.performBuild(context, job);
        } catch (Exception e) {
            // Propagate any exception from chunk building
            job.future.completeExceptionally(e);
            e.printStackTrace();
            return;
        }

        // The result can be null if the task is cancelled
        if (result != null) {
            // Notify the future that the result is now available
            job.future.complete(result);
        } else if (!job.isCancelled()) {
            // If the job wasn't cancelled and no result was produced, we've hit a bug
            job.future.completeExceptionally(new RuntimeException("No result was produced by the task"));
        }
    }

    private class WorkerRunnable implements Runnable {
        private final AtomicBoolean running = ChunkBuilder.this.running;

        // Making this thread-local provides a small boost to performance by avoiding the overhead in synchronizing
        // caches between different CPU cores
        private final TerrainBuildContext context;

        public WorkerRunnable(TerrainBuildContext context) {
            this.context = context;
        }

        @Override
        public void run() {
            // Run until the chunk builder shuts down
            while (this.running.get()) {
                WrappedTask job = ChunkBuilder.this.getNextJob(true);

                long t1 = System.nanoTime();
                
                if (job == null) {
                    continue;
                }

                try {
                    processJob(job, this.context);
                } finally {
                    this.context.release();
                }
                
                long t2 = System.nanoTime();
                ChunkBuilder.this.addBuildSample(t2 - t1);
            }
        }
    }

    private static class WrappedTask implements CancellationSource {
        private final AbstractBuilderTask task;
        private final CompletableFuture<SectionBuildResult> future;

        private WrappedTask(AbstractBuilderTask task) {
            this.task = task;
            this.future = new CompletableFuture<>();
        }

        @Override
        public boolean isCancelled() {
            return this.future.isCancelled();
        }
    }
}
