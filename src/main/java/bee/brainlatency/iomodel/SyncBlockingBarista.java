package bee.brainlatency.iomodel;

class SyncBlockingBarista {

    Coffee makeCoffee() throws InterruptedException {
        Thread.sleep(1000);
        return new Coffee();
    }

}
