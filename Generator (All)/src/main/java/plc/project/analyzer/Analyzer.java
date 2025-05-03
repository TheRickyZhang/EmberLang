package plc.project.analyzer;

import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static plc.project.analyzer.Environment.TYPES;

public final class Analyzer implements Ast.Visitor<Ir, AnalyzeException> {

    private Scope scope;

    public Analyzer(Scope scope) {
        this.scope = scope;
    }

    @Override
    public Ir.Source visit(Ast.Source ast) throws AnalyzeException {
        var statements = new ArrayList<Ir.Stmt>();
        for (var statement : ast.statements()) {
            statements.add(visit(statement));
        }
        return new Ir.Source(statements);
    }

    private Ir.Stmt visit(Ast.Stmt ast) throws AnalyzeException {
        return (Ir.Stmt) visit((Ast) ast); //helper to cast visit(Ast.Stmt) to Ir.Stmt
    }


    @Override
    public Ir.Stmt.Let visit(Ast.Stmt.Let ast) throws AnalyzeException {
        String name = ast.name();
        if(scope.get(name, true).isPresent()) {
            throw new AnalyzeException(name + " is already defined");
        }
        Ir.Expr value = ast.value().isPresent() ? visit(ast.value().get()) : null;
        Type type = resolveType(ast.type().orElse(null), value);
        scope.define(name, type);
        return new Ir.Stmt.Let(name, type, Optional.ofNullable(value));
    }

    @Override
    public Ir.Stmt.Def visit(Ast.Stmt.Def ast) throws AnalyzeException {
        String name = ast.name();
        if(scope.get(name, true).isPresent()) {
            throw new AnalyzeException(name + " is already present");
        }
        if(ast.parameterTypes().size() > ast.parameters().size()) {
            throw new AnalyzeException("More parameter types than parameters");
        }
        List<String> paramNames = ast.parameters();
        List<Type> paramTypes = new ArrayList<>();
        int sz = paramNames.size();

        // Check for uniqueness of names
        if(paramNames.stream().distinct().count() != sz) {
            throw new AnalyzeException("Param names must be unique");
        }

        for(int i = 0; i < sz; ++i) {
            paramTypes.add(resolveType(ast.parameterTypes().get(i).orElse(null), null));
        }
        Type returnType = resolveType(ast.returnType().orElse(null), null);

        // NOTE: We need to declare this as early as possible so it is available for recursive functions!
        Type funcType = new Type.Function(paramTypes, returnType);
        scope.define(name, funcType);

        Scope old = scope;
        scope = new Scope(old);
        for(int i = 0; i < sz; ++i) {
            scope.define(paramNames.get(i),paramTypes.get(i));
        }
        // VERIFY
        scope.define("$RETURNS", returnType);

        List<Ir.Stmt> body = new ArrayList<>();
        try {   // FROM Evaluator - ensure you have a try, finally -> restore scope
            for(Ast.Stmt stmt : ast.body()) body.add(visit(stmt));
        } finally {
            scope = old;
        }

        List<Ir.Stmt.Def.Parameter> params = new ArrayList<>();
        for (int i = 0; i < sz; ++i) {
            params.add(new Ir.Stmt.Def.Parameter(paramNames.get(i), paramTypes.get(i)));
        }
        return new Ir.Stmt.Def(name, params, returnType, body);
    }

    @Override
    public Ir.Stmt.If visit(Ast.Stmt.If ast) throws AnalyzeException {
        Ir.Expr condition = visit(ast.condition());
        requireSubtype(condition.type(), Type.BOOLEAN);

        Scope prevScope = scope;
        List<Ir.Stmt> thenIR = new ArrayList<>();
        {
            scope = new Scope(scope); // thenScope
            try {
                for(Ast.Stmt s : ast.thenBody()) thenIR.add(visit(s));
            } finally {
                scope = prevScope;
            }
        }
        List<Ir.Stmt> elseIR = new ArrayList<>();
        {
            scope = new Scope(scope); // elseScope
            try {
                for(Ast.Stmt s : ast.elseBody()) elseIR.add(visit(s));
            } finally {
                scope = prevScope;
            }
        }
        return new Ir.Stmt.If(condition, thenIR, elseIR);
    }

