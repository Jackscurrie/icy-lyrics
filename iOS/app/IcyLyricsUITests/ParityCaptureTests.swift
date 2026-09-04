import XCTest

final class ParityCaptureTests: XCTestCase {
    let portrait = ["onboarding", "empty", "portrait", "portrait-long", "portrait-failed",
                    "background-static", "background-disabled", "reduced-motion", "settings", "library",
                    "library-empty", "legal", "diagnostics"]
    let landscape = ["landscape-artwork", "landscape-titles", "landscape-mixed", "landscape-lyrics",
                     "landscape-mixed-right", "multilingual", "syllables"]

    override func setUpWithError() throws { continueAfterFailure = false }
    func testPortraitFixtures() { capture(portrait, orientation: .portrait) }
    func testLandscapeLeftFixtures() { capture(landscape, orientation: .landscapeLeft) }
    func testLandscapeRightFixtures() { capture(landscape, orientation: .landscapeRight) }
    func testLargeTextFixture() {
        capture(["portrait-long", "multilingual"], orientation: .portrait,
                extraArguments: ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
    }
    private func capture(_ scenarios: [String], orientation: UIDeviceOrientation, extraArguments: [String] = []) {
        for scenario in scenarios {
            let app = XCUIApplication()
            app.launchArguments = ["--icy-fixture", scenario, "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extraArguments
            XCUIDevice.shared.orientation = orientation
            app.launch()
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "Fixture did not launch: \(scenario)")
            XCTAssertTrue(app.windows.firstMatch.waitForExistence(timeout: 15))
            // Give the Compose/Metal surface time to deliver its deterministic frame.
            // Rendering assertions and strict pixel comparison are separate from this capture test.
            let rendered = expectation(description: "Compose surface frame")
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { rendered.fulfill() }
            wait(for: [rendered], timeout: 5)
            let shot = app.windows.firstMatch.screenshot()
            XCTAssertGreaterThan(shot.pngRepresentation.count, 5000)
            let attachment = XCTAttachment(screenshot: shot)
            attachment.name = "\(scenario)-\(orientation.rawValue)\(extraArguments.isEmpty ? "" : "-large-text")"
            attachment.lifetime = .keepAlways
            add(attachment)
            app.terminate()
        }
    }
}
