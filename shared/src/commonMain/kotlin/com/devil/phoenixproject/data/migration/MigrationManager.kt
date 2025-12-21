package com.devil.phoenixproject.data.migration

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch

/**
 * Manages data migrations on app startup.
 * Call [checkAndRunMigrations] after Koin is initialized.
 */
class MigrationManager(
) {
    private val log = Logger.withTag("MigrationManager")
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Check for and run any pending migrations.
     * This should be called once on app startup.
     */
    fun checkAndRunMigrations() {
        scope.launch {
            try {
                runMigrations()
            } catch (e: Exception) {
                log.e(e) { "Migration failed" }
            }
        }
    }

    private suspend fun runMigrations() {
        // No migrations currently.
    }
}