    @Override
    public Ir.Stmt.For visit(Ast.Stmt.For ast) throws AnalyzeException {
        Ir.Expr expression = visit(ast.expression());
        requireSubtype(expression.type(), Type.ITERABLE);

        Scope prevScope = scope;
        scope = new Scope(scope);

        // A bit unsure what the integer handling is about?
        String varName = ast.name();
        scope.define(varName, Type.INTEGER);
        List<Ir.Stmt> body = new ArrayList<>();
        try {
            for (Ast.Stmt stmt : ast.body()) body.add(visit(stmt));
        } finally {
            scope = prevScope;
        }
        return new Ir.Stmt.For(varName, Type.INTEGER, expression, body);
    }

    @Override
    public Ir.Stmt.Return visit(Ast.Stmt.Return ast) throws AnalyzeException {
        Optional<Type> expectedType = scope.get("$RETURNS", false);
        if(expectedType.isEmpty()) {
            throw new AnalyzeException("Return statement outside of a function");
        }
        Ir.Expr returnExpr = ast.value().isPresent() ? visit(ast.value().get()) : new Ir.Expr.Literal(null, Type.NIL);
        requireSubtype(returnExpr.type(), expectedType.get());
        return new Ir.Stmt.Return(Optional.of(returnExpr));
    }

    @Override
    public Ir.Stmt.Expression visit(Ast.Stmt.Expression ast) throws AnalyzeException {
        var expression = visit(ast.expression());
        return new Ir.Stmt.Expression(expression);
    }

    @Override
    public Ir.Stmt.Assignment visit(Ast.Stmt.Assignment ast) throws AnalyzeException {
        Ir.Expr lhs = visit(ast.expression());
        Ir.Expr rhs = visit(ast.value());
        Ast.Expr expr = ast.expression();
        if(expr instanceof Ast.Expr.Variable) {
            String varName = ((Ast.Expr.Variable) expr).name();
            Optional<Type> t = scope.get(varName, false);
            if(t.isEmpty()) {
                throw new AnalyzeException("Cannot assign undeclared variable");
            }
            requireSubtype(rhs.type(), t.get());
            return new Ir.Stmt.Assignment.Variable((Ir.Expr.Variable) lhs, rhs);
        } else if(expr instanceof Ast.Expr.Property) {
            Type t = lhs.type();
            requireSubtype(rhs.type(), t);
            return new Ir.Stmt.Assignment.Property((Ir.Expr.Property) lhs, rhs);
        } else {
            throw new AnalyzeException("Invalid assignment target lol bozo");
        }
    }

    private Ir.Expr visit(Ast.Expr ast) throws AnalyzeException {
        return (Ir.Expr) visit((Ast) ast); //helper to cast visit(Ast.Expr) to Ir.Expr
    }

    @Override
    public Ir.Expr.Literal visit(Ast.Expr.Literal ast) throws AnalyzeException {
        var type = switch (ast.value()) {
            case null -> Type.NIL;
            case Boolean _ -> Type.BOOLEAN;
            case BigInteger _ -> Type.INTEGER;
            case BigDecimal _ -> Type.DECIMAL;
            case String _ -> Type.STRING;
            //If the AST value isn't one of the above types, the Parser is
            //returning an incorrect AST - this is an implementation issue,
            //hence throw AssertionError rather than AnalyzeException.
            default -> throw new AssertionError(ast.value().getClass());
        };
        return new Ir.Expr.Literal(ast.value(), type);
    }

    @Override
    public Ir.Expr.Group visit(Ast.Expr.Group ast) throws AnalyzeException {
        return new Ir.Expr.Group(visit(ast.expression()));
    }

    @Override
    public Ir.Expr.Binary visit(Ast.Expr.Binary ast) throws AnalyzeException {
        String op = ast.operator();
        Ir.Expr left = visit(ast.left());
        Ir.Expr right = visit(ast.right());
        Type t;

        switch (op) {
            case "+" -> {
                if (left.type().equals(Type.STRING) || right.type().equals(Type.STRING)) {
                    t = Type.STRING;
                } else if (isSubtype(left.type(), Type.INTEGER) || isSubtype(left.type(), Type.DECIMAL)) {
                    if (!left.type().equals(right.type())) throw new AnalyzeException("Ops of '+' must be same type");
                    t = left.type();
                } else {
                    throw new AnalyzeException("Ops of '+' must be String or numeric");
                }
            }
            case "-", "*", "/", "//" -> {
                if (isSubtype(left.type(), Type.INTEGER) || isSubtype(left.type(), Type.DECIMAL)) {
                    if (!left.type().equals(right.type())) throw new AnalyzeException("Ops of '" + op + "' must be same type");
                    t = left.type();
                } else {
                    throw new AnalyzeException("Ops of '" + op + "' must be numeric");
                }
            }
            case "<", "<=", ">", ">=" -> {
                if (!isSubtype(left.type(), Type.COMPARABLE)) throw new AnalyzeException("Left op of '" + op + "' must be Comparable");
                if (!left.type().equals(right.type())) {
                    throw new AnalyzeException("Ops of '" + op + "' must be of the same type");
                }
                t = Type.BOOLEAN;
            }
            case "==", "!=" -> {
                if (!isSubtype(left.type(), Type.EQUATABLE) || !isSubtype(right.type(), Type.EQUATABLE)) {
                    throw new AnalyzeException("Ops of '" + op + "' must be Equatable");
                }
                t = Type.BOOLEAN;
            }
            case "AND", "OR" -> {
                requireSubtype(left.type(), Type.BOOLEAN);
                requireSubtype(right.type(), Type.BOOLEAN);
                t = Type.BOOLEAN;
            }
            default -> throw new AnalyzeException("Unknown operator: " + op);
        }
        return new Ir.Expr.Binary(op, left, right, t);
    }

