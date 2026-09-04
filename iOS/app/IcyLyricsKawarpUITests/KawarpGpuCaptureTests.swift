import XCTest
import UIKit
import ImageIO
import CryptoKit

/// Separate opt-in bundle: these eight uniform snapshots do not join the29 ordinary captures.
final class KawarpGpuCaptureTests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    func testEightProductionShaderUniformPhasesOnUIKitMetal() throws {
        let runID = UUID().uuidString
        let cases = ["256x512", "512x256"].flatMap { size in [0, 1, 3, 12].map { "\(size)-phase-\($0)" } }
        try attachJSON(["catalog": "kawarp-gpu-uniform-phases-v1", "runId": runID, "cases": cases,
                        "appearanceParityVerified": false], name: "kawarp-gpu-catalog")
        XCUIDevice.shared.orientation = .portrait
        for caseID in cases {
            let app = XCUIApplication()
            app.launchArguments = ["--icy-kawarp-probe", caseID, "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            app.launchEnvironment["ICY_KAWARP_RUN_ID"] = runID
            app.launch()
            defer { app.terminate() }
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30))
            let marker = app.descendants(matching: .any).matching(identifier: "icy-kawarp-ready").firstMatch
            XCTAssertTrue(marker.waitForExistence(timeout: 20))
            let matchingDraw = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                guard let metadata = self.metadata(marker) else { return false }
                return metadata["ready"] as? Bool == true && metadata["id"] as? String == caseID
                    && metadata["runId"] as? String == runID
            }, object: nil)
            let readiness = XCTWaiter.wait(for: [matchingDraw], timeout: 30)
            if readiness != .completed {
                let failure = XCTAttachment(string: "\(marker.value ?? "missing")\n\(app.debugDescription)")
                failure.name = caseID + "-failure"; failure.lifetime = .keepAlways; add(failure)
                attachPNG(XCUIScreen.main.screenshot().pngRepresentation, name: caseID + "-failure-screen")
            }
            XCTAssertEqual(readiness, .completed, "Requires actual matching Compose draw and visible Metal drawable")
            let surface = app.descendants(matching: .any).matching(identifier: "icy-kawarp-surface").firstMatch
            XCTAssertTrue(surface.waitForExistence(timeout: 5))
            guard var details = metadata(marker), details["ready"] as? Bool == true,
                  let measurementBefore = details["geometryMeasurementSequence"] as? Int else {
                XCTFail("Missing fresh draw metadata"); return
            }
            guard let geometry = details["nativeGeometry"] as? [String: Any],
                  let bounds = geometry["windowBoundsPoints"] as? [Double], bounds.count == 4,
                  let scale = geometry["screenScale"] as? Double else {
                throw NativeDisplayCapture.CaptureError.invalid("GPU window geometry is missing")
            }
            attachPNG(surface.screenshot().pngRepresentation, name: caseID + "-xctest-diagnostic")
            let expectedWidth = Int((bounds[2] * scale).rounded())
            let expectedHeight = Int((bounds[3] * scale).rounded())
            let native = try NativeDisplayCapture.capture(name: caseID, metadata: details,
                width: expectedWidth, height: expectedHeight, in: self)
            guard let current = metadata(marker), current["ready"] as? Bool == true,
                  current["id"] as? String == caseID, current["runId"] as? String == runID,
                  let measurementAfter = current["geometryMeasurementSequence"] as? Int,
                  measurementAfter > measurementBefore,
                  (current["nativeGeometrySha256"] as? String) == (details["nativeGeometrySha256"] as? String),
                  (current["configurationSha256"] as? String) == (details["configurationSha256"] as? String) else {
                throw NativeDisplayCapture.CaptureError.invalid("GPU draw changed during native capture")
            }
            let png = native.png
            let dimensions = try pngDimensions(png)
            // UIImage.cgImage can describe a differently oriented backing image.
            // Assert the actual exported PNG pixels, with no resize or orientation guess.
            details["captureWidthPx"] = dimensions.width
            details["captureHeightPx"] = dimensions.height
            details["capturePngOrientation"] = dimensions.orientation
            details["capturePngSha256"] = SHA256.hash(data: png).map { String(format: "%02x", $0) }.joined()
            details["captureSurface"] = "simctl framebuffer with measured UIKit child crop"
            details["nativeCapture"] = native.response
            details["geometryMeasurementSequenceAfterCapture"] = measurementAfter
            details["nativeGeometryStableDuringCapture"] = true
            details["cropPolicy"] = "Retain full native PNG; collector extracts every pixel of the measured integral child viewport without resampling"
            details["xcuiSurfaceFramePoints"] = [surface.frame.minX, surface.frame.minY, surface.frame.width, surface.frame.height]
            attachPNG(png, name: caseID)
            attachPNG(app.windows.firstMatch.screenshot().pngRepresentation, name: caseID + "-window-context")
            try attachJSON(details, name: caseID + "-geometry")
            // Retain observed PNG and metadata even when XCTest alters the
            // screenshot geometry, so that failure can be diagnosed directly.
            XCTAssertEqual(dimensions.width, expectedWidth)
            XCTAssertEqual(dimensions.height, expectedHeight)
            XCTAssertEqual(dimensions.orientation, 1)
        }
    }

    private func metadata(_ element: XCUIElement) -> [String: Any]? {
        guard let value = element.value as? String, let bytes = value.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any]
    }
    private func attachPNG(_ data: Data, name: String) {
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.png")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    private func attachJSON(_ record: [String: Any], name: String) throws {
        let data = try JSONSerialization.data(withJSONObject: record, options: [.prettyPrinted, .sortedKeys])
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "public.json")
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    private func pngDimensions(_ bytes: Data) throws -> (width: Int, height: Int, orientation: Int) {
        let source = try XCTUnwrap(CGImageSourceCreateWithData(bytes as CFData, nil))
        let properties = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any])
        return (try XCTUnwrap(properties[kCGImagePropertyPixelWidth as String] as? Int),
                try XCTUnwrap(properties[kCGImagePropertyPixelHeight as String] as? Int),
                properties[kCGImagePropertyOrientation as String] as? Int ?? 1)
    }
}
