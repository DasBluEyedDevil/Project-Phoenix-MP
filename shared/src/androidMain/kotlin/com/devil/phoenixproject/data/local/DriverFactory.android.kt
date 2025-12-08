package com.devil.phoenixproject.data.local

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.devil.phoenixproject.database.VitruvianDatabase
import java.io.File

actual class DriverFactory(private val context: Context) {

    companion object {
        private const val TAG = "DriverFactory"
        private const val DATABASE_NAME = "vitruvian.db"

        // Increment this when you make schema changes during development.
        // This forces a database reset when the app is updated.
        // For production, use proper SQLDelight migrations (.sqm files) instead.
        private const val DEV_SCHEMA_VERSION = 2
        private const val PREFS_NAME = "vitruvian_db_prefs"
        private const val KEY_SCHEMA_VERSION = "dev_schema_version"
    }

    actual fun createDriver(): SqlDriver {
        // Check if we need to reset the database due to schema changes
        checkAndResetIfNeeded()

        return AndroidSqliteDriver(
            schema = VitruvianDatabase.Schema,
            context = context,
            name = DATABASE_NAME,
            callback = object : AndroidSqliteDriver.Callback(VitruvianDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Enable foreign keys
                    db.execSQL("PRAGMA foreign_keys = ON;")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Destructive migration: drop all tables and recreate
                    Log.i(TAG, "Database schema changed from $oldVersion to $newVersion - recreating tables")
                    dropAllTablesAndRecreate(db)
                }

                override fun onCorruption(db: SupportSQLiteDatabase) {
                    Log.e(TAG, "Database corruption detected - deleting database")
                    deleteDatabaseFile()
                }
            }
        )
    }

    /**
     * Check if the development schema version has changed and reset if needed.
     * This handles the case where the .sq file changes but SQLDelight's version stays at 1.
     */
    private fun checkAndResetIfNeeded() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedVersion = prefs.getInt(KEY_SCHEMA_VERSION, 0)

        if (savedVersion != DEV_SCHEMA_VERSION) {
            Log.i(TAG, "Dev schema version changed ($savedVersion -> $DEV_SCHEMA_VERSION) - resetting database")
            deleteDatabaseFile()
            prefs.edit().putInt(KEY_SCHEMA_VERSION, DEV_SCHEMA_VERSION).apply()
        }
    }

    /**
     * Delete the database file completely.
     */
    private fun deleteDatabaseFile() {
        try {
            val dbFile = context.getDatabasePath(DATABASE_NAME)
            if (dbFile.exists()) {
                dbFile.delete()
                Log.i(TAG, "Deleted database file: ${dbFile.absolutePath}")
            }
            // Also delete journal and wal files
            File(dbFile.path + "-journal").delete()
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting database file", e)
        }
    }

    /**
     * Drop all user tables and recreate the schema.
     */
    private fun dropAllTablesAndRecreate(db: SupportSQLiteDatabase) {
        try {
            // Disable foreign keys temporarily
            db.execSQL("PRAGMA foreign_keys = OFF;")

            // Get list of all user tables
            val cursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'"
            )
            val tables = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    tables.add(it.getString(0))
                }
            }

            // Drop all indexes first
            val indexCursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='index' " +
                "AND name NOT LIKE 'sqlite_%'"
            )
            indexCursor.use {
                while (it.moveToNext()) {
                    val indexName = it.getString(0)
                    try {
                        db.execSQL("DROP INDEX IF EXISTS $indexName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not drop index $indexName: ${e.message}")
                    }
                }
            }

            // Drop all tables
            tables.forEach { table ->
                db.execSQL("DROP TABLE IF EXISTS \"$table\"")
                Log.d(TAG, "Dropped table: $table")
            }

            // Re-enable foreign keys
            db.execSQL("PRAGMA foreign_keys = ON;")

            // Recreate schema
            val driver = AndroidSqliteDriver(db)
            VitruvianDatabase.Schema.create(driver)
            Log.i(TAG, "Schema recreated successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error during schema recreation", e)
            throw e
        }
    }
}
