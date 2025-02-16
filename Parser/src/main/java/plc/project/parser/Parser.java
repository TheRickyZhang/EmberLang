package plc.project.parser;

import plc.project.lexer.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

/**
 * This style of parser is called <em>recursive descent</em>. Each rule in our
 * grammar has dedicated function, and references to other rules correspond to
 * calling that function. Recursive rules are therefore supported by actual
 * recursive calls, while operator precedence is encoded via the grammar.
 *
 * <p>The parser has a similar architecture to the lexer, just with
 * {@link Token}s instead of characters. As before, {@link TokenStream#peek} and
 * {@link TokenStream#match} help with traversing the token stream. Instead of
 * emitting tokens, you will instead need to extract the literal value via
 * {@link TokenStream#get} to be added to the relevant AST.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public Ast.Source parseSource() throws ParseException {
        List<Ast.Stmt> statements = new ArrayList<>();
        while (tokens.has(0)) {
            statements.add(parseStmt());
        }
        return new Ast.Source(statements);
    }

    Ast.Stmt parseStmt() throws ParseException {
        if (tokens.peek("LET")) {
            return parseLetStmt();
        } else if (tokens.peek("DEF")) {
            return parseDefStmt();
        } else if (tokens.peek("IF")) {
            return parseIfStmt();
        } else if (tokens.peek("FOR")) {
            return parseForStmt();
        } else if (tokens.peek("RETURN")) {
            return parseReturnStmt();
        } else {
            // Fallback: parse an expression (or assignment) statement.
            return parseExpressionOrAssignmentStmt();
        }
    }

    private Ast.Stmt.Let parseLetStmt() throws ParseException {
        if(!tokens.match("LET")) throw new ParseException("No LET");
        String id = getIdentifier();
        Optional<Ast.Expr> val = Optional.empty();
        if(tokens.match("=")) {
             val = Optional.of(parseExpr());
        }
        checkSemicolon();
        return new Ast.Stmt.Let(id, val);
    }

    private Ast.Stmt.Def parseDefStmt() throws ParseException {
        if (!tokens.match("DEF")) throw new ParseException("Expected 'DEF'");
        String id = getIdentifier();

        List<String> parameters = parseCommaSeparatedList(this::getIdentifier);

        if (!tokens.match("DO")) throw new ParseException("Expected 'DO' before function body");
        List<Ast.Stmt> body = new ArrayList<>();
        while (!tokens.peek("END")) {
            body.add(parseStmt());
        }
        if (!tokens.match("END")) throw new ParseException("Expected 'END' after function body");

        return new Ast.Stmt.Def(id, parameters, body);
    }


    private Ast.Stmt.If parseIfStmt() throws ParseException {
        checkMacro("IF");
        Ast.Expr condition = parseExpr();
        checkMacro("DO");
        List<Ast.Stmt> thenBody = new ArrayList<>();
        List<Ast.Stmt> elseBody = new ArrayList<>();

        while(!tokens.peek("ELSE") && !tokens.peek("END")) thenBody.add(parseStmt());
        if(tokens.match("ELSE")) {
            while(!tokens.peek("END")) elseBody.add(parseStmt());
        }
        checkMacro("END");
        return new Ast.Stmt.If(condition, thenBody, elseBody);
    }

    private Ast.Stmt.For parseForStmt() throws ParseException {
        checkMacro("FOR");
        String id = getIdentifier();
        checkMacro("IN");
        Ast.Expr expression = parseExpr();
        checkMacro("DO");

        List<Ast.Stmt> body = new ArrayList<>();
        while (!tokens.peek("END")) body.add(parseStmt());
        checkMacro("END");

        return new Ast.Stmt.For(id, expression, body);
    }

    private Ast.Stmt.Return parseReturnStmt() throws ParseException {
        checkMacro("RETURN");
        Optional<Ast.Expr> value = Optional.empty();
        if (!tokens.peek(";")) value = Optional.of(parseExpr());
        checkSemicolon();
        return new Ast.Stmt.Return(value);
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        Ast.Expr expr = parseExpr();
        Ast.Expr val = null;
        if (tokens.match("=")) {
            val = parseExpr();
        }
        return (val == null) ? new Ast.Stmt.Expression(expr) : new Ast.Stmt.Assignment(expr, val);
    }

    public Ast.Expr parseExpr() throws ParseException {
        return parseLogicalExpr();
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        Ast.Expr expr = parseComparisonExpr();
        while(tokens.peek("AND") || tokens.peek("OR")) {
            String operator = tokens.peek("AND") ? "AND" : "OR";
            tokens.match(operator);
            Ast.Expr right = parseComparisonExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        Ast.Expr expr = parseAdditiveExpr();
        String[] operators = {"<=", ">=", "==", "!=", "<", ">"};
        while (true) {
            String op = null;
            for (String candidate : operators) {
                if (tokens.peek(candidate)) {
                    op = candidate; tokens.match(candidate);
                    break;
                }
            }
            if (op == null) break;
            Ast.Expr right = parseAdditiveExpr();
            expr = new Ast.Expr.Binary(op, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        Ast.Expr expr = parseMultiplicativeExpr();
        while (tokens.peek("+") || tokens.peek("-")) {
            String operator = tokens.peek("+") ? "+" : "-";
            tokens.match(operator);
            Ast.Expr right = parseMultiplicativeExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        Ast.Expr expr = parseSecondaryExpr();
        while (tokens.peek("*") || tokens.peek("/")) {
            String operator = tokens.peek("*") ? "*" : "/";
            tokens.match(operator);
            Ast.Expr right = parseSecondaryExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        Ast.Expr primary = parsePrimaryExpr();
        if(!tokens.match(".")) return primary;
        String id = getIdentifier();
        List<Ast.Expr> arguments = parseCommaSeparatedList(this::parseExpr);
        return new Ast.Expr.Method(primary, id, arguments);
    }

    private Ast.Expr parsePrimaryExpr() throws ParseException {
        if (tokens.peek(Token.Type.INTEGER) ||
                tokens.peek(Token.Type.DECIMAL) ||
                tokens.peek(Token.Type.CHARACTER) ||
                tokens.peek(Token.Type.STRING) ||
                tokens.peek("NIL") ||
                tokens.peek("TRUE") ||
                tokens.peek("FALSE")) {
            return parseLiteralExpr();
        }
        if (tokens.peek("(")) {
            return parseGroupExpr();
        }
        if (tokens.peek("OBJECT")) {
            return parseObjectExpr();
        }
        if (tokens.peek(Token.Type.IDENTIFIER)) {
            return parseVariableOrFunctionExpr();
        }

        throw new ParseException("Unexpected token: " + tokens.get(0));
    }

    private Ast.Expr.Literal parseLiteralExpr() throws ParseException {
        if(tokens.match(Token.Type.NIL)) {
            return new Ast.Expr.Literal(null); // We can use built-in null as nil
        } else if (tokens.match(Token.Type.TRUE)) {
            return new Ast.Expr.Literal(true);
        } else if (tokens.match(Token.Type.FALSE)) {
            return new Ast.Expr.Literal(false);
        } else if (tokens.peek(Token.Type.INTEGER)) {
            Token token = tokens.get(0);
            tokens.match(Token.Type.INTEGER);
            return new Ast.Expr.Literal(Integer.parseInt(token.literal()));
        } else if (tokens.peek(Token.Type.DECIMAL)) {
            Token token = tokens.get(0);
            tokens.match(Token.Type.DECIMAL);
            return new Ast.Expr.Literal(Float.parseFloat(token.literal()));
        } else if (tokens.peek(Token.Type.CHARACTER)) {
            Token token = tokens.get(0);
            tokens.match(Token.Type.CHARACTER);
            return new Ast.Expr.Literal(token.literal());
        } else if (tokens.peek(Token.Type.STRING)) {
            Token token = tokens.get(0);
            tokens.match(Token.Type.STRING);
            return new Ast.Expr.Literal(token.literal());
        } else {
            throw new ParseException("Invalid literal expression");
        }
    }

    private Ast.Expr.Group parseGroupExpr() throws ParseException {
        if(!tokens.match("(")) throw new ParseException("Expected ( to start expression");
        Ast.Expr expression = parseExpr();
        if(!tokens.match(")")) throw new ParseException("Expected ) after expression");
        return new Ast.Expr.Group(expression);
    }

    private Ast.Expr.ObjectExpr parseObjectExpr() throws ParseException {
        checkMacro("OBJECT");
        Optional<String> id = Optional.empty();
        if (tokens.peek(Token.Type.IDENTIFIER)) {
            String temp = tokens.get(0).literal();
            tokens.match(Token.Type.IDENTIFIER);
            id = Optional.of(temp);
        }
        checkMacro("DO");
        List<Ast.Stmt.Let> fields = new ArrayList<>();
        List<Ast.Stmt.Def> methods = new ArrayList<>();
        while(tokens.peek("LET")) fields.add(parseLetStmt());
        while(tokens.peek("DEF")) methods.add(parseDefStmt());
        checkMacro("END");
        return new Ast.Expr.ObjectExpr(id, fields, methods);
    }

    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        String id = getIdentifier();
        if (!tokens.peek("(")) return new Ast.Expr.Variable(id);
        List<Ast.Expr> arguments = parseCommaSeparatedList(this::parseExpr);
        return new Ast.Expr.Function(id, arguments);
    }

    //----------------------HELPER FUNCTIONS-----------------------//
    public interface ThrowingSupplier<T> {
        T get() throws ParseException;
    }

    private void checkMacro(String s) throws ParseException {
        if(!tokens.match(s)) throw new ParseException("No " + s);
    }

    private void checkSemicolon() throws ParseException {
        if (!tokens.match(";")) throw new ParseException("No semicolon");
    }

    private String getIdentifier() throws ParseException {
        if (!tokens.peek(Token.Type.IDENTIFIER)) throw new ParseException("Expected identifier");
        String id = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);
        return id;
    }

    // Takes in open (T , T , ... T)* closed.
    // Returns List<T> elements, or throw error from invalid open, closed, or propagated lambda
    private <T> List<T> parseCommaSeparatedList(ThrowingSupplier<T> elementParser) throws ParseException {
        if (!tokens.match("("))
            throw new ParseException("Expected '" + "(" + "'");
        List<T> elements = new ArrayList<>();
        if (!tokens.peek(")")) {
            do {
                elements.add(elementParser.get());
            } while (tokens.match(","));
        }
        if (!tokens.match(")"))
            throw new ParseException("Expected '" + ")" + "'");
        return elements;
    }

    //--------------------END HELPER FUNCTIONS---------------------//

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at (index + offset).
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Returns the token at (index + offset).
         */
        public Token get(int offset) {
            checkState(has(offset));
            return tokens.get(index + offset);
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is either a {@link Token.Type}, matching tokens
         * of that type, or a {@link String}, matching tokens with that literal.
         * In effect, {@code new Token(Token.Type.IDENTIFIER, "literal")} is
         * matched by both {@code peek(Token.Type.IDENTIFIER)} and
         * {@code peek("literal")}.
         */
        public boolean peek(Object... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var token = tokens.get(index + offset);
                var pattern = patterns[offset];
                checkState(pattern instanceof Token.Type || pattern instanceof String, pattern);
                if (!token.type().equals(pattern) && !token.literal().equals(pattern)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the token stream.
         */
        public boolean match(Object... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
            }
            return peek;
        }

    }

}
