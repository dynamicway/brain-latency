package bee.brainlatency.iomodel;

class SyncCustomer {
    private Coffee coffee;

    void takeCoffee(BlockingBarista barista) {
        this.coffee = barista.makeCoffee(); // Blocked until coffe is ready.
    }

    void takeCoffee(NonBlockingBarista barista) {
        barista.makeCoffee();
        while (!barista.isReady()) { // Polling
            try {
                // Can do other work while waiting.
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        this.coffee = barista.getCoffee();
    }
}
