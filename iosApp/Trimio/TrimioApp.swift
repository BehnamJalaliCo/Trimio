import SwiftUI
import Shared

/// The iOS shell: the whole app is the shared Compose UI (see shared/src/iosMain).
@main
struct TrimioApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea()
                .preferredColorScheme(.dark)
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
