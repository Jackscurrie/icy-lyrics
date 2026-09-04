#if DEBUG
import UIKit
import IcyShared

/// Debug-only capture instrumentation; the child still draws the production UI.
@MainActor
final class ParityFixtureViewController: UIViewController {
    private let scenario: String
    private let marker = UILabel()
    private var composeController: UIViewController!
    private var lastDraw: String?

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
            DispatchQueue.main.async {
                self?.lastDraw = draw
                self?.acknowledgeDraw(draw)
            }
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

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // UIKit may finish its geometry update after the last Compose draw.
        // Recheck that draw; never manufacture a new draw acknowledgement.
        if let lastDraw { acknowledgeDraw(lastDraw) }
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
        let safe: UIEdgeInsets
        let safeSource: String
        if #available(iOS 26.1, *) {
            // CMP 1.11.1 UIKitWindowInsetsManager uses this same region,
            // including vertical corner avoidance on iOS 26.1+. Its wrapper
            // deliberately retains raw safe areas on iPhone running 26.0.
            safe = content.edgeInsets(for: .safeArea(cornerAdaptation: .vertical))
            safeSource = "UIKit safeArea with vertical corner adaptation"
        } else {
            safe = content.safeAreaInsets
            safeSource = "UIKit safeAreaInsets"
        }
        // Match Compose's CGFloat -> Float dp -> Float pixel -> roundToPx path.
        let nativeInsets = [safe.left, safe.top, safe.right, safe.bottom].map {
            Int((Float($0) * Float(scale)).rounded())
        }
        let boundsMatch = width == Int((content.bounds.width * scale).rounded()) &&
            height == Int((content.bounds.height * scale).rounded()) && width > 0 && height > 0
        let insetsMatch = drawnInsets == nativeInsets
        let frame = content.convert(content.bounds, to: window)
        func rect(_ value: CGRect) -> [Double] {
            [Double(value.minX), Double(value.minY), Double(value.width), Double(value.height)]
        }
        func insets(_ value: UIEdgeInsets) -> [Double] {
            [Double(value.left), Double(value.top), Double(value.right), Double(value.bottom)]
        }
        metadata["ready"] = boundsMatch && insetsMatch
        metadata["drawBoundsMatchUIKit"] = boundsMatch
        metadata["drawInsetsMatchUIKit"] = insetsMatch
        metadata["scenario"] = scenario
        metadata["windowBoundsPoints"] = rect(window.bounds)
        metadata["contentBoundsInWindowPoints"] = rect(frame)
        metadata["contentSafeAreaInsetsPoints"] = insets(content.safeAreaInsets)
        metadata["contentSafeDrawingInsetsPoints"] = insets(safe)
        metadata["safeDrawingInsetsSource"] = safeSource
        metadata["safeDrawingInsetsPixelConversion"] = "Float32 points * Float32 displayScale, roundToInt"
        metadata["windowSafeAreaInsetsPoints"] = insets(window.safeAreaInsets)
        metadata["displayScale"] = Double(scale)
        metadata["nativeDisplayScale"] = Double(scene.screen.nativeScale)
        metadata["screenBoundsPoints"] = rect(scene.screen.bounds)
        metadata["screenNativeBoundsPixels"] = rect(scene.screen.nativeBounds)
        metadata["interfaceOrientationRawValue"] = scene.interfaceOrientation.rawValue
        metadata["preferredContentSizeCategory"] = content.traitCollection.preferredContentSizeCategory.rawValue
        metadata["locale"] = Locale.current.identifier
        metadata["timezone"] = NSTimeZone.default.identifier
        metadata["readiness"] = "Compose draw completed with matching UIKit geometry; spring settlement is not asserted"
        metadata["captureSurface"] = "XCTest application window; separate from Android Compose-root capture"
        if let result = try? JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]),
           let value = String(data: result, encoding: .utf8) {
            marker.accessibilityValue = value
        }
    }
}
#endif
