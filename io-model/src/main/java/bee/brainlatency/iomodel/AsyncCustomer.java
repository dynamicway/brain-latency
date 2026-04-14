package bee.brainlatency.iomodel;

import java.util.concurrent.CompletableFuture;

class AsyncCustomer {
    private Coffee coffee;

    void takeCoffee(BlockingBarista barista) {
        CompletableFuture.supplyAsync(barista::makeCoffee)
                .thenAccept(brewedCoffee -> this.coffee = brewedCoffee);
    }

    void takeCoffee(NonBlockingBarista barista) {
        barista.makeCoffee().thenAccept(brewedCoffee -> this.coffee = brewedCoffee);
    }

}
