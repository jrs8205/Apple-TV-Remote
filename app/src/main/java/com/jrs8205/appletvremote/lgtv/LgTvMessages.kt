package com.jrs8205.appletvremote.lgtv

import org.json.JSONArray
import org.json.JSONObject

/** Builds and reads the JSON messages of the webOS second-screen (SSAP) protocol. */
object LgTvMessages {

    const val APP_NAME = "Apple TV Remote"

    fun register(id: String, clientKey: String?): String {
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", manifest())
        if (clientKey != null) payload.put("client-key", clientKey)
        return JSONObject().put("type", "register").put("id", id).put("payload", payload).toString()
    }

    fun request(id: String, uri: String, payload: Map<String, Any?> = emptyMap()): String =
        JSONObject().put("type", "request").put("id", id).put("uri", uri).put("payload", JSONObject(payload)).toString()

    /** One parsed incoming message. [payload] is null when the TV sent none. */
    class Incoming(val type: String, val id: String?, val payload: JSONObject?, val error: String?)

    fun parse(text: String): Incoming? = runCatching {
        val json = JSONObject(text)
        Incoming(
            type = json.optString("type"),
            id = json.optString("id").takeIf { it.isNotEmpty() },
            payload = json.optJSONObject("payload"),
            error = json.optString("error").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    private fun manifest(): JSONObject = JSONObject()
        .put("manifestVersion", 1)
        .put("appVersion", "1.1")
        .put("permissions", JSONArray(PERMISSIONS))
        .put("signatures", JSONArray().put(JSONObject().put("signature", SIGNATURE).put("signatureVersion", 1)))
        .put(
            "signed",
            JSONObject()
                .put("appId", "com.lge.test")
                .put("created", "20140509")
                .put("localizedAppNames", JSONObject().put("", "LG Remote App"))
                .put("localizedVendorNames", JSONObject().put("", "LG Electronics"))
                .put("permissions", JSONArray(SIGNED_PERMISSIONS))
                .put("serial", "2f930e2d2cfe083771f68e4fe7bb07")
                .put("vendorId", "com.lge"),
        )

    // The manifest below is the one LG published with its sample app; every third-party remote sends it.
    private val PERMISSIONS = listOf(
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "TEST_OPEN", "TEST_PROTECTED", "CONTROL_AUDIO",
        "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_TV", "CONTROL_POWER", "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
        "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE",
        "READ_COUNTRY_INFO", "READ_SETTINGS", "CONTROL_TV_SCREEN", "CONTROL_TV_STANBY", "CONTROL_FAVORITE_GROUP",
        "CONTROL_USER_INFO", "CHECK_BLUETOOTH_DEVICE", "CONTROL_BLUETOOTH", "CONTROL_TIMER_INFO", "STB_INTERNAL_CONNECTION",
        "CONTROL_RECORDING", "READ_RECORDING_STATE", "WRITE_RECORDING_LIST", "READ_RECORDING_LIST", "READ_RECORDING_SCHEDULE",
        "WRITE_RECORDING_SCHEDULE", "READ_STORAGE_DEVICE_LIST", "READ_TV_PROGRAM_INFO", "CONTROL_BOX_CHANNEL",
        "READ_TV_ACR_AUTH_TOKEN", "READ_TV_CONTENT_STATE", "READ_TV_CURRENT_TIME", "ADD_LAUNCHER_CHANNEL", "SET_CHANNEL_SKIP",
        "RELEASE_CHANNEL_SKIP", "CONTROL_CHANNEL_BLOCK", "DELETE_SELECT_CHANNEL", "CONTROL_CHANNEL_GROUP", "SCAN_TV_CHANNELS",
        "CONTROL_TV_POWER", "CONTROL_WOL",
    )

    private val SIGNED_PERMISSIONS = listOf(
        "TEST_SECURE", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "READ_INSTALLED_APPS", "READ_LGE_SDX",
        "READ_NOTIFICATIONS", "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT", "CONTROL_POWER", "READ_CURRENT_CHANNEL",
        "READ_RUNNING_APPS", "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS", "READ_TV_CURRENT_TIME",
    )

    private const val SIGNATURE =
        "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR" +
            "+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4" +
            "RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM" +
            "2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="
}
