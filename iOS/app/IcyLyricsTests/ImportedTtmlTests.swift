import Foundation
import XCTest
@testable import IcyLyrics

final class ImportedTtmlTests: XCTestCase {
    func testBoundedReadContinuesAcrossShortReads() throws {
        var chunks: [Data?] = [Data([1]), Data([2, 3]), Data([4]), nil]
        var requests = [Int]()
        let result = try ImportedTtml.readBounded(maximumBytes: 4) { count in
            requests.append(count)
            return chunks.removeFirst()
        }
        XCTAssertEqual(result, Data([1, 2, 3, 4]))
        XCTAssertEqual(requests, [5, 4, 2, 1])
    }

    func testBoundedReadDetectsGrowthBeyondTheLimit() {
        var chunks = [Data([1, 2]), Data([3]), Data([4])]
        XCTAssertThrowsError(try ImportedTtml.readBounded(maximumBytes: 3) { _ in chunks.removeFirst() }) { error in
            XCTAssertEqual((error as? CocoaError)?.code, .fileReadTooLarge)
        }
    }

    func testDefaultByteLimitAcceptsEightMillionAndRejectsOneExtraByte() throws {
        for length in [8_000_000, 8_000_001] {
            var remaining = length
            let read: (Int) -> Data? = { count in
                guard remaining > 0 else { return nil }
                let size = min(count, remaining)
                remaining -= size
                return Data(repeating: 0x61, count: size)
            }
            if length == 8_000_000 {
                XCTAssertEqual(try ImportedTtml.readBounded(read: read).count, length)
            } else {
                XCTAssertThrowsError(try ImportedTtml.readBounded(read: read)) { error in
                    XCTAssertEqual((error as? CocoaError)?.code, .fileReadTooLarge)
                }
            }
        }
    }

    func testCancellationInterruptsABoundedReadBeforeAnotherChunk() async {
        let read = Task.detached { () throws -> Data in
            var reads = 0
            return try ImportedTtml.readBounded { _ in
                reads += 1
                XCTAssertEqual(reads, 1)
                withUnsafeCurrentTask { $0?.cancel() }
                return Data([1])
            }
        }
        do { _ = try await read.value; XCTFail("Cancelled import continued reading") }
        catch { XCTAssertTrue(error is CancellationError) }
    }

    func testImportMakesAnIndependentDurableCopy() throws {
        let folder = try makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = folder.appendingPathComponent("selected.ttml")
        let imports = folder.appendingPathComponent("Imports")
        let text = "<tt><body><p>Music \u{1F3B5} &amp; words</p></body></tt>"
        try Data(text.utf8).write(to: source)
        let imported = try ImportedTtml.copy(source, into: imports)
        try FileManager.default.removeItem(at: source)
        XCTAssertEqual(imported.text, text)
        XCTAssertEqual(imported.url.deletingLastPathComponent().standardizedFileURL, imports.standardizedFileURL)
        XCTAssertEqual(try String(contentsOf: imported.url, encoding: .utf8), text)
        imported.discard()
        XCTAssertFalse(FileManager.default.fileExists(atPath: imported.url.path))
    }

    func testInvalidUTF8AndCharacterLimitDoNotLeavePartialCopies() throws {
        let folder = try makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = folder.appendingPathComponent("selected.ttml")
        let imports = folder.appendingPathComponent("Imports")
        for data in [Data([0xff, 0xfe, 0x80]), Data(repeating: 0x61, count: 2_000_001)] {
            try data.write(to: source)
            XCTAssertThrowsError(try ImportedTtml.copy(source, into: imports))
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: imports.path), [])
            XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
        }
    }

    func testCharacterLimitCountsUTF16UnitsIncludingSurrogatePairs() throws {
        let folder = try makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = folder.appendingPathComponent("selected.ttml")
        let imports = folder.appendingPathComponent("Imports")
        let allowed = String(repeating: "\u{1F3B5}", count: 1_000_000)
        XCTAssertEqual(allowed.utf16.count, 2_000_000)
        try Data(allowed.utf8).write(to: source)
        let imported = try ImportedTtml.copy(source, into: imports)
        XCTAssertEqual(imported.text, allowed)
        imported.discard()
        try Data((allowed + "\u{1F3B5}").utf8).write(to: source)
        XCTAssertThrowsError(try ImportedTtml.copy(source, into: imports)) { error in
            XCTAssertEqual((error as? CocoaError)?.code, .fileReadCorruptFile)
        }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: imports.path), [])
    }

    func testOversizedFileDoesNotLeaveAPartialCopy() throws {
        let folder = try makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = folder.appendingPathComponent("selected.ttml")
        let imports = folder.appendingPathComponent("Imports")
        try Data(repeating: 0x61, count: 8_000_001).write(to: source)
        XCTAssertThrowsError(try ImportedTtml.copy(source, into: imports)) { error in
            XCTAssertEqual((error as? CocoaError)?.code, .fileReadTooLarge)
        }
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: imports.path), [])
        XCTAssertTrue(FileManager.default.fileExists(atPath: source.path))
    }

    func testCancelledCopyLeavesTheSelectedFileUntouched() async throws {
        let folder = try makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let source = folder.appendingPathComponent("selected.ttml")
        let imports = folder.appendingPathComponent("Imports")
        let original = Data("<tt/>".utf8)
        try original.write(to: source)
        let copy = Task.detached { () throws -> ImportedTtml in
            withUnsafeCurrentTask { $0?.cancel() }
            return try ImportedTtml.copy(source, into: imports)
        }
        do { _ = try await copy.value; XCTFail("Cancelled import produced a copy") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(try Data(contentsOf: source), original)
        XCTAssertFalse(FileManager.default.fileExists(atPath: imports.path))
    }

    private func makeTemporaryDirectory() throws -> URL {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("icy-import-test-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder
    }
}
