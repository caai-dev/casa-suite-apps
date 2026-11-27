package com.casaaudit.casana

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stay signed in (1.7, 27 Nov 2026): Casana's login kept on this phone so the app can sign in again by itself.
 *
 * Apper's sign-in lasts 8 hours and its "Remember me" box does nothing, so people were asked to sign in again and
 * again. The email and password are encrypted with an AES key that lives in the Android Keystore (it never leaves
 * the phone's secure hardware and cannot be read by other apps), and only the encrypted bytes are kept in this app's
 * private storage. Sign out in Casana deletes them.
 */
object LoginStore {
    private const val KEY_ALIAS = "casana-login-key"
    private const val PREFS = "casana-login"
    private const val ITEM = "login"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun save(context: Context, email: String?, password: String?) {
        if (email.isNullOrBlank() || password.isNullOrEmpty()) return
        try {
            val plain = JSONObject().put("email", email.trim()).put("password", password).toString().toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(plain)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ITEM, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        } catch (_: Exception) {
            clear(context)
        }
    }

    /** The saved login as {"email","password"}, or "" when there is none or it cannot be read. */
    fun read(context: Context): String {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ITEM, null) ?: return ""
        return try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            if (sealed.size <= 12) return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            String(cipher.doFinal(sealed.copyOfRange(12, sealed.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(context)
            ""
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ITEM).apply()
    }
}
