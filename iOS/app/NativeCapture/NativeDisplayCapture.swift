import Foundation
import XCTest
import ImageIO
import CryptoKit

/// Test-runner/host handshake retaining the original simulator framebuffer.
/// A measured window-coordinate permutation may reorder whole pixels; no scaling.
enum NativeDisplayCapture {
    struct Frame {
        let png: Data
        let width: Int
        let height: Int
        let orientation: Int
        let response: [String: Any]
    }

    enum CaptureError: Error {
        case invalid(String)
    }

    static func capture(name: String, metadata: [String: Any], width: Int, height: Int,
                        in test: XCTestCase) throws -> Frame {
        guard width > 0 && height > 0 else { throw CaptureError.invalid("Native dimensions are missing") }
        let manager = FileManager.default
        let documents = try manager.url(for: .documentDirectory, in: .userDomainMask,
                                        appropriateFor: nil, create: true)
        let base = documents.appendingPathComponent("icy-native-capture", isDirectory: true)
        let requests = base.appendingPathComponent("requests", isDirectory: true)
        let responses = base.appendingPathComponent("responses", isDirectory: true)
        try manager.createDirectory(at: requests, withIntermediateDirectories: true)
        try manager.createDirectory(at: responses, withIntermediateDirectories: true)
        let id = UUID().uuidString
        let request: [String: Any] = ["schemaVersion": 1, "requestId": id, "scenario": name,
            "createdAtUnixMs": Date().timeIntervalSince1970 * 1000,
            "expectedWidthPx": width, "expectedHeightPx": height, "metadata": metadata]
        let bytes = try JSONSerialization.data(withJSONObject: request, options: [.sortedKeys])
        attach(bytes, type: "public.json", name: name + "-capture-request", in: test)
        try bytes.write(to: requests.appendingPathComponent(id + ".json"), options: .atomic)
        let responseURL = responses.appendingPathComponent(id + ".json")
        let acknowledgement = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            manager.fileExists(atPath: responseURL.path)
        }, object: nil)
        guard XCTWaiter.wait(for: [acknowledgement], timeout: 60) == .completed else {
            attach(bytes, type: "public.json", name: name + "-host-request-timeout", in: test)
            throw CaptureError.invalid("Native framebuffer host did not acknowledge request \(id)")
        }
        let responseBytes = try Data(contentsOf: responseURL)
        attach(responseBytes, type: "public.json", name: name + "-capture-response", in: test)
        guard let response = try JSONSerialization.jsonObject(with: responseBytes) as? [String: Any],
              response["schemaVersion"] as? Int == 1,
              response["requestId"] as? String == id,
              response["scenario"] as? String == name,
              response["source"] as? String == "simctl framebuffer",
              response["requestSha256"] as? String == digest(bytes) else {
            throw CaptureError.invalid("Native framebuffer response identity does not match")
        }
        let pngURL = responses.appendingPathComponent(id + ".png")
        guard manager.fileExists(atPath: pngURL.path) else {
            throw CaptureError.invalid("Native framebuffer capture failed: \(response)")
        }
        let png = try Data(contentsOf: pngURL)
        do {
            guard png.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]) else {
                throw CaptureError.invalid("Host capture is not a PNG")
            }
            let hash = digest(png)
            guard response["sha256"] as? String == hash,
                  let image = CGImageSourceCreateWithData(png as CFData, nil),
                  let properties = CGImageSourceCopyPropertiesAtIndex(image, 0, nil) as? [String: Any],
                  let actualWidth = properties[kCGImagePropertyPixelWidth as String] as? Int,
                  let actualHeight = properties[kCGImagePropertyPixelHeight as String] as? Int else {
                throw CaptureError.invalid("Native PNG checksum or image properties are invalid")
            }
            let orientation = properties[kCGImagePropertyOrientation as String] as? Int ?? 1
            guard response["status"] as? String == "captured",
                  response["widthPx"] as? Int == actualWidth,
                  response["heightPx"] as? Int == actualHeight,
                  actualWidth == width, actualHeight == height, orientation == 1 else {
                throw CaptureError.invalid("Native window PNG is \(actualWidth)x\(actualHeight), orientation \(orientation); expected \(width)x\(height). Host response: \(response)")
            }
            if response["imageTransformed"] as? Bool == true {
                let rawURL = responses.appendingPathComponent(id + ".raw.png")
                let raw = try Data(contentsOf: rawURL)
                attach(raw, type: "public.png", name: name + "-raw-framebuffer", in: test)
                guard response["rawSha256"] as? String == digest(raw),
                      response["coordinateMapping"] as? [String: Any] != nil,
                      let rawImage = CGImageSourceCreateWithData(raw as CFData, nil),
                      let rawProperties = CGImageSourceCopyPropertiesAtIndex(rawImage, 0, nil) as? [String: Any],
                      let rawWidth = rawProperties[kCGImagePropertyPixelWidth as String] as? Int,
                      let rawHeight = rawProperties[kCGImagePropertyPixelHeight as String] as? Int,
                      rawWidth > 0, rawHeight > 0,
                      rawWidth == response["rawWidthPx"] as? Int,
                      rawHeight == response["rawHeightPx"] as? Int,
                      (rawProperties[kCGImagePropertyOrientation as String] as? Int ?? 1) == 1 else {
                    throw CaptureError.invalid("Original framebuffer or coordinate mapping evidence is missing")
                }
            } else if response["imageTransformed"] as? Bool != false {
                throw CaptureError.invalid("Host did not declare the pixel coordinate operation")
            }
            return Frame(png: png, width: actualWidth, height: actualHeight,
                         orientation: orientation, response: response)
        } catch {
            attach(png, type: "public.png", name: name + "-rejected-framebuffer", in: test)
            throw error
        }
    }

    private static func digest(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func attach(_ data: Data, type: String, name: String, in test: XCTestCase) {
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: type)
        attachment.name = name
        attachment.lifetime = .keepAlways
        test.add(attachment)
    }
}
