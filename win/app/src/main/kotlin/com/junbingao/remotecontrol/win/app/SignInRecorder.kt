package com.junbingao.remotecontrol.win.app

import com.junbingao.remotecontrol.core.state.GatewayAPI
import com.junbingao.remotecontrol.core.transport.LoginResponse

/**
 * The error the last sign-in or registration ended with, kept as the gateway sent it.
 *
 * `ConnectionStore` words a refusal in the iPhone app's sentences before any screen sees it; the
 * login page says the web's instead, which are chosen by status (`signInErrorText` and
 * `registerErrorText` in `LoginPage.tsx`). This is the Windows-side adapter that keeps the status,
 * as the Mac's is: every API the store builds is wrapped by `RecordingGatewayAPI`, which notes what
 * `login` and `register` threw.
 */
class SignInRecorder {
    @Volatile
    var lastError: Throwable? = null
        private set

    internal fun record(error: Throwable?) {
        lastError = error
    }
}

/** A `GatewayAPI` that passes every call to the one it wraps and notes how a sign-in or a registration ended. */
internal class RecordingGatewayAPI(val base: GatewayAPI, private val recorder: SignInRecorder) : GatewayAPI by base {
    override suspend fun login(username: String, password: String): LoginResponse =
        recording { base.login(username, password) }

    override suspend fun register(username: String, password: String): LoginResponse =
        recording { base.register(username, password) }

    private inline fun recording(call: () -> LoginResponse): LoginResponse {
        try {
            val response = call()
            recorder.record(null)
            return response
        } catch (error: Throwable) {
            recorder.record(error)
            throw error
        }
    }
}
