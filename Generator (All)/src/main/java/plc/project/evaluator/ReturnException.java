package plc.project.evaluator;

public class ReturnException extends RuntimeException {
    private final RuntimeValue value;

    public ReturnException(RuntimeValue value) {
        super(null, null, false, false);
        this.value = value;
    }
    public RuntimeValue getValue() {
        return value;
    }
}
