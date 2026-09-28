import SwiftUI

/// `web/src/features/voice/WorkingPill.tsx`: the composer's primary slot while
/// the app, and not the person, has the next move — Send's pill with a spinner
/// in it (`docs/DESIGN.md` § "The composer" → **Done becomes a spinner, and the
/// spinner becomes Send**). Deliberately not a button, and not a disabled one
/// either: a control that looks live and does nothing is the one thing the row
/// must never show. It says in words what is being waited for, because a
/// spinner alone says only that something is happening.
struct WorkingPill: View {
    let label: String

    var body: some View {
        InverseSpinner()
            .padding(.horizontal, Space.sp3)
            .frame(minWidth: 34, minHeight: 34, maxHeight: 34)
            .background(Capsule().fill(Palette.ink))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityAddTraits(.updatesFrequently)
    }
}

/// `.working-pill .spinner`: the ring read against the ink fill, not the page.
private struct InverseSpinner: View {
    @State private var turning = false

    var body: some View {
        ZStack {
            Circle().inset(by: 1).stroke(Color.white.opacity(0.4), lineWidth: 2)
            Circle().inset(by: 1)
                .trim(from: 0.625, to: 0.875)
                .stroke(Palette.inkInverse, lineWidth: 2)
        }
        .frame(width: 14, height: 14)
        .rotationEffect(.degrees(turning ? 360 : 0))
        .onAppear {
            withAnimation(.linear(duration: 0.7).repeatForever(autoreverses: false)) { turning = true }
        }
    }
}
