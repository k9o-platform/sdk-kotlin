package dev.k9o.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** One event on a realtime topic. [eventId] is the same on every gateway replica (`evt_...`). */
data class RealtimeEvent(
    val topic: String,
    val eventType: String,
    val payload: JsonElement,
    val occurredAt: String,
    val eventId: String,
)

/** Why the gateway could not resume a feed (its `event: resync` frame). */
data class RealtimeResync(val reason: String, val action: String)

/** One event from [Realtime.replay]: the ledger adds [sequence] (a string, as the gateway sends it); [eventId] may be null. */
data class RealtimeReplayEvent(
    val topic: String,
    val eventType: String,
    val payload: JsonElement,
    val occurredAt: String,
    val sequence: String?,
    val eventId: String?,
)

/**
 * One answer of [Realtime.replay]. [nextCursor] is where the next call starts (the last event's `sequence`), or null when
 * there is none. [hasMore] is true when more events follow this page. [resync] is `"reread-source"` when the topic keeps
 * no ledger (only `audit.project.*` topics keep one): read current state from the data plane, then [Realtime.subscribe].
 */
data class RealtimeReplayPage(
    val topic: String,
    val events: List<RealtimeReplayEvent>,
    val nextCursor: String?,
    val hasMore: Boolean,
    val resync: String?,
)

private const val REPLAY_MAX = 10_000
private const val TOPIC_MAX = 200
private const val DEDUPE_MAX = 1000
private const val DEDUPE_TTL_MS = 60_000L
private val DIGITS = Regex("^[0-9]+$")
private val AUDIT_EVENT_ID = Regex("^evt_[0-9A-Za-z]{1,40}$")

private fun JsonObject.text(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive }?.asString

private fun checkTopic(topic: String, what: String) {
    if (topic.isEmpty() || topic.length > TOPIC_MAX) {
        throw invalidValue("$what: topic must be a non-empty string of at most $TOPIC_MAX characters", mapOf("field" to "topic"))
    }
}

private fun checkSequence(name: String, v: String?) {
    if (v != null && !DIGITS.matches(v)) {
        throw invalidValue("realtime.replay: $name must be a non-negative integer", mapOf("field" to name))
    }
}

/** The ids of recently delivered events, bounded by count and age, so the events a resume re-sends are dropped. */
internal class SeenEventIds(private val max: Int = DEDUPE_MAX, private val ttlMs: Long = DEDUPE_TTL_MS, private val now: () -> Long = System::currentTimeMillis) {
    private val seen = LinkedHashMap<String, Long>()

    /** True the first time an id is offered (and remembers it); false for a duplicate still remembered. */
    fun firstTime(id: String): Boolean {
        val t = now()
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (t - e.value <= ttlMs && seen.size <= max) break
            it.remove()
        }
        if (seen.containsKey(id)) return false
        seen[id] = t
        if (seen.size > max) seen.remove(seen.keys.first())
        return true
    }
}

private fun resyncOf(data: String?): RealtimeResync {
    val o = try {
        data?.let { JsonParser.parseString(it) }?.takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }
    return RealtimeResync(o?.text("reason") ?: "cursor-unknown", o?.text("action") ?: "reread-source")
}

private fun page(data: JsonElement, topic: String): RealtimeReplayPage {
    val o = if (data.isJsonObject) data.asJsonObject else JsonObject()
    val events = (o.get("events")?.takeIf { it.isJsonArray }?.asJsonArray ?: emptyList<JsonElement>()).mapNotNull { e ->
        val item = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val type = item.get("eventType")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: return@mapNotNull null
        RealtimeReplayEvent(
            topic = item.text("topic") ?: topic,
            eventType = type,
            payload = item.get("payload") ?: JsonNull.INSTANCE,
            occurredAt = item.text("occurredAt") ?: "",
            sequence = item.text("sequence"),
            eventId = item.text("eventId")?.takeIf { it.isNotEmpty() },
        )
    }
    return RealtimeReplayPage(
        topic = o.text("topic") ?: topic,
        events = events,
        nextCursor = o.text("nextCursor"),
        hasMore = o.get("hasMore")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean } ?: false,
        resync = o.text("resync"),
    )
}

/**
 * `core.realtime`: a change feed over topics. [subscribe] tails a topic live; [replay] reads the topic's ledger.
 * Only `audit.project.*` topics keep a ledger; for every other topic [replay] answers no events and
 * `resync = "reread-source"`. A filter narrows what you receive; row-level security still decides what you may see.
 * Gateway codes such as `K9O_INVALID_FILTER` and `K9O_SUBSCRIPTION_LIMIT_EXCEEDED` arrive as [KError].
 */
class Realtime internal constructor(private val http: HttpCore) {
    private val gson = Gson()

