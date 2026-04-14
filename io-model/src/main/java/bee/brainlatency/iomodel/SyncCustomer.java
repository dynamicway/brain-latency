package bee.brainlatency.iomodel;

class SyncCustomer {
    private Coffee coffee;

    void takeCoffee(BlockingBarista barista) {
        this.coffee = barista.makeCoffee(); // Blocked until coffe is ready.
    }

    void takeCoffee(NonBlockingBarista barista) {
        this.coffee = barista.makeCoffee().join();
    }
}
