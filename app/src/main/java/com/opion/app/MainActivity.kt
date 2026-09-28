// Developed by Yuri Alexander Pagel Krüger

package com.opion.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.opion.app.audit.AuditItem
import com.opion.app.audit.Level
import com.opion.app.audit.SecurityAuditor
import com.opion.app.clipboard.ClipboardWiper
import com.opion.app.databinding.ActivityMainBinding
import com.opion.app.databinding.ItemAuditBinding
import com.opion.app.exif.ExifSanitizer
import com.opion.app.exif.SanitizeResult
import com.opion.app.exif.Strategy
import com.opion.app.links.TrackingCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val console = StringBuilder()

    private sealed interface Output {
        data class Text(val value: String) : Output
        data class Image(val result: SanitizeResult) : Output
    }

    private var output: Output? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) startSanitize(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        wireUi()
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshClipboardState()
    }

    private fun wireUi() {
        binding.btnExif.setOnClickListener { pickImage.launch("image/*") }
        binding.btnCleanLink.setOnClickListener { cleanFromInput() }
        binding.btnWipe.setOnClickListener { wipeClipboard() }
        binding.btnShortcuts.setOnClickListener {
            startActivity(Intent(this, ShortcutsActivity::class.java))
        }
        binding.btnAudit.setOnClickListener { runAudit() }

        binding.btnShareOut.setOnClickListener { shareOutput() }
        binding.btnCopyOut.setOnClickListener { copyOutput() }
        binding.btnSaveOut.setOnClickListener { saveToGallery() }
    }

    private fun setBusy(busy: Boolean) {
        binding.pbBusy.isVisible = busy
        binding.btnExif.isEnabled = !busy
        binding.btnAudit.isEnabled = !busy
        binding.btnCleanLink.isEnabled = !busy
    }

    private fun log(line: String) {
        if (console.isNotEmpty()) console.append('\n')
        console.append(line)
        if (console.length > 7000) console.delete(0, console.length - 7000)
        binding.tvConsole.text = console.toString()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type.orEmpty()
                if (type.startsWith("image/")) {
                    val uri = IntentCompat.getParcelableExtra(
                        intent, Intent.EXTRA_STREAM, Uri::class.java
                    )
                    if (uri != null) {
                        log(getString(R.string.con_share_image))
                        startSanitize(uri)
                        return
                    }
                }
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                if (!text.isNullOrBlank()) {
                    log(getString(R.string.con_share_text))
                    cleanText(text)
                    return
                }
                log(getString(R.string.con_share_empty))
            }

            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!text.isNullOrBlank()) {
                    log(getString(R.string.con_share_selected))
                    cleanText(text)
                }
            }
        }
    }

    private fun startSanitize(uri: Uri) {
        setBusy(true)
        log(getString(R.string.con_processing))
        lifecycleScope.launch {
            val res = ExifSanitizer.sanitize(this@MainActivity, uri)
            setBusy(false)

            if (!res.ok) {
                log("✗ " + (res.error ?: getString(R.string.con_err_unknown)))
                toast(res.error ?: getString(R.string.con_err_process))
                return@launch
            }

            output = Output.Image(res)
            binding.btnShareOut.text = getString(R.string.btn_share_file)
            binding.btnCopyOut.isVisible = false
            binding.btnSaveOut.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            binding.rowOutputActions.isVisible = true

            log(
                getString(R.string.con_clean_copy, res.fileName) + "\n" +
                        getString(
                            R.string.con_method,
                            getString(
                                if (res.strategy == Strategy.STRIP_TAGS)
                                    R.string.con_method_strip
                                else
                                    R.string.con_method_reencode
                            )
                        ) + "\n" +
                        when {
                            res.removedTags > 0 ->
                                getString(R.string.con_tags_removed, res.removedTags) + "\n"

                            res.removedTags < 0 ->
                                getString(R.string.con_exif_block_dropped) + "\n"

                            else ->
                                getString(R.string.con_no_metadata) + "\n"
                        } +
                        getString(R.string.con_orientation) +
                        if (res.note.isNotEmpty())
                            "\n" + getString(R.string.con_note, res.note)
                        else ""
            )
        }
    }

    private fun cleanFromInput() {
        val input = binding.etLink.text?.toString().orEmpty()
        if (input.isBlank()) {
            toast(getString(R.string.con_paste_first))
            return
        }
        cleanText(input)
    }

    private fun cleanText(raw: String) {
        val result = TrackingCleaner.cleanAll(raw)

        if (!result.changed) {
            log(getString(R.string.con_no_change, getString(R.string.mod2_nochange)))
        } else {
            log(
                getString(R.string.con_urls_processed, result.cleanedUrls.size) + "\n" +
                        (
                                if (result.removedParams.isNotEmpty())
                                    getString(
                                        R.string.con_params_removed,
                                        result.removedParams.joinToString(", ")
                                    ) + "\n"
                                else ""
                                ) +
                        getString(R.string.con_result_header) + "\n" +
                        result.cleanedText
            )
        }

        output = Output.Text(result.cleanedText)
        binding.etLink.setText(result.cleanedText)
        binding.etLink.setSelection(result.cleanedText.length)

        binding.btnShareOut.text = getString(R.string.btn_share_text)
        binding.btnCopyOut.isVisible = true
        binding.btnSaveOut.isVisible = false
        binding.rowOutputActions.isVisible = true
    }

    private fun refreshClipboardState() {
        val snap = ClipboardWiper.snapshot(this)
        binding.tvClipState.text =
            if (!snap.hasContent) getString(R.string.mod3_state_none)
            else getString(R.string.mod3_state_some, snap.length)
    }

    private fun wipeClipboard() {
        val ok = ClipboardWiper.wipe(this)
        log(getString(if (ok) R.string.con_clip_ok else R.string.con_clip_fail))
        toast(getString(if (ok) R.string.mod3_done else R.string.mod3_fail))
        refreshClipboardState()
    }

    private fun runAudit() {
        setBusy(true)
        log(getString(R.string.con_audit_running))
        lifecycleScope.launch {
            val items = SecurityAuditor.run(this@MainActivity)
            setBusy(false)
            renderAudit(items)

            val bad = items.count { it.level == Level.BAD }
            val warn = items.count { it.level == Level.WARN }
            log(getString(R.string.con_audit_done, items.size, bad, warn))

            binding.scrollRoot.post {
                binding.scrollRoot.smoothScrollTo(0, binding.auditContainer.top)
            }
        }
    }

    private fun renderAudit(items: List<AuditItem>) {
        binding.auditContainer.removeAllViews()
        for (item in items) {
            val row = ItemAuditBinding.inflate(layoutInflater, binding.auditContainer, false)
            row.tvMarker.text = when (item.level) {
                Level.OK -> "[OK]"
                Level.INFO -> "[INFO]"
                Level.WARN -> "[WARN]"
                Level.BAD -> "[FAIL]"
            }
            row.tvMarker.setTextColor(
                getColor(
                    when (item.level) {
                        Level.OK -> R.color.ok
                        Level.INFO -> R.color.info
                        Level.WARN -> R.color.warn
                        Level.BAD -> R.color.bad
                    }
                )
            )
            row.tvTitle.text = item.title
            row.tvDetail.text = item.detail
            binding.auditContainer.addView(row.root)
        }
    }

    private fun shareOutput() {
        when (val o = output) {
            is Output.Text -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, o.value)
                }
                startActivity(Intent.createChooser(send, getString(R.string.chooser_title)))
            }

            is Output.Image -> {
                val uri = o.result.shareUri ?: return
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = o.result.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.chooser_title)))
            }

            null -> toast(getString(R.string.nothing_to_share))
        }
    }

    private fun copyOutput() {
        val o = output as? Output.Text ?: return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), o.value))
        toast(getString(R.string.copied_ok))
        refreshClipboardState()
    }

    private fun saveToGallery() {
        val o = output as? Output.Image ?: return
        val file = o.result.file ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            toast(getString(R.string.con_gallery_needs_q))
            return
        }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                ExifSanitizer.saveToGallery(
                    this@MainActivity,
                    file,
                    o.result.fileName,
                    o.result.mimeType
                )
            }
            log(getString(if (uri != null) R.string.con_gallery_ok else R.string.con_gallery_fail))
            toast(
                getString(
                    if (uri != null) R.string.con_gallery_toast_ok
                    else R.string.con_gallery_toast_fail
                )
            )
        }
    }
}
