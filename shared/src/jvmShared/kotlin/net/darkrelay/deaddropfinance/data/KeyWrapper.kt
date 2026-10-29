package net.darkrelay.deaddropfinance.data

/** Wraps and unwraps the database key. The real one uses Android Keystore; tests use a fake. */
interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
}
