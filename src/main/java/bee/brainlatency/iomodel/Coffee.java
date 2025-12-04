package bee.brainlatency.iomodel;

class Coffee {
    private Coffee() {

    }

    static Coffee brew() {
        try {
            Thread.sleep(1000);
            return new Coffee();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
