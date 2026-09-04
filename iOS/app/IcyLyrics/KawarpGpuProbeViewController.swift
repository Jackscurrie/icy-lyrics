#if DEBUG
import UIKit
import Metal
import QuartzCore
import CryptoKit
import IcyShared

@MainActor
private final class KawarpGeometryMarker: UILabel {
    var currentValue: (() -> String?)?
    override var accessibilityValue: String? {
        get { currentValue?() ?? super.accessibilityValue }
        set { super.accessibilityValue = newValue }
    }
}

/// Opt-in diagnostic surface only. No authentication, real playback, or ordinary UI is created.
@MainActor
final class KawarpGpuProbeViewController: UIViewController {
    private let caseID: String
    private let runID: String
    private let pixelSize: CGSize
    private var compose: UIViewController!
    private let surface = UIView()
    private let marker = KawarpGeometryMarker()
    private var lastDraw: String?
    private var output: URL!
    private var measurementSequence = 0

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
        marker.currentValue = { [weak self] in self?.measureCurrentDraw() }
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
    }

    private func measureCurrentDraw() -> String? {
        guard let json = lastDraw, let bytes = json.data(using: .utf8),
              var draw = (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any],
              let window = view.window, let scene = window.windowScene,
              let width = draw["drawWidthPx"] as? NSNumber, let height = draw["drawHeightPx"] as? NSNumber,
              let density = draw["density"] as? NSNumber else { return lastDraw }
        measurementSequence += 1
        let scale = window.screen.scale
        let bounds = surface.convert(surface.bounds, to: window)
        let inspection = inspectLayers(in: surface.layer)
        let metal = inspection.metal
        let ready = draw["matchesRequestedSize"] as? Bool == true
            && width.doubleValue == Double(pixelSize.width) && height.doubleValue == Double(pixelSize.height)
            && density.doubleValue == Double(scale)
            && abs(bounds.width * scale - pixelSize.width) < 0.001 && abs(bounds.height * scale - pixelSize.height) < 0.001
            && abs(bounds.minX * scale - (bounds.minX * scale).rounded()) < 0.001
            && abs(bounds.minY * scale - (bounds.minY * scale).rounded()) < 0.001
            && window.bounds.contains(bounds)
            && metal.contains { $0["eligibleForProbe"] as? Bool == true }
        let geometry: [String: Any] = [
            "surfaceBoundsInWindowPoints": [bounds.minX, bounds.minY, bounds.width, bounds.height],
            "windowBoundsPoints": [window.bounds.minX, window.bounds.minY, window.bounds.width, window.bounds.height],
            "requestedWidthPx": pixelSize.width, "requestedHeightPx": pixelSize.height,
            "screenScale": scale, "nativeScale": window.screen.nativeScale,
            "interfaceOrientationRawValue": scene.interfaceOrientation.rawValue,
            "safeAreaInsetsPoints": [surface.safeAreaInsets.left, surface.safeAreaInsets.top, surface.safeAreaInsets.right, surface.safeAreaInsets.bottom],
            "metalEvidenceSchemaVersion": 1,
            "metalLayers": metal,
            "layerHierarchy": inspection.hierarchy
        ]
        guard let geometryBytes = try? JSONSerialization.data(withJSONObject: geometry, options: [.sortedKeys]) else { return nil }
        draw["ready"] = ready
        draw["runId"] = runID
        draw["nativeGeometry"] = geometry
        draw["nativeGeometrySha256"] = SHA256.hash(data: geometryBytes).map { String(format: "%02x", $0) }.joined()
        draw["nativeGeometryCanonicalJson"] = String(data: geometryBytes, encoding: .utf8)
        draw["backendEvidence"] = "Production RuntimeEffect on Compose UIKit; observed CAMetalLayer or pinned CMPMetalLayer with actual device and matching drawable size"
        draw["deviceModel"] = UIDevice.current.model
        draw["systemVersion"] = UIDevice.current.systemVersion
        draw["readinessScope"] = "Matching production draw callback plus actual pixel-aligned geometry and visible Metal drawable; fixed uniforms, no settling or clock assertion"
        draw["geometryMeasurementSequence"] = measurementSequence
        draw["geometryMeasurementSource"] = "Fresh main-actor UIKit layer inspection for this accessibility query; retains the last real Compose draw"
        guard let result = try? JSONSerialization.data(withJSONObject: draw, options: [.sortedKeys]),
              let value = String(data: result, encoding: .utf8) else { return nil }
        // Preserve non-ready diagnostic state too; the full collector still
        // requires ready=true and matching captured configuration.
        if let output { try? result.write(to: output.appendingPathComponent("native-draw.json"), options: .atomic) }
        return value
    }

    private func inspectLayers(in root: CALayer) -> (metal: [[String: Any]], hierarchy: [[String: Any]]) {
        var metal: [[String: Any]] = []
        var hierarchy: [[String: Any]] = []
        let cmpClass = NSClassFromString("CMPMetalLayer")
        func rect(_ value: CGRect) -> [Double] {
            [Double(value.minX), Double(value.minY), Double(value.width), Double(value.height)]
        }
        func visit(_ layer: CALayer, path: String, ancestorsVisible: Bool) {
            let visible = ancestorsVisible && !layer.isHidden && layer.opacity > 0 && !layer.bounds.isEmpty
            var info: [String: Any] = ["path": path, "class": NSStringFromClass(type(of: layer)),
                "boundsPoints": rect(layer.bounds), "framePoints": rect(layer.frame),
                "hidden": layer.isHidden, "opacity": layer.opacity, "masksToBounds": layer.masksToBounds,
                "ancestorsVisible": ancestorsVisible, "visible": visible,
                "contentsPresent": layer.contents != nil, "contentsScale": layer.contentsScale, "opaque": layer.isOpaque]
            if let contents = layer.contents { info["contentsClass"] = String(describing: type(of: contents)) }
            var device: (any MTLDevice)?
            var drawableSize: CGSize?
            var isMetal = false
            var readerValid = false
            var needsPresentedContents = false
            if let native = layer as? CAMetalLayer {
                isMetal = true
                readerValid = true
                device = native.device
                drawableSize = native.drawableSize
                info["reader"] = "CAMetalLayer public properties"
                info["pixelFormatRawValue"] = native.pixelFormat.rawValue
                info["framebufferOnly"] = native.framebufferOnly
            } else if let cmpClass, layer.isKind(of: cmpClass) {
                isMetal = true
                needsPresentedContents = true
                // CMP 1.11.1, commit73ac84978a9e4ddca7e062dc0ee357ad875450fa:
                // CMPMetalLayer.h exports device, drawableSize, drawablesGeneration.
                // It is a CALayer, not CAMetalLayer. Read only those declared
                // Objective-C properties via KVC; never private Apple state,
                // nextDrawable(), or a newly created default Metal device.
                info["reader"] = "CMPMetalLayer exported properties; CMP1.11.1"
                let keys = ["device", "drawableSize", "drawablesGeneration"]
                if keys.allSatisfy({ layer.responds(to: NSSelectorFromString($0)) }),
                   let boxed = layer.value(forKey: "drawableSize") as? NSValue,
                   String(cString: boxed.objCType) == String(cString: NSValue(cgSize: .zero).objCType),
                   let generation = layer.value(forKey: "drawablesGeneration") as? NSNumber {
                    device = layer.value(forKey: "device") as? any MTLDevice
                    drawableSize = boxed.cgSizeValue
                    info["drawablesGeneration"] = generation
                    readerValid = true
                } else {
                    info["readerError"] = "Pinned exported getter/type contract is unavailable"
                }
                // CMPMetalLayer.m presentOnMainThread assigns its presented
                // IOSurface to public CALayer.contents. Require actual contents,
                // in addition to the layer's actual device and drawable size.
            }
            if isMetal {
                info["deviceName"] = device?.name ?? "missing"
                info["hasDevice"] = device != nil
                if let drawableSize {
                    info["drawableWidthPx"] = drawableSize.width
                    info["drawableHeightPx"] = drawableSize.height
                }
                info["readerContractValid"] = readerValid
                info["requiresPresentedContents"] = needsPresentedContents
                info["eligibleForProbe"] = readerValid && visible && device != nil && drawableSize == pixelSize
                    && (!needsPresentedContents || layer.contents != nil)
                metal.append(info)
            }
            hierarchy.append(info)
            // A zero-size nonclipping container can still have visible children.
            // Keep every node in diagnostics even when it cannot qualify.
            let childVisible = ancestorsVisible && !layer.isHidden && layer.opacity > 0
                && (!layer.masksToBounds || !layer.bounds.isEmpty)
            for (index, child) in (layer.sublayers ?? []).enumerated() {
                visit(child, path: path + "/" + String(index), ancestorsVisible: childVisible)
            }
        }
        visit(root, path: "surface", ancestorsVisible: true)
        return (metal, hierarchy)
    }
}
#endif
