package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.KableBleRepository
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.IosCsvExporter
import com.devil.phoenixproject.util.IosDataBackupManager
import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSUserDefaults

actual val platformModule: Module = module {
    single { DriverFactory() }
    single<BleRepository> { KableBleRepository() }
    single<Settings> {
        val defaults = NSUserDefaults.standardUserDefaults
        NSUserDefaultsSettings(defaults)
    }
    single<CsvExporter> { IosCsvExporter() }
    single<DataBackupManager> { IosDataBackupManager(get()) }
}
