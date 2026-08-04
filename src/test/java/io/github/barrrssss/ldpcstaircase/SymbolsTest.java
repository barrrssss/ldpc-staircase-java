package io.github.barrrssss.ldpcstaircase;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SymbolsTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 17, 248, 818})
    void packedWordsRoundTripEverySymbolByte(int length) {
        byte[] symbol = new SplitMix64(length).nextBytes(length);

        assertArrayEquals(symbol,
                Symbols.fromLongWords(Symbols.toLongWords(symbol), symbol.length));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 17, 248, 818})
    void packedWordXorMatchesByteXor(int length) {
        byte[] left = new SplitMix64(length).nextBytes(length);
        byte[] right = new SplitMix64(~length).nextBytes(length);
        byte[] expected = left.clone();
        Symbols.xorInto(expected, right);

        long[] actualWords = Symbols.toLongWords(left);
        Symbols.xorInto(actualWords, Symbols.toLongWords(right));

        assertArrayEquals(expected, Symbols.fromLongWords(actualWords, length));
    }
}
