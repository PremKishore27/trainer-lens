import Foundation
import SwiftUI
import PhotosUI

/// One Pokémon found in the screenshots.
struct MonCard: Identifiable {
    let id = UUID()
    var card: CardModel?
    var message: String?
    var title: String
}

@MainActor
final class AppModel: ObservableObject {
    @Published var db: SpeciesDb.Data?
    @Published var dbNote = "Loading Pokémon data…"
    @Published var cards: [MonCard] = []
    @Published var busy = false
    @Published var ivNote: String?

    let bag = BagMemory()
    @Published var bagVersion = 0
    @Published var bagUsed: Int?
    @Published var bagCapacity: Int?
    @Published var targetFree = 30
    @Published var bagNote: String?

    private static let files = ["pokemon_stats.json", "pokemon_types.json", "pokemon_evolutions.json", "pokemon_height_weight_scale.json"]

    init() { Task { await loadData() } }

    // MARK: data

    private var cacheDir: URL {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("pogoapi", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// Bundled copy first (works offline), then the cached or freshly downloaded copy (new Pokémon appear without an update).
    func loadData() async {
        func build(_ get: (String) -> Data?) -> SpeciesDb.Data? {
            guard let s = get(Self.files[0]), let t = get(Self.files[1]), let e = get(Self.files[2]) else { return nil }
            return try? SpeciesDb.build(stats: s, types: t, evolutions: e, sizes: get(Self.files[3]))
        }
        let bundled = build { n in
            Bundle.main.url(forResource: (n as NSString).deletingPathExtension, withExtension: "json").flatMap { try? Data(contentsOf: $0) }
        }
        let cached = build { [cacheDir] n in try? Data(contentsOf: cacheDir.appendingPathComponent(n)) }
        db = cached ?? bundled
        dbNote = db == nil ? "Pokémon data missing" : (cached != nil ? "Pokémon data: downloaded copy" : "Pokémon data: built-in copy")
        // refresh in the background
        var fresh: [String: Data] = [:]
        for n in Self.files {
            guard let url = URL(string: SpeciesDb.base + n) else { continue }
            var req = URLRequest(url: url); req.timeoutInterval = 20
            if let (data, resp) = try? await URLSession.shared.data(for: req), (resp as? HTTPURLResponse)?.statusCode == 200 { fresh[n] = data }
        }
        if fresh.count == Self.files.count, let built = build({ fresh[$0] }) {
            for (n, d) in fresh { try? d.write(to: cacheDir.appendingPathComponent(n)) }
            db = built
            dbNote = "Pokémon data: up to date"
        }
    }

    // MARK: pictures

    private func images(from items: [PhotosPickerItem]) async -> [UIImage] {
        var out: [UIImage] = []
        for it in items {
            if let d = try? await it.loadTransferable(type: Data.self), let img = UIImage(data: d) { out.append(img) }
        }
        return out
    }

    // MARK: IV

    func checkIV(_ items: [PhotosPickerItem]) async {
        guard let db = db else { ivNote = "Pokémon data isn't loaded yet."; return }
        busy = true; defer { busy = false }
        ivNote = nil
        let imgs = await images(from: items)
        if imgs.isEmpty { ivNote = "Couldn't open those pictures."; return }
        let reads: [ScreenRead] = await Task.detached { imgs.compactMap { try? OCR.read($0) } }.value
        cards = IvSession.analyze(reads, db)
        if cards.isEmpty { ivNote = "No Pokémon page found. Use a screenshot of a Pokémon's page (CP at the top, HP line in the middle)." }
    }

    // MARK: bag

    func scanBag(_ items: [PhotosPickerItem]) async {
        busy = true; defer { busy = false }
        bagNote = nil
        let imgs = await images(from: items)
        if imgs.isEmpty { bagNote = "Couldn't open those pictures."; return }
        let reads: [ScreenRead] = await Task.detached { imgs.compactMap { try? OCR.read($0) } }.value
        var seen = 0
        for r in reads {
            let p = ItemParser.parse(r.tokens, screenH: r.height)
            if p.counts.isEmpty && p.capacity == nil { bag.observe([:]); continue }
            seen += 1
            if let u = p.used { bagUsed = u; bagCapacity = p.capacity }
            bag.observe(p.counts)
        }
        if seen == 0 { bagNote = "No item list found. Use screenshots of the Items screen (Poké Ball → Items)." }
        bagVersion += 1
    }

    func resetBag() { bag.clear(); bagUsed = nil; bagCapacity = nil; targetFree = 30; bagNote = nil; bagVersion += 1 }
}

/// Turns a set of screenshots into one card per Pokémon: a summary page (CP + HP) starts a Pokémon, an appraisal page
/// (the three bars, the "caught on" line) is added to the Pokémon before it.
enum IvSession {
    struct Mon {
        var cp: Int, hp: Int
        var dust: [Int] = []
        var clues = Clues()
        var appraised: Iv?
    }

    static func analyze(_ reads: [ScreenRead], _ db: SpeciesDb.Data) -> [MonCard] {
        var mons: [Mon] = []
        for r in reads {
            let clues = SpeciesDb.readClues(r.tokens, screenH: r.height, db)
            let bars = AppraisalReader.read(r.tokens, r.pixels)
            if let s = SummaryParser.parse(r.tokens, screenH: r.height) {
                if let i = mons.firstIndex(where: { $0.cp == s.cp && $0.hp == s.hp }) {
                    merge(&mons[i], clues, s.dust, bars)
                } else {
                    var m = Mon(cp: s.cp, hp: s.hp); merge(&m, clues, s.dust, bars); mons.append(m)
                }
            } else if !mons.isEmpty, bars != nil || !clues.caught.isEmpty {
                merge(&mons[mons.count - 1], clues, nil, bars)   // appraisal page: the CP is hidden by the dialog
            }
        }
        return mons.map { m in
            if let r = Identify.analyze(clues: m.clues, cp: m.cp, hp: m.hp, dust: m.dust.first, appraisal: m.appraised, db) {
                let c = CardModels.from(r, cp: m.cp, hp: m.hp, appraised: m.appraised)
                return MonCard(card: c, message: nil, title: c.title)
            }
            return MonCard(card: nil, message: "Can't tell which Pokémon this is (CP \(m.cp), HP \(m.hp)). Add the appraisal screenshot (☰ → Appraise) that shows “This … was caught on …”.", title: "CP \(m.cp)")
        }
    }

    private static func merge(_ m: inout Mon, _ c: Clues, _ dust: Int?, _ bars: Iv?) {
        for n in c.names where !m.clues.names.contains(n) { m.clues.names.append(n) }
        if m.clues.family == nil { m.clues.family = c.family }
        if m.clues.types.isEmpty { m.clues.types = c.types }
        if m.clues.heightM == nil { m.clues.heightM = c.heightM }
        if m.clues.weightKg == nil { m.clues.weightKg = c.weightKg }
        if m.clues.evolveCost == nil { m.clues.evolveCost = c.evolveCost }
        if c.evolveButton { m.clues.evolveButton = true }
        if !c.caught.isEmpty { m.clues.caught = c.caught }
        if let d = dust { m.dust.append(d) }
        if let b = bars { m.appraised = b }
    }
}
