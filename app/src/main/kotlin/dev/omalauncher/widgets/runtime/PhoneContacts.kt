package dev.omalauncher.widgets.runtime

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import dev.omalauncher.system.changeObserver
import dev.omalauncher.system.hasPermission
import dev.omalauncher.widgets.lang.Lst
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/**
 * Contacts for OWL faces and widgets. An Android READ_CONTACTS grant does not grant every widget access:
 * each running widget must separately call contacts("request") in a user gesture. Nothing is read until then,
 * and a widget never gets another widget's query or results. No guest/agent interface exposes this data.
 */
class PhoneContacts(private val context: Context) {
    var ask: ((granted: (Boolean) -> Unit) -> Unit)? = null

    private class Session {
        val enabled = MutableStateFlow(false)
        val query = MutableStateFlow("")
    }

    // Only used on the main thread. Stop removes the session; weak keys also protect an abandoned instance.
    private val sessions = WeakHashMap<Any, Session>()
    private val permission = MutableStateFlow(check())
    private fun check() = context.hasPermission(Manifest.permission.READ_CONTACTS)
    private fun session(owner: Any): Session = sessions.getOrPut(owner) { Session() }

    /** Also called when coming back from Android Settings, where access may have been revoked. */
    fun recheck() {
        val allowed = check()
        permission.value = allowed
        if (!allowed) sessions.values.forEach {
            it.enabled.value = false
            it.query.value = ""
        }
    }

    /** Only an explicit widget tap enables its session, even when Android already allowed this app. */
    fun request(owner: Any) {
        recheck()
        val session = session(owner)
        if (permission.value) {
            session.enabled.value = true
        } else {
            // No activity/foreground: nothing asks. Background widgets cannot display permission dialogs.
            ask?.invoke { granted ->
                recheck()
                if (granted && permission.value) session.enabled.value = true
            }
        }
    }

    fun stop(owner: Any) {
        sessions.remove(owner)?.let {
            it.enabled.value = false
            it.query.value = ""
        }
    }

    /** Search is scoped to one live widget; opening a dialer/SMS app never sends a message or calls directly. */
    fun action(owner: Any, word: String, target: Value?) {
        if (word == "request") return request(owner)
        val session = sessions[owner] ?: return
        if (!session.enabled.value || !check()) {
            recheck()
            return
        }
        when (word) {
            "search" -> session.query.value = contactTerm(target?.toString().orEmpty())
            "dial" -> phoneNumber(target?.toString().orEmpty())?.let { open(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", it, null))) }
            "text" -> phoneNumber(target?.toString().orEmpty())?.let { open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", it, null))) }
            "open" -> {
                val id = ((target as? Obj)?.fields?.get("id") ?: target)?.toString()?.toLongOrNull()?.takeIf { it > 0 }
                if (id != null) open(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id)))
            }
        }
    }

    private fun open(intent: Intent) {
        // There might be no dialer, SMS or contacts app. Never crash the user's home screen.
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * {granted, query, items}. No whole-address-book source: blank search means no rows. Queries are debounced and
     * cancelled as the widget changes text or leaves the screen. An Android permission change clears results.
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun updates(owner: Any): Flow<Value> {
        val session = session(owner)
        return combine(permission, session.enabled, session.query.debounce(100L)) { granted, enabled, query ->
            Pair(granted && enabled, query)
        }.distinctUntilChanged().flatMapLatest { (allowed, query) ->
            when {
                !allowed -> flowOf(value(false, ""))
                query.isBlank() -> flowOf(value(true, ""))
                else ->
                    changes().mapLatest {
                        try {
                            val items = withContext(Dispatchers.IO) { search(query) }
                            if (check()) value(true, query, items) else value(false, "")
                        } catch (_: SecurityException) {
                            value(false, "")
                        }
                    }.catch { if (it is SecurityException) emit(value(false, "")) else throw it }
            }
        }.distinctUntilChanged()
    }

    /** Names, numbers and contact changes update just the displayed search, never a background scan. */
    private fun changes(): Flow<Unit> =
        callbackFlow {
            val resolver = context.contentResolver
            val observer = changeObserver(Handler(Looper.getMainLooper())) { trySend(Unit) }
            var registered = false
            try {
                resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
                registered = true
                resolver.registerContentObserver(Phone.CONTENT_URI, true, observer)
            } catch (e: RuntimeException) {
                if (registered) resolver.unregisterContentObserver(observer)
                throw e
            }
            trySend(Unit)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.conflate()

    /** Android's indexed filter matches display names and phone numbers without enumerating contacts. IO thread only. */
    private fun search(term: String): List<ContactMatch> {
        val query = contactTerm(term)
        if (query.isBlank()) return emptyList()
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(query))
        val fields = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        val rows = ArrayList<ContactLine>()
        context.contentResolver.query(uri, fields, null, null, null)?.use { cursor ->
            // Cap provider work as well as OWL output. Some contacts have many phone numbers.
            while (cursor.moveToNext() && rows.size < 120) {
                val name = cursor.getString(1).orEmpty()
                val number = cursor.getString(2).orEmpty()
                val label = Phone.getTypeLabel(context.resources, cursor.getInt(3), cursor.getString(4)).toString()
                rows += ContactLine(cursor.getLong(0), name, number, label)
            }
        }
        return groupContacts(rows)
    }

    private fun value(granted: Boolean, query: String, contacts: List<ContactMatch> = emptyList()): Value =
        Obj(mapOf(
            "granted" to bool(granted),
            "query" to Str(query),
            "items" to Lst(contacts.map { contact ->
                Obj(mapOf(
                    "id" to Str(contact.id.toString()),
                    "name" to Str(contact.name),
                    "numbers" to Lst(contact.numbers.map { number ->
                        Obj(mapOf("number" to Str(number.number), "label" to Str(number.label)))
                    }),
                ))
            }),
        ))
}
