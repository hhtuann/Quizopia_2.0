package com.quizopia.identity.application.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import org.junit.jupiter.api.Test;

class DisplayNameTest {
    // "Nguyễn Trần An" with precomposed U+1EC5/U+1EA7 versus its canonically decomposed form.
    private static final String PRECOMPOSED_NAME = "Nguy" + "\u1ec5" + "n Tr" + "\u1ea7" + "n An";
    private static final String DECOMPOSED_NAME = Normalizer.normalize(PRECOMPOSED_NAME, Normalizer.Form.NFD);

    @Test
    void normalizesToNfcAndTrimsSurroundingWhitespace() {
        assertEquals(PRECOMPOSED_NAME, DisplayName.normalize(DECOMPOSED_NAME));
        assertEquals(PRECOMPOSED_NAME, DisplayName.normalize("  " + DECOMPOSED_NAME + "  "));
        assertEquals(PRECOMPOSED_NAME, DisplayName.normalize(PRECOMPOSED_NAME));
    }

    @Test
    void compositionShortensDecomposedInput() {
        assertTrue(DECOMPOSED_NAME.length() > PRECOMPOSED_NAME.length());
        assertEquals(
                PRECOMPOSED_NAME.length(),
                DisplayName.normalize(DECOMPOSED_NAME).length());
        assertEquals(PRECOMPOSED_NAME, DisplayName.normalize(DECOMPOSED_NAME));
    }

    @Test
    void acceptsLengthBoundaries() {
        String supplementary = "\ud83d\ude00".repeat(100);
        assertEquals(supplementary, DisplayName.normalize(supplementary));
        assertThrows(DisplayNameInvalidException.class, () -> DisplayName.normalize(supplementary + "a"));
        assertEquals(
                DisplayName.MAX_LENGTH,
                DisplayName.normalize("n".repeat(DisplayName.MAX_LENGTH)).length());
        String decomposedBound = ("n".repeat(DisplayName.MAX_LENGTH - 2)) + 'e' + '\u0302';
        assertEquals(
                DisplayName.MAX_LENGTH - 1,
                DisplayName.normalize(decomposedBound).length());
    }

    @Test
    void rejectsOverlongValues() {
        assertThrows(
                DisplayNameInvalidException.class, () -> DisplayName.normalize("n".repeat(DisplayName.MAX_LENGTH + 1)));
        assertThrows(
                DisplayNameInvalidException.class,
                () -> DisplayName.normalize("  " + "n".repeat(DisplayName.MAX_LENGTH + 4) + "  "));
    }

    @Test
    void rejectsIsoControlCharacters() {
        for (String value : new String[] {
            "Bad\u0000Name",
            "Bad\u000bName",
            "Bad\u007fName",
            "Bad\u0085Name",
            "Bad\u009fName",
            "Bad\u001cName",
            "Bad\ud800Name",
            "Bad\udc00Name"
        }) {
            assertThrows(DisplayNameInvalidException.class, () -> DisplayName.normalize(value), value);
        }
    }

    @Test
    void rejectsBlankAndNull() {
        for (String value : new String[] {null, "", "   ", "\t", "\r\n"}) {
            assertThrows(DisplayNameInvalidException.class, () -> DisplayName.normalize(value), String.valueOf(value));
        }
    }

    @Test
    void rejectsValuesThatAreOnlyNonAsciiWhitespace() {
        for (String value : new String[] {"\u2009", " \u2009\u2003 ", "\u2009\u2009"}) {
            assertThrows(DisplayNameInvalidException.class, () -> DisplayName.normalize(value), value);
        }
    }

    @Test
    void whitespaceInsideTheNameIsPreserved() {
        assertEquals("Nguy\u1ec5n V\u0103n An", DisplayName.normalize("  Nguy\u1ec5n V\u0103n An  "));
    }
}
