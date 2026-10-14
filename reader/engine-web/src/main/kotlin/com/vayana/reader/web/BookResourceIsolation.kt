package com.vayana.reader.web

import org.json.JSONException
import org.json.JSONObject

// Book HTML and SVG share the reader's origin so the trusted shell can attach controls and annotations.
// They must not run their own scripts or embed active objects that can reach that shell or AndroidBridge.
internal val BookResourceHeaders = mapOf(
    "Cache-Control" to "no-store",
    "Content-Security-Policy" to "script-src 'none'; object-src 'none'",
)

/** Bad JSON, including invalid fields read by an event handler, must not escape a WebView callback. */
internal inline fun withBridgeEventPayload(jsonPayload: String, handle: (JSONObject) -> Unit) {
    try {
        handle(JSONObject(jsonPayload))
    } catch (_: JSONException) {
        // Ignore the malformed event; subsequent valid events still reach the reader.
    }
}
