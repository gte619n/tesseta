import XCTest

/// XCUITest smoke stub (IMPL-IOS-01 Phase 2A). The full smoke suite (dev-login →
/// first-sync gate → dashboard; add med + mark dose from notification; meal log;
/// workout session; offline relaunch) lands with the feature waves + ios-ci
/// ui-smoke job. This just proves the target builds and the app launches.
final class AppLaunchUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testAppLaunches() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10))
    }
}