    /**
     * `GET /@<projectSlug>/realtime/subscribe?topic=&filter=<json>` (server-sent events): tail [topic] live as a cold
     * [Flow]. [topic] is 1 to 200 characters. [filter] is a per-topic predicate, sent as JSON. Whenever the connection
     * ends the flow reconnects after the server's `retry` hint (default 1 second, at most 30) with `Last-Event-ID` set to
     * the last event received, and drops events it already delivered (by `eventId`). When the gateway cannot resume
     * (`event: resync`), events may have been missed: [onResync] is called so you can re-read current state, and the flow
     * carries on live from a fresh connection; without [onResync] the flow ends. The flow also ends when the collector is
     * cancelled; a non-2xx answer is thrown as a [KError].
     */
    fun subscribe(
        topic: String,
        filter: JsonObject? = null,
        onResync: (suspend (RealtimeResync) -> Unit)? = null,
    ): Flow<RealtimeEvent> {
        checkTopic(topic, "realtime.subscribe")
        val api = http.projectPath("realtime operations", "realtime/subscribe")
        val query = linkedMapOf("topic" to topic)
        if (filter != null) query["filter"] = gson.toJson(filter)
        return channelFlow {
            var lastEventId: String? = null
            var seen = SeenEventIds()
            var wait = 1000L
            while (isActive) {
                val sentLastEventId = lastEventId
                val response = http.openStream(api, query, if (sentLastEventId == null) emptyMap() else mapOf("last-event-id" to sentLastEventId))
                val handle = coroutineContext.job.invokeOnCompletion { runCatching { response.close() } }
                var resynced = false
                var sawData = false
                var resyncInfo = RealtimeResync("cursor-unknown", "reread-source")
                try {
                    val reader = BufferedReader(response.reader)
                    withContext(Dispatchers.IO) {
                        while (true) {
                            val frame = readFrame(reader) ?: break
                            frame.retry?.let { wait = minOf(it, 30_000L) }
                            if (frame.event == "resync") {
                                // A resync that opens a connection made without Last-Event-ID, before any data, only
                                // reports the head of the stream: the events follow on this connection.
                                if (sentLastEventId == null && !sawData) continue
                                resynced = true
                                resyncInfo = resyncOf(frame.data)
                                lastEventId = null
                                seen = SeenEventIds()
                                break
                            }
                            val text = frame.data ?: continue
                            sawData = true
                            val parsed = try {
                                JsonParser.parseString(text)
                            } catch (e: Exception) {
                                continue
                            }
                            if (!parsed.isJsonObject) continue
                            val o = parsed.asJsonObject
                            val type = o.get("eventType")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                                ?: continue
                            val eventId = o.text("eventId") ?: frame.id ?: ""
                            val cursor = frame.id?.takeIf { it.isNotEmpty() } ?: eventId
                            if (cursor.isNotEmpty()) lastEventId = cursor
                            if (eventId.isNotEmpty() && !seen.firstTime(eventId)) continue
                            send(
                                RealtimeEvent(
                                    topic = o.text("topic") ?: topic,
                                    eventType = type,
                                    payload = o.get("payload") ?: JsonNull.INSTANCE,
                                    occurredAt = o.text("occurredAt") ?: "",
                                    eventId = eventId,
                                ),
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    // A break in the connection is treated like the server closing it, unless we were cancelled.
                    if (!isActive) return@channelFlow
                } finally {
                    handle.dispose()
                    runCatching { response.close() }
                }
                if (resynced) {
                    // The position is lost: with nobody to re-read, the feed ends here.
                    if (onResync == null) return@channelFlow
                    onResync(resyncInfo)
                }
                delay(wait)
            }
        }
    }

    /**
     * `POST /@<projectSlug>/realtime/replay`: one call of the topic's ledger catch-up. [topic] is 1 to 200 characters.
     * [fromSequence] and [untilSequence] are non-negative whole numbers as strings of digits; [limit] is 1 to 10000 (without
     * it the gateway chooses). [afterAuditEventId] is for `audit.project.*` topics: replay the events after this one (the
     * last `eventId` you received); it looks like `evt_` followed by letters and digits. Only the given fields are sent.
     * Page on with `fromSequence = nextCursor` while `hasMore` is true. `audit.project.*` topics need the audit log's access
     * (403 `K9O_RBAC_DENY` otherwise); an unknown [afterAuditEventId] answers 400 `K9O_INVALID_CURSOR`.
     */
    suspend fun replay(
        topic: String,
        fromSequence: String? = null,
        untilSequence: String? = null,
        limit: Int? = null,
        afterAuditEventId: String? = null,
    ): Envelope<RealtimeReplayPage> {
        val path = http.projectPath("realtime operations", "realtime/replay")
        checkTopic(topic, "realtime.replay")
        checkSequence("fromSequence", fromSequence)
        checkSequence("untilSequence", untilSequence)
        if (afterAuditEventId != null && !AUDIT_EVENT_ID.matches(afterAuditEventId)) {
            throw invalidValue(
                "realtime.replay: afterAuditEventId must look like evt_ followed by letters and digits",
                mapOf("field" to "afterAuditEventId"),
            )
        }
        if (limit != null && (limit < 1 || limit > REPLAY_MAX)) {
            throw invalidValue("realtime.replay: limit must be an integer from 1 to $REPLAY_MAX", mapOf("field" to "limit"))
        }
        val body = JsonObject()
        body.addProperty("topic", topic)
        fromSequence?.let { body.addProperty("fromSequence", it) }
        untilSequence?.let { body.addProperty("untilSequence", it) }
        limit?.let { body.addProperty("limit", it) }
        afterAuditEventId?.let { body.addProperty("afterAuditEventId", it) }
        val env = http.request("POST", path, body = body)
        return Envelope(page(env.data, topic), env.meta)
    }
}
