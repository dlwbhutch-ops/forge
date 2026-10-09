package forge.ai;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Attack requirements read and mutate the live game, so stay on its owner thread. */
public final class AttackRequirementTask {
    private AttackRequirementTask() { }

    public static CompletableFuture<Integer> evaluate(Supplier<Integer> calculation) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Attack selection cancelled");
        }
        Integer result = calculation.get();
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Attack selection cancelled");
        }
        return CompletableFuture.completedFuture(result);
    }
}
