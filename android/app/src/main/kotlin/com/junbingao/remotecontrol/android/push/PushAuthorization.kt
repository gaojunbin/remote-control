package com.junbingao.remotecontrol.android.push

/**
 * What the system allows for the app's notifications — `PushAuthorization` in
 * `PushController.swift`, over Android's permission rather than `UNAuthorizationStatus`.
 */
enum class PushAuthorization {
    /** Never asked; the switch asks when it is turned on. */
    notDetermined,

    /** Refused, or turned off in Android Settings; only Settings can undo it. */
    denied,
    authorized,

    /** Delivered quietly. Android has no such grant; kept so the port reads as the iPhone's. */
    provisional,

    /** No notifications on this device at all. */
    unsupported,
    ;

    /** Provisional counts: the system still delivers, quietly. */
    val raisesBanners: Boolean get() = this == authorized || this == provisional
}
