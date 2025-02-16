package plc.project.lexer;

import java.util.ArrayList;
import java.util.List;

/**
 * The lexer works through a combination of {@link #lex()}, which repeatedly
 * calls {@link #lexToken()} and skips over whitespace/comments, and
 * {@link #lexToken()}, which determines the type of the next token and
 * delegates to the corresponding lex method.
 *
 * <p>Additionally, {@link CharStream} manages the lexer state and contains
 * {@link CharStream#peek} and {@link CharStream#match}. These are helpful
 * utilities for working with character state and building tokens.
 */
public final class Lexer {
    private final CharStream chars;
    public Lexer(String input) {
        this.chars = new CharStream(input);
    }

    public List<Token> lex() throws LexException {
        List<Token> tokens = new ArrayList<>();
        while (!chars.end()) {
            if (chars.peek("[ \b\n\r\t]")) {
                chars.match("[ \b\n\r\t]");
                chars.emit();
            }
            else if (chars.peek("/", "/")) {
                lexComment();
            } else {
                tokens.add(lexToken());
            }
        }
        return tokens;
    }

    private void lexComment() {
        // Assume any '/' begins a comment; consume until newline.
        chars.match("/");  chars.match("/");
        while (!chars.end() && !chars.peek("[\n\r]")) {
            chars.match(".");
        }
        if (chars.peek("[\n\r]"))  chars.match("[\n\r]");
        chars.emit();
    }

    private Token lexToken() throws LexException {
        if (chars.peek("[A-Za-z_]"))  return lexIdentifier();
        else if (chars.peek("[']"))     return lexCharacter();
        else if (chars.peek("[\"]"))    return lexString();
        else if (chars.peek("[0-9]") || chars.peek("[+-]", "[0-9]"))
            return lexNumber();
        else
            return lexOperator();
        // Any non-matching case in lexOperator will throw LexException
    }

    private Token lexIdentifier() {
        // [A-Za-z_][A-Za-z0-9_-]*
        chars.match("[A-Za-z_]");
        while (chars.peek("[A-Za-z0-9_-]")) chars.match("[A-Za-z0-9_-]");
        return new Token(Token.Type.IDENTIFIER, chars.emit());
    }

    private Token lexNumber() throws LexException {
        // ([+-]?\\d+)(\\.\\d+)?([eE][+-]?\\d+)?
        if (chars.peek("[+-]") && chars.peek("[+-]", "[0-9]"))  chars.match("[+-]");
        if (!chars.peek("[0-9]"))  throw new LexException("Expected digit");
        while (chars.peek("[0-9]")) chars.match("[0-9]");

        // Decimal
        if (chars.peek("[.]", "[0-9]")) {
            chars.match("[.]");
            while (chars.peek("[0-9]"))  chars.match("[0-9]");

            peekExponent();
            return new Token(Token.Type.DECIMAL, chars.emit());
        }
        peekExponent();
        return new Token(Token.Type.INTEGER, chars.emit());
    }

    private void peekExponent() throws LexException {
        if(!chars.peek("[e]")) return;
        if (chars.peek("[e]", "[+-]", "[0-9]") || chars.peek("[e]", "[0-9]")) {
            chars.match("[e]");
            if (chars.peek("[+-]")) chars.match("[+-]");
            if (!chars.peek("[0-9]"))
                throw new LexException("Bad exponent");
            while (chars.peek("[0-9]")) chars.match("[0-9]");
        }
        // Otherwise do not consume e, handle as a separate token.
    }

    // Helper function
    private void lexEscape() throws LexException {
        chars.match("\\\\");
        if (!chars.match("[bfnrt'\"\\\\]")) throw new LexException("Invalid escape sequence");
    }

    private Token lexCharacter() throws LexException {
        // "'(" + "[^'\\\\\\r\\n]" + "|" + "\\\\[bfnrt'\"\\\\]" + ")'"
        // Character literal: a single quote, one character (or escape), then a single quote.
        if (!chars.match("'"))
            throw new LexException("Expected opening '");
        if (chars.peek("\\\\")) {
            lexEscape();
        }
        else if (!chars.match("[^'\\\\\\r\\n]"))
            throw new LexException("Invalid character literal");
        if (!chars.match("'")) throw new LexException("Expected closing '");
        return new Token(Token.Type.CHARACTER, chars.emit());
    }

    private Token lexString() throws LexException {
        // "\"(" + "[^\\\\\"\\r\\n]" + "|" + "\\\\[bfnrt'\"\\\\]" + ")*\""
        if (!chars.match("\""))
            throw new LexException("Expected opening \"");
        while (!chars.end() && !chars.peek("\"")) {
            if (chars.peek("\\\\")) {
                lexEscape();
            } else if (!chars.match("[^\\\\\"\\r\\n]")) {
                throw new LexException("Invalid character in string literal");
            }
        }
        if (!chars.match("\"")) throw new LexException("Expected closing \"");
        return new Token(Token.Type.STRING, chars.emit());
    }

    private Token lexOperator() throws LexException {
        // "[<>!=]=?|."
        if (chars.peek("[<>!=]")) {
            if (chars.peek("[<>!=]", "=")) chars.match("[<>!=]", "=");
            else chars.match("[<>!=]");
        } else if(chars.peek("[^A-Za-z_0-9'\" \b\n\r\t]")){
            chars.match("[^A-Za-z_0-9'\" \b\n\r\t]");
        } else {
            throw new LexException("Invalid character");
        }
        return new Token(Token.Type.OPERATOR, chars.emit());
    }

    private static final class CharStream {
        private final String input;
        private int index = 0, length = 0;
        public CharStream(String input) { this.input = input; }
        public boolean end() { return index >= input.length(); } // Helper function

        public boolean peek(String... patterns) {
            if (index + patterns.length > input.length()) return false;
            for (int i = 0; i < patterns.length; i++) {
                String ch = String.valueOf(input.charAt(index + i));
                if (!ch.matches(patterns[i])) return false;
            }
            return true;
        }
        public boolean match(String... patterns) {
            if (peek(patterns)) {
                for (String ignored : patterns) {
                    index++;
                    length++;
                }
                return true;
            }
            return false;
        }
        public String emit() {
            String token = input.substring(index - length, index);
            length = 0;
            return token;
        }
    }
}
