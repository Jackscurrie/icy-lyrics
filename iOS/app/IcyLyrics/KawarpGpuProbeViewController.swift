#if DEBUG
import UIKit
import Metal
import QuartzCore
import CryptoKit
import IcyShared

/// Opt-in diagnostic surface only. No authentication, real playback, or ordinary UI is created.
@MainActor
final class KawarpGpuProbeViewController: UIViewController {
    private let caseID: String
    private let runID: String
    private let pixelSize: CGSize
    private var compose: UIViewController!
    private let surface = UIView()
    private let marker = UILabel()
    private var lastDraw: String?
    private var output: URL!

    init(caseID: String, runID: String) {
        let allowed = ["256x512", "512x256"].flatMap { size in [0, 1, 3, 12].map { "\(size)-phase-\($0)" } }
        precondition(allowed.contains(caseID) && UUID(uuidString: runID) != nil)
        self.caseID = caseID
        self.runID = runID
        pixelSize = caseID.hasPrefix("256x512") ? CGSize(width: 256, height: 512) : CGSize(width: 512, height: 256)
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        do {
            guard let input = Bundle.main.url(forResource: "input-artwork", withExtension: "png", subdirectory: "KawarpProbeAssets") else {
                throw NSError(domain: "KawarpGpuProbe", code: 1, userInfo: [NSLocalizedDescriptionKey: "Build Debug with ICY_KAWARP_PROBE=YES"])
            }
            output = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                .appendingPathComponent("KawarpGpuProbe", isDirectory: true).appendingPathComponent(runID, isDirectory: true)
                .appendingPathComponent(caseID, isDirectory: true)
            try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
            compose = IosKawarpGpuProbeKt.createIcyKawarpGpuProbeViewController(inputPath: input.path,
                outputDirectory: output.path, caseId: caseID, onFrameDrawn: { [weak self] json in
                    DispatchQueue.main.async { [weak self] in
                        self?.lastDraw = json
                        self?.acknowledge()
                    }
                })
            surface.isAccessibilityElement = true
            surface.accessibilityIdentifier = "icy-kawarp-surface"
            surface.accessibilityLabel = "Kawarp GPU surface"
            surface.clipsToBounds = true
            view.addSubview(surface)
            addChild(compose)
            compose.view.frame = surface.bounds
            compose.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            surface.addSubview(compose.view)
            compose.didMove(toParent: self)
        } catch {
            let failure = try? JSONSerialization.data(withJSONObject: ["error": error.localizedDescription])
            lastDraw = failure.flatMap { String(data: $0, encoding: .utf8) }
        }
        marker.isAccessibilityElement = true
        marker.accessibilityIdentifier = "icy-kawarp-ready"
        marker.accessibilityLabel = "Kawarp GPU draw acknowledgement"
        marker.accessibilityValue = lastDraw ?? "pending"
        marker.backgroundColor = .clear
        marker.textColor = .clear
        marker.frame = CGRect(x: 0, y: 0, width: 1, height: 1)
        view.addSubview(marker)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let window = view.window else { return }
        let scale = window.screen.scale
        // Real screen density remains native. Only this test viewport's point
        // frame is derived from the requested integral physical dimensions.
        let size = CGSize(width: pixelSize.width / scale, height: pixelSize.height / scale)
        let x = floor((view.bounds.width - size.width) * scale / 2) / scale
        let y = floor((view.bounds.height - size.height) * scale / 2) / scale
        surface.frame = CGRect(origin: CGPoint(x: x, y: y), size: size)
        compose?.view.frame = surface.bounds
        acknowledge()
    }

    private func acknowledge() {
        guard let json = lastDraw, let bytes = json.data(using: .utf8),
              var draw = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any],
              let window = view.window, let scene = window.windowScene,
              let width = draw["drawWidthPx"] as? NSNumber, let height = draw["drawHeightPx"] as? NSNumber,
              let density = draw["density"] as? NSNumber else { return }
        let scale = window.screen.scale
        let bounds = surface.convert(surface.bounds, to: window)
        let metal = metalLayers(in: surface.layer)
        let ready = draw["matchesRequestedSize"] as? Bool == true
            && width.doubleValue == Double(pixelSize.width) && height.doubleValue == Double(pixelSize.height)
            && density.doubleValue == Double(scale)
            && abs(bounds.width * scale - pixelSize.width) < 0.001 && abs(bounds.height * scale - pixelSize.height) < 0.001
            && abs(bounds.minX * scale - (bounds.minX * scale).rounded()) < 0.001
            && abs(bounds.minY * scale - (bounds.minY * scale).rounded()) < 0.001
            && window.bounds.contains(bounds)
            && metal.contains { layer in layer.device != nil && layer.drawableSize == pixelSize }
        let geometry: [String: Any] = [
            "surfaceBoundsInWindowPoints": [bounds.minX, bounds.minY, bounds.width, bounds.height],
            "windowBoundsPoints": [window.bounds.minX, window.bounds.minY, window.bounds.width, window.bounds.height],
            "requestedWidthPx": pixelSize.width, "requestedHeightPx": pixelSize.height,
            "screenScale": scale, "nativeScale": window.screen.nativeScale,
            "interfaceOrientationRawValue": scene.interfaceOrientation.rawValue,
            "safeAreaInsetsPoints": [surface.safeAreaInsets.left, surface.safeAreaInsets.top, surface.safeAreaInsets.right, surface.safeAreaInsets.bottom],
            "metalLayers": metal.map { layer -> [String: Any] in [
                "deviceName": layer.device?.name ?? "missing", "pixelFormatRawValue": layer.pixelFormat.rawValue,
                "drawableWidthPx": layer.drawableSize.width, "drawableHeightPx": layer.drawableSize.height,
                "contentsScale": layer.contentsScale, "framebufferOnly": layer.framebufferOnly,
                "boundsPoints": [layer.bounds.minX, layer.bounds.minY, layer.bounds.width, layer.bounds.height],
                "opaque": layer.isOpaque
            ] }
        ]
        guard let geometryBytes = try? JSONSerialization.data(withJSONObject: geometry, options: [.sortedKeys]) else { return }
        draw["ready"] = ready
        draw["runId"] = runID
        draw["nativeGeometry"] = geometry
        draw["nativeGeometrySha256"] = SHA256.hash(data: geometryBytes).map { String(format: "%02x", $0) }.joined()
        draw["nativeGeometryCanonicalJson"] = String(data: geometryBytes, encoding: .utf8)
        draw["backendEvidence"] = "Production RuntimeEffect binding on real Compose UIKit Canvas; visible CAMetalLayer with matching drawable and device"
        draw["deviceModel"] = UIDevice.current.model
        draw["systemVersion"] = UIDevice.current.systemVersion
        draw["readinessScope"] = "Matching production draw callback plus actual pixel-aligned geometry and visible Metal drawable; fixed uniforms, no settling or clock assertion"
        guard let result = try? JSONSerialization.data(withJSONObject: draw, options: [.sortedKeys]),
              let value = String(data: result, encoding: .utf8) else { return }
        marker.accessibilityValue = value
        if ready { try? result.write(to: output.appendingPathComponent("native-draw.json"), options: .atomic) }
    }

    private func metalLayers(in layer: CALayer, visible: Bool = true) -> [CAMetalLayer] {
        let visible = visible && !layer.isHidden && layer.opacity > 0 && !layer.bounds.isEmpty
        guard visible else { return [] }
        var result = (layer as? CAMetalLayer).map { [$0] } ?? []
        for child in layer.sublayers ?? [] { result += metalLayers(in: child, visible: visible) }
        return result
    }
}
#endif
