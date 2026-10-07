package io.github.madooroy.pinionkaroo

import java.util.UUID

/**
 * Pinion Smart.Shift BLE protocol, as reverse engineered by Tim Angus in
 * https://github.com/timangus/garmin-connectiq-pinion-barrel (Interface.mc, Parameters.mc, *Request.mc).
 *
 * The gearbox exposes one custom service. The current gear is pushed as a notification on its own
 * characteristic; everything else is CANopen SDO style request/response: write a request to
 * [REQUEST_UUID] and the answer arrives as an indication on [RESPONSE_UUID].
 */
object PinionProtocol {
    val SERVICE_UUID: UUID = UUID.fromString("00000000-33d2-4f94-9ee4-9312b3660005")

    /** Notify. Payload: byte 0 = current gear (1 based). */
    val CURRENT_GEAR_UUID: UUID = UUID.fromString("00000001-33d2-4f94-9ee4-9312b3660005")

    /** Write (with response). */
    val REQUEST_UUID: UUID = UUID.fromString("0000000d-33d2-4f94-9ee4-9312b3660005")

    /** Indicate. */
    val RESPONSE_UUID: UUID = UUID.fromString("0000000e-33d2-4f94-9ee4-9312b3660005")

    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MAX_GEARS = 12

    private const val OP_ERROR = 0x00
    private const val OP_READ = 0x01
    private const val OP_REPLY = 0x02

    /**
     * Readable parameters. [address] is the 3 bytes as they go on the wire
     * (CANopen object index, little endian, followed by the sub-index).
     */
    enum class Parameter(val address: ByteArray, val length: Int) {
        SERIAL_NUMBER(byteArrayOf(0x18, 0x10, 0x04), 4),
        CURRENT_GEAR(byteArrayOf(0x01, 0x61, 0x02), 1),

        /** Hundredths of a percent, e.g. 8750 = 87.5% */
        BATTERY_LEVEL(byteArrayOf(0x64, 0x61, 0x01), 2),
        NUMBER_OF_GEARS(byteArrayOf(0x00, 0x25, 0x00), 1),
    }

    /** `[0x01, length, addr0, addr1, addr2]` */
    fun readRequest(parameter: Parameter): ByteArray =
        byteArrayOf(OP_READ.toByte(), parameter.length.toByte()) + parameter.address

    /**
     * Decode a reply to [readRequest]: `[0x02, length, addr0, addr1, addr2, value (little endian)...]`.
     * Returns null for an error reply (`0x00 ...`), a reply to a different parameter, or anything malformed.
     */
    fun parseReadReply(parameter: Parameter, bytes: ByteArray): Long? {
        if (bytes.size < 5 || bytes[0].toInt() != OP_REPLY) return null
        val length = bytes[1].toInt() and 0xFF
        if (length !in intArrayOf(1, 2, 4) || bytes.size < 5 + length) return null
        if (!bytes.copyOfRange(2, 5).contentEquals(parameter.address)) return null
        var value = 0L
        for (i in 0 until length) {
            value = value or ((bytes[5 + i].toLong() and 0xFF) shl (8 * i))
        }
        return value
    }

    fun isErrorReply(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0].toInt() == OP_ERROR

    /** Gear from a [CURRENT_GEAR_UUID] notification, or null if it is outside 1..[gearCount]. */
    fun parseGear(bytes: ByteArray, gearCount: Int = MAX_GEARS): Int? {
        val gear = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return gear.takeIf { it in 1..gearCount }
    }

    fun batteryPercent(raw: Long): Double = (raw / 100.0).coerceIn(0.0, 100.0)

    fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }
}
