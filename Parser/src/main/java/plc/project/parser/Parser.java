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
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    public Ast.Stmt parseStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt.Let parseLetStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt.Def parseDefStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt.If parseIfStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt.For parseForStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt.Return parseReturnStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    public Ast.Expr parseExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        Ast.Expr expr = parseComparisonExpr();
       ]
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        throw new UnsupportedOperationException("TODO"); //TODO
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
        if(!tokens.peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier");
        Token id = tokens.get(0);
        tokens.match(Token.Type.IDENTIFIER);
        if(!tokens.match("(")) throw new ParseException("Missing (");
        List<Ast.Expr> arguments = new ArrayList<>();
        if (!tokens.peek(")")) {
            do {
                arguments.add(parseExpr());
            } while (tokens.match(","));
        }
        if(!tokens.match(")")) throw new ParseException("Missing )");
        return new Ast.Expr.Method(primary, id.literal(), arguments);
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
        if(tokens.peek(Token.Type.NIL)) {
            tokens.match(Token.Type.NIL);
            return new Ast.Expr.Literal(null); // We can use built-in null as nil
        } else if (tokens.peek(Token.Type.TRUE)) {
            tokens.match(Token.Type.TRUE);
            return new Ast.Expr.Literal(true);
        } else if (tokens.peek(Token.Type.FALSE)) {
            tokens.match(Token.Type.FALSE);
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
        if(!tokens.match("OBJECT")) throw new ParseException("Expected object");
        Optional<String> name = Optional.empty();
        if (tokens.peek(Token.Type.IDENTIFIER)) {
            name = Optional.of(tokens.get(0).literal());
            tokens.match(Token.Type.IDENTIFIER);
        }
        if(!tokens.match("DO")) throw new ParseException("Expected do");
        List<Ast.Stmt.Let> fields = new ArrayList<>();
        List<Ast.Stmt.Def> methods = new ArrayList<>();
        while(tokens.peek("LET")) fields.add(parseLetStmt());
        while(tokens.peek("DEF")) methods.add(parseDefStmt());
        if(!tokens.match("END")) throw new ParseException("Expected end");
        return new Ast.Expr.ObjectExpr(name, fields, methods);
    }

    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        if (!tokens.peek(Token.Type.IDENTIFIER)) throw new ParseException("Expected Identifier");
        String variable = tokens.get(0).literal();
        tokens.match(Token.Type.IDENTIFIER);
        if (!tokens.peek("(")) return new Ast.Expr.Variable(variable);

        List<Ast.Expr> arguments = new ArrayList<>();
        tokens.match("(");
        if (!tokens.peek(")")) {
            do {
                arguments.add(parseExpr());
            } while (tokens.match(","));
        }
        if (!tokens.match(")")) throw new ParseException("Expected ')' after arguments");
        return new Ast.Expr.Function(variable, arguments);
    }

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
