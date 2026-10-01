package com.junbingao.remotecontrol.core.state

import com.junbingao.remotecontrol.core.CheckRunner
import com.junbingao.remotecontrol.core.transport.TransportError
import kotlin.test.Test

/**
 * `ios/Verification/AccountChecks.swift`, the store's half: which sentence each refusal produces,
 * which is the part a reader actually meets. What the gateway answers, what the app sends and the
 * rules written once are in `transport/AccountChecks.kt`.
 */
class AccountChecks {
    // What each refusal says

    @Test
    fun refusals() {
        val checks = CheckRunner("accounts")
        checks.equal(AccountError.signIn(TransportError.Unauthorized), "Wrong username or password.",
                     "a wrong password is not told which half was wrong")
        checks.equal(AccountError.signIn(TransportError.Http(status = 403, code = "forbidden")), "This account is disabled.",
                     "a disabled account is told so")
        checks.equal(AccountError.register(TransportError.Http(status = 409, code = "conflict")), "That username is taken.",
                     "a taken username says so")
        checks.equal(AccountError.create(TransportError.Http(status = 409, code = "conflict")), "That username is taken.",
                     "and says the same thing when an admin is the one adding it")
        checks.equal(AccountError.manage(TransportError.Http(status = 409, code = "conflict")), "This account cannot be changed.",
                     "while a 409 on an account that exists is only ever the operator's row")
        checks.equal(AccountError.register(TransportError.Http(status = 403, code = "forbidden")), "Registration is closed.",
                     "registering into a closed gateway says so")
        checks.equal(AccountError.register(TransportError.Http(status = 400, code = "bad_request")), AccountError.rules,
                     "and a refused name is the only time the rule is stated")
        checks.equal(AccountError.passwordChange(TransportError.Unauthorized), "That is not your current password.",
                     "a wrong current password says which field was wrong")
        checks.equal(AccountError.manage(TransportError.Http(status = 404, code = "not_found")), "That account no longer exists.",
                     "an account that went away while the screen was open says so")
        checks.equal(AccountError.manage(TransportError.Http(status = 403, code = "forbidden")), "This account cannot be changed.",
                     "and so is a caller 3.9 will not take at all")
        checks.assertAll()
    }
}
