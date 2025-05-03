package plc.project.generator;

import plc.project.analyzer.Ir;
import plc.project.analyzer.Type;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class Generator implements Ir.Visitor<StringBuilder, RuntimeException> {

    private final StringBuilder builder = new StringBuilder();
    private int indent = 0;

    private void newline(int indent) {
        builder.append("\n");
        builder.append("    ".repeat(indent));
    }

    @Override
    public StringBuilder visit(Ir.Source ir) {
        builder.append(Environment.imports()).append("\n\n");
        builder.append("public final class Main {").append("\n\n");
        builder.append(Environment.definitions()).append("\n");
        //Java doesn't allow for nested functions, but we will pretend it does.
        //To support simple programs involving functions, we will "hoist" any
        //variable/function declaration at the start of the program to allow
        //these functions to be used as valid Java.
        indent = 1;
        boolean main = false;
        for (var statement : ir.statements()) {
            newline(indent);
            if (!main) {
                if (statement instanceof Ir.Stmt.Let || statement instanceof Ir.Stmt.Def) {
                    builder.append("static ");
                } else {
                    builder.append("public static void main(String[] args) {");
                    main = true;
                    indent = 2;
                    newline(indent);
                }
            }
            visit(statement);
        }
        if (main) {
            builder.append("\n").append("    }");
        }
        indent = 0;
        builder.append("\n\n").append("}");
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Let ir) {
        String t = getType(ir.type());
        if (ir.value().isPresent()) {
            if(ir.type() instanceof Type.Object) {
                builder.append("var");
            } else {
                builder.append(t);
            }
            builder.append(" ").append(ir.name()).append(" = ");
            visit(ir.value().get());
            end();
        } else {
            builder.append(t).append(" ").append(ir.name());
            end();
        }
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Def ir) {
        builder.append(getReturnType(ir.returns())).append(" ");
        builder.append(ir.name());
        withParen(() -> {
            int sz = ir.parameters().size();
            for(int i = 0; i < sz; ++i) {
                Ir.Stmt.Def.Parameter p = ir.parameters().get(i);
                builder.append(getType(p.type())).append(" ").append(p.name());
                if(i < sz-1) builder.append(", ");
            }
        });
        withBlock(() -> {
            for(Ir.Stmt s : ir.body()) {
                newline(indent);
                visit(s);
            }
        });
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.If ir) {
        builder.append("if ");
        withParen(() -> visit(ir.condition()));
        withBlock(() -> {
            for (Ir.Stmt s : ir.thenBody()) {
                newline(indent);
                visit(s);
            }
        });
        if (!ir.elseBody().isEmpty()) {
            builder.append(" else");
            withBlock(() -> {
                for (Ir.Stmt s : ir.elseBody()) {
                    newline(indent);
                    visit(s);
                }
            });
        }
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.For ir) {
        builder.append("for ");
        withParen(() -> {
            builder.append(getType(ir.type())).append(" ");
            builder.append(ir.name()).append(" : ");
            visit(ir.expression());
        });
        withBlock(()-> {
            for(Ir.Stmt e : ir.body()) {
                newline(indent);
                visit(e);
            }
        });
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Return ir) {
        builder.append("return ");
        if(ir.value().isPresent()) {
//            builder.append(ir.value().get());
            visit(ir.value().get());
        } else {
            builder.append("null");
        }
        end();
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Expression ir) {
        visit(ir.expression());
        end();
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Variable ir) {
        visit(ir.variable());
        builder.append(" = ");
        visit(ir.value());
        end();
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Stmt.Assignment.Property ir) {
        Ir.Expr.Property p = ir.property();
        visit(p.receiver());
        builder.append(".").append(p.name()).append(" = ");
        visit(ir.value());
        end();
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Literal ir) {
        var literal = switch (ir.value()) {
            case null -> "null";
            case Boolean b -> b.toString();
            case BigInteger i -> "new BigInteger(\"" + i + "\")";
            case BigDecimal d -> "new BigDecimal(\"" + d + "\")";
            case String s -> "\"" + s + "\""; //TODO: Escape characters?
            //If the IR value isn't one of the above types, the Parser/Analyzer
            //is returning an incorrect IR - this is an implementation issue,
            //hence throw AssertionError rather than a "standard" exception.
            default -> throw new AssertionError(ir.value().getClass());
        };
        builder.append(literal);
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Group ir) {
        withParen(() -> visit(ir.expression()));
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Binary ir) {
        String op    = ir.operator();
        String left  = capture(() -> visit(ir.left()));
        String right = capture(() -> visit(ir.right()));
        // x.m(y)
        java.util.function.Consumer<String> call = m ->
                builder.append("(").append(left).append(")")
                        .append(".").append(m)
                        .append("(").append(right).append(")");
        // x.compareTo(y) {op} 0
        java.util.function.Consumer<String> cmp = cmpOp ->
                builder.append("(").append(left).append(")")
                        .append(".compareTo(").append(right).append(")")
                        .append(" ").append(cmpOp).append(" 0");

        switch (op) {
            case "+"  -> {
                if (ir.type() == Type.STRING)
                    builder.append(left).append(" + ").append(right);
                else
                    call.accept("add");
            }
            case "-"  -> call.accept("subtract");
            case "*"  -> call.accept("multiply");
            case "/"  -> {
                call.accept("divide");
                if (ir.type() != Type.INTEGER) {
                    int p = builder.lastIndexOf(")");
                    builder.replace(p, p + 1, ", RoundingMode.HALF_EVEN)");
                }
            }
            case "<"  -> cmp.accept("<");
            case "<=" -> cmp.accept("<=");
            case ">"  -> cmp.accept(">");
            case ">=" -> cmp.accept(">=");
            case "==" -> builder.append("Objects.equals(")
                    .append(left).append(", ").append(right)
                    .append(")");
            case "!=" -> builder.append("!Objects.equals(")
                    .append(left).append(", ").append(right)
                    .append(")");
            case "AND" -> {
                boolean needsParens =
                        ir.left() instanceof Ir.Expr.Binary && ((Ir.Expr.Binary) ir.left()).operator().equals("OR");
                String lhs = needsParens ? "(" + left + ")" : left;
                builder.append(lhs).append(" && ").append(right);
            }
            case "OR" -> builder.append(left).append(" || ").append(right);
            default   -> throw new AssertionError("Unknown operator: " + op);
        }
        return builder;
    }



    @Override
    public StringBuilder visit(Ir.Expr.Variable ir) {
        builder.append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Property ir) {
        visit(ir.receiver());
        builder.append(".").append(ir.name());
        return builder;
    }

    @Override
    public StringBuilder visit(Ir.Expr.Function ir) {
        builder.append(ir.name());
        return appendArgs(ir.arguments());
    }

    @Override
    public StringBuilder visit(Ir.Expr.Method ir) {
        visit(ir.receiver());
        builder.append(".").append(ir.name());
        return appendArgs(ir.arguments());
    }

    @Override
    public StringBuilder visit(Ir.Expr.ObjectExpr ir) {
        builder.append("new ").append("Object()");
        withBlock(() -> {
            for (var field : ir.fields()) {
                newline(indent);
                builder.append(getType(field.type())).append(" ").append(field.name());
                if (field.value().isPresent()) {
                    builder.append(" = ");
                    visit(field.value().get());
                }
                end();
            }
            for (var method : ir.methods()) {
                newline(indent);
                // NOTE: This uses Void instead of Object when method.returns() is NIL
                builder.append(getReturnType(method.returns())).append(" ").append(method.name());
                withParen(() -> {
                    int sz = method.parameters().size();
                    for (int i = 0; i < sz; ++i) {
                        var p = method.parameters().get(i);
                        builder.append(getType(p.type())).append(" ").append(p.name());
                        if (i < sz - 1) builder.append(", ");
                    }
                });
                withBlock(() -> {
                    for (var s : method.body()) {
                        newline(indent);
                        visit(s);
                    }
                });
            }
        });
        return builder;
    }


    //------------------------------HELPER FUNCTIONS-------------------------------

    private static final Map<Type,String> JAVA_TYPES = Map.of(
            Type.NIL,      "Object",
            Type.BOOLEAN,  "boolean",
            Type.INTEGER,  "BigInteger",
            Type.DECIMAL,  "BigDecimal",
            Type.STRING,   "String",
            // and for your “interface” types if you want them:
            Type.ANY,         "Object",
            Type.EQUATABLE,   "Equatable",
            Type.COMPARABLE,  "Comparable",
            Type.ITERABLE,    "Iterable<BigInteger>"
    );

    private String getType(Type t) {
        String s = JAVA_TYPES.get(t);
        // Workaround
        //            throw new AssertionError("Unknown type");
        return Objects.requireNonNullElse(s, "Object");
    }

    private String getReturnType(Type t) {
        if(t == Type.NIL) return "Void";
        else return getType(t);
    }

    private void withParen(Runnable content) {
        builder.append("(");
        content.run();
        builder.append(")");
    }

    private void withBlock(Runnable content) {
        builder.append(" {");
        indent++;
        content.run();
        indent--;
        newline(indent);
        builder.append("}");
    }

    private void end() {
        builder.append(";");
    }

    private String capture(Runnable r) {
        int old = builder.length();
        r.run();
        String code = builder.substring(old);
        builder.setLength(old);
        return code;
    }

    private StringBuilder appendArgs(List<Ir.Expr> arguments) {
        withParen(() -> {
            int sz = arguments.size();
            for (int i = 0; i < sz; ++i) {
                visit(arguments.get(i));
                if (i < sz - 1) builder.append(", ");
            }
        });
        return builder;
    }
    //--------------------------------END HELPER FUNCTIONS----------------------------------
}
