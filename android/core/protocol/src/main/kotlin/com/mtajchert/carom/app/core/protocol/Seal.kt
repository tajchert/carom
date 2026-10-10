package com.mtajchert.carom.app.core.protocol

/** Seal: group-key encryption plus signature (SPEC §2.3). Shared by envelopes and records. */
object Seal {
    class Output(val aad: ByteArray, val ct: ByteArray, val sig: ByteArray)

    fun isValidKv(kv: Long): Boolean = kv in 1..Protocol.Limits.MAX_KV

    /** `aad = lp(tag) || lp(H[0]) || ... || lp(u64(kv)) || lp(salt)` */
    fun aad(tag: String, header: List<ByteArray>, kv: Long, salt: ByteArray): ByteArray =
        concat(listOf(lp(tag)) + header.map(::lp) + listOf(lp(u64(kv)), lp(salt)))

    /** The signed bytes: `aad || lp(ct)`. */
    fun sigInput(aad: ByteArray, ct: ByteArray): ByteArray = concat(aad, lp(ct))

    fun seal(
        tag: String,
        header: List<ByteArray>,
        plaintext: ByteArray,
        groupKey: ByteArray,
        kv: Long,
        signSk: ByteArray,
        salt: ByteArray,
    ): Output {
        require(isValidKv(kv)) { "kv must be a u32 >= 1" }
        require(salt.size == Protocol.SALT_LENGTH) { "salt must be 32 bytes" }
        require(groupKey.size == Protocol.KEY_LENGTH) { "group key must be 32 bytes" }
        val aad = aad(tag, header, kv, salt)
        val ct = Crypto.aeadSeal(Kdf.msgKey(groupKey, salt), aad, plaintext)
        val sig = Crypto.ed25519Sign(signSk, sigInput(aad, ct))
        return Output(aad, ct, sig)
    }

    fun verify(aad: ByteArray, ct: ByteArray, sig: ByteArray, signPub: ByteArray): Boolean =
        Crypto.ed25519Verify(signPub, sigInput(aad, ct), sig)

    /** Null when authentication fails. */
    fun decrypt(aad: ByteArray, ct: ByteArray, groupKey: ByteArray, salt: ByteArray): ByteArray? =
        Crypto.aeadOpen(Kdf.msgKey(groupKey, salt), aad, ct)
}
