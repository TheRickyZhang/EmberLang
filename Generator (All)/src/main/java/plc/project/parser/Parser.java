package plc.project.parser;

import plc.project.lexer.Token;

import java.math.BigDecimal;
import java.math.BigInteger;
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

    public Ast.Stmt parseStmt() throws ParseException {
        if(!tokens.has(0)) {
            throw new ParseException("This exception should not appear. Fallback for any uncaught parseStmt calls");
        }
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
        checkMacro("LET");
        String id = getIdentifier();
        Optional<String> t = parseOptionalTypeName();
        Optional<Ast.Expr> val = Optional.empty();
        if(tokens.match("=")) {
             val = Optional.of(parseExpr());
        }
        checkSemicolon();
        return new Ast.Stmt.Let(id, t, val);
    }

    private Ast.Stmt.Def parseDefStmt() throws ParseException {
        checkMacro("DEF");
        String id = getIdentifier();

        List<Optional<String>> parameterTypes  = new ArrayList<>();
        List<String> parameters = parseCommaSeparatedList(this::getIdentifier, parameterTypes);

        Optional<String> rt = parseOptionalTypeName();

        List<Ast.Stmt> body = new ArrayList<>();
        checkMacro("DO");
        while (!tokens.peek("END")) {
            if(!tokens.has(0)) throw new ParseException("No END");
            body.add(parseStmt());
        }
        checkMacro("END");

        return new Ast.Stmt.Def(id, parameters, parameterTypes, rt, body);
    }


    private Ast.Stmt.If parseIfStmt() throws ParseException {
        checkMacro("IF");
        Ast.Expr condition = parseExpr();
        checkMacro("DO");
        List<Ast.Stmt> thenBody = new ArrayList<>();
        List<Ast.Stmt> elseBody = new ArrayList<>();

        while(!tokens.peek("ELSE") && !tokens.peek("END")) {
            if(!tokens.has(0)) throw new ParseException("No END or ELSE");
            thenBody.add(parseStmt());
        }
        if(tokens.match("ELSE")) {
            while(!tokens.peek("END")) {
                if(!tokens.has(0)) throw new ParseException("No END");
                elseBody.add(parseStmt());
            }
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
        while (!tokens.peek("END")) {
            if(!tokens.has(0)) throw new ParseException("No END");
            body.add(parseStmt());
        }
        checkMacro("END");

        return new Ast.Stmt.For(id, expression, body);
    }

    private Ast.Stmt.Return parseReturnStmt() throws ParseException {
        checkMacro("RETURN");
        Optional<Ast.Expr> value = Optional.empty();
        if (!tokens.peek(";")) {
            if(!tokens.has(0)) throw new ParseException("Nothing after RETURN statement");
            value = Optional.of(parseExpr());
        }
        checkSemicolon();
        return new Ast.Stmt.Return(value);
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        Ast.Expr expr = parseExpr();
        Ast.Expr val = null;
        if (tokens.match("=")) {
            val = parseExpr();
        }
        checkSemicolon();
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
            if(!tokens.has(0)) {
                throw new ParseException("Nothing after logical expression");
            }
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
            if(!tokens.has(0)) {
                throw new ParseException("Nothing after comparison expression");
            }
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
            if(!tokens.has(0)) {
                throw new ParseException("Nothing after additive expression");
            }
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
            if(!tokens.has(0)) {
                throw new ParseException("Nothing after multiplicative expression");
            }
            Ast.Expr right = parseSecondaryExpr();
            expr = new Ast.Expr.Binary(operator, expr, right);
        }
        return expr;
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        Ast.Expr expr = parsePrimaryExpr();
        while(tokens.match(".")) {
            String id = getIdentifier();
            if(tokens.peek("(")) {
                List<Ast.Expr> arguments = parseCommaSeparatedList(this::parseExpr, null);
                expr = new Ast.Expr.Method(expr, id, arguments);
            } else {
                expr = new Ast.Expr.Property(expr, id);
            }
        }
        return expr;
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
        if(tokens.match("NIL")) {
            return new Ast.Expr.Literal(null); // We can use built-in null as nil
        } else if (tokens.match("TRUE")) {
            return new Ast.Expr.Literal(true);
        } else if (tokens.match("FALSE")) {
            return new Ast.Expr.Literal(false);
        } else if (tokens.peek(Token.Type.INTEGER)) {
            String lit = tokens.get(0).literal();
            tokens.match(Token.Type.INTEGER);
            // Need to handle special case where we have number represented as exponent
            if(lit.contains("e")) {
                BigDecimal bd = new BigDecimal(lit);
                try {
                    return new Ast.Expr.Literal(bd.toBigIntegerExact());
                } catch (ArithmeticException eee){
                    return new Ast.Expr.Literal(bd);
                }
            } else {
                return new Ast.Expr.Literal(new BigInteger(lit));
            }
        } else if (tokens.peek(Token.Type.DECIMAL)) {
            Token token = tokens.get(0);
            tokens.match(Token.Type.DECIMAL);
            return new Ast.Expr.Literal(new BigDecimal(token.literal()));
        } else if (tokens.peek(Token.Type.CHARACTER)) {
            Token token = tokens.get(0);
            tokens.match(Token.Type.CHARACTER);
            String noQuotes = token.literal().substring(1, token.literal().length()-1);
            String unescaped = unescape(noQuotes);
            return new Ast.Expr.Literal(unescaped.charAt(0));
        } else if (tokens.peek(Token.Type.STRING)) {
            Token token = tokens.get(0);
            tokens.match(Token.Type.STRING);
            // Lexer returns raw tokens, so splice off the quotes here
            String noQuotes = token.literal().substring(1, token.literal().length()-1);
            String unescaped = unescape(noQuotes);
            return new Ast.Expr.Literal(unescaped);
        } else {
            throw new ParseException("Invalid literal expression");
        }
    }

    private Ast.Expr.Group parseGroupExpr() throws ParseException {
        checkMacro("(");
        Ast.Expr expression = parseExpr();
        checkMacro(")");
        return new Ast.Expr.Group(expression);
    }

    private Ast.Expr.ObjectExpr parseObjectExpr() throws ParseException {
        checkMacro("OBJECT");
        Optional<String> id = Optional.empty();
        if (tokens.peek(Token.Type.IDENTIFIER) && !tokens.peek("DO")) {
            id = Optional.of(getIdentifier());
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
        List<Ast.Expr> arguments = parseCommaSeparatedList(this::parseExpr, null);
        return new Ast.Expr.Function(id, arguments);
    }

    //---------------------BEGIN HELPER FUNCTIONS-----------------------//
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

    // Takes in ( (T , T , ... T)* ), aka parenthesized argument list.
    // Returns List<T> elements, or throw error from invalid open, closed, or propagated lambda
    private <T> List<T> parseCommaSeparatedList(ThrowingSupplier<T> elementParser, List<Optional<String>> types) throws ParseException {
        if (!tokens.match("("))
            throw new ParseException("Expected '" + "(" + "'");
        List<T> elements = new ArrayList<>();
        if (!tokens.peek(")")) {
            do {
                elements.add(elementParser.get());
                Optional<String> name = parseOptionalTypeName();
                if(types != null) types.add(name);
            } while (tokens.match(","));
        }
        if (!tokens.match(")"))
            throw new ParseException("Expected '" + ")" + "'");
        return elements;
    }

    private String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if(c == '\\' && i+1 < s.length()) {
                i++;
                char next = s.charAt(i);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case '\\': sb.append('\\'); break;
                    case '\'': sb.append('\''); break;
                    case '"': sb.append('"'); break;
                    default: sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Optional<String> parseOptionalTypeName() throws ParseException {
        if (!tokens.match(":")) return Optional.empty();
        return Optional.of(getIdentifier());
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
