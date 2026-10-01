package com.junbingao.remotecontrol.win.devices

import androidx.compose.runtime.Composable
import com.junbingao.remotecontrol.win.app.LocalAppModel
import com.junbingao.remotecontrol.win.app.device
import com.junbingao.remotecontrol.win.layout.PageHead
import com.junbingao.remotecontrol.win.strings.S

/** `/devices/:deviceId` (A33). A placeholder until the lists feature replaces it. */
@Composable
fun DevicePage(deviceId: String) {
    val model = LocalAppModel.current
    PageHead(model.device(id = deviceId)?.name ?: S.devicePage.gone)
}
