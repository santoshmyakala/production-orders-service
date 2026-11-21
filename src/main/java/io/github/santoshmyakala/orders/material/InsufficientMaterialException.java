package io.github.santoshmyakala.orders.material;

public class InsufficientMaterialException extends RuntimeException {

    public InsufficientMaterialException(String productCode, int quantity) {
        super("Not enough material to produce %d × %s".formatted(quantity, productCode));
    }
}
