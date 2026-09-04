import XCTest
import UIKit

final class ParityCaptureTests: XCTestCase {
    let portrait = ["onboarding", "empty", "portrait", "portrait-long", "portrait-failed",
                    "background-static", "background-disabled", "reduced-motion", "settings", "library",
                    "library-empty", "legal", "diagnostics"]
    let landscape = ["landscape-artwork", "landscape-titles", "landscape-mixed", "landscape-lyrics",
                     "landscape-mixed-right", "multilingual", "syllables"]

    override func setUpWithError() throws { continueAfterFailure = false }
    func testPortraitFixtures() throws { try capture(portrait, orientation: .portrait) }
    func testLandscapeLeftFixtures() throws { try capture(landscape, orientation: .landscapeLeft) }
    func testLandscapeRightFixtures() throws { try capture(landscape, orientation: .landscapeRight) }
    func testLargeTextFixture() throws {
        let largeText = ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        try capture(["portrait-long"], orientation: .portrait, extraArguments: largeText)
        try capture(["multilingual"], orientation: .landscapeLeft, extraArguments: largeText)
    }
    private func capture(_ scenarios: [String], orientation: UIDeviceOrientation, extraArguments: [String] = []) throws {
        for scenario in scenarios {
            let app = XCUIApplication()
            defer { app.terminate() }
            app.launchArguments = ["--icy-fixture", scenario, "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extraArguments
            XCUIDevice.shared.orientation = orientation
            app.launch()
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "Fixture did not launch: \(scenario)")
            XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 15))
            let marker = app.descendants(matching: .any).matching(identifier: "icy-parity-ready").firstMatch
            XCTAssertTrue(marker.waitForExistence(timeout: 20), "Fixture draw acknowledgement is missing")
            let isLandscape = orientation == .landscapeLeft || orientation == .landscapeRight
            let expectedInterface = interfaceOrientation(for: orientation)
            let rendered = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                guard let metadata = self.readMetadata(marker),
                      metadata["ready"] as? Bool == true,
                      metadata["scenario"] as? String == scenario,
                      metadata["interfaceOrientationRawValue"] as? Int == expectedInterface.rawValue,
                      let width = metadata["contentWidthPx"] as? Int,
                      let height = metadata["contentHeightPx"] as? Int else { return false }
                return width > 0 && height > 0 && (width > height) == isLandscape
            }, object: nil)
            let drawResult = XCTWaiter.wait(for: [rendered], timeout: 30)
            if drawResult != .completed {
                let diagnostic = XCTAttachment(string: "\(marker.value ?? "missing")\n\(app.debugDescription)")
                diagnostic.name = "\(scenario)-\(orientation.rawValue)-draw-failure"
                diagnostic.lifetime = .keepAlways
                add(diagnostic)
                let failedScreen = XCTAttachment(data: XCUIScreen.main.screenshot().pngRepresentation,
                                                 uniformTypeIdentifier: "public.png")
                failedScreen.name = "\(scenario)-\(orientation.rawValue)-draw-failure-screen"
                failedScreen.lifetime = .keepAlways
                add(failedScreen)
            }
            XCTAssertEqual(drawResult, .completed, "Compose did not draw the requested orientation: \(scenario)")
            // Keep the existing settling interval, now measured after a real
            // matching draw. This is not deterministic spring-clock sampling.
            let settling = expectation(description: "Post-draw settling interval")
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { settling.fulfill() }
            wait(for: [settling], timeout: 5)
            guard var metadata = readMetadata(marker), metadata["ready"] as? Bool == true,
                  metadata["scenario"] as? String == scenario else { XCTFail("Invalid fixture metadata"); return }
            XCTAssertEqual(metadata["interfaceOrientationRawValue"] as? Int, expectedInterface.rawValue)
            XCTAssertEqual(metadata["timezone"] as? String, "America/Los_Angeles")
            if !extraArguments.isEmpty {
                XCTAssertEqual(metadata["preferredContentSizeCategory"] as? String,
                               "UICTContentSizeCategoryAccessibilityXXXL")
                XCTAssertGreaterThan((metadata["fontScale"] as? NSNumber)?.doubleValue ?? 0, 1)
            }
            let shot = app.windows.firstMatch.screenshot()
            let name = "\(scenario)-\(orientation.rawValue)\(extraArguments.isEmpty ? "" : "-large-text")"
            let diagnostic = XCTAttachment(data: shot.pngRepresentation, uniformTypeIdentifier: "public.png")
            diagnostic.name = name + "-xctest-diagnostic"
            diagnostic.lifetime = .keepAlways
            add(diagnostic)
            metadata["uiImageOrientationRawValue"] = shot.image.imageOrientation.rawValue
            metadata["uiImageScale"] = Double(shot.image.scale)
            metadata["uiImageSizePoints"] = [Double(shot.image.size.width), Double(shot.image.size.height)]
            metadata["requestedDeviceOrientationRawValue"] = orientation.rawValue
            metadata["expectedInterfaceOrientationRawValue"] = expectedInterface.rawValue
            metadata["requestedLargeText"] = !extraArguments.isEmpty
            metadata["settleDelayAfterDrawSeconds"] = 2
            let width = try XCTUnwrap(metadata["contentWidthPx"] as? Int)
            let height = try XCTUnwrap(metadata["contentHeightPx"] as? Int)
            let mappingBefore = try XCTUnwrap(metadata["fixedCoordinateMapping"] as? [String: Any])
            let mappingBeforeBytes = try JSONSerialization.data(withJSONObject: mappingBefore, options: [.sortedKeys])
            let native = try NativeDisplayCapture.capture(name: name, metadata: metadata,
                                                          width: width, height: height, in: self)
            guard let current = readMetadata(marker), current["ready"] as? Bool == true,
                  current["scenario"] as? String == scenario,
                  current["interfaceOrientationRawValue"] as? Int == expectedInterface.rawValue,
                  current["contentWidthPx"] as? Int == width, current["contentHeightPx"] as? Int == height,
                  let mappingAfter = current["fixedCoordinateMapping"] as? [String: Any],
                  try JSONSerialization.data(withJSONObject: mappingAfter, options: [.sortedKeys]) == mappingBeforeBytes else {
                throw NativeDisplayCapture.CaptureError.invalid("App geometry changed during native capture")
            }
            metadata["fixedCoordinateMappingAfterCapture"] = mappingAfter
            metadata["fixedCoordinateMappingStableDuringCapture"] = true
            metadata["capturedWindowWidthPx"] = native.width
            metadata["capturedWindowHeightPx"] = native.height
            metadata["captureSurface"] = "simctl framebuffer with XCTest-driven UIKit window; measured window coordinates; original framebuffer retained"
            metadata["nativeCapture"] = native.response
            let attachment = XCTAttachment(data: native.png, uniformTypeIdentifier: "public.png")
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
            let details = try! JSONSerialization.data(withJSONObject: metadata, options: [.prettyPrinted, .sortedKeys])
            let geometry = XCTAttachment(data: details, uniformTypeIdentifier: "public.json")
            geometry.name = name + "-geometry"
            geometry.lifetime = .keepAlways
            add(geometry)
            XCTAssertEqual(native.width > native.height, isLandscape)
        }
    }

    private func readMetadata(_ marker: XCUIElement) -> [String: Any]? {
        guard let value = marker.value as? String, let bytes = value.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any]
    }

    private func interfaceOrientation(for device: UIDeviceOrientation) -> UIInterfaceOrientation {
        // UIKit names landscape interfaces opposite to device rotation:
        // https://developer.apple.com/documentation/uikit/uiinterfaceorientation
        switch device {
        case .portrait: return .portrait
        case .landscapeLeft: return .landscapeRight
        case .landscapeRight: return .landscapeLeft
        default: preconditionFailure("Unsupported parity capture orientation")
        }
    }
}
