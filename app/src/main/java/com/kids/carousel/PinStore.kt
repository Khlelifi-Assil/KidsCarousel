package com.kids.carousel

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID

object PinStore {
    private fun prefs(c: Context): SharedPreferences = EncryptedSharedPreferences.create(
        c, "secure_prefs",
        MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun hash(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun hasPin(c: Context) = prefs(c).contains("hash")

    fun setPin(c: Context, pin: String) {
        val salt = UUID.randomUUID().toString()
        prefs(c).edit().putString("salt", salt).putString("hash", hash(salt, pin)).apply()
    }

    fun check(c: Context, pin: String): Boolean {
        val p = prefs(c)
        val salt = p.getString("salt", null) ?: return false
        return p.getString("hash", null) == hash(salt, pin)
    }
}