    @Override
    public Ir.Expr.Variable visit(Ast.Expr.Variable ast) throws AnalyzeException {
        String name = ast.name();

        Optional<Type> thisType = scope.get("this", false);

        if(thisType.isPresent() && thisType.get() instanceof Type.Object(Scope s)) {
            boolean isObjectMember = s.get(name, true).isPresent();
            boolean isLocal = scope.get(name, true).isPresent();
            if(isObjectMember && !isLocal) {
                throw new AnalyzeException("Direct field access not allowed for " + name + ", use this");
            }
        }
        Optional<Type> t = scope.get(name, false);
        if(t.isEmpty()) {
            throw new AnalyzeException(name + " not found in scope");
        }
        return new Ir.Expr.Variable(name, t.get());
    }

    @Override
    public Ir.Expr.Property visit(Ast.Expr.Property ast) throws AnalyzeException {
        Ir.Expr receiver = visit(ast.receiver());
        // IDK exactly here but the linter suggested I follow this pattern matching
        if (!(receiver.type() instanceof Type.Object(Scope _scope))) {
            throw new AnalyzeException("Receiver is not an object");
        }
        Optional<Type> propType = _scope.get(ast.name(), true);
        if (propType.isEmpty()) {
            throw new AnalyzeException("Property " + ast.name() + " not defined in object");
        }
        return new Ir.Expr.Property(receiver, ast.name(), propType.get());
    }

    @Override
    public Ir.Expr.Function visit(Ast.Expr.Function ast) throws AnalyzeException {
        String name = ast.name();
        Optional<Type> optType = scope.get(name, false);
        if (optType.isEmpty()) {
            throw new AnalyzeException("Fn " + name + " is not defined");
        }
        Type type = optType.get();
        // Note pattern matching
        if (!(type instanceof Type.Function(List<Type> parameters, Type returns))) {
            throw new AnalyzeException(name + " is not a function");
        }

        List<Ir.Expr> res = new ArrayList<>();
        List<Ast.Expr> astArgs = ast.arguments();
        if (astArgs.size() != parameters.size()) {
            throw new AnalyzeException("Fn " + name + " expects " + parameters.size() + " args lol bozo");
        }
        for (int i = 0; i < astArgs.size(); i++) {
            Ir.Expr arg = visit(ast.arguments().get(i));
            requireSubtype(arg.type(), parameters.get(i));
            res.add(arg);
        }
        return new Ir.Expr.Function(name, res, returns);
    }

    @Override
    public Ir.Expr.Method visit(Ast.Expr.Method ast) throws AnalyzeException {
        Ir.Expr receiver = visit(ast.receiver());
        // Note pattern matching for Type.Object
        if (!(receiver.type() instanceof Type.Object(Scope objectScope))) {
            throw new AnalyzeException("Receiver is not an object");
        }

        String name = ast.name();
        Optional<Type> optType = objectScope.get(name, false);
        if (optType.isEmpty()) {
            throw new AnalyzeException("Method " + name + " is not defined in the object");
        }
        Type type = optType.get();
        // Note pattern matching for function type
        if (!(type instanceof Type.Function(List<Type> parameters, Type returns))) {
            throw new AnalyzeException("Method " + name + " is not a function");
        }

        List<Ir.Expr> res = new ArrayList<>();
        List<Ast.Expr> astArgs = ast.arguments();
        if (astArgs.size() != parameters.size()) {
            throw new AnalyzeException("Method " + name + " expects " + parameters.size() + " args but got " + astArgs.size() + " instead");
        }
        for (int i = 0; i < astArgs.size(); i++) {
            Ir.Expr arg = visit(ast.arguments().get(i));
            requireSubtype(arg.type(), parameters.get(i));
            res.add(arg);
        }
        return new Ir.Expr.Method(receiver, name, res, returns);
    }

