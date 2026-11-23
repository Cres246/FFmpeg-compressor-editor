package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.CompletedOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fflabDataStore: DataStore<Preferences> by preferencesDataStore(name = "fflab_prefs")

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val HISTORY_BLOB = stringPreferencesKey("recent_outputs_v1")
    }

    val historyFlow: Flow<List<CompletedOutput>> = context.fflabDataStore.data.map { prefs ->
        val raw = prefs[Keys.HISTORY_BLOB].orEmpty()
        decodeHistory(raw)
    }

    suspend fun appendHistory(items: List<CompletedOutput>) {
        if (items.isEmpty()) return
        context.fflabDataStore.edit { prefs ->
            val current = decodeHistory(prefs[Keys.HISTORY_BLOB].orEmpty())
            val merged = (items + current).distinctBy { it.id }.take(25)
            prefs[Keys.HISTORY_BLOB] = encodeHistory(merged)
        }
    }

    suspend fun clearHistory() {
        context.fflabDataStore.edit { prefs ->
            prefs.remove(Keys.HISTORY_BLOB)
        }
    }

    private fun encodeHistory(items: List<CompletedOutput>): String {
        return items.joinToString("\n") { item ->
            listOf(
                sanitize(item.id),
                sanitize(item.fileName),
                sanitize(item.displayPath),
                sanitize(item.contentUriString),
                sanitize(item.mimeType),
                item.outputSizeBytes.toString(),
                item.inputSizeBytes.toString(),
                sanitize(item.modeLabel),
                item.timestampMs.toString()
            ).joinToString("\t")
        }
    }

    private fun decodeHistory(raw: String): List<CompletedOutput> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split("\t")
            if (p.size < 9) return@mapNotNull null
            CompletedOutput(
                id = p[0],
                fileName = p[1],
                displayPath = p[2],
                contentUriString = p[3],
                mimeType = p[4],
                outputSizeBytes = p[5].toLongOrNull() ?: 0L,
                inputSizeBytes = p[6].toLongOrNull() ?: 0L,
                modeLabel = p[7],
                timestampMs = p[8].toLongOrNull() ?: 0L
            )
        }.toList()
    }

    private fun sanitize(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
