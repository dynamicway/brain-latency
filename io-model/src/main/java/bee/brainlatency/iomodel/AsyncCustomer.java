package bee.brainlatency.iomodel;

import java.util.concurrent.CompletableFuture;

class AsyncCustomer {
    private Coffee coffee;

    void takeCoffee(BlockingBarista barista) {
        // async execution, blocking wait for result
        this.coffee = CompletableFuture.supplyAsync(barista::makeCoffee).join();
    }

    void takeCoffee(NonBlockingBarista barista) {
        // start async task, poll for completion, and retrieve result
        CompletableFuture.runAsync(() -> {
            barista.makeCoffee();
            while (!barista.isReady()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }).thenRun(() -> this.coffee = barista.getCoffee());
        // returns immediately
    }

}
