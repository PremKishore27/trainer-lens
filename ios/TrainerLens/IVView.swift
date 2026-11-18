import SwiftUI
import PhotosUI

struct IVView: View {
    @EnvironmentObject var model: AppModel
    @State private var picked: [PhotosPickerItem] = []

    var body: some View {
        Screen(title: "IV checker") {
            PhotosPicker(selection: $picked, maxSelectionCount: 12, matching: .screenshots) {
                Label("Choose Pokémon screenshots", systemImage: "photo.on.rectangle.angled")
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Theme.teal)).foregroundStyle(Color.black)
            }
            .onChange(of: picked) { _, items in
                guard !items.isEmpty else { return }
                Task { await model.checkIV(items); picked = [] }
            }
            if model.busy { HStack { ProgressView(); Text("Reading…").foregroundStyle(Theme.text2) } }
            if let n = model.ivNote { Note(text: n) }
            if model.cards.isEmpty && !model.busy && model.ivNote == nil {
                Note(text: "Take a screenshot of a Pokémon's page in Pokémon GO, then choose it here. For exact IVs also add the screenshot of the appraisal (☰ → Appraise) with the three bars showing. Pick both together.")
            }
            ForEach(model.cards) { c in
                if let card = c.card { IVCardView(card: card) }
                else { Text(c.message ?? "").font(.callout).padding(16).frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 18).fill(Theme.card)) }
            }
            Note(text: model.dbNote + ". Read on your iPhone; nothing is uploaded.")
        }
    }
}

struct Ring: View {
    let value: Double, color: Color, big: String, small: String
    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.12), lineWidth: 12)
            Circle().trim(from: 0, to: max(0.02, min(value, 1))).stroke(color, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text(big).font(.system(size: big.count > 4 ? 22 : 30, weight: .bold, design: .rounded)).minimumScaleFactor(0.6)
                Text(small).font(.caption).foregroundStyle(Theme.text2)
            }
        }
        .frame(width: 128, height: 128)
    }
}

struct IVCardView: View {
    let card: CardModel
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(card.title).font(.title3.bold())
                if let b = card.badge { Text(b).font(.caption).foregroundStyle(Theme.text2) }
                Spacer()
            }
            HStack(spacing: 18) {
                Ring(value: card.ring, color: Theme.tone(card.tone), big: card.pct, small: card.pctSub)
                VStack(spacing: 10) {
                    ForEach(Array(zip(["ATK", "DEF", "HP"], card.stats).enumerated()), id: \.offset) { _, p in
                        StatRow(label: p.0, bar: p.1)
                    }
                }
            }
            Text(card.pick).font(.headline).pill(Theme.mark(card.pickMark))
            HStack { ForEach(card.chips, id: \.self) { Text($0).font(.subheadline.weight(.semibold)).pill(Color.white.opacity(0.5)) } }
            VStack(spacing: 6) {
                ForEach(card.leagues, id: \.name) { l in
                    HStack { Text(l.name).foregroundStyle(Theme.text2); Spacer()
                        Text(l.value).bold().foregroundStyle(Theme.mark(l.mark)) }
                }
            }
            Text(card.status).font(.footnote).foregroundStyle(Theme.text2)
            if !card.others.isEmpty { Text("Could also be: " + card.others.joined(separator: ", ")).font(.footnote).foregroundStyle(Theme.text2) }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 22).fill(Theme.card))
    }
}

struct StatRow: View {
    let label: String, bar: StatBar
    var body: some View {
        HStack(spacing: 8) {
            Text(label).font(.caption.bold()).foregroundStyle(Theme.text2).frame(width: 34, alignment: .leading)
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.white.opacity(0.12))
                    Capsule().fill(Theme.teal).frame(width: g.size.width * CGFloat(bar.max) / 15)
                    if bar.min != bar.max { Capsule().fill(Color.white.opacity(0.9)).frame(width: g.size.width * CGFloat(bar.min) / 15) }
                }
            }.frame(height: 8)
            Text(bar.text).font(.callout.bold()).frame(width: 52, alignment: .trailing)
        }
    }
}
