package it.orbitacasa.web

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Sessione(
    val id: String,
    var nome: String,
    var url: String,
    var autofirmato: Boolean
) {
    // Nome del profilo WebView: ogni sessione ha cookie e storage propri
    val profilo: String get() = "s$id"
}

object SessioniStore {
    private fun prefs(c: Context) = c.getSharedPreferences("orbitaweb", Context.MODE_PRIVATE)

    fun elenco(c: Context): MutableList<Sessione> {
        val out = mutableListOf<Sessione>()
        try {
            val arr = JSONArray(prefs(c).getString("sessioni", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Sessione(
                        o.getString("id"),
                        o.getString("nome"),
                        o.getString("url"),
                        o.optBoolean("autofirmato", false)
                    )
                )
            }
        } catch (e: Exception) {
            // elenco illeggibile: si riparte vuoti
        }
        return out
    }

    fun salva(c: Context, lista: List<Sessione>) {
        val arr = JSONArray()
        for (s in lista) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("nome", s.nome)
                    .put("url", s.url)
                    .put("autofirmato", s.autofirmato)
            )
        }
        prefs(c).edit().putString("sessioni", arr.toString()).apply()
    }

    fun nuovoId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    // Usati solo se il WebView del telefono non supporta i profili
    fun cookie(c: Context, id: String): String = prefs(c).getString("cookie_$id", "") ?: ""

    fun salvaCookie(c: Context, id: String, valore: String) {
        prefs(c).edit().putString("cookie_$id", valore).apply()
    }
}
