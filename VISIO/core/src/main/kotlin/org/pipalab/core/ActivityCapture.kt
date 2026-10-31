package org.pipalab.core

import org.json.JSONObject

/** A requested recording is not a completed recording or a verified activity. */
data class ActivityCapture(
    val id: String, val typeId: String, val activity: String, val description: String,
    val audio: Boolean, val durationSeconds: Int, val createdMs: Long,
    val technicalTest: Boolean = false
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(typeId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(activity.isNotBlank() && activity.length <= 60)
        require(description.length <= 1200)
        require(durationSeconds in 15..600)
        require(createdMs > 0)
    }
    fun json() = JSONObject().put("id", id).put("type_id", typeId).put("activity", activity)
        .put("description", description).put("audio_requested", audio).put("duration_seconds", durationSeconds)
        .put("created_ms", createdMs).put("technical_test", technicalTest).put("schema_version", 1)
    companion object {
        fun parse(j: JSONObject) = ActivityCapture(j.getString("id"), j.getString("type_id"),
            j.getString("activity"), j.optString("description"), j.getBoolean("audio_requested"),
            j.getInt("duration_seconds"), j.getLong("created_ms"), j.optBoolean("technical_test"))
    }
}

object ActivityTypes {
    val defaults = linkedMapOf("rest" to "Reposo", "walking" to "Caminar", "eating" to "Comer",
        "drinking" to "Beber", "phone" to "Usar teléfono", "lighter" to "Usar encendedor",
        "keys" to "Manipular llaves", "clap" to "Aplaudir", "target" to "Actividad objetivo", "other" to "Otra actividad")
    val markers = linkedMapOf("lighter" to "Encendedor", "gesture" to "Gesto", "noise" to "Otro sonido", "sync" to "Sincronización")
    fun transferable(name: String) = name.matches(Regex("[A-Za-z0-9_.-]{1,80}")) && !name.contains("..") &&
        (name.endsWith(".csv") || name.endsWith(".wav") || name in setOf("meta.json", "audio.json"))
}
