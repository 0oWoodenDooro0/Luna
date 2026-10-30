package com.github._0owoodendooro0.curtly

import java.security.SecureRandom

interface KeyGenerator {
    fun generate(): String
}

class RandomAlphanumericKeyGenerator(
    private val length: Int = 6,
    private val alphabet: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
) : KeyGenerator {
    private val random = SecureRandom()

    override fun generate(): String {
        return (1..length)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
    }
}
