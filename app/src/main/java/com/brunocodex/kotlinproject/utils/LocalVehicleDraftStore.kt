package com.brunocodex.kotlinproject.utils

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object LocalVehicleDraftStore {

    data class Entry(
        val id: String,
        val payloadJson: String,
        val updatedAt: Long
    )

    private const val PREFS_NAME = "vehicle_register_draft_prefs"
    private const val KEY_DRAFTS = "provider_vehicle_register_drafts"
    private const val LEGACY_SINGLE_DRAFT_KEY = "provider_vehicle_register_draft"

    fun readAll(context: Context): List<Entry> {
        val prefs = prefs(context)
        migrateLegacySingleDraftIfNeeded(prefs)

        val raw = prefs.getString(KEY_DRAFTS, null).orEmpty().trim()
        if (raw.isBlank()) return emptyList()

        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val entries = mutableListOf<Entry>()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val payloadJson = item.optString("payloadJson").trim()
            val updatedAt = item.optLong("updatedAt", 0L)
            if (id.isBlank() || payloadJson.isBlank()) continue
            entries += Entry(
                id = id,
                payloadJson = payloadJson,
                updatedAt = updatedAt
            )
        }

        return entries.sortedByDescending { it.updatedAt }
    }

    fun upsert(context: Context, entry: Entry) {
        val entries = readAll(context).toMutableList()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            entries[index] = entry
        } else {
            entries += entry
        }
        persist(context, entries)
    }

    fun remove(context: Context, draftId: String) {
        val targetId = draftId.trim()
        if (targetId.isBlank()) return
        val entries = readAll(context).filterNot { it.id == targetId }
        persist(context, entries)
    }

    fun newDraftId(): String {
        val shortUuid = UUID.randomUUID().toString().replace("-", "").take(10)
        return "local_${System.currentTimeMillis()}_$shortUuid"
    }

    private fun persist(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("payloadJson", entry.payloadJson)
                    put("updatedAt", entry.updatedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_DRAFTS, array.toString()).apply()
    }

    private fun migrateLegacySingleDraftIfNeeded(prefs: android.content.SharedPreferences) {
        val legacyRaw = prefs.getString(LEGACY_SINGLE_DRAFT_KEY, null).orEmpty().trim()
        if (legacyRaw.isBlank()) return

        val alreadyMigrated = prefs.getString(KEY_DRAFTS, null).orEmpty().isNotBlank()
        if (alreadyMigrated) {
            prefs.edit().remove(LEGACY_SINGLE_DRAFT_KEY).apply()
            return
        }

        val migratedArray = JSONArray().put(
            JSONObject().apply {
                put("id", newDraftId())
                put("payloadJson", legacyRaw)
                put("updatedAt", System.currentTimeMillis())
            }
        )

        prefs.edit()
            .putString(KEY_DRAFTS, migratedArray.toString())
            .remove(LEGACY_SINGLE_DRAFT_KEY)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
