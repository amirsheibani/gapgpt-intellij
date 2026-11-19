package ir.gapgpt.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/** The GapGPT API key lives only in the IDE's PasswordSafe, never in [GapGptSettings.State]. */
object ApiKeyStore {

    private val attributes = CredentialAttributes(generateServiceName("GapGPT", "apiKey"))

    /** Blocking (may hit the OS keychain): call from a background thread. */
    fun get(): String? = PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotBlank() }

    /** Blocking: call from a background thread. A blank [key] removes the stored key. */
    fun set(key: String?) {
        val value = key?.trim().orEmpty()
        PasswordSafe.instance.set(attributes, if (value.isEmpty()) null else Credentials("apikey", value))
    }
}
