import SwiftUI

enum Theme {
    static let bg = Color(red: 0.07, green: 0.10, blue: 0.17)
    static let card = Color(red: 0.12, green: 0.16, blue: 0.28)
    static let teal = Color(red: 0.18, green: 0.83, blue: 0.75)
    static let green = Color(red: 0.29, green: 0.87, blue: 0.50)
    static let amber = Color(red: 0.98, green: 0.75, blue: 0.14)
    static let red = Color(red: 0.97, green: 0.44, blue: 0.44)
    static let text2 = Color.white.opacity(0.65)

    static func tone(_ t: Tone) -> Color {
        switch t { case .great: return green; case .decent: return amber; case .weak: return red; case .neutral: return teal }
    }
    static func mark(_ m: Mark) -> Color {
        switch m { case .good: return green; case .maybe: return amber; case .bad: return red; case .none: return text2 }
    }
}

struct Screen<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    var body: some View {
        NavigationStack {
            ZStack {
                Theme.bg.ignoresSafeArea()
                ScrollView { VStack(alignment: .leading, spacing: 14) { content }.padding(16) }
            }
            .navigationTitle(title)
            .toolbarColorScheme(.dark, for: .navigationBar)
        }
    }
}

struct Pill: ViewModifier {
    var color: Color
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 14).padding(.vertical, 9)
            .background(Capsule().fill(color.opacity(0.28)))
            .overlay(Capsule().stroke(color.opacity(0.75), lineWidth: 1))
    }
}
extension View { func pill(_ c: Color) -> some View { modifier(Pill(color: c)) } }

struct Note: View {
    let text: String
    var body: some View {
        Text(text).font(.footnote).foregroundStyle(Theme.text2).frame(maxWidth: .infinity, alignment: .leading)
    }
}
