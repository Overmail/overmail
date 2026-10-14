import SwiftUI

/// The floating bar at the bottom of the app: a pill with the tabs, and beside it the round button
/// for a new mail. Of glass on iOS 26 and later, of a material before.
struct ShellTabBar: View {
    let selected: ShellTab
    /// Told about the tab that is already selected as well: a second tap may mean something.
    let onSelect: (ShellTab) -> Void
    let onCompose: () -> Void

    @Namespace private var indicator

    var body: some View {
        GlassGroup(spacing: Self.spacing) {
            HStack(spacing: Self.spacing) {
                HStack(spacing: 4) {
                    ForEach(ShellTab.allCases, id: \.self) { tab in
                        item(tab)
                    }
                }
                .padding(4)
                .glass(in: Capsule())

                Button(action: onCompose) {
                    Image(systemName: "paperplane")
                        .font(.system(size: 20, weight: .medium))
                        .frame(width: Self.height, height: Self.height)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("shell.compose"))
                .glass(in: Circle())
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.75), value: selected)
    }

    private func item(_ tab: ShellTab) -> some View {
        let isSelected = tab == selected
        return Button { onSelect(tab) } label: {
            VStack(spacing: 2) {
                Image(systemName: tab.systemImage)
                    .symbolVariant(isSelected ? .fill : .none)
                    .font(.system(size: 18, weight: .medium))
                    .frame(height: 24)
                Text(tab.title)
                    .font(.caption.weight(.medium))
            }
            .foregroundStyle(isSelected ? Color.accentColor : Color.primary)
            .frame(width: 84, height: Self.height - 8)
            .background {
                // Behind the items rather than on one of them, so it can slide from one to the next.
                if isSelected {
                    Capsule()
                        .fill(Color.primary.opacity(0.08))
                        .matchedGeometryEffect(id: "indicator", in: indicator)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    static let height: CGFloat = 60
    private static let spacing: CGFloat = 8
}

/// Glass shapes close together melt into each other; a plain group before iOS 26.
private struct GlassGroup<Content: View>: View {
    let spacing: CGFloat
    @ViewBuilder let content: () -> Content

    var body: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: spacing, content: content)
        } else {
            content()
        }
    }
}

private extension View {
    @ViewBuilder
    func glass(in shape: some Shape) -> some View {
        if #available(iOS 26, *) {
            glassEffect(.regular.interactive(), in: shape)
        } else {
            background(.regularMaterial, in: shape)
                .shadow(color: .black.opacity(0.12), radius: 12, y: 4)
        }
    }
}

// MARK: - Previews

#Preview("Tab bar") {
    @Previewable @State var selected = ShellTab.stack
    ZStack(alignment: .bottom) {
        LinearGradient(colors: [.orange, .pink, .indigo], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea()
        ShellTabBar(selected: selected, onSelect: { selected = $0 }, onCompose: {})
    }
}

#Preview("Tab bar, dark") {
    @Previewable @State var selected = ShellTab.list
    ZStack(alignment: .bottom) {
        Color.black.ignoresSafeArea()
        ShellTabBar(selected: selected, onSelect: { selected = $0 }, onCompose: {})
    }
    .preferredColorScheme(.dark)
}
