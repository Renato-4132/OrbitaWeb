package it.orbitacasa.web

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class WebActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var barra: LinearProgressIndicator
    private lateinit var sess: Sessione
    private var multi = false
    private var pwPendente: String? = null
    private var tentativoImpronta = false
    private var offertaFatta = false

    // Tiene in memoria i blob creati dalla pagina: se la pagina li revoca subito dopo il click
    // (comune nei pulsanti "scarica"), il download funziona lo stesso.
    private val jsCache = "(function(){if(window.__orbitaBlobs)return;window.__orbitaBlobs={};" +
        "var c=URL.createObjectURL;URL.createObjectURL=function(o){var u=c.apply(this,arguments);" +
        "try{if(o instanceof Blob)window.__orbitaBlobs[u]=o;}catch(e){}return u;};})();"
    // Intercetta i click sui link <a download> (es. il tasto ⬇️ dei documenti) e scarica il file
    // direttamente dalla pagina, con la sessione già autenticata, poi lo passa all'app.
    private val jsDownload = "(function(){if(window.__orbitaDl)return;window.__orbitaDl=1;" +
        "document.addEventListener('click',function(e){" +
        "var a=e.target&&e.target.closest?e.target.closest('a[download]'):null;" +
        "if(!a||!a.href||a.href.indexOf('blob:')===0||a.href.indexOf('data:')===0)return;" +
        "e.preventDefault();e.stopPropagation();" +
        "var nome=a.getAttribute('download')||'';" +
        "try{OrbitaApp.inizio();}catch(x){}" +
        "fetch(a.href,{credentials:'include'}).then(function(r){" +
        "if(!r.ok)throw new Error('HTTP '+r.status);" +
        "var t=(r.headers.get('Content-Type')||'').split(';')[0].trim();" +
        "if(t==='text/html')throw new Error('sessione scaduta, rifai il login');" +
        "return r.blob().then(function(b){return {b:b,t:t};});" +
        "}).then(function(o){var fr=new FileReader();" +
        "fr.onloadend=function(){OrbitaApp.salvaBlob(fr.result,o.t,nome);};" +
        "fr.onerror=function(){OrbitaApp.errore('lettura del file');};fr.readAsDataURL(o.b);" +
        "}).catch(function(err){OrbitaApp.errore(String(err&&err.message||err));});" +
        "},true);})();"
    // Pagina di login: nasconde il tasto biometrico web (non funziona nella WebView), ricorda la
    // password digitata e, se l'impronta è attiva, aggiunge il tasto "Accedi con impronta".
    private fun jsLogin(conTasto: Boolean): String =
        "(function(){var f=document.getElementById('loginForm'),p=document.getElementById('pass');" +
        "if(!f||!p||window.__orbitaLg)return;window.__orbitaLg=1;" +
        "var st=document.createElement('style');" +
        "st.textContent='#btnBiometrico,#msgBiometrico{display:none!important}';document.head.appendChild(st);" +
        "f.addEventListener('submit',function(){try{OrbitaApp.pwInvio(p.value);}catch(x){}},true);" +
        (if (conTasto)
            "var b=document.createElement('button');b.type='button';b.className='btn-submit';" +
            "b.style.marginTop='8px';b.textContent='\uD83D\uDC46 Accedi con impronta';" +
            "b.onclick=function(){try{OrbitaApp.improntaLogin();}catch(x){}};" +
            "f.parentNode.insertBefore(b,f.nextSibling);"
        else "") +
        "})();"
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var permPendente: PermissionRequest? = null

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
            fileCb = null
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val richiesta = permPendente
            if (richiesta != null) {
                if (ok) richiesta.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else richiesta.deny()
            }
            permPendente = null
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // Cookie manager del profilo della sessione (o quello globale se i profili non ci sono)
    private fun cookies(): CookieManager =
        if (multi) ProfileStore.getInstance().getOrCreateProfile(sess.profilo).cookieManager
        else CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra("id")
        val trovata = SessioniStore.elenco(this).firstOrNull { it.id == id }
        if (trovata == null) {
            finish()
            return
        }
        sess = trovata

        web = WebView(this)
        multi = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        // Deve essere la prima chiamata sul WebView
        if (multi) WebViewCompat.setProfile(web, sess.profilo)

        setContentView(R.layout.activity_web)
        barra = findViewById(R.id.barra)
        findViewById<FrameLayout>(R.id.webHolder).addView(
            web,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.web_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.azione_ricarica -> {
                    web.reload()
                    true
                }
                R.id.azione_browser -> {
                    val u = web.url?.takeIf { it.startsWith("http") } ?: sess.url
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "Nessuna app per aprire il link", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        cookies().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Ponte(), "OrbitaApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val u = request?.url ?: return false
                val schema = u.scheme ?: ""
                // blob:/data: devono restare nel WebView, così arrivano al listener dei download
                if (schema == "blob" || schema == "data" || schema == "about" || schema == "javascript") return false
                if (schema == "http" || schema == "https") {
                    if (u.host == Uri.parse(sess.url).host) return false
                }
                // Link esterni (altri siti, mailto, tel...) al sistema
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, u))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this@WebActivity, "Nessuna app per aprire il link", Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                barra.visibility = View.VISIBLE
                view?.evaluateJavascript(jsCache, null)
                view?.evaluateJavascript(jsDownload, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(jsCache, null)
                view?.evaluateJavascript(jsDownload, null)
                gestisciLogin(view, url)
                barra.visibility = View.INVISIBLE
                salvaCookie()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                val r = request ?: return
                if (r.isForMainFrame) mostraErrore(view, r.url.toString(), error?.errorCode ?: 0)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                if (sess.autofirmato) {
                    handler?.proceed()
                    return
                }
                MaterialAlertDialogBuilder(this@WebActivity)
                    .setTitle("Certificato non attendibile")
                    .setMessage("Il server usa un certificato che il telefono non riesce a verificare. Continuare per questa sessione?")
                    .setPositiveButton("Continua") { _, _ ->
                        sess.autofirmato = true
                        val l = SessioniStore.elenco(this@WebActivity)
                        l.firstOrNull { it.id == sess.id }?.autofirmato = true
                        SessioniStore.salva(this@WebActivity, l)
                        handler?.proceed()
                    }
                    .setNegativeButton("Annulla") { _, _ ->
                        handler?.cancel()
                        finish()
                    }
                    .setOnCancelListener { handler?.cancel() }
                    .show()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                barra.progress = newProgress
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                fileCb?.onReceiveValue(null)
                fileCb = filePathCallback
                return try {
                    var filtro = true
                    val mimi = fileChooserParams.acceptTypes
                        .flatMap { it.split(",") }
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { t ->
                            if (t.startsWith(".")) {
                                MimeTypeMap.getSingleton().getMimeTypeFromExtension(t.removePrefix("."))
                                    .also { if (it == null) filtro = false }
                            } else t
                        }
                        .distinct()
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = if (filtro && mimi.size == 1) mimi[0] else "*/*"
                        if (filtro && mimi.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimi.toTypedArray())
                        putExtra(
                            Intent.EXTRA_ALLOW_MULTIPLE,
                            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                        )
                    }
                    fileLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val videoRichiesto = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (!videoRichiesto) {
                    request.deny()
                    return
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    permPendente = request
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            scarica(url, userAgent, contentDisposition, mimeType)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        avvia()
    }

    private fun avvia() {
        if (multi) {
            web.loadUrl(sess.url)
            return
        }
        // WebView senza profili: si ripristinano i cookie salvati di questa sessione
        Toast.makeText(
            this,
            "WebView non aggiornato: due sessioni sullo stesso server possono interferire",
            Toast.LENGTH_LONG
        ).show()
        val cm = CookieManager.getInstance()
        cm.removeAllCookies {
            SessioniStore.cookie(this, sess.id).split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { cm.setCookie(sess.url, it) }
            cm.flush()
            web.loadUrl(sess.url)
        }
    }

    private fun salvaCookie() {
        try {
            val cm = cookies()
            if (!multi) {
                SessioniStore.salvaCookie(this, sess.id, cm.getCookie(sess.url) ?: "")
            }
            cm.flush()
        } catch (e: Exception) {
            // niente
        }
    }

    private fun mostraErrore(view: WebView?, url: String, codice: Int) {
        val (titolo, dett) = when (codice) {
            WebViewClient.ERROR_HOST_LOOKUP ->
                "Server non trovato" to "L'indirizzo non risulta raggiungibile. Controlla la rete e l'indirizzo della sessione."
            WebViewClient.ERROR_TIMEOUT ->
                "Il server non risponde" to "La connessione è troppo lenta o il server è spento."
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO ->
                "Connessione non riuscita" to "Controlla di essere sul Wi-Fi di casa o connesso a Internet, e che il server sia acceso."
            else ->
                "Pagina non disponibile" to "Qualcosa è andato storto nel collegamento al server."
        }
        val host = android.text.TextUtils.htmlEncode(Uri.parse(url).host ?: url)
        val html = """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><style>
:root{color-scheme:light dark;--bg:#f4f6fb;--fg:#1c2230;--sub:#667085;--card:#fff;--acc:#2f6fed}
@media(prefers-color-scheme:dark){:root{--bg:#10141c;--fg:#e8ecf4;--sub:#98a2b3;--card:#1a2030;--acc:#6b9bff}}
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:var(--bg);color:var(--fg);font-family:system-ui,sans-serif;padding:24px;box-sizing:border-box}
.c{background:var(--card);border-radius:20px;padding:32px 24px;max-width:360px;width:100%;text-align:center;box-shadow:0 4px 24px rgba(0,0,0,.08)}
.i{font-size:56px;margin-bottom:8px}
h1{font-size:20px;margin:0 0 8px}
p{color:var(--sub);font-size:14px;line-height:1.5;margin:0 0 16px}
.h{font-size:12px;color:var(--sub);margin-bottom:22px;word-break:break-all}
button{width:100%;padding:14px;border:0;border-radius:12px;font-size:16px;font-weight:600}
.p{background:var(--acc);color:#fff;margin-bottom:10px}
.s{background:transparent;color:var(--acc)}
</style></head><body><div class="c"><div class="i">&#128225;</div>
<h1>$titolo</h1><p>$dett</p><div class="h">$host</div>
<button class="p" onclick="location.replace(U)">Riprova</button>
<button class="s" onclick="OrbitaApp.esci()">Torna alle sessioni</button></div>
<script>var U=${JSONObject.quote(url)};</script></body></html>"""
        view?.loadDataWithBaseURL(url, html, "text/html", "utf-8", null)
    }

    // ---------- Accesso con impronta ----------

    private fun gestisciLogin(view: WebView?, url: String?) {
        val u = url?.let { Uri.parse(it) } ?: return
        if (u.path == "/login") {
            if (tentativoImpronta && u.getQueryParameter("error") != null) {
                // la password salvata non vale più (cambiata): va riattivata
                Impronta.elimina(this, sess.id)
                Toast.makeText(this, "Password cambiata: accedi e riattiva l'impronta", Toast.LENGTH_LONG).show()
            }
            tentativoImpronta = false
            pwPendente = null
            val tasto = Impronta.disponibile(this) && Impronta.salvata(this, sess.id)
            view?.evaluateJavascript(jsLogin(tasto), null)
        } else if (pwPendente != null) {
            proponiImpronta()
        }
    }

    private fun proponiImpronta() {
        val pw = pwPendente ?: return
        pwPendente = null
        if (offertaFatta || !Impronta.disponibile(this) || Impronta.salvata(this, sess.id)) return
        offertaFatta = true
        MaterialAlertDialogBuilder(this)
            .setTitle("Accesso con impronta")
            .setMessage("Vuoi usare l'impronta per i prossimi accessi a questa sessione?")
            .setPositiveButton("Sì") { _, _ ->
                Impronta.salva(this, sess.id, pw) { ok, msg ->
                    val testo = if (ok) "Impronta attivata" else "Impronta non attivata: $msg"
                    Toast.makeText(this, testo, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun accediConImpronta() {
        Impronta.leggi(this, sess.id) { pw, msg ->
            if (pw == null) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } else {
                tentativoImpronta = true
                web.evaluateJavascript(
                    "(function(){var p=document.getElementById('pass'),f=document.getElementById('loginForm');" +
                        "if(p&&f){p.value=" + JSONObject.quote(pw) + ";f.submit();}})();",
                    null
                )
            }
        }
    }

    // ---------- Download (PDF compresi) ----------

    private fun jsEsc(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "").replace("\r", "")

    private fun scarica(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (url.startsWith("data:")) {
            val nome = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val testa = url.substringBefore(",")
            val corpo = url.substringAfter(",", "")
            val tipo = mimeType?.takeIf { it.isNotBlank() }
                ?: testa.removePrefix("data:").substringBefore(";").ifBlank { "application/octet-stream" }
            Thread {
                try {
                    val bytes = if (testa.contains(";base64")) Base64.decode(corpo, Base64.DEFAULT)
                    else Uri.decode(corpo).toByteArray()
                    salvaStream(nome, tipo, ByteArrayInputStream(bytes))
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Download non riuscito: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
            return
        }
        if (url.startsWith("blob:")) {
            var nome = URLUtil.guessFileName(url, contentDisposition, mimeType)
            if (mimeType == "application/pdf" && !nome.endsWith(".pdf", true)) nome = "documento.pdf"
            val mime = jsEsc(mimeType ?: "")
            val js = "(function(){var u='" + jsEsc(url) + "',blobs=window.__orbitaBlobs||{};" +
                "function send(b){var r=new FileReader();" +
                "r.onloadend=function(){OrbitaApp.salvaBlob(r.result,(b.type||'" + mime + "'),'" + jsEsc(nome) + "');};" +
                "r.onerror=function(){OrbitaApp.errore('lettura del file');};r.readAsDataURL(b);}" +
                "if(blobs[u]){send(blobs[u]);return;}" +
                "var x=new XMLHttpRequest();x.open('GET',u,true);x.responseType='blob';" +
                "x.onload=function(){send(x.response);};" +
                "x.onerror=function(){OrbitaApp.errore('il file non è più disponibile');};x.send();})();"
            web.evaluateJavascript(js, null)
            return
        }
        val cookie = cookies().getCookie(url)
        Toast.makeText(this, "Scarico il file…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                if (conn is HttpsURLConnection && sess.autofirmato) fidati(conn)
                if (!userAgent.isNullOrEmpty()) conn.setRequestProperty("User-Agent", userAgent)
                if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                if (conn.responseCode !in 200..299) throw IOException("HTTP " + conn.responseCode)
                val tipoHeader = conn.contentType?.substringBefore(";")?.trim()
                val tipo = if (!tipoHeader.isNullOrEmpty()) tipoHeader else (mimeType ?: "application/octet-stream")
                val disp = conn.getHeaderField("Content-Disposition") ?: contentDisposition
                val nome = URLUtil.guessFileName(url, disp, tipo)
                conn.inputStream.use { salvaStream(nome, tipo, it) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Download non riuscito: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun fidati(c: HttpsURLConnection) {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(tm), SecureRandom())
        c.sslSocketFactory = ctx.socketFactory
        c.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }

    private fun nomeSicuro(nome: String, mime: String): String {
        var n = nome.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (n.isEmpty()) n = "documento"
        if (mime == "application/pdf" && !n.endsWith(".pdf", true)) n += ".pdf"
        return n
    }

    private fun salvaStream(nomeOrig: String, mime: String, input: InputStream) {
        val nome = nomeSicuro(nomeOrig, mime)
        val tipo = if ((mime.isBlank() || mime == "application/octet-stream") && nome.endsWith(".pdf", true))
            "application/pdf" else mime
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, tipo)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Impossibile creare il file")
        contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
        runOnUiThread {
            Toast.makeText(this, "Salvato in Download: $nome", Toast.LENGTH_SHORT).show()
            apri(uri, tipo)
        }
    }

    private fun apri(uri: Uri, tipo: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, tipo)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            // nessun visualizzatore: il file resta in Download
        }
    }

    // Ponte JavaScript per i download di tipo blob:
    inner class Ponte {
        @JavascriptInterface
        fun pwInvio(p: String) {
            pwPendente = p
        }

        @JavascriptInterface
        fun esci() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun improntaLogin() {
            runOnUiThread { accediConImpronta() }
        }

        @JavascriptInterface
        fun inizio() {
            runOnUiThread { Toast.makeText(this@WebActivity, "Scarico il file…", Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun errore(m: String) {
            runOnUiThread {
                Toast.makeText(this@WebActivity, "Download non riuscito: $m", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun salvaBlob(dataUrl: String, mime: String, nome: String) {
            try {
                val b64 = dataUrl.substringAfter(",", "")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val tipo = if (mime.isNotBlank()) mime else (dataUrl.substringAfter("data:", "").substringBefore(";"))
                salvaStream(nome, tipo, ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@WebActivity, "Download non riuscito: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- Ciclo di vita ----------

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        salvaCookie()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
