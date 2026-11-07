package it.orbitacasa.web

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : AppCompatActivity() {

    private var lista: MutableList<Sessione> = mutableListOf()
    private lateinit var elenco: LinearLayout
    private lateinit var vuoto: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        elenco = findViewById(R.id.elenco)
        vuoto = findViewById(R.id.vuoto)

        findViewById<MaterialButton>(R.id.btnAggiungi).setOnClickListener { dialogoSessione(null, null) }
        findViewById<MaterialButton>(R.id.btnQr).setOnClickListener { scansiona() }
    }

    override fun onResume() {
        super.onResume()
        aggiorna()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun aggiorna() {
        lista = SessioniStore.elenco(this)
        elenco.removeAllViews()
        vuoto.visibility = if (lista.isEmpty()) View.VISIBLE else View.GONE

        for (s in lista) {
            val card = layoutInflater.inflate(R.layout.item_sessione, elenco, false) as MaterialCardView
            card.findViewById<TextView>(R.id.tvNome).text = s.nome
            card.findViewById<TextView>(R.id.tvUrl).text =
                if (s.autofirmato) "${s.url} · certificato autofirmato" else s.url
            card.setOnClickListener {
                startActivity(
                    Intent(this, WebActivity::class.java).putExtra("id", s.id)
                )
            }
            val menuBtn = card.findViewById<MaterialButton>(R.id.btnMenu)
            menuBtn.setOnClickListener {
                PopupMenu(this, menuBtn).apply {
                    menu.add(0, 1, 0, "Modifica")
                    menu.add(0, 2, 1, "Elimina")
                    setOnMenuItemClickListener { item ->
                        if (item.itemId == 1) dialogoSessione(s, null) else confermaElimina(s)
                        true
                    }
                    show()
                }
            }
            elenco.addView(card)
        }
    }

    // ---------------------------------------------------------------- QR

    private fun scansiona() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(this, options).startScan()
            .addOnSuccessListener { b ->
                val testo = b.rawValue?.trim().orEmpty()
                if (testo.isEmpty()) {
                    toast("QR vuoto")
                } else {
                    val url = urlDaQr(testo)
                    if (url == null) toast("QR non valido: serve l'indirizzo del server (https://...)")
                    else dialogoSessione(null, url)
                }
            }
            .addOnFailureListener { e -> toast("Scansione non riuscita: ${e.message}") }
    }

    private fun urlDaQr(testo: String): String? {
        val t = testo.trim()
        if (!(t.startsWith("http://", true) || t.startsWith("https://", true))) return null
        val u = Uri.parse(t)
        val percorso = u.path ?: ""
        // Il QR Indirizzo contiene l'indirizzo del webhook: si tiene solo il server
        return if (percorso.startsWith("/api/")) "${u.scheme}://${u.authority}" else t
    }

    // ------------------------------------------------------ Aggiungi / modifica

    private fun dialogoSessione(s: Sessione?, urlIniziale: String?) {
        val vista = layoutInflater.inflate(R.layout.dialog_sessione, null)
        val tilNome = vista.findViewById<TextInputLayout>(R.id.tilNome)
        val tilUrl = vista.findViewById<TextInputLayout>(R.id.tilUrl)
        val etNome = vista.findViewById<TextInputEditText>(R.id.etNome)
        val etUrl = vista.findViewById<TextInputEditText>(R.id.etUrl)
        val swAuto = vista.findViewById<MaterialSwitch>(R.id.swAutofirmato)

        etNome.setText(s?.nome ?: "")
        etUrl.setText(s?.url ?: urlIniziale ?: "")
        swAuto.isChecked = s?.autofirmato ?: false

        val dialogo = MaterialAlertDialogBuilder(this)
            .setTitle(if (s == null) "Nuova sessione" else "Modifica sessione")
            .setView(vista)
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()

        dialogo.setOnShowListener {
            dialogo.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                var u = etUrl.text.toString().trim()
                if (u.isEmpty()) {
                    tilUrl.error = "Indirizzo mancante"
                    return@setOnClickListener
                }
                tilUrl.error = null
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                val n = etNome.text.toString().trim().ifEmpty { Uri.parse(u).host ?: u }
                tilNome.error = null
                if (s == null) {
                    lista.add(Sessione(SessioniStore.nuovoId(), n, u, swAuto.isChecked))
                } else {
                    s.nome = n
                    s.url = u
                    s.autofirmato = swAuto.isChecked
                }
                SessioniStore.salva(this, lista)
                dialogo.dismiss()
                aggiorna()
            }
        }
        dialogo.show()
    }

    private fun confermaElimina(s: Sessione) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminare «${s.nome}»?")
            .setMessage("Si cancellano anche login e dati di questa sessione.")
            .setPositiveButton("Elimina") { _, _ ->
                lista.removeAll { it.id == s.id }
                SessioniStore.salva(this, lista)
                SessioniStore.salvaCookie(this, s.id, "")
                try {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        ProfileStore.getInstance().deleteProfile(s.profilo)
                    }
                } catch (e: Exception) {
                    // profilo ancora in memoria: i dati restano ma la sessione non esiste più
                }
                aggiorna()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }
}
