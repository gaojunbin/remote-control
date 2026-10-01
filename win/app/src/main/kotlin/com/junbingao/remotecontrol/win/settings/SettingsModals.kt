package com.junbingao.remotecontrol.win.settings

import androidx.compose.runtime.MutableState

/**
 * The dialogs of Settings and Users are each made fresh for an opening — a form object holding
 * what was typed — so a modal is open exactly while its state holds one, and closing it lets the
 * form go: `isPresented = form.settingsModalOpen`, `onDismiss = { form.settingsModalOpen = false }`.
 * The form itself is handed to the modal as the value the presenting view read, and its fields are
 * snapshot state, so the overlay layer draws what was typed as it is typed.
 */
var <T : Any> MutableState<T?>.settingsModalOpen: Boolean
    get() = value != null
    set(open) {
        if (!open) value = null
    }
