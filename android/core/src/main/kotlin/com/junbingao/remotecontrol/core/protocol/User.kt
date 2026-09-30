package com.junbingao.remotecontrol.core.protocol

import com.junbingao.remotecontrol.core.state.L10n
import com.junbingao.remotecontrol.core.state.RelativeTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * What the account routes of protocol 3.9 are gated on (A24).
 *
 * A [WireEnum] rather than a closed enumeration: a role this build has never heard of decodes
 * rather than failing, and [isAdmin] answers false for it, so a newer gateway can name a role
 * without an older app showing it the admin's screens by accident.
 */
@JvmInline
@Serializable(with = UserRole.Serializer::class)
value class UserRole(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    /** The one question the app asks of a role. Anything unknown is not admin. */
    val isAdmin: Boolean get() = this == admin

    /** The word under the username in Settings and on a Users row. */
    val title: String
        get() = when (this) {
            admin -> L10n.string("Admin")
            member -> L10n.string("Member")
            else -> rawValue
        }

    companion object {
        val admin = UserRole("admin")
        val member = UserRole("member")
    }

    object Serializer : WireEnumSerializer<UserRole>("UserRole", ::UserRole)
}

/** Whether an account can sign in. A disabled one cannot, and its devices are refused until it is enabled again (A24). */
@JvmInline
@Serializable(with = UserState.Serializer::class)
value class UserState(override val rawValue: String) : WireEnum {
    override fun toString(): String = rawValue

    val title: String
        get() = when (this) {
            active -> L10n.string("Active")
            disabled -> L10n.string("Disabled")
            else -> rawValue
        }

    companion object {
        val active = UserState("active")
        val disabled = UserState("disabled")
    }

    object Serializer : WireEnumSerializer<UserState>("UserState", ::UserState)
}

/**
 * The rules an account is made under, written once. The gateway enforces them; the app repeats
 * the password length because a form that offers a button it knows will be refused is worse than
 * one that waits.
 */
object AccountRules {
    /**
     * The operator's account: its password is the gateway's `RC_PASSWORD`, and it cannot be
     * disabled, demoted, re-passworded or deleted.
     */
    const val operatorUsername = "admin"
    val passwordLength: IntRange = 8..128

    fun isPasswordLongEnough(password: String): Boolean =
        password.codePointCount(0, password.length) >= passwordLength.first
}

/**
 * The account an app signed in as: `LoginResponse.user`, `AuthSessionResponse.user` and the app
 * `hello`'s `user` (protocol 4.10).
 */
@Serializable
data class UserIdentity(val username: String = "", val role: UserRole = UserRole.member)

/**
 * One account as the admin lists it (protocol 4.10): the same account, with its state, its
 * timestamps and how many devices it has enrolled.
 */
@Serializable
data class UserRecord(
    val username: String = "",
    val role: UserRole = UserRole.member,
    val state: UserState = UserState.active,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("last_login_at") val lastLoginAt: Long? = null,
    val devices: Int = 0,
) {
    val id: String get() = username

    /** The operator's row, which offers no actions at all. */
    val isOperator: Boolean get() = username == AccountRules.operatorUsername

    val isActive: Boolean get() = state == UserState.active

    /** `role · state` for the meta line. */
    val roleAndState: String get() = "${role.title} · ${state.title}"

    /** "2 devices", or nothing at all when the account has enrolled none. */
    val deviceSummary: String
        get() = if (devices == 0) "" else L10n.string(if (devices == 1) "%lld device" else "%lld devices", devices)

    /** The last sign-in as a relative time, or "never". */
    fun lastLoginSummary(now: Instant = Instant.now()): String {
        val last = lastLoginAt
        if (last == null || last <= 0) return L10n.string("never")
        return RelativeTime.short(since = last, now = now)
    }

    /** The identity this record signs in as, used where the two meet. */
    val identity: UserIdentity get() = UserIdentity(username = username, role = role)
}
