import SwiftUI
import PhotosUI

struct BagView: View {
    @EnvironmentObject var model: AppModel
    @State private var picked: [PhotosPickerItem] = []

    var body: some View {
        let _ = model.bagVersion // refresh when the memory changes
        let confirmed = model.bag.confirmed()
        let unconfirmed = model.bag.unconfirmed()
        Screen(title: "Bag advisor") {
            PhotosPicker(selection: $picked, maxSelectionCount: 30, matching: .screenshots) {
                Label("Choose Items screenshots", systemImage: "photo.stack")
                    .font(.headline).frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Theme.teal)).foregroundStyle(Color.black)
            }
            .onChange(of: picked) { _, items in
                guard !items.isEmpty else { return }
                Task { await model.scanBag(items); picked = [] }
            }
            if model.busy { HStack { ProgressView(); Text("Reading…").foregroundStyle(Theme.text2) } }
            if let n = model.bagNote { Note(text: n) }
            if model.bag.size == 0 && !model.busy && model.bagNote == nil {
                Note(text: "In Pokémon GO tap the Poké Ball → Items and take screenshots while you scroll the whole list. Choose them all here; the more of the list they cover, the better the advice.")
            } else if model.bag.size > 0 {
                summary(confirmed: confirmed, unconfirmed: unconfirmed)
            }
            Note(text: "The advice is a suggestion. Double-check before you discard anything.")
        }
    }

    @ViewBuilder private func summary(confirmed: [String: Int], unconfirmed: [String: Int]) -> some View {
        let types = "\(model.bag.size) item types"
        if let u = model.bagUsed, let c = model.bagCapacity {
            let free = c - u
            VStack(alignment: .leading, spacing: 8) {
                HStack { Text("\(u) / \(c)").font(.title3.bold()); Spacer(); Text(types).foregroundStyle(Theme.text2) }
                ProgressView(value: min(Double(u) / Double(c), 1)).tint(free >= model.targetFree ? Theme.green : (free > 0 ? Theme.amber : Theme.red))
            }.padding(16).background(RoundedRectangle(cornerRadius: 18).fill(Theme.card))
            let plan = Recommender.plan(confirmed, used: u, capacity: c, targetFree: model.targetFree)
            if plan.needed > 0 {
                Text("Free \(plan.needed) slots to reach \(model.targetFree) free.").font(.headline)
                ForEach(plan.discards, id: \.item) { d in
                    HStack { Text(d.item).bold(); Spacer(); Text("−\(d.amount)" + (d.belowKeep ? " · below keep limit" : "")).bold() }
                        .foregroundStyle(Theme.red).pill(Theme.red)
                }
                if plan.freed < plan.needed { Note(text: "Add screenshots of the rest of the list for a better plan (short by \(plan.needed - plan.freed)).") }
            } else {
                Text("\(free) free slots – nothing to discard.").font(.headline)
            }
            HStack {
                Button("Free 10 more") { model.targetFree += 10 }.buttonStyle(.bordered)
                Button("Reset", role: .destructive) { model.resetBag() }.buttonStyle(.bordered)
            }
        } else {
            Text("Bag size not read – showing what is over your limit.").font(.headline)
            Text(types).foregroundStyle(Theme.text2)
        }
        let review = Recommender.review(confirmed)
        if !review.remove.isEmpty {
            Text("Over your keep limit").font(.headline).padding(.top, 6)
            ForEach(review.remove, id: \.item) { d in
                HStack { Text(d.item).bold(); Spacer(); Text("−\(d.amount)").bold() }.foregroundStyle(Theme.red).pill(Theme.red)
            }
        }
        if !review.keep.isEmpty {
            Text("Keep").font(.headline).padding(.top, 6)
            ForEach(review.keep, id: \.name) { k in
                HStack { Text(k.name).bold(); Spacer(); Text("×\(k.count)").bold() }.foregroundStyle(Theme.green).pill(Theme.green)
            }
        }
        if !unconfirmed.isEmpty {
            Note(text: "Seen once (needs a second screenshot before I advise on it): " + unconfirmed.keys.sorted().joined(separator: ", "))
        }
        if model.bagUsed == nil { Button("Reset", role: .destructive) { model.resetBag() }.buttonStyle(.bordered) }
    }
}
