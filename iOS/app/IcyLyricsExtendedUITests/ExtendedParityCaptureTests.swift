import XCTest
import UIKit

/// Opt-in coverage. The ordinary IcyLyrics scheme does not include this bundle.
@MainActor
final class ExtendedParityCaptureTests: XCTestCase {
    private var actions = [[String: Any]]()
    override func setUpWithError() throws { continueAfterFailure = false }

    func testPortraitExpanded() throws {
        try capture("portrait-expanded", base: "portrait", anchor: "Collapse lyrics") { app in
            try self.tap(app.buttons["Expand lyrics"], named: "Expand lyrics")
        }
    }
    func testSettingsFullscreen() throws { try settings("settings-fullscreen", title: "Fullscreen") }
    func testSettingsSources() throws { try settings("settings-sources", title: "Lyric sources") }
    func testSettingsTroubleshooting() throws { try settings("settings-troubleshooting", title: "Troubleshooting") }
    func testSettingsPrivacy() throws { try settings("settings-privacy", title: "Privacy") }
    func testTokenConsent() throws {
        try capture("token-consent", base: "settings", anchor: "Allow token sharing?") { app in
            try self.scrollTo("Spicy Lyrics", app: app)
            // ToggleRow is not a semantic ancestor on Android. Identify the
            // actual Switch using the observed label/description vertical span,
            // then invoke its semantic action rather than tapping coordinates.
            let label = self.element("Spicy Lyrics", app: app)
            let description = self.element("Experimental provider using a connected Spotify session.", app: app)
            try self.requireVisible(label, named: "Spicy Lyrics")
            try self.requireVisible(description, named: "Spicy Lyrics description")
            let lower = min(label.frame.minY, description.frame.minY)
            let upper = max(label.frame.maxY, description.frame.maxY)
            let switches = app.switches.allElementsBoundByIndex.filter {
                $0.isHittable && $0.frame.midY >= lower && $0.frame.midY <= upper
            }
            guard switches.count == 1 else {
                throw ProbeError.missing("Exactly one accessible Switch within the observed Spicy Lyrics label/description span; found \(switches.count)")
            }
            self.actions.append(["action": "identify semantic Switch by observed row text", "label": self.describe(label),
                "description": self.describe(description), "switch": self.describe(switches[0])])
            try self.tap(switches[0], named: "Spicy Lyrics Switch")
            try self.requireVisible(app.buttons["Allow and enable"], named: "Allow and enable")
            try self.requireVisible(app.buttons["Cancel"], named: "Cancel")
        }
    }
    func testLegalLower() throws {
        try capture("legal-lower", base: "legal", anchor: "Third-party software") { app in
            try self.scrollTo("Third-party software", app: app)
        }
    }
    func testLegalAgpl() throws { try legal("legal-agpl", thirdParty: false, scrolled: false) }
    func testLegalAgplScrolled() throws { try legal("legal-agpl-scrolled", thirdParty: false, scrolled: true) }
    func testLegalThirdParty() throws { try legal("legal-third-party", thirdParty: true, scrolled: false) }
    func testLegalThirdPartyScrolled() throws { try legal("legal-third-party-scrolled", thirdParty: true, scrolled: true) }