    // This freaking thing. AHHHHHHHHH
    @Override
    public Ir.Expr.ObjectExpr visit(Ast.Expr.ObjectExpr ast) throws AnalyzeException {
        Optional<String> name = ast.name();
        if (name.isPresent() && Environment.TYPES.containsKey(name.get())) {
            throw new AnalyzeException("Object name " + name + " cannot be a type name");
        }

        Scope objectScope = new Scope(null);
        Type.Object objectType = new Type.Object(objectScope);

        Scope prevScope = scope;
        scope = objectScope;
        try {
            List<Ir.Stmt.Let> fields = new ArrayList<>();
            Set<String> fieldNames = new HashSet<>();
            for (Ast.Stmt.Let fieldAst : ast.fields()) {
                String fieldName = fieldAst.name();
                if (!fieldNames.add(fieldName)) {
                    throw new AnalyzeException("Field " + fieldName + " is defined more than once in object " + name);
                }
                fields.add(visit(fieldAst));
            }

            // EXPLICITLY from specs:
            // Analyze methods: for each method, create a new method scope that extends the object scope
            // and define "this" there as an implicit parameter.

            // FIRST pre declare all methods in the object scope (Fixed to ACTUALLY declare instead of generic empty)
            Set<String> allMethodNames = new HashSet<>();
            for (Ast.Stmt.Def m : ast.methods()) {
                String methodName = m.name();
                if (fieldNames.contains(methodName) || !allMethodNames.add(methodName)) {
                    throw new AnalyzeException("Method " + methodName + " conflicts with " + name);
                }
                List<Type> paramTypes = new ArrayList<>();
                int sz = m.parameters().size();
                for(int i = 0; i < sz; ++i) {
                    paramTypes.add(resolveType(m.parameterTypes().get(i).orElse(null), null));
                }
                Type rt = resolveType(m.returnType().orElse(null), null);
                objectScope.define(methodName, new Type.Function(paramTypes, rt));
            }

            // THEN analyze each method in its own scope.
            List<Ir.Stmt.Def> methods = new ArrayList<>();
            for (Ast.Stmt.Def m : ast.methods()) {
                Scope methodScope = new Scope(objectScope);
                methodScope.define("this", objectType);

                for(int i = 0; i < m.parameters().size(); ++i) {
                    String param = m.parameters().get(i);
                    Type t = resolveType(m.parameterTypes().get(i).orElse(null), null);
                    methodScope.define(param, t);
                }

                Scope old = scope;
                scope = methodScope;
                try {
                    methods.add(visit(m));
                } finally {
                    scope = old;
                }
            }

            return new Ir.Expr.ObjectExpr(name, fields, methods, objectType);
        } finally {
            scope = prevScope;
        }
    }


    // ------------- BEGIN HELPER -------------

    // Originally used Optional parameters, but because of lint / anti-pattern moved to nullables.
    private Type resolveType(String typeName, Ir.Expr value) throws AnalyzeException {
        Type declared = null;
        if (typeName != null) {
            if (!TYPES.containsKey(typeName)) {
                throw new AnalyzeException("Type " + typeName + " is not defined");
            }
            declared = TYPES.get(typeName);
        }
        Type inferred = (value != null) ? value.type() : Type.ANY;
        if (declared != null && value != null) {
            requireSubtype(value.type(), declared);
        }
        return (declared != null) ? declared : inferred;
    }

    public static void requireSubtype(Type type, Type other) throws AnalyzeException {
        if (type.equals(other) || other.equals(Type.ANY)) return;
        if (other.equals(Type.EQUATABLE)) {
            if (type.equals(Type.NIL) || type.equals(Type.ITERABLE)) return;
            requireSubtype(type, Type.COMPARABLE);
            return;
        }
        if (other.equals(Type.COMPARABLE)) {
            if (type.equals(Type.BOOLEAN) || type.equals(Type.INTEGER) ||
                    type.equals(Type.DECIMAL) || type.equals(Type.STRING)) {
                return;
            }
        }
        throw new AnalyzeException("Type " + type + " is not a subtype of " + other);
    }
    private boolean isSubtype(Type type, Type supertype) {
        try {
            requireSubtype(type, supertype);
            return true;
        } catch (AnalyzeException e) {
            return false;
        }
    }

    // ------------- END HELPER -----------
}
