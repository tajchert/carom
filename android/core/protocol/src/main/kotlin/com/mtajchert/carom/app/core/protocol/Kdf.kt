package com.mtajchert.carom.app.core.protocol

/** HKDF-derived keys (SPEC §2.1, §2.4, §6, §8). */
object Kdf {
    private val EMPTY = ByteArray(0)

    /** `msg_key(K, salt) = HKDF(ikm=K, salt=salt, info="carom/v1/msg", L=32)` */
    fun msgKey(groupKey: ByteArray, salt: ByteArray): ByteArray {
        require(groupKey.size == Protocol.KEY_LENGTH) { "group key must be 32 bytes" }
        require(salt.size == Protocol.SALT_LENGTH) { "salt must be 32 bytes" }
        return Crypto.hkdf(groupKey, salt, Protocol.Info.MSG)
    }

    /** `K_collapse(K) = HKDF(ikm=K, salt="", info="carom/v1/collapse", L=32)` */
    fun collapseKey(groupKey: ByteArray): ByteArray {
        require(groupKey.size == Protocol.KEY_LENGTH) { "group key must be 32 bytes" }
        return Crypto.hkdf(groupKey, EMPTY, Protocol.Info.COLLAPSE)
    }

    /** `k_pair = HKDF(ikm=pair_secret, salt="", info="carom/v1/pair", L=32)` */
    fun pairKey(pairSecret: ByteArray): ByteArray {
        require(pairSecret.size == Protocol.KEY_LENGTH) { "pair secret must be 32 bytes" }
        return Crypto.hkdf(pairSecret, EMPTY, Protocol.Info.PAIR)
    }

    /** `token_key = HKDF(ikm=UTF8(SETUP_TOKEN), salt="", info="carom/v1/token-key", L=32)` */
    fun tokenKey(setupToken: String): ByteArray = Crypto.hkdf(setupToken.utf8(), EMPTY, Protocol.Info.TOKEN_KEY)

    /** `key = HKDF(ikm=ss || psk, salt=eph_pub || rcpt_kx_pub, info="carom/v1/box", L=32)` */
    fun boxKey(sharedSecret: ByteArray, psk: ByteArray, ephPub: ByteArray, rcptKxPub: ByteArray): ByteArray =
        Crypto.hkdf(concat(sharedSecret, psk), concat(ephPub, rcptKxPub), Protocol.Info.BOX)
}
