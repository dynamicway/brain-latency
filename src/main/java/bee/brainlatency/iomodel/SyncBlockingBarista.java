package bee.brainlatency.iomodel;

class SyncBlockingBarista {

    Coffee makeCoffee() {
        try {
            Thread.sleep(1000);
            return new Coffee();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
