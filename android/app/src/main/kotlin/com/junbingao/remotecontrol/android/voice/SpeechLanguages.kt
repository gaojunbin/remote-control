package com.junbingao.remotecontrol.android.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Which languages the phone's recogniser can hear, and which of them it can hear without the
 * network — the counterpart of `SFSpeechRecognizer.supportedLocales()` and
 * `supportsOnDeviceRecognition`, behind the composer's "No on-device model for this language".
 *
 * Android says so from version 13 on. Below that nothing answers, and each set is null — not
 * empty: an unknown is not a refusal, and the recogniser itself reports a language it lacks.
 */
data class SpeechLanguages(
    /** Languages with an on-device model ready now. */
    val installed: Set<String>?,
    /** Languages an on-device model exists for, installed or not. */
    val supported: Set<String>?,
) {
    /** Whether [tag] can be heard on the device now: true, false, or null for unknown. */
    fun hearsOnDevice(tag: String): Boolean? = installed?.any { LanguageTags.same(it, tag) }

    /** The recogniser's own spelling of [tag], which is what to ask it for. */
    fun spelling(tag: String): String = installed?.firstOrNull { LanguageTags.same(it, tag) } ?: tag

    companion object {
        val unknown = SpeechLanguages(installed = null, supported = null)

        /** Ask the on-device recogniser. Must be called on the main thread. */
        suspend fun query(context: Context, tag: String = "en-US"): SpeechLanguages {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return unknown
            return support(context, tag)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private suspend fun support(context: Context, tag: String): SpeechLanguages {
            val recognizer = RecognizerRuns.make(context) ?: return unknown
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            return try {
                suspendCancellableCoroutine { continuation ->
                    recognizer.checkRecognitionSupport(
                        intent, ContextCompat.getMainExecutor(context),
                        object : RecognitionSupportCallback {
                            override fun onSupportResult(support: RecognitionSupport) {
                                if (continuation.isActive) continuation.resume(languages(support))
                            }

                            override fun onError(error: Int) {
                                if (continuation.isActive) continuation.resume(unknown)
                            }
                        },
                    )
                }
            } finally {
                recognizer.destroy()
            }
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun languages(support: RecognitionSupport) = SpeechLanguages(
            installed = support.installedOnDeviceLanguages.toSet(),
            supported = (support.supportedOnDeviceLanguages + support.installedOnDeviceLanguages).toSet(),
        )
    }
}

/**
 * Whether two language tags name the same language the way a person means it: `zh-CN` and
 * Google's `cmn-Hans-CN` are both Mandarin in simplified Chinese, `en-US` and `en_us` are one
 * English. The region counts only when both tags carry one.
 */
internal object LanguageTags {
    fun same(a: String, b: String): Boolean {
        val left = parse(a)
        val right = parse(b)
        if (left.language != right.language) return false
        return left.region == null || right.region == null || left.region == right.region
    }

    private data class Tag(val language: String, val region: String?)

    private fun parse(tag: String): Tag {
        val parts = tag.replace('_', '-').lowercase().split('-').filter { it.isNotEmpty() }
        val language = when (val first = parts.firstOrNull() ?: "") {
            "cmn", "zh" -> "zh"
            "yue" -> "yue"
            else -> first
        }
        val region = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
        return Tag(language, region)
    }
}
