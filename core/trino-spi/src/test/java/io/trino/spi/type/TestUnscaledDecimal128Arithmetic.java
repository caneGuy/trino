/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.spi.type;

import com.google.common.primitives.Bytes;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.testng.annotations.Test;

import java.math.BigInteger;
import java.util.Collections;

import static io.airlift.slice.SizeOf.SIZE_OF_LONG;
import static io.airlift.slice.Slices.wrappedIntArray;
import static io.airlift.slice.Slices.wrappedLongArray;
import static io.trino.spi.type.Decimals.MAX_DECIMAL_UNSCALED_VALUE;
import static io.trino.spi.type.Decimals.MIN_DECIMAL_UNSCALED_VALUE;
import static io.trino.spi.type.Decimals.bigIntegerTenToNth;
import static io.trino.spi.type.Decimals.decodeUnscaledValue;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.add;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.addWithOverflow;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.compare;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.divide;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.isNegative;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.isZero;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.multiply;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.multiply256Destructive;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.overflows;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.rescale;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.shiftLeft;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.shiftLeftDestructive;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.shiftLeftMultiPrecision;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.shiftRight;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.shiftRightMultiPrecision;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.throwIfOverflows;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.toUnscaledString;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimal;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimalToBigInteger;
import static io.trino.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimalToUnscaledLong;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestUnscaledDecimal128Arithmetic
{
    private static final Slice MAX_DECIMAL = unscaledDecimal(MAX_DECIMAL_UNSCALED_VALUE);
    private static final Slice MIN_DECIMAL = unscaledDecimal(MIN_DECIMAL_UNSCALED_VALUE);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    @Test
    public void testUnscaledBigIntegerToDecimal()
    {
        assertConvertsUnscaledBigIntegerToDecimal(MAX_DECIMAL_UNSCALED_VALUE);
        assertConvertsUnscaledBigIntegerToDecimal(MIN_DECIMAL_UNSCALED_VALUE);
        assertConvertsUnscaledBigIntegerToDecimal(BigInteger.ZERO);
        assertConvertsUnscaledBigIntegerToDecimal(BigInteger.ONE);
        assertConvertsUnscaledBigIntegerToDecimal(BigInteger.ONE.negate());
    }

    @Test
    public void testUnscaledBigIntegerToDecimalOverflow()
    {
        assertUnscaledBigIntegerToDecimalOverflows(MAX_DECIMAL_UNSCALED_VALUE.add(BigInteger.ONE));
        assertUnscaledBigIntegerToDecimalOverflows(MAX_DECIMAL_UNSCALED_VALUE.setBit(95));
        assertUnscaledBigIntegerToDecimalOverflows(MAX_DECIMAL_UNSCALED_VALUE.setBit(127));
        assertUnscaledBigIntegerToDecimalOverflows(MIN_DECIMAL_UNSCALED_VALUE.subtract(BigInteger.ONE));
    }

    @Test
    public void testUnscaledLongToDecimal()
    {
        assertConvertsUnscaledLongToDecimal(0);
        assertConvertsUnscaledLongToDecimal(1);
        assertConvertsUnscaledLongToDecimal(-1);
        assertConvertsUnscaledLongToDecimal(Long.MAX_VALUE);
        assertConvertsUnscaledLongToDecimal(Long.MIN_VALUE);
    }

    @Test
    public void testDecimalToUnscaledLongOverflow()
    {
        assertDecimalToUnscaledLongOverflows(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE));
        assertDecimalToUnscaledLongOverflows(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE));
        assertDecimalToUnscaledLongOverflows(MAX_DECIMAL_UNSCALED_VALUE);
        assertDecimalToUnscaledLongOverflows(MIN_DECIMAL_UNSCALED_VALUE);
    }

    @Test
    public void testRescale()
    {
        assertRescale(unscaledDecimal(10), 0, unscaledDecimal(10L));
        assertRescale(unscaledDecimal(-10), 0, unscaledDecimal(-10L));
        assertRescale(unscaledDecimal(10), -20, unscaledDecimal(0L));
        assertRescale(unscaledDecimal(14), -1, unscaledDecimal(1));
        assertRescale(unscaledDecimal(14), -2, unscaledDecimal(0));
        assertRescale(unscaledDecimal(14), -3, unscaledDecimal(0));
        assertRescale(unscaledDecimal(15), -1, unscaledDecimal(2));
        assertRescale(unscaledDecimal(15), -2, unscaledDecimal(0));
        assertRescale(unscaledDecimal(15), -3, unscaledDecimal(0));
        assertRescale(unscaledDecimal(1050), -3, unscaledDecimal(1));
        assertRescale(unscaledDecimal(15), 1, unscaledDecimal(150));
        assertRescale(unscaledDecimal(-14), -1, unscaledDecimal(-1));
        assertRescale(unscaledDecimal(-14), -2, unscaledDecimal(0));
        assertRescale(unscaledDecimal(-14), -20, unscaledDecimal(0));
        assertRescale(unscaledDecimal(-15), -1, unscaledDecimal(-2));
        assertRescale(unscaledDecimal(-15), -2, unscaledDecimal(0));
        assertRescale(unscaledDecimal(-15), -20, unscaledDecimal(0));
        assertRescale(unscaledDecimal(-14), 1, unscaledDecimal(-140));
        assertRescale(unscaledDecimal(0), 1, unscaledDecimal(0));
        assertRescale(unscaledDecimal(0), -1, unscaledDecimal(0));
        assertRescale(unscaledDecimal(0), -20, unscaledDecimal(0));
        assertRescale(unscaledDecimal(4), -1, unscaledDecimal(0));
        assertRescale(unscaledDecimal(5), -1, unscaledDecimal(1));
        assertRescale(unscaledDecimal(5), -2, unscaledDecimal(0));
        assertRescale(unscaledDecimal(10), 10, unscaledDecimal(100000000000L));
        assertRescale(unscaledDecimal("150000000000000000000"), -20, unscaledDecimal(2));
        assertRescale(unscaledDecimal("-140000000000000000000"), -20, unscaledDecimal(-1));
        assertRescale(unscaledDecimal("50000000000000000000"), -20, unscaledDecimal(1));
        assertRescale(unscaledDecimal("150500000000000000000"), -18, unscaledDecimal(151));
        assertRescale(unscaledDecimal("-140000000000000000000"), -18, unscaledDecimal(-140));
        assertRescale(unscaledDecimal(BigInteger.ONE.shiftLeft(63)), -18, unscaledDecimal(9L));
        assertRescale(unscaledDecimal(BigInteger.ONE.shiftLeft(62)), -18, unscaledDecimal(5L));
        assertRescale(unscaledDecimal(BigInteger.ONE.shiftLeft(62)), -19, unscaledDecimal(0L));
        assertRescale(MAX_DECIMAL, -1, unscaledDecimal(MAX_DECIMAL_UNSCALED_VALUE.divide(BigInteger.TEN).add(BigInteger.ONE)));
        assertRescale(MIN_DECIMAL, -10, unscaledDecimal(MIN_DECIMAL_UNSCALED_VALUE.divide(BigInteger.valueOf(10000000000L)).subtract(BigInteger.ONE)));
        assertRescale(unscaledDecimal(1), 37, unscaledDecimal("10000000000000000000000000000000000000"));
        assertRescale(unscaledDecimal(-1), 37, unscaledDecimal("-10000000000000000000000000000000000000"));
        assertRescale(unscaledDecimal("10000000000000000000000000000000000000"), -37, unscaledDecimal(1));
    }

    @Test
    public void testRescaleOverflows()
    {
        assertRescaleOverflows(unscaledDecimal(1), 38);
    }

    @Test
    public void testAdd()
    {
        assertAdd(unscaledDecimal(0), unscaledDecimal(0), unscaledDecimal(0));
        assertAdd(unscaledDecimal(1), unscaledDecimal(0), unscaledDecimal(1));
        assertAdd(unscaledDecimal(1), unscaledDecimal(1), unscaledDecimal(2));
        assertAdd(unscaledDecimal(-1), unscaledDecimal(0), unscaledDecimal(-1));
        assertAdd(unscaledDecimal(-1), unscaledDecimal(-1), unscaledDecimal(-2));
        assertAdd(unscaledDecimal(-1), unscaledDecimal(1), unscaledDecimal(0));
        assertAdd(unscaledDecimal(1), unscaledDecimal(-1), unscaledDecimal(0));
        assertAdd(unscaledDecimal("10000000000000000000000000000000000000"), unscaledDecimal(0), unscaledDecimal("10000000000000000000000000000000000000"));
        assertAdd(unscaledDecimal("10000000000000000000000000000000000000"), unscaledDecimal("10000000000000000000000000000000000000"), unscaledDecimal("20000000000000000000000000000000000000"));
        assertAdd(unscaledDecimal("-10000000000000000000000000000000000000"), unscaledDecimal(0), unscaledDecimal("-10000000000000000000000000000000000000"));
        assertAdd(unscaledDecimal("-10000000000000000000000000000000000000"), unscaledDecimal("-10000000000000000000000000000000000000"), unscaledDecimal("-20000000000000000000000000000000000000"));
        assertAdd(unscaledDecimal("-10000000000000000000000000000000000000"), unscaledDecimal("10000000000000000000000000000000000000"), unscaledDecimal(0));
        assertAdd(unscaledDecimal("10000000000000000000000000000000000000"), unscaledDecimal("-10000000000000000000000000000000000000"), unscaledDecimal(0));

        assertAdd(unscaledDecimal(1L << 32), unscaledDecimal(0), unscaledDecimal(1L << 32));
        assertAdd(unscaledDecimal(1L << 31), unscaledDecimal(1L << 31), unscaledDecimal(1L << 32));
        assertAdd(unscaledDecimal(1L << 32), unscaledDecimal(1L << 33), unscaledDecimal((1L << 32) + (1L << 33)));
    }

    @Test
    public void testThrowIfOverflows()
    {
        Slice decimal = add(unscaledDecimal(MAX_DECIMAL_UNSCALED_VALUE), unscaledDecimal(1));
        assertThatThrownBy(() -> throwIfOverflows(decimal))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Decimal overflow");
        assertThatThrownBy(() -> throwIfOverflows(decimal.getLong(0), decimal.getLong(SIZE_OF_LONG)))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Decimal overflow");
    }

    @Test
    public void testAddReturnOverflow()
    {
        assertAddReturnOverflow(TWO, TWO);
        assertAddReturnOverflow(MAX_DECIMAL_UNSCALED_VALUE, MAX_DECIMAL_UNSCALED_VALUE);
        assertAddReturnOverflow(MAX_DECIMAL_UNSCALED_VALUE.negate(), MAX_DECIMAL_UNSCALED_VALUE);
        assertAddReturnOverflow(MAX_DECIMAL_UNSCALED_VALUE, MAX_DECIMAL_UNSCALED_VALUE.negate());
        assertAddReturnOverflow(MAX_DECIMAL_UNSCALED_VALUE.negate(), MAX_DECIMAL_UNSCALED_VALUE.negate());
    }

    @Test
    public void testMultiply()
    {
        assertMultiply(0, 0, 0);
        assertMultiply(1, 0, 0);
        assertMultiply(0, 1, 0);
        assertMultiply(-1, 0, 0);
        assertMultiply(0, -1, 0);
        assertMultiply(1, 1, 1);
        assertMultiply(1, -1, -1);
        assertMultiply(-1, -1, 1);
        assertMultiply(MAX_DECIMAL_UNSCALED_VALUE, 0, 0);
        assertMultiply(MAX_DECIMAL_UNSCALED_VALUE, 1, MAX_DECIMAL_UNSCALED_VALUE);
        assertMultiply(MIN_DECIMAL_UNSCALED_VALUE, 0, 0);
        assertMultiply(MIN_DECIMAL_UNSCALED_VALUE, 1, MIN_DECIMAL_UNSCALED_VALUE);
        assertMultiply(MAX_DECIMAL_UNSCALED_VALUE, -1, MIN_DECIMAL_UNSCALED_VALUE);
        assertMultiply(MIN_DECIMAL_UNSCALED_VALUE, -1, MAX_DECIMAL_UNSCALED_VALUE);
        assertMultiply(new BigInteger("FFFFFFFFFFFFFFFF", 16), new BigInteger("FFFFFFFFFFFFFF", 16), new BigInteger("fffffffffffffeff00000000000001", 16));
        assertMultiply(new BigInteger("FFFFFF0096BFB800", 16), new BigInteger("39003539D9A51600", 16), new BigInteger("39003500FB00AB761CDBB17E11D00000", 16));
        assertMultiply(Integer.MAX_VALUE, Integer.MIN_VALUE, (long) Integer.MAX_VALUE * Integer.MIN_VALUE);
        assertMultiply(new BigInteger("99999999999999"), new BigInteger("-1000000000000000000000000"), new BigInteger("-99999999999999000000000000000000000000"));
        assertMultiply(new BigInteger("12380837221737387489365741632769922889"), 3, new BigInteger("37142511665212162468097224898309768667"));
    }

    @Test
    public void testMultiply256()
    {
        assertMultiply256(MAX_DECIMAL, MAX_DECIMAL, wrappedLongArray(0xECEBBB8000000001L, 0xE0FF0CA0BC87870BL, 0x0764B4ABE8652978L, 0x161BCCA7119915B5L));
        assertMultiply256(wrappedLongArray(0xFFFFFFFFFFFFFFFFL, 0x0FFFFFFFFFFFFFFFL), wrappedLongArray(0xFFFFFFFFFFFFFFFFL, 0x0FFFFFFFFFFFFFFFL),
                wrappedLongArray(0x0000000000000001L, 0xE000000000000000L, 0xFFFFFFFFFFFFFFFFL, 0x00FFFFFFFFFFFFFFL));
        assertMultiply256(wrappedLongArray(0x1234567890ABCDEFL, 0x0EDCBA0987654321L), wrappedLongArray(0xFEDCBA0987654321L, 0x1234567890ABCDEL),
                wrappedLongArray(0xC24A442FE55618CFL, 0xAA71A60D0DA49DDAL, 0x7C163D5A13DF8695L, 0x0010E8EEF9BD1294L));
    }

    private static void assertMultiply256(Slice left, Slice right, Slice expected)
    {
        int[] leftArg = new int[8];
        leftArg[0] = left.getInt(0);
        leftArg[1] = left.getInt(4);
        leftArg[2] = left.getInt(8);
        leftArg[3] = left.getInt(12);

        multiply256Destructive(leftArg, right);
        assertEquals(wrappedIntArray(leftArg), expected);
    }

    @Test
    public void testMultiplyOverflow()
    {
        assertMultiplyOverflows(unscaledDecimal("99999999999999"), unscaledDecimal("-10000000000000000000000000"));
        assertMultiplyOverflows(MAX_DECIMAL, unscaledDecimal("10"));
    }

    @Test
    public void testShiftRight()
    {
        assertShiftRight(unscaledDecimal(0), 0, true, unscaledDecimal(0));
        assertShiftRight(unscaledDecimal(0), 33, true, unscaledDecimal(0));

        assertShiftRight(unscaledDecimal(1), 1, true, unscaledDecimal(1));
        assertShiftRight(unscaledDecimal(1), 1, false, unscaledDecimal(0));
        assertShiftRight(unscaledDecimal(1), 2, true, unscaledDecimal(0));
        assertShiftRight(unscaledDecimal(1), 2, false, unscaledDecimal(0));
        assertShiftRight(unscaledDecimal(-4), 1, true, unscaledDecimal(-2));
        assertShiftRight(unscaledDecimal(-4), 1, false, unscaledDecimal(-2));
        assertShiftRight(unscaledDecimal(-4), 2, true, unscaledDecimal(-1));
        assertShiftRight(unscaledDecimal(-4), 2, false, unscaledDecimal(-1));
        assertShiftRight(unscaledDecimal(-4), 3, true, unscaledDecimal(-1));
        assertShiftRight(unscaledDecimal(-4), 3, false, unscaledDecimal(0));
        assertShiftRight(unscaledDecimal(-4), 4, true, unscaledDecimal(0));
        assertShiftRight(unscaledDecimal(-4), 4, false, unscaledDecimal(0));

        assertShiftRight(unscaledDecimal(1L << 32), 32, true, unscaledDecimal(1));
        assertShiftRight(unscaledDecimal(1L << 31), 32, true, unscaledDecimal(1));
        assertShiftRight(unscaledDecimal(1L << 31), 32, false, unscaledDecimal(0));
        assertShiftRight(unscaledDecimal(3L << 33), 34, true, unscaledDecimal(2));
        assertShiftRight(unscaledDecimal(3L << 33), 34, false, unscaledDecimal(1));
        assertShiftRight(unscaledDecimal(BigInteger.valueOf(0x7FFFFFFFFFFFFFFFL).setBit(63).setBit(64)), 1, true, unscaledDecimal(BigInteger.ONE.shiftLeft(64)));

        assertShiftRight(MAX_DECIMAL, 1, true, unscaledDecimal(MAX_DECIMAL_UNSCALED_VALUE.shiftRight(1).add(BigInteger.ONE)));
        assertShiftRight(MIN_DECIMAL, 1, true, unscaledDecimal(MAX_DECIMAL_UNSCALED_VALUE.shiftRight(1).add(BigInteger.ONE).negate()));
        assertShiftRight(MAX_DECIMAL, 66, true, unscaledDecimal(MAX_DECIMAL_UNSCALED_VALUE.shiftRight(66).add(BigInteger.ONE)));
    }

    @Test
    public void testDivide()
    {
        // simple cases
        assertDivideAllSigns("0", "10");
        assertDivideAllSigns("5", "10");
        assertDivideAllSigns("50", "100");
        assertDivideAllSigns("99", "10");
        assertDivideAllSigns("95", "10");
        assertDivideAllSigns("91", "10");
        assertDivideAllSigns("1000000000000000000000000", "10");
        assertDivideAllSigns("1000000000000000000000000", "3");
        assertDivideAllSigns("1000000000000000000000000", "9");
        assertDivideAllSigns("1000000000000000000000000", "100000000000000000000000");
        assertDivideAllSigns("1000000000000000000000000", "333333333333333333333333");
        assertDivideAllSigns("1000000000000000000000000", "111111111111111111111111");

        // dividend < divisor
        assertDivideAllSigns(new int[] {4, 3, 2, 0}, new int[] {4, 3, 2, 1});
        assertDivideAllSigns(new int[] {4, 3, 0, 0}, new int[] {4, 3, 2, 0});
        assertDivideAllSigns(new int[] {4, 0, 0, 0}, new int[] {4, 3, 0, 0});
        assertDivideAllSigns(new int[] {0, 0, 0, 0}, new int[] {4, 0, 0, 0});

        // different lengths
        assertDivideAllSigns(new int[] {1423957378, 1765820914, 0xFFFFFFFF, 0}, new int[] {4, 0x0000FFFF, 0, 0});
        assertDivideAllSigns(new int[] {1423957378, 1765820914, 0xFFFFFFFF, 0}, new int[] {2042457708, 0, 0, 0});
        assertDivideAllSigns(new int[] {1423957378, -925263858, 0, 0}, new int[] {2042457708, 0, 0, 0});
        assertDivideAllSigns(new int[] {0xFFFFFFFF, 0, 0, 0}, new int[] {2042457708, 0, 0, 0});

        // single int divisor
        assertDivideAllSigns(new int[] {1423957378, -1444436990, -925263858, 1106345725}, new int[] {2042457708, 0, 0, 0});
        assertDivideAllSigns(new int[] {0, 0xF7000000, 0, 0x39000000}, new int[] {-1765820914, 0, 0, 0});

        // normalization scale = 1
        assertDivideAllSigns(new int[] {0x0FF00210, 0xF7001230, 0xFB00AC00, 0x39003500}, new int[] {-1765820914, 2042457708, 0xFFFFFFFF, 0});
        assertDivideAllSigns(new int[] {0x0FF00210, 0xF7001230, 0xFB00AC00, 0x39003500}, new int[] {-1765820914, 0xFFFFFF00, 0, 0});
        assertDivideAllSigns(new int[] {0x0FF00210, 0xF7001230, 0xFB00AC00, 0x39003500}, new int[] {-1765820914, 0xFF000000, 0, 0});

        // normalization scale > 1
        assertDivideAllSigns(new int[] {0x0FF00210, 0xF7001230, 0xFB00AC00, 0x39003500}, new int[] {-1765820914, 2042457708, 0xFFFFFFFF, 0x7FFFFFFF});
        assertDivideAllSigns(new int[] {0x0FF00210, 0xF7001230, 0xFB00AC00, 0x39003500}, new int[] {-1765820914, 2042457708, 0x4FFFFFFF, 0});
        assertDivideAllSigns(new int[] {0x0FF00210, 0xF7001230, 0xFB00AC00, 0x39003500}, new int[] {-1765820914, 2042457708, 0x0000FFFF, 0});

        // normalization scale signed overflow
        assertDivideAllSigns(new int[] {1, 1, 1, 0x7FFFFFFF}, new int[] {0xFFFFFFFF, 1, 0, 0});

        // u2 = v1
        assertDivideAllSigns(new int[] {0, 0x8FFFFFFF, 0x8FFFFFFF, 0}, new int[] {0xFFFFFFFF, 0x8FFFFFFF, 0, 0});

        // qhat is greater than q by 1
        assertDivideAllSigns(new int[] {1, 1, 0xFFFFFFFF, 0}, new int[] {0xFFFFFFFF, 0x7FFFFFFF, 0, 0});

        // qhat is greater than q by 2
        assertDivideAllSigns(new int[] {1, 1, 0xFFFFFFFF, 0}, new int[] {0xFFFFFFFF, 0x7FFFFFFF, 0, 0});

        // overflow after multiplyAndSubtract
        assertDivideAllSigns(new int[] {0x00000003, 0x00000000, 0x80000000, 0}, new int[] {0x00000001, 0x00000000, 0x20000000, 0});
        assertDivideAllSigns(new int[] {0x00000003, 0x00000000, 0x00008000, 0}, new int[] {0x00000001, 0x00000000, 0x00002000, 0});
        assertDivideAllSigns(new int[] {0, 0, 0x00008000, 0x00007fff}, new int[] {1, 0, 0x00008000, 0});

        // test cases from http://www.hackersdelight.org/hdcodetxt/divmnu64.c.txt
        // license: http://www.hackersdelight.org/permissions.htm
        assertDivideAllSigns(new int[] {3, 0, 0, 0}, new int[] {2, 0, 0, 0});
        assertDivideAllSigns(new int[] {3, 0, 0, 0}, new int[] {3, 0, 0, 0});
        assertDivideAllSigns(new int[] {3, 0, 0, 0}, new int[] {4, 0, 0, 0});
        assertDivideAllSigns(new int[] {3, 0, 0, 0}, new int[] {0xffffffff, 0, 0, 0});
        assertDivideAllSigns(new int[] {0xffffffff, 0, 0, 0}, new int[] {1, 0, 0, 0});
        assertDivideAllSigns(new int[] {0xffffffff, 0, 0, 0}, new int[] {0xffffffff, 0, 0, 0});
        assertDivideAllSigns(new int[] {0xffffffff, 0, 0, 0}, new int[] {3, 0, 0, 0});
        assertDivideAllSigns(new int[] {0xffffffff, 0xffffffff, 0, 0}, new int[] {1, 0, 0, 0});
        assertDivideAllSigns(new int[] {0xffffffff, 0xffffffff, 0, 0}, new int[] {0xffffffff, 0, 0, 0});
        assertDivideAllSigns(new int[] {0xffffffff, 0xfffffffe, 0, 0}, new int[] {0xffffffff, 0, 0, 0});
        assertDivideAllSigns(new int[] {0x00005678, 0x00001234, 0, 0}, new int[] {0x00009abc, 0, 0, 0});
        assertDivideAllSigns(new int[] {0, 0, 0, 0}, new int[] {0, 1, 0, 0});
        assertDivideAllSigns(new int[] {0, 7, 0, 0}, new int[] {0, 3, 0, 0});
        assertDivideAllSigns(new int[] {5, 7, 0, 0}, new int[] {0, 3, 0, 0});
        assertDivideAllSigns(new int[] {0, 6, 0, 0}, new int[] {0, 2, 0, 0});
        assertDivideAllSigns(new int[] {0x80000000, 0, 0, 0}, new int[] {0x40000001, 0, 0, 0});
        assertDivideAllSigns(new int[] {0x00000000, 0x80000000, 0, 0}, new int[] {0x40000001, 0, 0, 0});
        assertDivideAllSigns(new int[] {0x00000000, 0x80000000, 0, 0}, new int[] {0x00000001, 0x40000000, 0, 0});
        assertDivideAllSigns(new int[] {0x0000789a, 0x0000bcde, 0, 0}, new int[] {0x0000789a, 0x0000bcde, 0, 0});
        assertDivideAllSigns(new int[] {0x0000789b, 0x0000bcde, 0, 0}, new int[] {0x0000789a, 0x0000bcde, 0, 0});
        assertDivideAllSigns(new int[] {0x00007899, 0x0000bcde, 0, 0}, new int[] {0x0000789a, 0x0000bcde, 0, 0});
        assertDivideAllSigns(new int[] {0x0000ffff, 0x0000ffff, 0, 0}, new int[] {0x0000ffff, 0x0000ffff, 0, 0});
        assertDivideAllSigns(new int[] {0x0000ffff, 0x0000ffff, 0, 0}, new int[] {0x00000000, 0x0000ffff, 0, 0});
        assertDivideAllSigns(new int[] {0x000089ab, 0x00004567, 0x00000123, 0}, new int[] {0x00000000, 0x00000001, 0, 0});
        assertDivideAllSigns(new int[] {0x000089ab, 0x00004567, 0x00000123, 0}, new int[] {0x00000000, 0x00000001, 0, 0});
        assertDivideAllSigns(new int[] {0x00000000, 0x0000fffe, 0x00008000, 0}, new int[] {0x0000ffff, 0x00008000, 0, 0});
        assertDivideAllSigns(new int[] {0x00000003, 0x00000000, 0x80000000, 0}, new int[] {0x00000001, 0x00000000, 0x20000000, 0});
        assertDivideAllSigns(new int[] {0x00000003, 0x00000000, 0x00008000, 0}, new int[] {0x00000001, 0x00000000, 0x00002000, 0});
        assertDivideAllSigns(new int[] {0, 0, 0x00008000, 0x00007fff}, new int[] {1, 0, 0x00008000, 0});
        assertDivideAllSigns(new int[] {0, 0x0000fffe, 0, 0x00008000}, new int[] {0x0000ffff, 0, 0x00008000, 0});
        assertDivideAllSigns(new int[] {0, 0xfffffffe, 0, 0x80000000}, new int[] {0x0000ffff, 0, 0x80000000, 0});
        assertDivideAllSigns(new int[] {0, 0xfffffffe, 0, 0x80000000}, new int[] {0xffffffff, 0, 0x80000000, 0});

        // with rescale
        assertDivideAllSigns("100000000000000000000000", 10, "111111111111111111111111", 10);
        assertDivideAllSigns("100000000000000000000000", 10, "111111111111", 22);
        assertDivideAllSigns("99999999999999999999999999999999999999", 37, "99999999999999999999999999999999999999", 37);
        assertDivideAllSigns("99999999999999999999999999999999999999", 2, "99999999999999999999999999999999999999", 1);
        assertDivideAllSigns("99999999999999999999999999999999999999", 37, "9", 37);
        assertDivideAllSigns("99999999999999999999999999999999999999", 37, "1", 37);
        assertDivideAllSigns("11111111111111111111111111111111111111", 37, "2", 37);
        assertDivideAllSigns("11111111111111111111111111111111111111", 37, "2", 1);
        assertDivideAllSigns("97764425639372288753711864842425458618", 36, "32039006229599111733094986468789901155", 0);
        assertDivideAllSigns("34354576602352622842481633786816220283", 0, "31137583115118564930544829855652258045", 0);
        assertDivideAllSigns("96690614752287690630596513604374991473", 0, "10039352042372909488692220528497751229", 0);
        assertDivideAllSigns("87568357716090115374029040878755891076", 0, "46106713604991337798209343815577148589", 0);
    }

    @Test
    public void testOverflows()
    {
        assertTrue(overflows(unscaledDecimal("100"), 2));
        assertTrue(overflows(unscaledDecimal("-100"), 2));
        assertFalse(overflows(unscaledDecimal("99"), 2));
        assertFalse(overflows(unscaledDecimal("-99"), 2));
    }

    @Test
    public void testCompare()
    {
        assertCompare(unscaledDecimal(0), unscaledDecimal(0), 0);

        assertCompare(unscaledDecimal(0), unscaledDecimal(10), -1);
        assertCompare(unscaledDecimal(10), unscaledDecimal(0), 1);

        assertCompare(unscaledDecimal(-10), unscaledDecimal(-11), 1);
        assertCompare(unscaledDecimal(-11), unscaledDecimal(-11), 0);
        assertCompare(unscaledDecimal(-12), unscaledDecimal(-11), -1);

        assertCompare(unscaledDecimal(10), unscaledDecimal(11), -1);
        assertCompare(unscaledDecimal(11), unscaledDecimal(11), 0);
        assertCompare(unscaledDecimal(12), unscaledDecimal(11), 1);
    }

    @Test
    public void testNegate()
    {
        assertEquals(negate(negate(MIN_DECIMAL)), MIN_DECIMAL);
        assertEquals(negate(MIN_DECIMAL), MAX_DECIMAL);
        assertEquals(negate(MIN_DECIMAL), MAX_DECIMAL);

        assertEquals(negate(unscaledDecimal(1)), unscaledDecimal(-1));
        assertEquals(negate(unscaledDecimal(-1)), unscaledDecimal(1));
    }

    @Test
    public void testIsNegative()
    {
        assertEquals(isNegative(MIN_DECIMAL), true);
        assertEquals(isNegative(MAX_DECIMAL), false);
        assertEquals(isNegative(unscaledDecimal(0)), false);
    }

    @Test
    public void testToString()
    {
        assertEquals(toUnscaledString(unscaledDecimal(0)), "0");
        assertEquals(toUnscaledString(unscaledDecimal(1)), "1");
        assertEquals(toUnscaledString(unscaledDecimal(-1)), "-1");
        assertEquals(toUnscaledString(unscaledDecimal(MAX_DECIMAL)), MAX_DECIMAL_UNSCALED_VALUE.toString());
        assertEquals(toUnscaledString(unscaledDecimal(MIN_DECIMAL)), MIN_DECIMAL_UNSCALED_VALUE.toString());
        assertEquals(toUnscaledString(unscaledDecimal("1000000000000000000000000000000000000")), "1000000000000000000000000000000000000");
        assertEquals(toUnscaledString(unscaledDecimal("-1000000000002000000000000300000000000")), "-1000000000002000000000000300000000000");
    }

    @Test
    public void testShiftLeftMultiPrecision()
    {
        assertEquals(shiftLeftMultiPrecision(
                        new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000}, 4, 0),
                new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                        0b11111111000000011010101010101011, 0b00000000000000000000000000000000});
        assertEquals(shiftLeftMultiPrecision(
                        new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000}, 5, 1),
                new int[] {0b01000010100010110100001010001010, 0b10101101001011010110101010101011, 0b10100101111100011111000101010100,
                        0b11111110000000110101010101010110, 0b00000000000000000000000000000001});
        assertEquals(shiftLeftMultiPrecision(
                        new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000}, 5, 31),
                new int[] {0b10000000000000000000000000000000, 0b11010000101000101101000010100010, 0b00101011010010110101101010101010,
                        0b10101001011111000111110001010101, 0b1111111100000001101010101010101});
        assertEquals(shiftLeftMultiPrecision(
                        new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000}, 5, 32),
                new int[] {0b00000000000000000000000000000000, 0b10100001010001011010000101000101, 0b01010110100101101011010101010101,
                        0b01010010111110001111100010101010, 0b11111111000000011010101010101011});
        assertEquals(shiftLeftMultiPrecision(
                        new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000, 0b00000000000000000000000000000000}, 6, 33),
                new int[] {0b00000000000000000000000000000000, 0b01000010100010110100001010001010, 0b10101101001011010110101010101011,
                        0b10100101111100011111000101010100, 0b11111110000000110101010101010110, 0b00000000000000000000000000000001});
        assertEquals(shiftLeftMultiPrecision(
                        new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000, 0b00000000000000000000000000000000}, 6, 37),
                new int[] {0b00000000000000000000000000000000, 0b00101000101101000010100010100000, 0b11010010110101101010101010110100,
                        0b01011111000111110001010101001010, 0b11100000001101010101010101101010, 0b00000000000000000000000000011111});
        assertEquals(shiftLeftMultiPrecision(
                        new int[] {0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000, 0b00000000000000000000000000000000}, 6, 64),
                new int[] {0b00000000000000000000000000000000, 0b00000000000000000000000000000000, 0b10100001010001011010000101000101,
                        0b01010110100101101011010101010101, 0b01010010111110001111100010101010, 0b11111111000000011010101010101011});
    }

    @Test
    public void testShiftRightMultiPrecision()
    {
        assertEquals(shiftRightMultiPrecision(
                        new int[] {
                                0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                                0b11111111000000011010101010101011, 0b00000000000000000000000000000000}, 4, 0),
                new int[] {
                        0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                        0b11111111000000011010101010101011, 0b00000000000000000000000000000000});
        assertEquals(shiftRightMultiPrecision(
                        new int[] {
                                0b00000000000000000000000000000000, 0b10100001010001011010000101000101, 0b01010110100101101011010101010101,
                                0b01010010111110001111100010101010, 0b11111111000000011010101010101011}, 5, 1),
                new int[] {
                        0b10000000000000000000000000000000, 0b11010000101000101101000010100010, 0b00101011010010110101101010101010,
                        0b10101001011111000111110001010101, 0b1111111100000001101010101010101});
        assertEquals(shiftRightMultiPrecision(
                        new int[] {
                                0b00000000000000000000000000000000, 0b10100001010001011010000101000101, 0b01010110100101101011010101010101,
                                0b01010010111110001111100010101010, 0b11111111000000011010101010101011}, 5, 32),
                new int[] {
                        0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                        0b11111111000000011010101010101011, 0b00000000000000000000000000000000});
        assertEquals(shiftRightMultiPrecision(
                        new int[] {
                                0b00000000000000000000000000000000, 0b00000000000000000000000000000000, 0b10100001010001011010000101000101,
                                0b01010110100101101011010101010101, 0b01010010111110001111100010101010, 0b11111111000000011010101010101011}, 6, 33),
                new int[] {
                        0b10000000000000000000000000000000, 0b11010000101000101101000010100010, 0b00101011010010110101101010101010,
                        0b10101001011111000111110001010101, 0b01111111100000001101010101010101, 0b00000000000000000000000000000000});
        assertEquals(shiftRightMultiPrecision(
                        new int[] {
                                0b00000000000000000000000000000000, 0b00000000000000000000000000000000, 0b10100001010001011010000101000101,
                                0b01010110100101101011010101010101, 0b01010010111110001111100010101010, 0b11111111000000011010101010101011}, 6, 37),
                new int[] {
                        0b00101000000000000000000000000000, 0b10101101000010100010110100001010, 0b01010010101101001011010110101010,
                        0b01011010100101111100011111000101, 0b00000111111110000000110101010101, 0b00000000000000000000000000000000});
        assertEquals(shiftRightMultiPrecision(
                        new int[] {
                                0b00000000000000000000000000000000, 0b00000000000000000000000000000000, 0b10100001010001011010000101000101,
                                0b01010110100101101011010101010101, 0b01010010111110001111100010101010, 0b11111111000000011010101010101011}, 6, 64),
                new int[] {
                        0b10100001010001011010000101000101, 0b01010110100101101011010101010101, 0b01010010111110001111100010101010,
                        0b11111111000000011010101010101011, 0b00000000000000000000000000000000, 0b00000000000000000000000000000000});
    }

    @Test
    public void testShiftLeftCompareToBigInteger()
    {
        assertShiftLeft(new BigInteger("446319580078125"), 19);

        assertShiftLeft(TWO.pow(1), 10);
        assertShiftLeft(TWO.pow(5).add(TWO.pow(1)), 10);
        assertShiftLeft(TWO.pow(1), 100);
        assertShiftLeft(TWO.pow(5).add(TWO.pow(1)), 100);

        assertShiftLeft(TWO.pow(70), 30);
        assertShiftLeft(TWO.pow(70).add(TWO.pow(1)), 30);

        assertShiftLeft(TWO.pow(106), 20);
        assertShiftLeft(TWO.pow(106).add(TWO.pow(1)), 20);

        assertShiftLeftOverflow(TWO.pow(2), 127);
        assertShiftLeftOverflow(TWO.pow(64), 64);
        assertShiftLeftOverflow(TWO.pow(100), 28);
    }

    @Test
    public void testShiftLeft()
    {
        assertEquals(shiftLeft(wrappedLongArray(0x1234567890ABCDEFL, 0xEFDCBA0987654321L), 0), wrappedLongArray(0x1234567890ABCDEFL, 0xEFDCBA0987654321L));
        assertEquals(shiftLeft(wrappedLongArray(0x1234567890ABCDEFL, 0xEFDCBA0987654321L), 1), wrappedLongArray(0x2468ACF121579BDEL, 0xDFB974130ECA8642L));
        assertEquals(shiftLeft(wrappedLongArray(0x1234567890ABCDEFL, 0x00DCBA0987654321L), 8), wrappedLongArray(0x34567890ABCDEF00L, 0xDCBA098765432112L));
        assertEquals(shiftLeft(wrappedLongArray(0x1234567890ABCDEFL, 0x0000BA0987654321L), 16), wrappedLongArray(0x567890ABCDEF0000L, 0xBA09876543211234L));
        assertEquals(shiftLeft(wrappedLongArray(0x1234567890ABCDEFL, 0x0000000087654321L), 32), wrappedLongArray(0x90ABCDEF00000000L, 0x8765432112345678L));
        assertEquals(shiftLeft(wrappedLongArray(0x1234567890ABCDEFL, 0L), 64), wrappedLongArray(0x0000000000000000L, 0x1234567890ABCDEFL));
        assertEquals(shiftLeft(wrappedLongArray(0x0034567890ABCDEFL, 0L), 64 + 8), wrappedLongArray(0x0000000000000000L, 0x34567890ABCDEF00L));
        assertEquals(shiftLeft(wrappedLongArray(0x000000000000CDEFL, 0L), 64 + 48), wrappedLongArray(0x0000000000000000L, 0xCDEF000000000000L));
        assertEquals(shiftLeft(wrappedLongArray(0x1L, 0L), 64 + 63), wrappedLongArray(0x0000000000000000L, 0x8000000000000000L));
    }

    private void assertAdd(Slice left, Slice right, Slice result)
    {
        assertEquals(add(left, right), result);

        // test with array based version of the method
        long[] resultArray = new long[2];
        add(
                left.getLong(0),
                left.getLong(SIZE_OF_LONG),
                right.getLong(0),
                right.getLong(SIZE_OF_LONG),
                resultArray,
                0);
        assertEquals(unscaledDecimalToBigInteger(resultArray[0], resultArray[1]), unscaledDecimalToBigInteger(result));
    }

    private void assertAddReturnOverflow(BigInteger left, BigInteger right)
    {
        Slice result = unscaledDecimal();
        Slice leftSlice = unscaledDecimal(left);
        Slice rightSlice = unscaledDecimal(right);
        long overflow = addWithOverflow(leftSlice, rightSlice, result);

        BigInteger actual = unscaledDecimalToBigInteger(result);
        BigInteger expected = left.add(right).remainder(TWO.pow(UnscaledDecimal128Arithmetic.UNSCALED_DECIMAL_128_SLICE_LENGTH * 8 - 1));
        BigInteger expectedOverflow = left.add(right).divide(TWO.pow(UnscaledDecimal128Arithmetic.UNSCALED_DECIMAL_128_SLICE_LENGTH * 8 - 1));

        assertEquals(actual, expected);
        assertEquals(overflow, expectedOverflow.longValueExact());

        // test with array based version of the method
        long[] resultArray = new long[2];
        overflow = addWithOverflow(leftSlice.getLong(0), leftSlice.getLong(SIZE_OF_LONG), rightSlice.getLong(0), rightSlice.getLong(SIZE_OF_LONG), resultArray, 0);

        assertEquals(unscaledDecimalToBigInteger(resultArray[0], resultArray[1]), expected);
        assertEquals(overflow, expectedOverflow.longValueExact());
    }

    private static void assertUnscaledBigIntegerToDecimalOverflows(BigInteger value)
    {
        assertThatThrownBy(() -> unscaledDecimal(value))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Decimal overflow");
    }

    private static void assertDecimalToUnscaledLongOverflows(BigInteger value)
    {
        Slice decimal = unscaledDecimal(value);
        assertThatThrownBy(() -> unscaledDecimalToUnscaledLong(decimal))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Decimal overflow");
    }

    private static void assertMultiplyOverflows(Slice left, Slice right)
    {
        assertThatThrownBy(() -> multiply(left, right))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Decimal overflow");
    }

    private static void assertRescaleOverflows(Slice decimal, int rescaleFactor)
    {
        assertThatThrownBy(() -> rescale(decimal, rescaleFactor))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Decimal overflow");
    }

    private static void assertCompare(Slice left, Slice right, int expectedResult)
    {
        assertEquals(compare(left, right), expectedResult);
        assertEquals(compare(left.getLong(0), left.getLong(SIZE_OF_LONG), right.getLong(0), right.getLong(SIZE_OF_LONG)), expectedResult);
    }

    private static void assertConvertsUnscaledBigIntegerToDecimal(BigInteger value)
    {
        assertEquals(unscaledDecimalToBigInteger(unscaledDecimal(value)), value);
    }

    private static void assertConvertsUnscaledLongToDecimal(long value)
    {
        assertEquals(unscaledDecimalToUnscaledLong(unscaledDecimal(value)), value);
        assertEquals(unscaledDecimal(value), unscaledDecimal(BigInteger.valueOf(value)));
    }

    private static void assertShiftRight(Slice decimal, int rightShifts, boolean roundUp, Slice expectedResult)
    {
        Slice result = unscaledDecimal();
        shiftRight(decimal, rightShifts, roundUp, result);
        assertEquals(result, expectedResult);
    }

    private static void assertDivideAllSigns(int[] dividend, int[] divisor)
    {
        assertDivideAllSigns(Slices.wrappedIntArray(dividend), 0, Slices.wrappedIntArray(divisor), 0);
    }

    private void assertShiftLeftOverflow(BigInteger value, int leftShifts)
    {
        assertThatThrownBy(() -> assertShiftLeft(value, leftShifts))
                .isInstanceOf(ArithmeticException.class)
                .hasMessage("Decimal overflow");
    }

    private void assertShiftLeft(BigInteger value, int leftShifts)
    {
        Slice decimal = unscaledDecimal(value);
        BigInteger expectedResult = value.multiply(TWO.pow(leftShifts));
        shiftLeftDestructive(decimal, leftShifts);
        assertEquals(decodeUnscaledValue(decimal), expectedResult);
    }

    private static void assertDivideAllSigns(String dividend, String divisor)
    {
        assertDivideAllSigns(dividend, 0, divisor, 0);
    }

    private static void assertDivideAllSigns(String dividend, int dividendRescaleFactor, String divisor, int divisorRescaleFactor)
    {
        assertDivideAllSigns(unscaledDecimal(dividend), dividendRescaleFactor, unscaledDecimal(divisor), divisorRescaleFactor);
    }

    private static void assertDivideAllSigns(Slice dividend, int dividendRescaleFactor, Slice divisor, int divisorRescaleFactor)
    {
        assertDivide(dividend, dividendRescaleFactor, divisor, divisorRescaleFactor);
        if (!isZero(divisor)) {
            assertDivide(dividend, dividendRescaleFactor, negate(divisor), divisorRescaleFactor);
        }
        if (!isZero(dividend)) {
            assertDivide(negate(dividend), dividendRescaleFactor, divisor, divisorRescaleFactor);
        }
        if (!isZero(dividend) && !isZero(divisor)) {
            assertDivide(negate(dividend), dividendRescaleFactor, negate(divisor), divisorRescaleFactor);
        }
    }

    private static void assertDivide(Slice dividend, int dividendRescaleFactor, Slice divisor, int divisorRescaleFactor)
    {
        BigInteger dividendBigInteger = decodeUnscaledValue(dividend);
        BigInteger divisorBigInteger = decodeUnscaledValue(divisor);
        BigInteger rescaledDividend = dividendBigInteger.multiply(bigIntegerTenToNth(dividendRescaleFactor));
        BigInteger rescaledDivisor = divisorBigInteger.multiply(bigIntegerTenToNth(divisorRescaleFactor));
        BigInteger[] expectedQuotientAndRemainder = rescaledDividend.divideAndRemainder(rescaledDivisor);
        BigInteger expectedQuotient = expectedQuotientAndRemainder[0];
        BigInteger expectedRemainder = expectedQuotientAndRemainder[1];

        boolean overflowIsExpected = expectedQuotient.abs().compareTo(bigIntegerTenToNth(38)) >= 0 || expectedRemainder.abs().compareTo(bigIntegerTenToNth(38)) >= 0;

        Slice quotient = unscaledDecimal();
        Slice remainder = unscaledDecimal();
        try {
            divide(dividend, dividendRescaleFactor, divisor, divisorRescaleFactor, quotient, remainder);
            if (overflowIsExpected) {
                fail("overflow is expected");
            }
        }
        catch (ArithmeticException e) {
            if (!overflowIsExpected) {
                fail("overflow wasn't expected");
            }
            else {
                return;
            }
        }

        BigInteger actualQuotient = decodeUnscaledValue(quotient);
        BigInteger actualRemainder = decodeUnscaledValue(remainder);

        if (expectedQuotient.equals(actualQuotient) && expectedRemainder.equals(actualRemainder)) {
            return;
        }

        fail(format("%s / %s ([%s * 2^%d] / [%s * 2^%d]) Expected: %s(%s). Actual: %s(%s)",
                rescaledDividend, rescaledDivisor,
                dividendBigInteger, dividendRescaleFactor,
                divisorBigInteger, divisorRescaleFactor,
                expectedQuotient, expectedRemainder,
                actualQuotient, actualRemainder));
    }

    private static Slice negate(Slice slice)
    {
        Slice copy = unscaledDecimal(slice);
        UnscaledDecimal128Arithmetic.negate(copy);
        return copy;
    }

    private static int[] toInt8Array(BigInteger value)
    {
        byte[] bigIntegerBytes = value.toByteArray();
        Collections.reverse(Bytes.asList(bigIntegerBytes));

        byte[] bytes = new byte[8 * 4 + 1];
        System.arraycopy(bigIntegerBytes, 0, bytes, 0, bigIntegerBytes.length);
        return toInt8Array(bytes);
    }

    private static int[] toInt8Array(byte[] bytes)
    {
        Slice slice = Slices.wrappedBuffer(bytes);

        int[] ints = new int[8];
        for (int i = 0; i < ints.length; i++) {
            ints[i] = slice.getInt(i * Integer.SIZE / Byte.SIZE);
        }
        return ints;
    }

    private static boolean isShort(BigInteger value)
    {
        return value.abs().shiftRight(63).equals(BigInteger.ZERO);
    }

    private static void assertMultiply(BigInteger a, long b, BigInteger result)
    {
        assertMultiply(a, BigInteger.valueOf(b), result);
    }

    private static void assertMultiply(BigInteger a, long b, long result)
    {
        assertMultiply(a, BigInteger.valueOf(b), BigInteger.valueOf(result));
    }

    private static void assertMultiply(long a, long b, BigInteger result)
    {
        assertMultiply(BigInteger.valueOf(a), b, result);
    }

    private static void assertMultiply(long a, long b, long result)
    {
        assertMultiply(a, b, BigInteger.valueOf(result));
    }

    private static void assertMultiply(BigInteger a, BigInteger b, BigInteger result)
    {
        assertEquals(unscaledDecimal(result), multiply(unscaledDecimal(a), unscaledDecimal(b)));
        if (isShort(a) && isShort(b)) {
            assertEquals(unscaledDecimal(result), multiply(a.longValue(), b.longValue()));
        }
        if (isShort(a) && !isShort(b)) {
            assertEquals(unscaledDecimal(result), multiply(unscaledDecimal(b), a.longValue()));
        }
        if (!isShort(a) && isShort(b)) {
            assertEquals(unscaledDecimal(result), multiply(unscaledDecimal(a), b.longValue()));
        }
    }

    private static void assertRescale(Slice decimal, int rescale, Slice expected)
    {
        assertEquals(rescale(decimal, rescale), expected);
        if (isShort(unscaledDecimalToBigInteger(decimal))) {
            Slice actual = rescale(unscaledDecimalToUnscaledLong(decimal), rescale);
            assertEquals(expected, actual);
        }
    }
}
