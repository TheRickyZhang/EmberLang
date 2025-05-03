package plc.project.evaluator;

import plc.project.parser.Ast;

import java.math.BigDecimal;
//import java.math.MathContext;
import java.math.RoundingMode;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Patch notes:
// Be very careful with scope! If we are entering a new scope, create a copy and then restore later
// Use finally to ALWAYS restore scope in case of error
// Keep in mind that the lambdas are referring to but not acting on the scope!

public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        RuntimeValue value = new RuntimeValue.Primitive(null);
        try {
            for (var stmt : ast.statements()) {
                value = visit(stmt);
            }
            return value;
        } catch (ReturnException e) {
            throw new EvaluateException("Return statement outside of function");
        }
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
        if (scope.get(ast.name(), true).isPresent()) {
            throw new EvaluateException("Variable " + ast.name() + " already defined.");
        }
        RuntimeValue value = ast.value().isPresent() ? visit(ast.value().get()) : new RuntimeValue.Primitive(null);
        scope.define(ast.name(), value);
        return value;
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        // System.out.println("VISIT " + System.identityHashCode(scope));

        if(scope.get(ast.name(), true).isPresent()) {
            throw new EvaluateException("Function " + ast.name() + " already defined");
        }
        int sz = ast.parameters().size();
        if(ast.parameters().stream().distinct().count() != sz) {
            throw new EvaluateException("Duplicate parameter names");
        }

        Scope currScope = scope; // NOTE we need to "freeze" this scope
        RuntimeValue.Function.Definition def = (List<RuntimeValue> args) -> {
            Scope functionScope = new Scope(currScope);
            if(args.size() != sz) {
                throw new EvaluateException("Incorrect arg cnt " + ast.name());
            }
            for(int i = 0; i < sz; ++i) {
                String paramName = ast.parameters().get(i);
                functionScope.define(paramName, args.get(i));
            }
            Scope oldScope = scope;
            scope = functionScope;
            try {
                for (Ast.Stmt s : ast.body()) {
                    visit(s);
                }
            } catch (ReturnException re) {
                return re.getValue();
            } finally {
                scope = oldScope;
            }
            // If no return statement was encountered, return NIL
            return new RuntimeValue.Primitive(null);
        };
        RuntimeValue functionValue = new RuntimeValue.Function(ast.name(), def);
        scope.define(ast.name(), functionValue);
        return functionValue;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.If ast) throws EvaluateException {
        Boolean b = requireType(visit(ast.condition()), Boolean.class);
        if(b == null) throw new EvaluateException("Boolean should not be null");
        List<Ast.Stmt> statements = b.equals(Boolean.TRUE) ? ast.thenBody() : ast.elseBody();
        RuntimeValue res = new RuntimeValue.Primitive(null);

        Scope blockScope = new Scope(scope);
        Scope previousScope = scope;
        scope = blockScope;
        try {
            for(Ast.Stmt s : statements) {
                res = visit(s);
            }
        } finally {
            scope = previousScope;
        }
        return res;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.For ast) throws EvaluateException {
        Scope parent = scope;
        RuntimeValue itVal = visit(ast.expression());
        List<?> rawListObj = requireType(
                itVal,
                List.class
        );
        if(rawListObj == null) {
            throw new EvaluateException("HUH??? This shouldn't happen");
        }
        for (Object rawElem : rawListObj) {
            if (!(rawElem instanceof RuntimeValue element)) {
                throw new EvaluateException("For loop attempting to iterate using invalid values");
            }
            scope = new Scope(parent);
            scope.define(ast.name(), element);
            try {
                for (Ast.Stmt stmt : ast.body()) {
                    visit(stmt);
                }
            } finally {
                scope = parent;
            }
        }
        return new RuntimeValue.Primitive(null);
    }


    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        // System.out.println("RETURN " + System.identityHashCode(scope));
        RuntimeValue value = (ast.value().isEmpty()) ? new RuntimeValue.Primitive(null) : visit(ast.value().get());
        throw new ReturnException(value); // Throw distinct exception for return control
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        // System.out.println("Expression " + System.identityHashCode(scope));
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {
        if (ast.expression() instanceof Ast.Expr.Property(Ast.Expr receiver, String name)) {
            RuntimeValue.ObjectValue ob = requireType(visit(receiver), RuntimeValue.ObjectValue.class);
            if(ob == null) throw new EvaluateException("Ob should not be null in assignment");

            // TOLOOK: Ensure we are always looking at the CURRENT scope??? (currently not changed)
            if(name == null || ob.scope().get(name, false).isEmpty()) {
                throw new EvaluateException("name in assignment not defined");
            }
            RuntimeValue value = visit(ast.value());
            ob.scope().set(name, value);
            return value;
        } else if (ast.expression() instanceof Ast.Expr.Variable(String name)) {
            if(name == null || scope.get(name, false).isEmpty()) {
                throw new EvaluateException("name in assignment not defined");
            }
            RuntimeValue value = visit(ast.value());
            scope.set(name, value);
            return value;
        }
        throw new EvaluateException("Assignment target must be a variable or property.");
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Literal ast) throws EvaluateException {
        return new RuntimeValue.Primitive(ast.value());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Group ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException {
        String op = ast.operator();
        // Do these first to account for short-circuiting
        if(op.equals("AND")) {
            Boolean left = requireType(visit(ast.left()), Boolean.class);
            if (left == Boolean.FALSE) {
                return new RuntimeValue.Primitive(false);
            }
            Boolean right = requireType(visit(ast.right()) , Boolean.class);
            return new RuntimeValue.Primitive(right);
        } else if(op.equals("OR")) {
            Boolean left = requireType(visit(ast.left()), Boolean.class);
            if (left == Boolean.TRUE) {
                return new RuntimeValue.Primitive(true);
            }
            Boolean right = requireType(visit(ast.right()), Boolean.class);
            return new RuntimeValue.Primitive(right);
        }
        RuntimeValue x = visit(ast.left()), y = visit(ast.right()) ;
        switch (op) {
            case "+" -> {
                // String = concatenation
                if (isOfType(x, String.class) || isOfType(y, String.class)) {
                    return new RuntimeValue.Primitive(runtimeValueToString(x) + runtimeValueToString(y));
                }
                return evaluateNumeric(op, x, y);
            }
            case "-", "*", "/" -> {
                return evaluateNumeric(op, x, y);
            }
            case "==", "!=" -> {
                boolean wantEq = op.equals("==");
                // Short circuit for comparing objects
                if (x instanceof RuntimeValue.ObjectValue ox && y instanceof RuntimeValue.ObjectValue oy) {
                    return new RuntimeValue.Primitive(ox.equals(oy) == wantEq);
                } else if (x instanceof RuntimeValue.ObjectValue || y instanceof RuntimeValue.ObjectValue) {
                    return new RuntimeValue.Primitive(!wantEq);
                }
                Object left = requireType(x, Object.class);
                Object right = requireType(y, Object.class);
                return new RuntimeValue.Primitive(Objects.equals(left, right) == wantEq);
            }
            case "<", "<=", ">", ">=" -> {
                Object left = requireType(x, Object.class);
                Object right = requireType(y, Object.class);
                checkNull(left, right);
                if (!left.getClass().equals(right.getClass())) {
                    throw new EvaluateException("Operands must be same type for comparison");
                }
                if (!(left instanceof Comparable<?> comp)) {
                    throw new EvaluateException("Left operand is not Comparable");
                }
                @SuppressWarnings("unchecked")  // Hm... Can't fix?
                int cmp = ((Comparable<Object>) comp).compareTo(right);
                return switch (op) {
                    case "<"  -> new RuntimeValue.Primitive(cmp < 0);
                    case "<=" -> new RuntimeValue.Primitive(cmp <= 0);
                    case ">"  -> new RuntimeValue.Primitive(cmp > 0);
                    case ">=" -> new RuntimeValue.Primitive(cmp >= 0);
                    default   -> throw new EvaluateException("Unsupported comparison operator: " + op);
                };
            }
            default -> throw new EvaluateException("Unexpected binary operator " + op);
        }
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Variable ast) throws EvaluateException {
        // System.out.println("VAR " + System.identityHashCode(scope));
        return scope.get(ast.name(), false)
                .orElseThrow(() -> new EvaluateException("Variable " + ast.name() + " is not defined."));
    }


    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        // System.out.println("PROPERTY " + System.identityHashCode(scope));
        RuntimeValue.ObjectValue r = requireType(visit(ast.receiver()), RuntimeValue.ObjectValue.class);
        if(r == null) throw new EvaluateException("Cannot have property of null");
        return r.scope().get(ast.name(), true).orElseThrow(
                ()->new EvaluateException("Property " + ast.name() + " not defined")
        );
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException {
        // System.out.println("FUNCTION " + System.identityHashCode(scope));
        RuntimeValue funcValue = scope.get(ast.name(), false)
                .orElseThrow(() -> new EvaluateException("Undefined function: " + ast.name()));
        RuntimeValue.Function function = requireType(funcValue, RuntimeValue.Function.class);
        if(function == null) {
            throw new EvaluateException("Cannot have defiiton on null");
        }
        List<RuntimeValue> args = new ArrayList<>();
        for (Ast.Expr arg : ast.arguments()) {
            args.add(visit(arg));
        }
        return function.definition().invoke(args);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Method ast) throws EvaluateException {
        // System.out.println("METHOD " + System.identityHashCode(scope));
        RuntimeValue.ObjectValue ob = requireType(visit(ast.receiver()), RuntimeValue.ObjectValue.class);
        if(ob == null) throw new EvaluateException("cannot have method of null");
        RuntimeValue methodValue = ob.scope().get(ast.name(), true)
                .orElseThrow(() -> new EvaluateException("Method " + ast.name() + " not defined"));
        RuntimeValue.Function methodFunction = requireType(methodValue, RuntimeValue.Function.class);
        if(methodFunction == null) throw new EvaluateException("Cannot have dfention on null method");
        List<RuntimeValue> args = new ArrayList<>();
        args.add(ob); // NOTE: We need to pass this as first argument
        for (Ast.Expr arg : ast.arguments()) {
            args.add(visit(arg));
        }
        return methodFunction.definition().invoke(args);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        // Child of current scope
        Scope objectScope = new Scope(scope);

        // All fields are LET statements
        for (Ast.Stmt.Let field : ast.fields()) {
            if (objectScope.get(field.name(), true).isPresent()) {
                throw new EvaluateException("Field " + field.name() + " already defined in object.");
            }
            RuntimeValue fieldValue = field.value().isPresent() ? visit(field.value().get()) : new RuntimeValue.Primitive(null);
            objectScope.define(field.name(), fieldValue);
        }
        RuntimeValue.ObjectValue objectValue = new RuntimeValue.ObjectValue(ast.name(), objectScope);

        // All methods are DEFs
        for (Ast.Stmt.Def method : ast.methods()) {
            int sz = method.parameters().size();
            if (method.parameters().stream().distinct().count() != sz) {
                throw new EvaluateException("Duplicate parameter names in method");
            }
            if (objectScope.get(method.name(), true).isPresent()) {
                throw new EvaluateException("Method " + method.name() + " already defined");
            }
            RuntimeValue.Function.Definition methodDef = (List<RuntimeValue> args) -> {
                // The first argument is reserved for "this"
                if (args.size() != method.parameters().size() + 1) {
                    throw new EvaluateException("Incorrect number of arguments for method " + method.name());
                }
                // Create a new scope for method execution, child of the object scope.
                Scope methodScope = new Scope(objectScope);
                methodScope.define("this", args.getFirst());
                for (int i = 0; i < method.parameters().size(); i++) { // Note because args[0]=this, we map i/i+1
                    String param = method.parameters().get(i);
                    methodScope.define(param, args.get(i + 1));
                }
                Scope previousScope = scope;
                scope = methodScope;
                try {
                    RuntimeValue result = new RuntimeValue.Primitive(null);
                    for (Ast.Stmt s : method.body()) {
                        result = visit(s);
                    }
                    return result;
                } catch (ReturnException re) {
                    return re.getValue();
                } finally {
                    scope = previousScope; // NEED to restore this original scope
                }
            };
            RuntimeValue.Function methodFunction = new RuntimeValue.Function(method.name(), methodDef);
            objectScope.define(method.name(), methodFunction);
        }
        return objectValue;
    }


    /**
     * Helper function for extracting RuntimeValues of specific types. If the
     * type is subclass of {@link RuntimeValue} the check applies to the value
     * itself, otherwise the value is expected to be a {@link RuntimeValue.Primitive}
     * and the check applies to the primitive value.
     */
    // UPDATE: Made public for use in Environment.java
    public static <T> T requireType(RuntimeValue value, Class<T> type) throws EvaluateException {
        if (RuntimeValue.class.isAssignableFrom(type)) {
            if (!type.isInstance(value)) {
                throw new EvaluateException("Expected value to be of type " + type + ", received " + value.getClass() + ".");
            }
            return (T) value;
        } else {
            var primitive = requireType(value, RuntimeValue.Primitive.class);
            if(primitive == null) throw new EvaluateException("HUH a returned null in my requireType?? Should never happen");
            if (primitive.value() == null) return null;
            if (!type.isInstance(primitive.value())) {
                var received = primitive.value().getClass();
                throw new EvaluateException("Expected value to be of type " + type + ", received " + received + ".");
            }
            return (T) primitive.value();
        }
    }

    // Helper to convert a RuntimeValue to a String
    private String runtimeValueToString(RuntimeValue value) {
        if (value instanceof RuntimeValue.Primitive) {
            Object val = ((RuntimeValue.Primitive) value).value();
            return val == null ? "NIL" : String.valueOf(val);
        } else if (value instanceof RuntimeValue.ObjectValue ov) {
            return ov.print();
        }
        return "";
    }

    /**************** CUSTOM HELPER FUNCTIONS **********************/

    private boolean isOfType(RuntimeValue value, Class<?> expected) {
        if (value instanceof RuntimeValue.Primitive) {
            Object casted = ((RuntimeValue.Primitive) value).value();
            return expected.isInstance(casted);
        }
        return false;
    }

    // Helper for numeric operations (BigInteger and fallback to BigDecimal)
    private RuntimeValue evaluateNumeric(String op, RuntimeValue x, RuntimeValue y) throws EvaluateException {
        try {
            BigInteger a = requireType(x, BigInteger.class);
            BigInteger b = requireType(y, BigInteger.class);
            checkNull(a, b);
            return switch (op) {
                case "+" -> new RuntimeValue.Primitive(a.add(b));
                case "-" -> new RuntimeValue.Primitive(a.subtract(b));
                case "*" -> new RuntimeValue.Primitive(a.multiply(b));
                case "/" -> {
                    if (b.equals(BigInteger.ZERO)) throw new EvaluateException("Division by zero");
                    yield new RuntimeValue.Primitive(a.divide(b));
                }
                default -> throw new EvaluateException("Unsupported numeric operator: " + op);
            };
        } catch (EvaluateException e) {
            BigDecimal a = requireType(x, BigDecimal.class);
            BigDecimal b = requireType(y, BigDecimal.class);
            checkNull(a, b);
            return switch (op) {
                case "+" -> new RuntimeValue.Primitive(a.add(b));
                case "-" -> new RuntimeValue.Primitive(a.subtract(b));
                case "*" -> new RuntimeValue.Primitive(a.multiply(b));
                case "/" -> {
                    if (b.compareTo(BigDecimal.ZERO) == 0) throw new EvaluateException("Division by zero");
                    int scale = Math.max(a.scale(), b.scale()); // LOOK: must set manual scale
                    yield new RuntimeValue.Primitive(a.divide(b, scale, RoundingMode.HALF_EVEN)); // Half even
                }
                default -> throw new EvaluateException("Unsupported numeric operator: " + op);
            };
        }
    }

    private void checkNull(Object a, Object b) throws EvaluateException {
        if(a == null || b == null) throw new EvaluateException("Cannot compare null");
    }

    /*************** END CUSTOM HELPER FUNCTIONS ********************/
    String bestClass = "COP4020";
}
