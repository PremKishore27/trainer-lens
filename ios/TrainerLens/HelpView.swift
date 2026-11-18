import SwiftUI

struct HelpView: View {
    @EnvironmentObject var model: AppModel
    var body: some View {
        Screen(title: "How to use") {
            step("1", "Take screenshots in Pokémon GO", "Open a Pokémon from your list and take a normal screenshot (side button + volume up). For exact IVs open ☰ → Appraise, tap until the three bars show, and take another.")
            step("2", "Choose them in the IV tab", "Pick the Pokémon screenshot and the appraisal screenshot together. You get the IV %, the Attack / Defense / HP values, the level and which league it is good for.")
            step("3", "Bag: scroll and screenshot", "Poké Ball → Items. Take screenshots while you scroll the whole list, then choose them all in the Bag tab. Items to throw away show in red, items to keep in green.")
            Text("Good to know").font(.headline).padding(.top, 6)
            Note(text: "• iPhones don't allow a floating window over another app, so this version works on screenshots instead of live.\n• Everything is read on your iPhone. No pictures are saved or uploaded; the app only downloads the public Pokémon stats list (pogoapi.net).\n• It never touches the game.\n• Trainer Lens isn't affiliated with Niantic, Nintendo or The Pokémon Company.")
            Note(text: model.dbNote)
            Link("Support on Ko-fi", destination: URL(string: "https://ko-fi.com/premboii")!).font(.headline).foregroundStyle(Theme.teal)
            Link("Project page on GitHub", destination: URL(string: "https://github.com/PremKishore27/trainer-lens")!).font(.headline).foregroundStyle(Theme.teal)
        }
    }

    private func step(_ n: String, _ title: String, _ body: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Text(n).font(.title3.bold()).foregroundStyle(Color.black).frame(width: 34, height: 34).background(Circle().fill(Theme.teal))
            VStack(alignment: .leading, spacing: 4) { Text(title).font(.headline); Text(body).font(.callout).foregroundStyle(Theme.text2) }
        }
        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(Theme.card))
    }
}
