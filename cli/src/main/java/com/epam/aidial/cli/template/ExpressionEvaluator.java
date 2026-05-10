package com.epam.aidial.cli.template;

import java.util.Map;

/**
 * Boolean expression evaluator for {@code !if} guards. Supports {@code ==}, {@code !=},
 * {@code &&}, {@code ||}, {@code !} and parenthesised sub-expressions. Operands are either
 * single-quoted literals or {@code ${...}} placeholders. Precedence (loose to tight):
 * {@code ||} → {@code &&} → {@code !} → atom. Short-circuits on {@code &&}/{@code ||}.
 */
final class ExpressionEvaluator {

    private final PlaceholderSubstitutor substitutor;

    ExpressionEvaluator(Map<String, Object> ctx) {
        this.substitutor = new PlaceholderSubstitutor(ctx);
    }

    boolean evaluate(String expr) {
        Parser p = new Parser(expr);
        boolean result = p.parseOr();
        p.skipWhitespace();
        if (!p.eof()) {
            throw new TemplateException("Trailing characters in expression at offset " + p.pos
                    + ": '" + expr + "'");
        }
        return result;
    }

    private final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        boolean parseOr() {
            boolean left = parseAnd();
            while (true) {
                skipWhitespace();
                if (consumeIf("||")) {
                    if (left) {
                        skipAnd();
                    } else {
                        boolean right = parseAnd();
                        left = left || right;
                    }
                } else {
                    return left;
                }
            }
        }

        boolean parseAnd() {
            boolean left = parseNot();
            while (true) {
                skipWhitespace();
                if (consumeIf("&&")) {
                    if (!left) {
                        // Short-circuit: discard right-hand side syntactically.
                        skipNot();
                    } else {
                        boolean right = parseNot();
                        left = left && right;
                    }
                } else {
                    return left;
                }
            }
        }

        boolean parseNot() {
            skipWhitespace();
            if (consumeIf("!") && peek() != '=') {
                return !parseNot();
            }
            return parseAtom();
        }

        boolean parseAtom() {
            skipWhitespace();
            if (consumeIf("(")) {
                boolean v = parseOr();
                skipWhitespace();
                if (!consumeIf(")")) {
                    throw new TemplateException("Missing ')' in expression: " + src);
                }
                return v;
            }
            // Comparison: lhs (==|!=) rhs
            String lhs = readOperand();
            skipWhitespace();
            if (pos + 1 < src.length() && (src.startsWith("==", pos) || src.startsWith("!=", pos))) {
                String op = src.substring(pos, pos + 2);
                pos += 2;
                String rhs = readOperand();
                String l = substitute(lhs);
                String r = substitute(rhs);
                return "==".equals(op) ? l.equals(r) : !l.equals(r);
            }
            // Bare operand → truthiness
            String value = substitute(lhs);
            return isTruthy(value);
        }

        /**
         * Read a single operand: either a quoted literal or an unquoted token (placeholder /
         * identifier / boolean keyword). Stops at whitespace, comparison/logic operator, or ')'.
         */
        String readOperand() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new TemplateException("Unexpected end of expression: " + src);
            }
            char c = src.charAt(pos);
            if (c == '\'' || c == '"') {
                int close = src.indexOf(c, pos + 1);
                if (close < 0) {
                    throw new TemplateException("Unterminated string literal in: " + src);
                }
                String literal = src.substring(pos, close + 1);
                pos = close + 1;
                return literal;
            }
            int start = pos;
            int depth = 0;
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                }
                if (depth == 0) {
                    if (Character.isWhitespace(ch) || ch == ')' || ch == '(') {
                        break;
                    }
                    if (ch == '=' || ch == '!' || ch == '&' || ch == '|') {
                        break;
                    }
                }
                pos++;
            }
            if (pos == start) {
                throw new TemplateException("Expected operand at offset " + pos + " in: " + src);
            }
            return src.substring(start, pos);
        }

        /**
         * Skip an AND-level chain syntactically without resolving placeholders. Used when an
         * OR short-circuits with a true LHS — the entire RHS chain is discarded.
         */
        void skipAnd() {
            skipNot();
            while (true) {
                skipWhitespace();
                if (consumeIf("&&")) {
                    skipNot();
                } else {
                    return;
                }
            }
        }

        /** Skip a single NOT/atom-level operand without resolving placeholders. */
        void skipNot() {
            skipWhitespace();
            if (consumeIf("!") && peek() != '=') {
                skipNot();
                return;
            }
            // skipAtom inline:
            skipWhitespace();
            if (consumeIf("(")) {
                int depth = 1;
                while (pos < src.length() && depth > 0) {
                    char ch = src.charAt(pos++);
                    if (ch == '(') {
                        depth++;
                    } else if (ch == ')') {
                        depth--;
                    }
                }
                return;
            }
            readOperand();
            skipWhitespace();
            if (pos + 1 < src.length() && (src.startsWith("==", pos) || src.startsWith("!=", pos))) {
                pos += 2;
                readOperand();
            }
        }

        void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        boolean eof() {
            return pos >= src.length();
        }

        char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        boolean consumeIf(String token) {
            if (src.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }
    }

    private String substitute(String operand) {
        if (operand == null) {
            return "";
        }
        // Strip enclosing single or double quotes if literal.
        if (operand.length() >= 2
                && (operand.charAt(0) == '\'' || operand.charAt(0) == '"')
                && operand.charAt(operand.length() - 1) == operand.charAt(0)) {
            return operand.substring(1, operand.length() - 1);
        }
        // Boolean keywords.
        if ("true".equals(operand)) {
            return "true";
        }
        if ("false".equals(operand)) {
            return "false";
        }
        if (operand.startsWith("${") && operand.endsWith("}")) {
            return substitutor.substitute(operand);
        }
        // Bareword — treat as a literal so '${vars.x} == foo' still works.
        return operand;
    }

    private static boolean isTruthy(String s) {
        if (s == null) {
            return false;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || s.isEmpty()) {
            return false;
        }
        return true;
    }
}
