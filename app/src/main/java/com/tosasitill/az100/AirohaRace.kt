package com.tosasitill.az100

/**
 * Airoha SPP framing, reimplemented from the wire format of Audio Connect
 * 4.4.0 (`com.airoha.liblinker.transport.H4Transport`,
 * `com.airoha.libbase.RaceCommand.packet.RacePacket`).
 *
 * Over the RFCOMM socket the headset speaks "H4": a one byte channel, one byte
 * packet type, then a length.  Three channel values ever appear on this link:
 *
 * ```
 *   2  ACL          04 0f <len LE16> <body>
 *   4  HCI event    <event> <len+3> ...
 *   5  RACE         05 <type> <len LE16> <race id LE16> <payload>
 * ```
 *
 * The RACE length counts the 2 id bytes plus the payload, so a full frame is
 * `4 + len` bytes; that is the only non-obvious part of the format (it is what
 * `H4Transport.parseRace` implements).
 *
 * MMI commands are RACE frames with type `0x5A` (90, "needs response"); the
 * headset answers with `0x5B` (91, response) and pushes state changes as `0x5D`
 * (93, indication).
 */
object AirohaRace {

    /** RACE packet types (`com.airoha.libbase.RaceCommand.constant.RaceType`). */
    const val TYPE_CMD = 0x5A   // 90, expects an answer
    const val TYPE_RESP = 0x5B  // 91, answer to a command
    const val TYPE_IND = 0x5D   // 93, unsolicited notification

    /** Race ids used by the module (RaceIdPana, RaceId). */
    const val RID_GET_OUTSIDE = 10      // RACE_CUSTOMER_GET_OUTSIDE_CTRL
    const val RID_SET_OUTSIDE = 11      // RACE_BLUETOOTH_GET_BOX_BATTERY id reused by pana set
    const val RID_GET_CRADLE = 64       // RACE_CUSTOMER_GET_CRADLE_BATTERY
    const val RID_GET_ADAPTIVE = 103    // RACE_CUSTOMER_GET_ADAPTIVE_ANC
    const val RID_SET_ADAPTIVE = 104    // RACE_CUSTOMER_SET_ADAPTIVE_ANC
    const val RID_GET_AGENT = 3284      // RACE_BLUETOOTH_IS_AGENT_RIGHT_DEVICE
    const val RID_GET_BATTERY = 3286    // RACE_BLUETOOTH_TWS_GET_BATTERY

    /**
     * Build one MMI command frame.  Exactly what `RacePacket.getRaw()` produces
     * for a type 90 packet: `05 5A <len LE16> <id LE16> <payload>` with
     * `len = 2 + payload.size`.
     */
    fun command(raceId: Int, payload: ByteArray?): ByteArray {
        val length = 2 + (payload?.size ?: 0)
        val frame = ByteArray(4 + length)
        frame[0] = 5
        frame[1] = TYPE_CMD.toByte()
        frame[2] = (length and 0xff).toByte()
        frame[3] = ((length shr 8) and 0xff).toByte()
        frame[4] = (raceId and 0xff).toByte()
        frame[5] = ((raceId shr 8) and 0xff).toByte()
        if (payload != null) payload.copyInto(frame, 6)
        return frame
    }

    fun u16le(data: ByteArray, index: Int): Int =
        (data[index].toInt() and 0xff) or ((data[index + 1].toInt() and 0xff) shl 8)

    fun hex(data: ByteArray?): String {
        if (data == null) return "null"
        val builder = StringBuilder(data.size * 3)
        for (value in data) {
            if (builder.isNotEmpty()) builder.append(' ')
            val v = value.toInt() and 0xff
            builder.append(Character.forDigit(v shr 4, 16))
                .append(Character.forDigit(v and 0xf, 16))
        }
        return builder.toString()
    }

    /** One parsed H4 packet as it came off the socket. */
    class Frame(val raw: ByteArray) {

        /** Channel byte: 5/21/7 are RACE, 2 ACL, 4 HCI event. */
        val isRace: Boolean
            get() {
                val channel = raw[0].toInt() and 0xff
                return channel == 5 || channel == 0x15 || channel == 7
            }

        /** RACE packet type (0x5A/0x5B/0x5D), 0 for non RACE packets. */
        val type: Int get() = if (raw.size > 1) raw[1].toInt() and 0xff else 0

        /** Race id, or -1 when this is not a RACE frame. */
        val raceId: Int get() = if (isRace && raw.size >= 6) u16le(raw, 4) else -1

        /** Status byte shared by every MMI response, -1 when absent. */
        val status: Int get() = if (raw.size > 6) raw[6].toInt() and 0xff else -1

        fun u8(index: Int): Int = raw[index].toInt() and 0xff
    }

    fun interface Sink {
        fun onFrame(frame: Frame)
    }

    /**
     * Incremental H4 parser.  Mirrors `H4Transport.parseRxDataToPacket` byte for
     * byte, including its resynchronisation rule (an unknown channel byte -- or an
     * ACL/HCI length that cannot be right -- drops exactly one byte and starts
     * over, which is how the stock parser recovers from noise).
     */
    class Parser {

        private var buffer = ByteArray(4096)
        private var size = 0

        fun feed(data: ByteArray, offset: Int, count: Int, sink: Sink) {
            if (count <= 0) return
            if (size + count > buffer.size) {
                var capacity = buffer.size
                while (capacity < size + count) capacity *= 2
                buffer = buffer.copyOf(capacity)
            }
            data.copyInto(buffer, size, offset, offset + count)
            size += count

            var position = 0
            while (true) {
                val length = frameLength(position)
                if (length == 0) break         // need more bytes
                if (length < 0) {              // noise: skip one byte and retry
                    position++
                    continue
                }
                sink.onFrame(Frame(buffer.copyOfRange(position, position + length)))
                position += length
            }
            if (position > 0) {
                buffer.copyInto(buffer, 0, position, size)
                size -= position
            }
        }

        /** 0 = need more data, <0 = drop one byte, >0 = frame length. */
        private fun frameLength(position: Int): Int {
            val available = size - position
            if (available < 1) return 0
            val channel = buffer[position].toInt() and 0xff

            if (channel == 4) {                 // HCI event
                if (available >= 2) {
                    val event = buffer[position + 1].toInt() and 0xff
                    if (event != 0xff && event != 0x0e && event != 0x0f) return -1
                }
                if (available < 3) return 0
                val length = 3 + (buffer[position + 2].toInt() and 0xff)
                return if (available >= length) length else 0
            }

            if (channel == 2) {                 // ACL
                if (available >= 3
                    && ((buffer[position + 1].toInt() and 0xff) != 0
                            || (buffer[position + 2].toInt() and 0xff) != 0x0f)
                ) {
                    return -1
                }
                if (available < 5) return 0
                val body = u16le(buffer, position + 3)
                if (body == 0 || body > 1995) return -1
                val length = 5 + body
                return if (available >= length) length else 0
            }

            if (channel == 5 || channel == 0x15 || channel == 7) {   // RACE
                if (available < 4) return 0
                val length = 4 + u16le(buffer, position + 2)
                if (length > 2000 || length < 6) return -1
                return if (available >= length) length else 0
            }

            return -1
        }
    }
}
