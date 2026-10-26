package top.onlyquant.tingxie.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.store by preferencesDataStore("tingxie")

const val DefaultServer = "https://tingxie.onlyquant.top"

/** A signed-in device: where it talks to and the token it talks with. */
data class Session(val server: String, val token: String, val deviceId: Long)

/** A sign-in waiting for the browser to come back with a code. */
data class PendingLogin(val server: String, val state: String, val verifier: String)

/** Small app state kept across launches. */
class Prefs(private val context: Context) {
    private object K {
        val server = stringPreferencesKey("server")
        val token = stringPreferencesKey("token")
        val deviceId = longPreferencesKey("device_id")
        val lastDoc = longPreferencesKey("last_doc")
        val pendingState = stringPreferencesKey("pending_state")
        val pendingVerifier = stringPreferencesKey("pending_verifier")
        val pendingServer = stringPreferencesKey("pending_server")
    }

    private suspend fun read(): Preferences = context.store.data.first()

    suspend fun server(): String = read()[K.server] ?: DefaultServer

    suspend fun setServer(url: String) = context.store.edit { it[K.server] = url }

    suspend fun session(): Session? {
        val p = read()
        val token = p[K.token] ?: return null
        return Session(p[K.server] ?: DefaultServer, token, p[K.deviceId] ?: 0)
    }

    suspend fun signIn(s: Session) = context.store.edit {
        it[K.server] = s.server
        it[K.token] = s.token
        it[K.deviceId] = s.deviceId
    }

    /** Forgets the token and everything tied to the account; the server stays. */
    suspend fun signOut() = context.store.edit {
        it.remove(K.token)
        it.remove(K.deviceId)
        it.remove(K.lastDoc)
    }

    suspend fun lastDoc(): Long? = read()[K.lastDoc]

    suspend fun setLastDoc(id: Long?) = context.store.edit {
        if (id == null) it.remove(K.lastDoc) else it[K.lastDoc] = id
    }

    // The browser round trip may outlive the process, so the PKCE secrets
    // wait on disk.
    suspend fun pendingLogin(): PendingLogin? {
        val p = read()
        return PendingLogin(
            p[K.pendingServer] ?: return null,
            p[K.pendingState] ?: return null,
            p[K.pendingVerifier] ?: return null,
        )
    }

    suspend fun setPendingLogin(l: PendingLogin?) = context.store.edit {
        if (l == null) {
            it.remove(K.pendingServer)
            it.remove(K.pendingState)
            it.remove(K.pendingVerifier)
        } else {
            it[K.pendingServer] = l.server
            it[K.pendingState] = l.state
            it[K.pendingVerifier] = l.verifier
        }
    }
}
