package dev.k9o.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun queuedBody(operation: String, target: String?, payload: String) =
    """{"data":{"id":"apr_5oLshD2MVT27","status":"pending_approval","collection":"conformance_kv","operation":"$operation",""" +
        """"makerId":"usr_conformance0001","proposedPayload":$payload,"targetRecordId":${if (target == null) "null" else "\"$target\""}},""" +
        """"meta":{"cost":{},"timing":{},"context":{"requestId":"req_1","traceId":"req_1","projectId":"proj_1"},""" +
        """"approval":{"status":"pending_approval"}}}"""

private const val APPLIED = """{"data":{"id":"r1","val":"x"},"meta":{}}"""

class ApprovalTest {
    private fun core(t: StubTransport) = K9OCore("https://acme.api.k9o.io", "member-portal", accessToken = "tok", transport = t)

    private val base = "https://acme.api.k9o.io/@member-portal/data/conformance_kv"

    @Test fun `insert sends the flag and decodes the 202 queued change`() = runBlocking {
        val t = StubTransport(202, queuedBody("insert", null, """{"id":"contract2-probe-0001","val":"contract2-probe"}"""))
        val r = core(t).from("conformance_kv")
            .insertForApproval(mapOf("id" to "contract2-probe-0001", "val" to "contract2-probe")).execute()
        val req = t.requests.single()
        assertEquals("POST", req.method)
        assertEquals("$base?requireApproval=true", req.url)
        assertEquals(
            JsonParser.parseString("""{"id":"contract2-probe-0001","val":"contract2-probe"}"""),
            JsonParser.parseString(req.body),
        )
        val q = assertIs<ApprovalGatedWrite.Queued>(r)
        assertEquals("apr_5oLshD2MVT27", q.change.id)
        assertEquals("pending_approval", q.change.status)
        assertEquals("insert", q.change.operation)
        assertEquals("conformance_kv", q.change.collection)
        assertEquals("usr_conformance0001", q.change.makerId)
        assertEquals("contract2-probe-0001", q.change.proposedPayload.get("id").asString)
        assertNull(q.change.targetRecordId)
        assertEquals("pending_approval", (q.meta["approval"] as Map<*, *>)["status"])
    }

    @Test fun `insert decodes the applied row when an auto_approve policy applied the write`() = runBlocking {
        val t = StubTransport(201, APPLIED)
        val r = core(t).from("conformance_kv").insertForApproval(mapOf("id" to "r1", "val" to "x")).execute()
        assertEquals("$base?requireApproval=true", t.requests.single().url)
        val a = assertIs<ApprovalGatedWrite.Applied>(r)
        assertEquals("r1", a.envelope.data.get("id").asString)
    }

    @Test fun `update sends the flag and a queued answer carries the target record`() = runBlocking {
        val t = StubTransport(202, queuedBody("update", "r 1", """{"val":"n"}"""))
        val r = core(t).from("conformance_kv").update(mapOf("val" to "n")).eq("id", "r 1").requireApproval().execute()
        val req = t.requests.single()
        assertEquals("PATCH", req.method)
        assertEquals("$base/r%201?requireApproval=true", req.url)
        assertEquals(JsonParser.parseString("""{"val":"n"}"""), JsonParser.parseString(req.body))
        val q = assertIs<ApprovalGatedWrite.Queued>(r)
        assertEquals("update", q.change.operation)
        assertEquals("r 1", q.change.targetRecordId)
    }

    @Test fun `update decodes the applied row`() = runBlocking {
        val t = StubTransport(200, APPLIED)
        val r = core(t).from("conformance_kv").update(mapOf("val" to "n")).eq("id", "r1").requireApproval().execute()
        assertTrue(r is ApprovalGatedWrite.Applied)
    }

    @Test fun `delete sends the flag with no body and a queued delete has an empty payload`() = runBlocking {
        val t = StubTransport(202, queuedBody("delete", "r1", "{}"))
        val r = core(t).from("conformance_kv").delete().eq("id", "r1").requireApproval().execute()
        val req = t.requests.single()
        assertEquals("DELETE", req.method)
        assertEquals("$base/r1?requireApproval=true", req.url)
        assertNull(req.body)
        val q = assertIs<ApprovalGatedWrite.Queued>(r)
        assertEquals("delete", q.change.operation)
        assertEquals(0, q.change.proposedPayload.size())
    }

    @Test fun `delete decodes the applied answer`() = runBlocking {
        val t = StubTransport(200, """{"data":{"id":"r1"},"meta":{}}""")
        val r = core(t).from("conformance_kv").delete().eq("id", "r1").requireApproval().execute()
        assertTrue(r is ApprovalGatedWrite.Applied)
    }

    @Test fun `writes without requireApproval send no query`() = runBlocking {
        val t = StubTransport(200, APPLIED)
        val c = core(t)
        c.from("conformance_kv").insert(mapOf("id" to "r1"))
        c.from("conformance_kv").update(mapOf("a" to 1)).eq("id", "r1").execute()
        c.from("conformance_kv").delete().eq("id", "r1").execute()
        assertEquals(listOf(base, "$base/r1", "$base/r1"), t.requests.map { it.url })
    }

    @Test fun `a requireApproval write still checks the id filter before any request`() = runBlocking {
        val t = StubTransport(200, APPLIED)
        val e = assertFailsWith<KError> { core(t).from("conformance_kv").update(mapOf("a" to 1)).requireApproval().execute() }
        assertEquals("K9O_INVALID_FIELD_VALUE", e.code)
        assertTrue(t.requests.isEmpty())
    }
}
