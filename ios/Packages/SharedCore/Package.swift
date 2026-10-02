// swift-tools-version:5.9
import PackageDescription

// IMPL-IOS-01 Phase 1C — local wrapper so the SKIE-built XCFramework is consumed
// as an SPM `binaryTarget`. This is the reliable way to surface SKIE's generated
// Swift layer (`.swiftmodule` overlay: StateFlow -> AsyncSequence, sealed ->
// enum): a plain `-F` framework search path finds the ObjC clang module but NOT
// the Swift overlay, whereas SPM does the xcframework slice resolution + module
// path wiring. The framework must be built first:
//     cd shared && ./gradlew :core:assembleSharedCoreXCFramework
let package = Package(
    name: "SharedCore",
    products: [
        .library(name: "SharedCore", targets: ["SharedCore"]),
    ],
    targets: [
        .binaryTarget(
            name: "SharedCore",
            path: "../../../shared/core/build/XCFrameworks/release/SharedCore.xcframework"
        ),
    ]
)
