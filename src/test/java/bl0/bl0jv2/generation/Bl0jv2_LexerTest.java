package bl0.bl0jv2.generation;

import bl0.bl0jv2.data.NativeMethods;
import bl0.bl0jv2.exceptions.Bl0j_LexerException;
import bl0.bl0jv2.generation.tokens.EOFToken;
import bl0.bl0jv2.generation.tokens.NativeCallToken;
import bl0.bl0jv2.generation.tokens.OpToken;
import bl0.bl0jv2.generation.tokens.Token;
import bl0.bl0jv2.generation.tokens.blocks.LBracketToken;
import bl0.bl0jv2.generation.tokens.blocks.RBracketToken;
import bl0.bl0jv2.generation.tokens.data.BooleanToken;
import bl0.bl0jv2.generation.tokens.data.IdentityToken;
import bl0.bl0jv2.generation.tokens.data.NilToken;
import bl0.bl0jv2.generation.tokens.data.NumberToken;
import bl0.bl0jv2.generation.tokens.data.StringToken;
import bl0.bl0jv2.generation.tokens.statements.DefToken;
import bl0.bl0jv2.generation.tokens.statements.ElseToken;
import bl0.bl0jv2.generation.tokens.statements.IfToken;
import bl0.bl0jv2.generation.tokens.statements.ReturnToken;
import bl0.bl0jv2.generation.tokens.statements.WhileToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Bl0jv2_LexerTest {

    private final Bl0jv2_Lexer lexer = new Bl0jv2_Lexer();

    @Test
    void emptyInputOnlyProducesEOF() {
        List<Token> tokens = lexer.getTokens("");
        assertEquals(1, tokens.size());
        assertInstanceOf(EOFToken.class, tokens.get(0));
    }

    @Test
    void numberToken() {
        List<Token> tokens = lexer.getTokens("42");
        assertInstanceOf(NumberToken.class, tokens.get(0));
        assertEquals("42", ((NumberToken) tokens.get(0)).value);
    }

    @Test
    void floatLiteralToken() {
        List<Token> tokens = lexer.getTokens("3.14");
        assertInstanceOf(NumberToken.class, tokens.get(0));
        assertEquals("3.14", ((NumberToken) tokens.get(0)).value);
    }

    @Test
    void trailingDotIsNotConsumedAsPartOfTheNumber() {
        // no digit follows the '.', so it must not be swallowed into the
        // number token as a bogus decimal point. The number token itself
        // correctly stops at "42"; the lone '.' is then rejected on its own
        // by the general "unexpected character" path, since '.' isn't a
        // token in its own right (yet)
        assertThrows(Bl0j_LexerException.class, () -> lexer.getTokens("42."));
    }

    @Test
    void stringToken() {
        List<Token> tokens = lexer.getTokens("'hello'");
        assertInstanceOf(StringToken.class, tokens.get(0));
        assertEquals("hello", ((StringToken) tokens.get(0)).value);
    }

    @Test
    void identityToken() {
        List<Token> tokens = lexer.getTokens("myVar");
        assertInstanceOf(IdentityToken.class, tokens.get(0));
        assertEquals("myVar", ((IdentityToken) tokens.get(0)).name);
    }

    @Test
    void booleanKeywords() {
        List<Token> tokens = lexer.getTokens("true false");
        assertInstanceOf(BooleanToken.class, tokens.get(0));
        assertTrue(((BooleanToken) tokens.get(0)).value);
        assertInstanceOf(BooleanToken.class, tokens.get(1));
        assertEquals(false, ((BooleanToken) tokens.get(1)).value);
    }

    @Test
    void nilKeyword() {
        assertInstanceOf(NilToken.class, lexer.getTokens("nil").get(0));
    }

    @Test
    void controlFlowKeywords() {
        List<Token> tokens = lexer.getTokens("if else while def return");
        assertInstanceOf(IfToken.class, tokens.get(0));
        assertInstanceOf(ElseToken.class, tokens.get(1));
        assertInstanceOf(WhileToken.class, tokens.get(2));
        assertInstanceOf(DefToken.class, tokens.get(3));
        assertInstanceOf(ReturnToken.class, tokens.get(4));
    }

    @Test
    void bracketTokens() {
        List<Token> tokens = lexer.getTokens("[]");
        assertInstanceOf(LBracketToken.class, tokens.get(0));
        assertInstanceOf(RBracketToken.class, tokens.get(1));
    }

    @Test
    void nativeCallKeywordsMapToCorrectIds() {
        List<Token> tokens = lexer.getTokens("print println wait");
        assertEquals(NativeMethods.PRINT, ((NativeCallToken) tokens.get(0)).id);
        assertEquals(NativeMethods.PRINT_LN, ((NativeCallToken) tokens.get(1)).id);
        assertEquals(NativeMethods.WAIT, ((NativeCallToken) tokens.get(2)).id);
    }

    @Test
    void twoCharacterOperatorsAreGreedilyMatched() {
        List<Token> tokens = lexer.getTokens("== != <= >= ++ --");
        assertEquals(Operator.EQUALS, ((OpToken) tokens.get(0)).op);
        assertEquals(Operator.NOT_EQUALS, ((OpToken) tokens.get(1)).op);
        assertEquals(Operator.LESS_EQUALS, ((OpToken) tokens.get(2)).op);
        assertEquals(Operator.GREATER_EQUALS, ((OpToken) tokens.get(3)).op);
        assertEquals(Operator.PLUS_PLUS, ((OpToken) tokens.get(4)).op);
        assertEquals(Operator.MINUS_MINUS, ((OpToken) tokens.get(5)).op);
    }

    @Test
    void singleCharacterOperatorsWhenNotDoubled() {
        List<Token> tokens = lexer.getTokens("= ! < > + - *");
        assertEquals(Operator.ASSIGNMENT, ((OpToken) tokens.get(0)).op);
        assertEquals(Operator.NOT, ((OpToken) tokens.get(1)).op);
        assertEquals(Operator.LESS, ((OpToken) tokens.get(2)).op);
        assertEquals(Operator.GREATER, ((OpToken) tokens.get(3)).op);
        assertEquals(Operator.PLUS, ((OpToken) tokens.get(4)).op);
        assertEquals(Operator.MINUS, ((OpToken) tokens.get(5)).op);
        assertEquals(Operator.STAR, ((OpToken) tokens.get(6)).op);
    }

    @Test
    void everyTokenStreamEndsWithEOF() {
        List<Token> tokens = lexer.getTokens("1 + 1");
        assertInstanceOf(EOFToken.class, tokens.get(tokens.size() - 1));
    }

    @Test
    void unexpectedCharacterThrowsLexerException() {
        assertThrows(Bl0j_LexerException.class, () -> lexer.getTokens("@"));
    }
}
