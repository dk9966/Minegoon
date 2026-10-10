package dev.minegoon;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Lets helper work run as plain sequential code on a virtual thread while every
 * world read and write still happens on the server thread.
 */
public final class MainThread {

    private final Plugin plugin;

    public MainThread(Plugin plugin) {
        this.plugin = plugin;
    }

    public <T> T call(Supplier<T> task) {
        if (Bukkit.isPrimaryThread()) {
            return task.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (result.isCancelled()) {
                return;
            }
            try {
                result.complete(task.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return await(result);
    }

    public void run(Runnable task) {
        call(() -> {
            task.run();
            return null;
        });
    }

    /** Sleeps for game ticks, so timings stay fair when the server lags. */
    public void sleep(int ticks) {
        CompletableFuture<Void> woken = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(plugin, () -> woken.complete(null), Math.max(1, ticks));
        await(woken);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw new Cancelled();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Thrown on the worker thread when its work is stopped or paused. */
    public static final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("cancelled", null, false, false);
        }
    }
}
