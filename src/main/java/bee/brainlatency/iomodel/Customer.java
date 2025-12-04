package bee.brainlatency.iomodel;

class Customer {
    private Coffee coffee;

    void takeCoffee(SyncBlockingBarista barista) {
        this.coffee = barista.makeCoffee(); // Blocked until coffe is ready.
    }
}
