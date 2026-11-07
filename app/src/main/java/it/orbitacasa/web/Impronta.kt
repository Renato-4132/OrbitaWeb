package it.orbitacasa.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Salva la password di una sessione cifrata con una chiave del telefono (Keystore)
// utilizzabile solo dopo l'impronta. La password non esce mai dal telefono.
object Impronta {
    private const val KS = "AndroidKeyStore"
    private const val TRASF = "AES/GCM/NoPadding"

    private fun prefs(c: Context) = c.getSharedPreferences("orbitaweb_impronta", Context.MODE_PRIVATE)
    private fun alias(id: String) = "orbita_imp_$id"
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun disponibile(c: Context): Boolean =
        BiometricManager.from(c).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun salvata(c: Context, id: String): Boolean = prefs(c).contains("d_$id")

    fun elimina(c: Context, id: String) {
        prefs(c).edit().remove("d_$id").remove("iv_$id").apply()
        try {
            KeyStore.getInstance(KS).apply { load(null) }.deleteEntry(alias(id))
        } catch (e: Exception) {
            // niente
        }
    }

    private fun chiave(id: String): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getKey(alias(id), null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(
            KeyGenParameterSpec.Builder(
                alias(id),
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return kg.generateKey()
    }

    private fun chiedi(
        act: FragmentActivity, cipher: Cipher, titolo: String, sotto: String,
        ok: (Cipher) -> Unit, ko: (String) -> Unit
    ) {
        val prompt = BiometricPrompt(
            act, ContextCompat.getMainExecutor(act),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val c = result.cryptoObject?.cipher
                    if (c != null) ok(c) else ko("Errore di autenticazione")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    ko(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(titolo)
            .setSubtitle(sotto)
            .setNegativeButtonText("Annulla")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    fun salva(act: FragmentActivity, id: String, password: String, fine: (Boolean, String) -> Unit) {
        try {
            val cipher = Cipher.getInstance(TRASF)
            cipher.init(Cipher.ENCRYPT_MODE, chiave(id))
            chiedi(act, cipher, "Attiva l'impronta", "Conferma per salvare l'accesso",
                { c ->
                    try {
                        val enc = c.doFinal(password.toByteArray(Charsets.UTF_8))
                        prefs(act).edit().putString("d_$id", b64(enc)).putString("iv_$id", b64(c.iv)).apply()
                        fine(true, "")
                    } catch (e: Exception) {
                        elimina(act, id)
                        fine(false, e.message ?: "errore")
                    }
                },
                { m -> fine(false, m) })
        } catch (e: Exception) {
            elimina(act, id)
            fine(false, e.message ?: "errore")
        }
    }

    fun leggi(act: FragmentActivity, id: String, fine: (String?, String) -> Unit) {
        val d = prefs(act).getString("d_$id", null)
        val iv = prefs(act).getString("iv_$id", null)
        if (d == null || iv == null) {
            fine(null, "Nessuna impronta salvata")
            return
        }
        try {
            val cipher = Cipher.getInstance(TRASF)
            cipher.init(Cipher.DECRYPT_MODE, chiave(id), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            chiedi(act, cipher, "Accedi a Orbita", "Appoggia il dito sul sensore",
                { c ->
                    try {
                        val pw = String(c.doFinal(Base64.decode(d, Base64.NO_WRAP)), Charsets.UTF_8)
                        fine(pw, "")
                    } catch (e: Exception) {
                        fine(null, e.message ?: "errore")
                    }
                },
                { m -> fine(null, m) })
        } catch (e: Exception) {
            // chiave invalidata (es. nuove impronte aggiunte): va riattivata
            elimina(act, id)
            fine(null, "Impronta cambiata sul telefono: accedi con la password e riattivala")
        }
    }
}
