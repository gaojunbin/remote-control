package com.junbingao.remotecontrol.android.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each of the recogniser's endings means for a dictation with no maximum duration. */
class RecognitionRulesTest {
    @Test
    fun aPauseIsARestartAndNothingMore() {
        assertEquals(RecognitionOutcome.Restart, RecognitionErrors.outcome(SpeechRecognizer.ERROR_NO_MATCH))
        assertEquals(RecognitionOutcome.Restart, RecognitionErrors.outcome(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
    }

    @Test
    fun aRefusedMicrophoneAndAMissingLanguageEndDictationAtOnce() {
        assertEquals(RecognitionOutcome.Fail(SpeechInputFailure.MicrophonePermission),
            RecognitionErrors.outcome(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(RecognitionOutcome.Fail(SpeechInputFailure.Unsupported), RecognitionErrors.outcome(12))
        assertEquals(RecognitionOutcome.Fail(SpeechInputFailure.Unsupported), RecognitionErrors.outcome(13))
        assertEquals(RecognitionOutcome.Fail(SpeechInputFailure.Recording), RecognitionErrors.outcome(SpeechRecognizer.ERROR_AUDIO))
    }

    @Test
    fun anythingElseIsARestartWhileTheRecogniserIsNotBroken() {
        assertEquals(RecognitionOutcome.RestartIfRecoverable, RecognitionErrors.outcome(SpeechRecognizer.ERROR_SERVER))
        assertEquals(RecognitionOutcome.RestartIfRecoverable, RecognitionErrors.outcome(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }

    @Test
    fun threeFailuresRightAfterStartingAreABrokenRecogniser() {
        val failures = PrematureFailures()
        assertTrue(failures.recoverable(elapsedMillis = 100))
        assertTrue(failures.recoverable(elapsedMillis = 100))
        assertFalse(failures.recoverable(elapsedMillis = 100))
    }

    @Test
    fun aFailureAfterAStretchOfListeningStartsTheCountAgain() {
        val failures = PrematureFailures()
        failures.recoverable(100)
        failures.recoverable(100)
        assertTrue(failures.recoverable(6_000))
        assertTrue(failures.recoverable(100))
        assertTrue(failures.recoverable(100))
        assertFalse(failures.recoverable(100))
    }

    @Test
    fun wordsHeardProveTheRecogniserWorks() {
        val failures = PrematureFailures()
        failures.recoverable(100)
        failures.recoverable(100)
        failures.reset()
        assertTrue(failures.recoverable(100))
    }

    @Test
    fun theRecognisersLoudnessSpansTheGlow() {
        assertEquals(0.0, RecognizerLevel.from(-5f), 0.0)
        assertEquals(0.0, RecognizerLevel.from(RecognizerLevel.quietDB.toFloat()), 0.0)
        assertEquals(0.5, RecognizerLevel.from(4f), 1e-9)
        assertEquals(1.0, RecognizerLevel.from(14f), 0.0)
        assertEquals(0.0, RecognizerLevel.from(Float.NaN), 0.0)
    }

    @Test
    fun aLanguageIsTheSameWhateverItsSpelling() {
        assertTrue(LanguageTags.same("zh-CN", "cmn-Hans-CN"))
        assertTrue(LanguageTags.same("en-US", "en_us"))
        assertTrue(LanguageTags.same("ja-JP", "ja"))
        assertFalse(LanguageTags.same("en-US", "en-GB"))
        assertFalse(LanguageTags.same("de-DE", "fr-FR"))
    }

    @Test
    fun anUnknownLanguageListIsNotARefusal() {
        assertNull(SpeechLanguages.unknown.hearsOnDevice("zh-CN"))
        val known = SpeechLanguages(installed = setOf("cmn-Hans-CN", "en-US"), supported = setOf("cmn-Hans-CN", "en-US", "de-DE"))
        assertEquals(true, known.hearsOnDevice("zh-CN"))
        assertEquals(false, known.hearsOnDevice("de-DE"))
        assertEquals("cmn-Hans-CN", known.spelling("zh-CN"))
        assertEquals("fr-FR", known.spelling("fr-FR"))
    }
}
