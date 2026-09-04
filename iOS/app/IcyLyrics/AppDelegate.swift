import UIKit
import IcyShared

@main
@MainActor
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Icy Lyrics", sessionRole: session.role)
        configuration.delegateClass = SceneDelegate.self
        return configuration
    }
}

@MainActor
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var host: NativeHost?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        host?.close()
        let window = UIWindow(windowScene: scene)
        let host = NativeHost(window: window)
        self.window = window
        self.host = host
        window.overrideUserInterfaceStyle = .dark
        window.rootViewController = host.makeViewController()
        window.makeKeyAndVisible()
        for context in options.urlContexts { host.open(context.url) }
    }

    func sceneDidBecomeActive(_ scene: UIScene) { host?.activate() }
    func sceneWillResignActive(_ scene: UIScene) { host?.deactivate() }
    func sceneDidDisconnect(_ scene: UIScene) {
        host?.close()
        host = nil
        window?.rootViewController = nil
        window = nil
    }
    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        for context in contexts { host?.open(context.url) }
    }
}
