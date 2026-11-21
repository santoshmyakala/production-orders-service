package io.github.santoshmyakala.orders.material;

public class MaterialServiceUnavailableException extends RuntimeException {

    public MaterialServiceUnavailableException(Throwable cause) {
        super("The material (ERP) service is unavailable", cause);
    }
}
