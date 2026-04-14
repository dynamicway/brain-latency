package bee.brainlatency.iomodel;

import java.util.concurrent.CompletableFuture;

class NonBlockingBarista {

    CompletableFuture<Coffee> makeCoffee() {
        return Coffee.brew();
    }

}
