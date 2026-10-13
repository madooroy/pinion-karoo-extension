package io.github.madooroy.pinionkaroo

import io.github.madooroy.pinionkaroo.PinionProtocol.Parameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinionProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun readRequestLayout() {
        assertArrayEquals(bytes(0x01, 0x02, 0x64, 0x61, 0x01), PinionProtocol.readRequest(Parameter.BATTERY_LEVEL))
        assertArrayEquals(bytes(0x01, 0x01, 0x01, 0x61, 0x02), PinionProtocol.readRequest(Parameter.CURRENT_GEAR))
    }

    @Test
    fun readReplyIsLittleEndian() {
        // 0x222e = 8750 -> 87.5%
        val raw = PinionProtocol.parseReadReply(Parameter.BATTERY_LEVEL, bytes(0x02, 0x02, 0x64, 0x61, 0x01, 0x2e, 0x22))
        assertEquals(8750L, raw)
        assertEquals(87.5, PinionProtocol.batteryPercent(raw!!), 0.001)
        assertEquals(0xEFBEADDEL, PinionProtocol.parseReadReply(Parameter.SERIAL_NUMBER, bytes(0x02, 0x04, 0x18, 0x10, 0x04, 0xDE, 0xAD, 0xBE, 0xEF)))
    }

    @Test
    fun readReplyRejectsErrorsAndMismatches() {
        val error = bytes(0x00, 0x02, 0x64, 0x61, 0x01)
        assertTrue(PinionProtocol.isErrorReply(error))
        assertNull(PinionProtocol.parseReadReply(Parameter.BATTERY_LEVEL, error))
        // Reply for a different parameter
        assertNull(PinionProtocol.parseReadReply(Parameter.BATTERY_LEVEL, bytes(0x02, 0x01, 0x01, 0x61, 0x02, 0x05)))
        // Truncated
        assertNull(PinionProtocol.parseReadReply(Parameter.BATTERY_LEVEL, bytes(0x02, 0x02, 0x64, 0x61, 0x01, 0x2e)))
    }

    @Test
    fun readRequestCarriesEachParametersLength() {
        for (parameter in Parameter.entries) {
            val request = PinionProtocol.readRequest(parameter)
            assertArrayEquals(parameter.address, request.copyOfRange(2, 5))
            assertEquals(parameter.length, request[1].toInt())
            assertEquals(0x01, request[0].toInt())
        }
    }

    @Test
    fun readReplyRejectsUnknownLengthsAndRunts() {
        // Length byte 3 is not one of the sizes the protocol uses
        assertNull(PinionProtocol.parseReadReply(Parameter.BATTERY_LEVEL, bytes(0x02, 0x03, 0x64, 0x61, 0x01, 0x01, 0x02, 0x03)))
        assertNull(PinionProtocol.parseReadReply(Parameter.CURRENT_GEAR, bytes(0x02)))
        assertNull(PinionProtocol.parseReadReply(Parameter.CURRENT_GEAR, ByteArray(0)))
        // A good reply with a trailing byte still parses; extra bytes are ignored
        assertEquals(5L, PinionProtocol.parseReadReply(Parameter.CURRENT_GEAR, bytes(0x02, 0x01, 0x01, 0x61, 0x02, 0x05, 0x99)))
    }

    @Test
    fun onlyTheErrorOpcodeCountsAsAnErrorReply() {
        assertFalse(PinionProtocol.isErrorReply(ByteArray(0)))
        assertFalse(PinionProtocol.isErrorReply(bytes(0x02, 0x01, 0x01, 0x61, 0x02, 0x05)))
        assertTrue(PinionProtocol.isErrorReply(bytes(0x00, 0x01, 0x01, 0x61, 0x02)))
    }

    @Test
    fun batteryPercentIsClampedToZeroAndHundred() {
        assertEquals(0.0, PinionProtocol.batteryPercent(-50L), 0.0)
        assertEquals(0.0, PinionProtocol.batteryPercent(0L), 0.0)
        assertEquals(42.0, PinionProtocol.batteryPercent(4200L), 0.0)
        assertEquals(100.0, PinionProtocol.batteryPercent(15000L), 0.0)
    }

    @Test
    fun bytesAreFormattedAsSpaceSeparatedHex() {
        with(PinionProtocol) {
            assertEquals("02 ff 1a", bytes(0x02, 0xff, 0x1a).toHex())
            assertEquals("", ByteArray(0).toHex())
        }
    }

    @Test
    fun gearIsLimitedToGearCount() {
        assertEquals(1, PinionProtocol.parseGear(bytes(1)))
        assertEquals(12, PinionProtocol.parseGear(bytes(12)))
        assertNull(PinionProtocol.parseGear(bytes(0)))
        assertNull(PinionProtocol.parseGear(bytes(13)))
        assertNull(PinionProtocol.parseGear(bytes(10), gearCount = 9))
        assertNull(PinionProtocol.parseGear(ByteArray(0)))
    }
}
