#if DEBUG
import UIKit
import IcyShared

/// Debug-only capture instrumentation; the child still draws the production UI.
@MainActor
final class ParityFixtureViewController: UIViewController {
    private let scenario: String
    private let marker = UILabel()
    private var composeController: UIViewController!

    init(scenario: String) {
        self.scenario = scenario
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("Use init(scenario:)") }

    override func viewDidLoad() {
        super.viewDidLoad()
        // Match the preserved Android reference environment without changing
        // either production date formatter or replacing its visible output.
        NSTimeZone.default = TimeZone(identifier: "America/Los_Angeles")!
        composeController = IosParityKt.createIcyParityViewController(scenarioId: scenario) { [weak self] draw in
            DispatchQueue.main.async { self?.acknowledgeDraw(draw) }
        }
        addChild(composeController)
        composeController.view.frame = view.bounds
        composeController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(composeController.view)
        composeController.didMove(toParent: self)

        // An empty, transparent label adds no screenshot pixels. It exposes the
        // app-owned draw/geometry acknowledgement to XCTest without hiding the
        // Compose accessibility tree or treating an arbitrary delay as readiness.
        marker.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        marker.backgroundColor = .clear
        marker.textColor = .clear
        marker.isUserInteractionEnabled = false
        marker.isAccessibilityElement = true
        marker.accessibilityIdentifier = "icy-parity-ready"
        marker.accessibilityLabel = "Icy parity draw acknowledgement"
        marker.accessibilityValue = "pending"
        view.addSubview(marker)
    }

    override var childForStatusBarStyle: UIViewController? { composeController }
    override var childForStatusBarHidden: UIViewController? { composeController }
    override var childForHomeIndicatorAutoHidden: UIViewController? { composeController }
    override var childForScreenEdgesDeferringSystemGestures: UIViewController? { composeController }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        composeController?.supportedInterfaceOrientations ?? super.supportedInterfaceOrientations
    }

    private func acknowledgeDraw(_ draw: String) {
        guard let window = view.window, let scene = window.windowScene,
              let bytes = draw.data(using: .utf8),
              var metadata = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let width = metadata["contentWidthPx"] as? Int,
              let height = metadata["contentHeightPx"] as? Int,
              let drawnInsets = metadata["safeDrawingInsetsPx"] as? [Int] else { return }
        let content = composeController.view!
        let scale = scene.screen.scale
        guard width == Int((content.bounds.width * scale).rounded()),
              height == Int((content.bounds.height * scale).rounded()), width > 0, height > 0 else { return }
        let safe = content.safeAreaInsets
        let nativeInsets = [safe.left, safe.top, safe.right, safe.bottom].map { Int(($0 * scale).rounded()) }
        guard drawnInsets == nativeInsets else { return }
        let frame = content.convert(content.bounds, to: window)
        func rect(_ value: CGRect) -> [Double] {
            [Double(value.minX), Double(value.minY), Double(value.width), Double(value.height)]
        }
        func insets(_ value: UIEdgeInsets) -> [Double] {
            [Double(value.left), Double(value.top), Double(value.right), Double(value.bottom)]
        }
        let date = DateFormatter()
        date.dateStyle = .medium
        date.timeStyle = .short
        metadata["ready"] = true
        metadata["scenario"] = scenario
        metadata["windowBoundsPoints"] = rect(window.bounds)
        metadata["contentBoundsInWindowPoints"] = rect(frame)
        metadata["contentSafeAreaInsetsPoints"] = insets(content.safeAreaInsets)
        metadata["windowSafeAreaInsetsPoints"] = insets(window.safeAreaInsets)
        metadata["displayScale"] = Double(scale)
        metadata["nativeDisplayScale"] = Double(scene.screen.nativeScale)
        metadata["screenBoundsPoints"] = rect(scene.screen.bounds)
        metadata["screenNativeBoundsPixels"] = rect(scene.screen.nativeBounds)
        metadata["interfaceOrientationRawValue"] = scene.interfaceOrientation.rawValue
        metadata["preferredContentSizeCategory"] = content.traitCollection.preferredContentSizeCategory.rawValue
        metadata["locale"] = Locale.current.identifier
        metadata["timezone"] = NSTimeZone.default.identifier
        metadata["libraryDateText"] = date.string(from: Date(timeIntervalSince1970: 1_788_436_800))
        metadata["readiness"] = "Compose draw completed with matching UIKit geometry; spring settlement is not asserted"
        metadata["captureSurface"] = "XCTest application window; separate from Android Compose-root capture"
        if let result = try? JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]),
           let value = String(data: result, encoding: .utf8) {
            marker.accessibilityValue = value
        }
    }
}
#endif
