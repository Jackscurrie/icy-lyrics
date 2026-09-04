#if DEBUG
import UIKit
import IcyShared

/// Query live UIKit measurements when the accessibility client requests them.
/// The fallback remains pending until a real Compose draw can be checked.
@MainActor
private final class ParityGeometryMarker: UILabel {
    var currentValue: (() -> String?)?

    override var accessibilityValue: String? {
        get { currentValue?() ?? super.accessibilityValue }
        set { super.accessibilityValue = newValue }
    }
}

/// Debug-only capture instrumentation; the child still draws the production UI.
@MainActor
final class ParityFixtureViewController: UIViewController {
    private let scenario: String
    private let marker = ParityGeometryMarker()
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
        marker.currentValue = { [weak self] in
            guard let self, let draw = self.lastDraw else { return nil }
            // This recomputes UIKit coordinates for each AX query. It checks
            // the last real draw; it never creates a new Compose draw event.
            return self.acknowledgementValue(for: draw)
        }
        view.addSubview(marker)
    }

    override var childForStatusBarStyle: UIViewController? { composeController }
    override var childForStatusBarHidden: UIViewController? { composeController }
    override var childForHomeIndicatorAutoHidden: UIViewController? { composeController }
    override var childForScreenEdgesDeferringSystemGestures: UIViewController? { composeController }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        composeController?.supportedInterfaceOrientations ?? super.supportedInterfaceOrientations
    }

    private func acknowledgementValue(for draw: String) -> String? {
        guard let window = view.window, let scene = window.windowScene,
              let bytes = draw.data(using: .utf8),
              var metadata = try? JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              let width = metadata["contentWidthPx"] as? Int,
              let height = metadata["contentHeightPx"] as? Int,
              let drawnInsets = metadata["safeDrawingInsetsPx"] as? [Int] else { return nil }
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
        metadata["fixedCoordinateMapping"] = measureFixedCoordinates(window: window, content: content)
        metadata["interfaceOrientationRawValue"] = scene.interfaceOrientation.rawValue
        metadata["preferredContentSizeCategory"] = content.traitCollection.preferredContentSizeCategory.rawValue
        metadata["locale"] = Locale.current.identifier
        metadata["timezone"] = NSTimeZone.default.identifier
        metadata["readiness"] = "Compose draw completed with matching UIKit geometry; spring settlement is not asserted"
        metadata["captureSurface"] = "XCTest application window; separate from Android Compose-root capture"
        if let result = try? JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys]),
           let value = String(data: result, encoding: .utf8) {
            return value
        }
        return nil
    }

    /// Observe public coordinate conversions only. These values do not rotate,
    /// resize, crop, or certify a framebuffer; the capture validator does that
    /// separately using the unchanged host PNG and this measured certificate.
    private func measureFixedCoordinates(window: UIWindow, content: UIView) -> [String: Any] {
        let screen = window.windowScene!.screen
        let fixed = screen.fixedCoordinateSpace
        let windowSpace: any UICoordinateSpace = window
        let contentSpace: any UICoordinateSpace = content
        func rect(_ value: CGRect) -> [Double] {
            [Double(value.minX), Double(value.minY), Double(value.width), Double(value.height)]
        }
        func point(_ value: CGPoint) -> [Double] { [Double(value.x), Double(value.y)] }
        func corners(_ bounds: CGRect) -> [CGPoint] {
            [CGPoint(x: bounds.minX, y: bounds.minY), CGPoint(x: bounds.maxX, y: bounds.minY),
             CGPoint(x: bounds.maxX, y: bounds.maxY), CGPoint(x: bounds.minX, y: bounds.maxY)]
        }
        let windowCorners = corners(window.bounds)
        let fixedWindowCorners = windowCorners.map { windowSpace.convert($0, to: fixed) }
        let contentCorners = corners(content.bounds)
        let windowContentCorners = contentCorners.map { contentSpace.convert($0, to: windowSpace) }
        let fixedContentCorners = contentCorners.map { contentSpace.convert($0, to: fixed) }
        let center = CGPoint(x: window.bounds.midX, y: window.bounds.midY)
        let pixelStep = 1 / screen.scale
        let samples = [center, CGPoint(x: center.x + pixelStep, y: center.y),
                       CGPoint(x: center.x, y: center.y + pixelStep)]
        let fixedSamples = samples.map { windowSpace.convert($0, to: fixed) }
        return [
            "schemaVersion": 1,
            "cornerOrder": ["TL", "TR", "BR", "BL"],
            "windowBoundsPoints": rect(window.bounds),
            "contentBoundsInWindowPoints": rect(content.convert(content.bounds, to: window)),
            "fixedBoundsPoints": rect(fixed.bounds),
            "screenNativeBoundsPixels": rect(screen.nativeBounds),
            "displayScale": Double(screen.scale),
            "nativeDisplayScale": Double(screen.nativeScale),
            "windowCornersInWindowPoints": windowCorners.map(point),
            "windowCornersInFixedPoints": fixedWindowCorners.map(point),
            "roundTripsInWindowPoints": fixedWindowCorners.map { point(fixed.convert($0, to: windowSpace)) },
            "contentCornersInWindowPoints": windowContentCorners.map(point),
            "contentCornersInFixedPoints": fixedContentCorners.map(point),
            "contentRoundTripsInWindowPoints": fixedContentCorners.map { point(fixed.convert($0, to: windowSpace)) },
            "sampleOrder": ["center", "centerPlusOnePixelX", "centerPlusOnePixelY"],
            "sampleWindowPoints": samples.map(point),
            "sampleFixedPoints": fixedSamples.map(point),
            "sampleRoundTripsInWindowPoints": fixedSamples.map { point(fixed.convert($0, to: windowSpace)) },
        ]
    }
}
#endif
