import Testing
// import SharedCore   // KMP core via SKIE; XCFramework declared in project.yml.

/// Swift Testing example (IMPL-IOS-01 Phase 2A). **STUB — pending the XCFramework.**
///
/// Asserts the SKIE-bridged shared `CollectionRegistry` resolves a wire string to
/// the expected collection. This is the iOS end of the XPLAT anti-drift keystone:
/// the exact same registry (25 collections + slash-form aliases) that Android
/// consumes must resolve identically on iOS — the class of bug behind the
/// "nutritionDays/entries" alias miss (see memory: nutrition-sync-slash-collection-bug).
///
/// When Phase 0D/1 land the XCFramework, uncomment `import SharedCore`, drop the
/// `.disabled` trait, and fill the body against the real bridged type.
@Suite("CollectionRegistry SKIE bridge")
struct CollectionRegistryBridgeTest {

    @Test("resolves the nutritionDays/entries slash-form alias", .disabled("pending SharedCore XCFramework (Phase 0D/1)"))
    func resolvesSlashFormAlias() throws {
        // let registry = CollectionRegistry.shared
        // let resolved = registry.resolve(wire: "nutritionDays/entries")
        // #expect(resolved == .nutritionEntries)
    }

    @Test("resolves a top-level collection wire string", .disabled("pending SharedCore XCFramework (Phase 0D/1)"))
    func resolvesTopLevel() throws {
        // let registry = CollectionRegistry.shared
        // #expect(registry.resolve(wire: "medications") == .medications)
    }
}
