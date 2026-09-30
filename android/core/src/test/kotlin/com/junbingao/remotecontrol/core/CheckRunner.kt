package com.junbingao.remotecontrol.core

import kotlin.test.fail

/**
 * `ios/Verification`'s assertion runner: a check that fails is recorded under its label and the
 * group carries on, so one run names every failure at once. [assertAll] ends a test with all of
 * them, or with nothing.
 */
class CheckRunner(private val group: String) {
    var passed = 0
        private set
    private val failures = mutableListOf<String>()

    fun expect(condition: Boolean, label: String) {
        if (condition) passed += 1 else failures.add("$group: $label")
    }

    fun <T> equal(actual: T, expected: T, label: String) {
        if (actual == expected) passed += 1 else failures.add("$group: $label — got $actual, expected $expected")
    }

    fun noThrow(label: String, body: () -> Unit) {
        try {
            body()
            passed += 1
        } catch (error: Throwable) {
            failures.add("$group: $label threw $error")
        }
    }

    fun throwsError(label: String, body: () -> Unit) {
        try {
            body()
            failures.add("$group: $label should have thrown")
        } catch (_: Throwable) {
            passed += 1
        }
    }

    fun assertAll() {
        if (failures.isNotEmpty()) fail("${failures.size} of ${failures.size + passed} checks failed:\n" + failures.joinToString("\n"))
    }
}
