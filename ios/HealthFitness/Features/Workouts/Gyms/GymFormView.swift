import SwiftUI
// import SharedCore  // LocationFormState, Amenity — Phase 0D

/// Shared gym-form surface used by both New and Edit (parity with the Android
/// `LocationFormState` + the reused form composables). Pure UI over a local form
/// model; the owning view binds it to the shared New/Edit VM's `form` StateFlow.
struct GymFormView: View {

    /// The catalog of amenities (mirrors the shared `Amenity.CATALOG`).
    static let amenities: [(id: String, label: String)] = [
        ("24hr", "24-Hour Access"), ("lockers", "Lockers"), ("showers", "Showers"),
        ("parking", "Parking"), ("wifi", "WiFi"), ("towels", "Towels"),
        ("sauna", "Sauna"), ("pool", "Pool"), ("childcare", "Childcare"),
        ("training", "Personal Training"),
    ]

    struct FormModel {
        var name = ""
        var address = ""
        var is24Hours = false
        var amenities: Set<String> = []
        var submitting = false
        var error: String?
    }

    @Binding var form: FormModel
    /// Optional cover-photo slot (Edit only); nil hides it (New has no id yet).
    var coverPhotoUrl: String?
    var uploading: Bool = false
    var onPickCover: (() -> Void)?
    var submitTitle: String
    var onSubmit: () -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Details") {
                    TextField("Gym name", text: $form.name)
                        .textFieldStyle(.plain).padding(10)
                        .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 10))
                    TextField("Address (optional)", text: $form.address)
                        .textFieldStyle(.plain).padding(10)
                        .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 10))
                    ToggleRow(label: "Open 24 hours", isOn: $form.is24Hours)
                }

                if let onPickCover {
                    SettingsCard(title: "Cover photo") {
                        if let url = coverPhotoUrl, !url.isEmpty {
                            AsyncImage(url: URL(string: url)) { image in
                                image.resizable().scaledToFill()
                            } placeholder: {
                                Rectangle().fill(Theme.canvasMuted)
                            }
                            .frame(height: 140).clipShape(RoundedRectangle(cornerRadius: 10))
                        }
                        Button(uploading ? "Uploading…" : "Choose photo", action: onPickCover)
                            .buttonStyle(.bordered).tint(Theme.accent).disabled(uploading)
                    }
                }

                SettingsCard(title: "Amenities") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), alignment: .leading)],
                              alignment: .leading, spacing: 8) {
                        ForEach(Self.amenities, id: \.id) { amenity in
                            let on = form.amenities.contains(amenity.id)
                            Text(amenity.label)
                                .font(.hfBodySm)
                                .foregroundStyle(on ? Theme.textInverse : Theme.textSecondary)
                                .padding(.horizontal, 10).padding(.vertical, 6)
                                .background(on ? Theme.accent : Theme.canvasMuted, in: Capsule())
                                .contentShape(Capsule())
                                .onTapGesture {
                                    if on { form.amenities.remove(amenity.id) }
                                    else { form.amenities.insert(amenity.id) }
                                }
                        }
                    }
                }

                if let error = form.error {
                    Text(error).font(.hfBodySm).foregroundStyle(Theme.alert)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button(action: onSubmit) {
                    if form.submitting { ProgressView() } else { Text(submitTitle) }
                }
                .frame(maxWidth: .infinity)
                .buttonStyle(.borderedProminent).tint(Theme.accent)
                .disabled(form.submitting)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
    }
}
