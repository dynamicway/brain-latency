package bee.brainlatency.iomodel;

class SyncNonBlockingBarista {
    private Coffee coffee;
    private boolean ready = false;

    void makeCoffee() {
        new Thread(() -> {
            this.coffee = Coffee.brew();
            this.ready = true;
        }).start();
    }

    boolean isReady() {
        return ready;
    }

    Coffee getCoffee() {
        return coffee;
    }
}
