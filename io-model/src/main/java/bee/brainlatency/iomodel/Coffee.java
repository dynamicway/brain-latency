package bee.brainlatency.iomodel;

import java.util.concurrent.CompletableFuture;

class Coffee {
    private Coffee() {

    }

    static CompletableFuture<Coffee> brew() {
        return CompletableFuture.supplyAsync(Coffee::new);
    }
}
