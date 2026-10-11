package com.mtajchert.carom.app.core.protocol

/** Domain-separation strings and limits from SPEC v1. */
object Protocol {
    const val VERSION = 1

    /** HKDF info strings. */
    object Info {
        const val MSG = "carom/v1/msg"
        const val COLLAPSE = "carom/v1/collapse"
        const val BOX = "carom/v1/box"
        const val PAIR = "carom/v1/pair"
        const val TOKEN_KEY = "carom/v1/token-key"
        const val BLOB_CONV = "carom/v1/blob-conv"
    }

    /** Seal / box / signature domain tags. */
    object Tag {
        const val ENVELOPE = "carom/v1/env"
        const val RECORD = "carom/v1/rec"
        const val REKEY = "carom/v1/rekey"
        const val WELCOME = "carom/v1/welcome"
        const val PAIR_REQUEST = "carom/v1/pair-req"
        const val BLOB = "carom/v1/blob"
    }

    object Limits {
        const val MAX_PLAINTEXT = 65_536

        /** Largest blob ciphertext (SPEC V2 §3.7; the Hub also allows 1 KiB of slack). */
        const val MAX_BLOB_BYTES = 1_048_576

        /**
         * Largest blob plaintext [Blobs.seal] accepts: `4 + n` pads to at most 1 044 480 bytes
         * (a multiple of 4096), so the ciphertext, with its 16-byte tag, stays within [MAX_BLOB_BYTES].
         */
        const val MAX_BLOB_PLAINTEXT = 1_044_476
        const val MAX_ENVELOPE_BYTES = 98_304
        const val MIN_TTL_S = 60L
        const val MAX_TTL_S = 604_800L
        const val MAX_RECIPIENTS = 32
        const val CLOCK_SKEW_MS = 300_000L
        const val MAX_KV = 0xffff_ffffL

        /** Receivers remember seen msg_ids for 7 days plus the clock skew (SPEC §3.3 rule 4). */
        const val SEEN_RETENTION_MS = 7L * 24 * 60 * 60 * 1000 + CLOCK_SKEW_MS

        /** Old group keys are kept for 7 days after a rekey (SPEC §5.5). */
        const val OLD_KEY_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        /** Largest integer a JSON number can carry exactly (JavaScript `Number.MAX_SAFE_INTEGER`). */
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }

    const val KEY_LENGTH = 32
    const val SALT_LENGTH = 32
    const val SIG_LENGTH = 64
    const val AEAD_TAG_LENGTH = 16
}
