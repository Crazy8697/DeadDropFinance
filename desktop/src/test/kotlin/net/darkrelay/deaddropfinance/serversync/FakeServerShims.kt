package net.darkrelay.deaddropfinance.serversync

import org.json.JSONArray
import org.json.JSONObject

// The shared module keeps this helper internal; FakeServer.kt (copied into these tests) uses it.
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
