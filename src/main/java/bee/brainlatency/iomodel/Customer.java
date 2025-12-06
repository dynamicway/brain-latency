package bee.brainlatency.iomodel;

class Customer {
    private Coffee coffee;

    void takeCoffee(SyncBlockingBarista barista) {
        this.coffee = barista.makeCoffee(); // Blocked until coffe is ready.
    }

    void takeCoffee(SyncNonBlockingBarista barista) {
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