    private func settings(_ id: String, title: String) throws {
        try capture(id, base: "settings", anchor: title) { try self.scrollTo(title, app: $0) }
    }
    private func legal(_ id: String, thirdParty: Bool, scrolled: Bool) throws {
        let button = thirdParty ? "Read third-party notices offline" : "Read the full license offline"
        let title = thirdParty ? "Third-party notices" : "GNU AGPL v3 or later"
        try capture(id, base: "legal", anchor: title) { app in
            try self.scrollTo(button, app: app)
            let launch = try self.uniqueButton(button, app: app)
            try self.requireStableFrame(launch, named: button)
            try self.tap(launch, named: button)
            let dialogTitle = self.element(title, app: app)
            let close = app.buttons["Close"]
            let appeared = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                dialogTitle.exists || close.exists
            }, object: nil)
            if XCTWaiter.wait(for: [appeared], timeout: 3) != .completed,
               !dialogTitle.exists, !close.exists,
               app.buttons[button].exists, app.buttons[button].isHittable {
                // One observed AGPL tap did not open a modal. A retry is allowed
                // only while the original button remains and neither modal
                // marker exists; never tap through a partly appeared dialog.
                let retry = try self.uniqueButton(button, app: app)
                try self.requireStableFrame(retry, named: button)
                if !dialogTitle.exists && !close.exists && retry.exists && retry.isHittable {
                    self.actions.append(["action": "bounded legal-open retry", "attempt": 2,
                        "reason": "Original launch button is visible; title and Close are both absent",
                        "element": self.describe(retry)])
                    try self.tap(retry, named: button)
                }
            }
            try self.requireVisible(dialogTitle, named: title)
            try self.requireVisible(close, named: "Close")
            if scrolled {
                // Actual Compose modal AX trees use a full-window ScrollView.
                // Its title/Close descendants identify it; its width does not.
                let candidates = app.scrollViews.allElementsBoundByIndex.filter {
                    $0.isHittable && $0.descendants(matching: .any).matching(NSPredicate(format: "label == %@", title)).firstMatch.exists
                        && $0.buttons["Close"].exists
                }
                guard candidates.count == 1 else {
                    throw ProbeError.missing("Exactly one accessible legal dialog containing its title and Close; found \(candidates.count)")
                }
                let scroll = candidates[0]
                let bodies = scroll.staticTexts.allElementsBoundByIndex.filter {
                    $0.label != title && $0.label != "Close" && $0.isHittable && !$0.frame.isEmpty
                        && $0.frame.minY >= dialogTitle.frame.maxY && $0.frame.maxY <= close.frame.minY
                        && app.frame.contains($0.frame)
                }
                guard bodies.count == 1 else {
                    throw ProbeError.missing("Exactly one observed legal text body between title and Close; found \(bodies.count)")
                }
                let body = bodies[0]
                try self.requireStableFrame(body, named: "legal text body")
                let start = body.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
                let end = body.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2))
                self.actions.append(["action": "dialog body drag upward", "regionBefore": self.describe(scroll),
                    "bodyBoundsPoints": self.rect(body.frame), "bodyLabelCharacters": body.label.count,
                    "startScreenPoint": [Double(start.screenPoint.x), Double(start.screenPoint.y)],
                    "endScreenPoint": [Double(end.screenPoint.x), Double(end.screenPoint.y)],
                    "offsetEquivalence": "Native gesture; not an asserted match to Android's 360dp ScrollBy"])
                start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0)
                try self.requireVisible(dialogTitle, named: title)
                try self.requireVisible(close, named: "Close")
                self.actions.append(["action": "dialog scroll result", "regionAfter": self.describe(scroll)])
            }
        }
    }

    private func capture(_ id: String, base: String, anchor: String, act: (XCUIApplication) throws -> Void) throws {
        actions = []
        let app = XCUIApplication()
        app.launchArguments = ["--icy-fixture", base, "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        XCUIDevice.shared.orientation = .portrait
        app.launch()
        defer { app.terminate() }
        do {
            guard app.wait(for: .runningForeground, timeout: 30) else { throw ProbeError.missing("Foreground fixture") }
            let marker = app.descendants(matching: .any).matching(identifier: "icy-parity-ready").firstMatch
            guard marker.waitForExistence(timeout: 20) else { throw ProbeError.missing("Compose draw marker") }
            let drawn = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
                guard let value = self.metadata(marker) else { return false }
                return value["ready"] as? Bool == true && value["scenario"] as? String == base &&
                    value["interfaceOrientationRawValue"] as? Int == UIInterfaceOrientation.portrait.rawValue
            }, object: nil)
            guard XCTWaiter.wait(for: [drawn], timeout: 30) == .completed else { throw ProbeError.missing("Portrait Compose draw") }
            try act(app)
            let visibleAnchor = element(anchor, app: app)
            try requireVisible(visibleAnchor, named: anchor)
            let settling = expectation(description: "Extended surface settling")
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { settling.fulfill() }
            wait(for: [settling], timeout: 5)
            try requireVisible(visibleAnchor, named: anchor)
            guard var details = metadata(marker), let scale = details["displayScale"] as? NSNumber,
                  let bounds = details["windowBoundsPoints"] as? [Double], bounds.count == 4 else {
                throw ProbeError.missing("Native window geometry")
            }
            let windows = app.windows.allElementsBoundByIndex.filter { $0.exists && !$0.frame.isEmpty }
            guard !windows.isEmpty else { throw ProbeError.missing("Application window") }
            // The application screenshot retains simultaneous app windows and
            // dialog dimming. Individual windows are also attached for review.
            let shot = app.screenshot()
            attach(XCTAttachment(data: shot.pngRepresentation, uniformTypeIdentifier: "public.png"),
                   name: "extended-v1-" + id + "-xctest-diagnostic")
            details["catalog"] = "extended-v1"
            details["baseScenario"] = base
            details["scenario"] = id
            details["extendedScenario"] = id
            details["captureSurface"] = "simctl framebuffer with XCTest-driven UIKit window; includes app dialogs and dimming"
            details["uiImageOrientationRawValue"] = shot.image.imageOrientation.rawValue
            details["uiImageScale"] = Double(shot.image.scale)
            details["uiImageSizePoints"] = [Double(shot.image.size.width), Double(shot.image.size.height)]
            details["requestedDeviceOrientationRawValue"] = UIDeviceOrientation.portrait.rawValue
            details["expectedInterfaceOrientationRawValue"] = UIInterfaceOrientation.portrait.rawValue
            details["requestedLargeText"] = false
            details["settleDelayAfterActionSeconds"] = 2
            details["readiness"] = "Initial Compose draw acknowledged; resulting surface anchor is hittable before and after a 2-second settle"
            details["anchor"] = describe(visibleAnchor)
            details["actions"] = actions
            details["applicationFramePoints"] = rect(app.frame)
            details["windows"] = windows.map(describe)
            details["scrollRegions"] = app.scrollViews.allElementsBoundByIndex.filter { $0.exists }.map(describe)
            details["scrollOffsetEquivalence"] = "Requires comparison with the Android extended reference; no offset equality asserted"
            details["appearanceParityVerified"] = false
            let native = try NativeDisplayCapture.capture(name: "extended-v1-" + id, metadata: details,
                width: Int((bounds[2] * scale.doubleValue).rounded()),
                height: Int((bounds[3] * scale.doubleValue).rounded()), in: self)
            try requireVisible(visibleAnchor, named: anchor)
            guard let current = self.metadata(marker), current["ready"] as? Bool == true,
                  current["scenario"] as? String == base,
                  current["interfaceOrientationRawValue"] as? Int == UIInterfaceOrientation.portrait.rawValue else {
                throw ProbeError.missing("Unchanged UIKit geometry after native capture")
            }
            details["capturedWindowWidthPx"] = native.width
            details["capturedWindowHeightPx"] = native.height
            details["nativeCapture"] = native.response
            attach(XCTAttachment(data: native.png, uniformTypeIdentifier: "public.png"), name: "extended-v1-" + id)
            for (index, window) in windows.enumerated() {
                attach(XCTAttachment(data: window.screenshot().pngRepresentation, uniformTypeIdentifier: "public.png"), name: "extended-v1-\(id)-window-\(index)")
            }
            attach(XCTAttachment(data: try JSONSerialization.data(withJSONObject: details, options: [.prettyPrinted, .sortedKeys]),
                                 uniformTypeIdentifier: "public.json"), name: "extended-v1-" + id + "-geometry")
            guard native.height > native.width else { throw ProbeError.missing("Native portrait framebuffer") }
        } catch {
            attach(XCTAttachment(string: app.debugDescription), name: "extended-v1-" + id + "-accessibility-failure")
            if app.state == .runningForeground {
                attach(XCTAttachment(data: app.screenshot().pngRepresentation, uniformTypeIdentifier: "public.png"),
                       name: "extended-v1-" + id + "-failure")
            }
            throw error
        }
    }

    private func element(_ label: String, app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }
    private func requireVisible(_ element: XCUIElement, named name: String) throws {
        guard element.waitForExistence(timeout: 10), element.isHittable else { throw ProbeError.missing(name) }
    }
    private func uniqueButton(_ label: String, app: XCUIApplication) throws -> XCUIElement {
        let query = app.buttons.matching(NSPredicate(format: "label == %@", label))
        guard query.firstMatch.waitForExistence(timeout: 10) else { throw ProbeError.missing(label) }
        let matches = query.allElementsBoundByIndex.filter { $0.exists && $0.isHittable && $0.isEnabled }
        guard matches.count == 1 else { throw ProbeError.missing("Exactly one enabled \(label) button; found \(matches.count)") }
        return matches[0]
    }
    private func requireStableFrame(_ element: XCUIElement, named name: String) throws {
        try requireVisible(element, named: name)
        var previous: CGRect?
        var unchangedSince: Date?
        let stable = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            guard element.exists && element.isHittable && !element.frame.isEmpty else {
                previous = nil; unchangedSince = nil; return false
            }
            let current = element.frame
            let now = Date()
            if previous == current, let since = unchangedSince {
                return now.timeIntervalSince(since) >= 0.3
            }
            previous = current; unchangedSince = now
            return false
        }, object: nil)
        guard XCTWaiter.wait(for: [stable], timeout: 5) == .completed else {
            throw ProbeError.missing("Stable visible frame for \(name)")
        }
        actions.append(["action": "observed stable frame", "name": name, "framePoints": rect(element.frame),
                        "minimumUnchangedSeconds": 0.3])
    }
    private func tap(_ element: XCUIElement, named name: String) throws {
        try requireVisible(element, named: name)
        actions.append(["action": "tap", "name": name, "element": describe(element)])
        element.tap()
    }
    private func scrollTo(_ label: String, app: XCUIApplication) throws {
        for attempt in 0...10 {
            let target = element(label, app: app)
            if target.exists && target.isHittable {
                actions.append(["action": "scroll anchor reached", "swipes": attempt, "anchor": describe(target)])
                return
            }
            guard attempt < 10 else { throw ProbeError.missing("Scrollable anchor: " + label) }
            let region = app.scrollViews.allElementsBoundByIndex.first { $0.isHittable } ?? app
            actions.append(["action": "swipeUp", "region": describe(region)])
            region.swipeUp(velocity: .slow)
        }
    }
    private func metadata(_ marker: XCUIElement) -> [String: Any]? {
        guard let value = marker.value as? String, let bytes = value.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: bytes)) as? [String: Any]
    }
    private func rect(_ value: CGRect) -> [Double] { [Double(value.minX), Double(value.minY), Double(value.width), Double(value.height)] }
    private func describe(_ element: XCUIElement) -> [String: Any] {
        ["label": element.label, "identifier": element.identifier, "elementType": element.elementType.rawValue,
         "value": element.value.map { String(describing: $0) } ?? "", "framePoints": rect(element.frame), "hittable": element.isHittable]
    }
    private func attach(_ attachment: XCTAttachment, name: String) {
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
    private enum ProbeError: Error { case missing(String) }
}
