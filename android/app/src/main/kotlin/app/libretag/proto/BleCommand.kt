package app.libretag.proto

/**
 * A command in the phone's Bluetooth advertising (PROTOCOL.md 3.1, firmware 0.7.7): manufacturer
 * data with company id 0xFFFF, then 21 B: type 0x22, the command tag xc(n, k) (4 B), ciphertext
 * bytes 1..8 of the sealed command (seq, opcode, args 0..5) and its MAC (8 B). It is the same sealed
 * command the mesh carries; the constant type byte and the zero tail are left out, and the tag puts
 * their keystream back before it opens the command exactly like one from the mesh.
 */
object BleCommand {
    const val COMPANY = 0xFFFF
    const val LEN = 21
    private const val CT_LEN = 8
    private const val TAG_LEN = 4
    private const val MAC_LEN = 8

    /** k for a wake-up that is not a check after report n (normal reporting, the heartbeat, LOST) */
    const val K_NEXT = 0xFFFF

    /**
     * The payload after the company id, or null when the plaintext has bytes after offset 8 (mesh
     * only). [k] = [K_NEXT]: sealed with C_e(n) of the tag's last report n; k >= 1: slot k of the
     * grid of anchor n, sealed from the slot key (PROTOCOL.md 2.6, 3.1).
     */
    fun payload(cmdE: ByteArray, n: Long, cmd: Command, k: Int): ByteArray? {
        val p = if (k == K_NEXT || k == 0) LibreTag.sealCommand(cmdE, n, cmd, k)
            else QuietMesh.sealSlotCommand(QuietMesh.slotKey(cmdE, n), k, cmd)
        return of(p, cmd)
    }

    /** The advert form of a sealed command [p] (29 B), or null when [cmd] has bytes after offset 8. */
    fun of(p: ByteArray, cmd: Command): ByteArray? {
        val pt = cmd.encode()
        if (pt.copyOfRange(1 + CT_LEN, pt.size).any { it.toInt() != 0 }) return null
        return byteArrayOf(LibreTag.TYPE_COMMAND.toByte()) + p.copyOf(TAG_LEN) +
            p.copyOfRange(TAG_LEN + 1, TAG_LEN + 1 + CT_LEN) + p.copyOfRange(p.size - MAC_LEN, p.size)
    }
}
