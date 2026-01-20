import SwiftUI
import shared

@main
struct VitruvianPhoenixApp: App {

    init() {
        // Initialize Koin for dependency injection
        KoinKt.doInitKoin()

        // Run migrations after Koin is initialized (mirrors Android VitruvianApp.onCreate())
        KoinKt.runMigrations()

        // Initialize RevenueCat for subscriptions
        // TODO: Uncomment when RevenueCat API keys are configured for production
        // Premium features are disabled until subscription system is ready
        // RevenueCatInitializer.shared.initialize()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
