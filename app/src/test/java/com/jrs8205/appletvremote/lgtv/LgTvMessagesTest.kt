package com.jrs8205.appletvremote.lgtv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LgTvMessagesTest {

    @Test
    fun registerCarriesTheManifestAndOptionalKey() {
        val fresh = JSONObject(LgTvMessages.register("register_1", null))
        assertEquals("register", fresh.getString("type"))
        assertEquals("register_1", fresh.getString("id"))
        val payload = fresh.getJSONObject("payload")
        assertEquals("PROMPT", payload.getString("pairingType"))
        assertFalse(payload.has("client-key"))
        val manifest = payload.getJSONObject("manifest")
        assertEquals(1, manifest.getInt("manifestVersion"))
        assertTrue(manifest.getJSONArray("permissions").length() > 40)
        assertEquals("com.lge.test", manifest.getJSONObject("signed").getString("appId"))
        assertEquals(1, manifest.getJSONArray("signatures").getJSONObject(0).getInt("signatureVersion"))

        val known = JSONObject(LgTvMessages.register("register_2", "abc"))
        assertEquals("abc", known.getJSONObject("payload").getString("client-key"))
    }

    @Test
    fun requestCarriesUriAndPayload() {
        val json = JSONObject(LgTvMessages.request("7", "ssap://tv/switchInput", mapOf("inputId" to "HDMI_2")))
        assertEquals("request", json.getString("type"))
        assertEquals("7", json.getString("id"))
        assertEquals("ssap://tv/switchInput", json.getString("uri"))
        assertEquals("HDMI_2", json.getJSONObject("payload").getString("inputId"))
    }

    @Test
    fun parsesResponsesAndErrors() {
        val ok = LgTvMessages.parse("""{"type":"registered","id":"register_1","payload":{"client-key":"k"}}""")!!
        assertEquals("registered", ok.type)
        assertEquals("k", ok.payload!!.getString("client-key"))
        assertNull(ok.error)
        val error = LgTvMessages.parse("""{"type":"error","id":"3","error":"401 insufficient permissions","payload":{}}""")!!
        assertEquals("401 insufficient permissions", error.error)
        assertNull(LgTvMessages.parse("not json"))
    }
}
