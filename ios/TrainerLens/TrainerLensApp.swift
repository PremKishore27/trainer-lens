import SwiftUI

@main
struct TrainerLensApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            TabView {
                IVView().tabItem { Label("IV", systemImage: "chart.bar.fill") }
                BagView().tabItem { Label("Bag", systemImage: "bag.fill") }
                HelpView().tabItem { Label("How to", systemImage: "questionmark.circle.fill") }
            }
            .tint(Theme.teal)
            .environmentObject(model)
            .preferredColorScheme(.dark)
        }
    }
}
