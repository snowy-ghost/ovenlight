package com.snowyghost.ovenlight

/** Release builds have no sample sharing data: see src/debug. */
object SharingFixture {
    const val isOn = false

    fun apply(app: WebApp): WebApp = app

    @Suppress("UNUSED_PARAMETER")
    fun machines(apps: List<WebApp>): List<OwnerSharing.Machine> = emptyList()
}
