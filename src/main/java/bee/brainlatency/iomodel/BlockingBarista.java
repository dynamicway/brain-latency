package bee.brainlatency.iomodel;

class BlockingBarista {

    Coffee makeCoffee() {
        return Coffee.brew();
    }
}
