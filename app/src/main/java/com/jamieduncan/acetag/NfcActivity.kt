package com.jamieduncan.acetag

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcA
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat

/**
 * Foreground dispatch boilerplate for the screens that touch tags. Subclasses just implement
 * [onTagScanned].
 *
 * The tech filter is NfcA and only NfcA: genuine Anycubic tags report no MifareUltralight tech
 * on at least a Pixel 9 Pro XL, so filtering on it silently never matches. See [Type2Tag].
 */
abstract class NfcActivity : AppCompatActivity() {

    protected val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

    protected abstract fun onTagScanned(tag: Tag)

    /** Null when NFC is usable, otherwise the reason to show the user. */
    protected fun nfcUnavailableReason(): String? = when {
        nfcAdapter == null -> "This device has no NFC hardware."
        nfcAdapter?.isEnabled == false -> "NFC is off. Turn it on in system settings, then come back."
        else -> null
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        adapter.enableForegroundDispatch(
            this,
            pendingIntent,
            arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)),
            arrayOf(arrayOf(NfcA::class.java.name)),
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)
            ?.let { onTagScanned(it) }
    }
}
