import Foundation

enum Rarity { case common, uncommon, rare
    var label: String { switch self { case .common: return "Common"; case .uncommon: return "Uncommon"; case .rare: return "Rare" } }
}

/// keep = how many to keep. `ItemRule.never` = never discard.
struct ItemRule { var name: String; var keep: Int; var rarity: Rarity
    static let never = Int.max
}

enum BagRules {
    /// Order = discard order: most common / least useful first, rare items are never suggested.
    static let defaults: [ItemRule] = [
        ItemRule(name: "Potion", keep: 0, rarity: .common),
        ItemRule(name: "Super Potion", keep: 0, rarity: .common),
        ItemRule(name: "Nanab Berry", keep: 20, rarity: .common),
        ItemRule(name: "Revive", keep: 30, rarity: .common),
        ItemRule(name: "Poke Ball", keep: 50, rarity: .common),
        ItemRule(name: "Hyper Potion", keep: 20, rarity: .common),
        ItemRule(name: "Razz Berry", keep: 50, rarity: .common),
        ItemRule(name: "Great Ball", keep: 50, rarity: .common),
        ItemRule(name: "Pinap Berry", keep: 30, rarity: .common),
        ItemRule(name: "Max Potion", keep: 50, rarity: .uncommon),
        ItemRule(name: "Max Revive", keep: 50, rarity: .uncommon),
        ItemRule(name: "Ultra Ball", keep: 150, rarity: .uncommon),
        ItemRule(name: "Silver Pinap Berry", keep: 30, rarity: .uncommon),
        ItemRule(name: "Golden Razz Berry", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Rare Candy", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Rare Candy XL", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Master Ball", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Lucky Egg", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Star Piece", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Gift", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Incense", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Daily Adventure Incense", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Lure Module", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Glacial Lure Module", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Mossy Lure Module", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Magnetic Lure Module", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Rainy Lure Module", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Golden Lure Module", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Raid Pass", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Premium Battle Pass", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Remote Raid Pass", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Egg Incubator", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Super Incubator", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Camera", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Poffin", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Sun Stone", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "King's Rock", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Metal Coat", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Dragon Scale", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Up-Grade", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Sinnoh Stone", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Unova Stone", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Fast TM", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Charged TM", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Elite Fast TM", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Elite Charged TM", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Rocket Radar", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Super Rocket Radar", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Mysterious Component", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Magmarizer", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Electirizer", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Reaper Cloth", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Protector", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Dubious Disc", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Deep Sea Tooth", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Deep Sea Scale", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Razor Claw", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Razor Fang", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Sachet", keep: ItemRule.never, rarity: .rare),
        ItemRule(name: "Whipped Dream", keep: ItemRule.never, rarity: .rare),
    ]
}

struct Discard { var item: String; var amount: Int; var rarity: Rarity; var belowKeep = false }
struct Plan { var discards: [Discard]; var freed: Int; var needed: Int }
struct Review { var remove: [Discard]; var keep: [(name: String, count: Int)] }

enum Recommender {
    static func review(_ counts: [String: Int], rules: [ItemRule] = BagRules.defaults) -> Review {
        var remove: [Discard] = []; var keep: [(name: String, count: Int)] = []
        for r in rules {
            guard let have = counts[r.name] else { continue }
            let surplus = r.keep == ItemRule.never ? 0 : have - r.keep
            if surplus > 0 { remove.append(Discard(item: r.name, amount: surplus, rarity: r.rarity)) }
            else { keep.append((r.name, have)) }
        }
        return Review(remove: remove, keep: keep)
    }

    /// What to discard to get `targetFree` empty slots: surplus above each keep limit first, then common items down to half.
    static func plan(_ counts: [String: Int], used: Int, capacity: Int, targetFree: Int,
                     rules: [ItemRule] = BagRules.defaults) -> Plan {
        let needed = max(targetFree - (capacity - used), 0)
        var out: [Discard] = []; var freed = 0
        var left = counts
        for r in rules {
            if freed >= needed { break }
            if r.keep == ItemRule.never { continue }
            guard let have = left[r.name] else { continue }
            let take = min(have - r.keep, needed - freed)
            if take <= 0 { continue }
            out.append(Discard(item: r.name, amount: take, rarity: r.rarity)); freed += take; left[r.name] = have - take
        }
        for r in rules {
            if freed >= needed { break }
            if r.rarity != .common { continue }
            guard let have = left[r.name] else { continue }
            let take = min(have - r.keep / 2, needed - freed)
            if take <= 0 { continue }
            out.append(Discard(item: r.name, amount: take, rarity: r.rarity, belowKeep: true)); freed += take
        }
        return Plan(discards: out, freed: freed, needed: needed)
    }
}

/// What the bag advisor has learned from every screenshot so far. A reading is confirmed when seen on two frames.
final class BagMemory {
    private var stable: [String: [Int: Int]] = [:]
    private var sightings: [String: [Int: Int]] = [:]
    private var latest: [String: Int] = [:]
    private var last: [String: Int] = [:]

    func observe(_ frame: [String: Int]) {
        for (item, n) in frame {
            sightings[item, default: [:]][n, default: 0] += 1
            latest[item] = n
            if last[item] == n { stable[item, default: [:]][n, default: 0] += 1 }
        }
        last = frame
    }

    private func score(_ item: String, _ n: Int) -> Int { 3 * (stable[item]?[n] ?? 0) + (sightings[item]?[n] ?? 0) }

    func counts() -> [String: Int] {
        var out: [String: Int] = [:]
        for (item, v) in sightings {
            // highest score, ties to the newest reading (then the first one, as Kotlin's maxWith does)
            let keys = v.keys.sorted()
            var best = keys[0]
            for k in keys.dropFirst() {
                let a = (score(item, k), k == latest[item] ? 1 : 0), b = (score(item, best), best == latest[item] ? 1 : 0)
                if a > b { best = k }
            }
            out[item] = best
        }
        return out
    }

    private func isConfirmed(_ item: String, _ n: Int) -> Bool { (stable[item]?[n] ?? 0) > 0 || (sightings[item]?[n] ?? 0) >= 2 }
    func confirmed() -> [String: Int] { counts().filter { isConfirmed($0.key, $0.value) } }
    func unconfirmed() -> [String: Int] { counts().filter { !isConfirmed($0.key, $0.value) } }
    var size: Int { sightings.count }
    func clear() { stable = [:]; sightings = [:]; latest = [:]; last = [:] }
}

struct ParsedScreen { var counts: [String: Int]; var used: Int?; var capacity: Int? }

enum ItemParser {
    private static let capacityRx = Rx("(\\d{1,4})\\s*/\\s*(\\d{1,4})")
    private static let hpWordRx = Rx("\\b(?:hp|kp)\\b", ignoreCase: true)
    private static let countRx = Rx("^[xX×]?\\s*(\\d{1,4})$")
    private static let inlineCountRx = Rx("(?:^|\\s)[xX×]\\s?(\\d{1,4})(?=\\s|$)")
    private static let trailingRx = Rx("(\\d{1,4})\\s*$")

    private static let known: [(String, String)] =
        stableSorted(BagRules.defaults.map { (Rules.norm($0.name), $0.name) }) { $0.0.count > $1.0.count }

    private static func matchName(_ text: String) -> String? {
        let n = Rules.norm(text)
        if n.isEmpty { return nil }
        return known.first { n.contains($0.0) && n.count <= $0.0.count + 8 }?.1
    }

    static func parse(_ tokens: [Token], screenH: Int = 0) -> ParsedScreen {
        var used: Int? = nil, cap: Int? = nil
        for t in tokens {
            if hpWordRx.contains(t.text) { continue }
            if let g = capacityRx.find(t.text), let u = Int(g[1] ?? ""), let c = Int(g[2] ?? "") {
                if (100...5000).contains(c) && u <= c + c / 2 && (cap == nil || c > cap!) { used = u; cap = c }
            }
        }
        func inSafeArea(_ r: Box) -> Bool {
            screenH <= 0 || (Double(r.top) > Double(screenH) * 0.08 && Double(r.bottom) < Double(screenH) * 0.94)
        }
        var counts: [String: Int] = [:]
        var names: [(Int, String)] = []   // token index, item name
        for (i, t) in tokens.enumerated() {
            if !inSafeArea(t.box) { continue }
            guard let name = matchName(t.text) else { continue }
            let trimmed = t.text.trimmingCharacters(in: .whitespacesAndNewlines)
            var inline: Int? = inlineCountRx.find(trimmed)?[1].flatMap { Int($0) }
            if inline == nil, Rules.norm(t.text).count > 4 { inline = trailingRx.find(trimmed)?[1].flatMap { Int($0) } }
            if let v = inline { counts[name] = v } else { names.append((i, name)) }
        }
        var pool: [Int] = []
        for (i, t) in tokens.enumerated() where countRx.matches(t.text.trimmingCharacters(in: .whitespacesAndNewlines)) && inSafeArea(t.box) { pool.append(i) }

        let ys = names.map { tokens[$0.0].box.centerY }.sorted()
        let minGap = names.map { tokens[$0.0].box.height }.max() ?? 20
        var gaps: [Int] = []
        if ys.count > 1 { for i in 0..<(ys.count - 1) { let g = ys[i + 1] - ys[i]; if g > minGap { gaps.append(g) } } }
        gaps.sort()
        let pitch = gaps.isEmpty ? max(screenH / 6, 200) : gaps[gaps.count / 2]
        struct Pair3 { var name: String; var label: Int; var num: Int; var d: Double }
        var pairs: [Pair3] = []
        for (li, name) in names {
            let label = tokens[li]
            for ni in pool {
                let n = tokens[ni]
                let dy = abs(n.box.centerY - label.box.centerY)
                if Double(dy) > Double(pitch) * 0.55 { continue }
                let dx = abs(n.box.centerX - label.box.centerX)
                pairs.append(Pair3(name: name, label: li, num: ni, d: hypot(Double(dx), Double(dy) * 2.0)))
            }
        }
        pairs = stableSorted(pairs) { $0.d < $1.d }
        var usedNums = Set<Int>(), usedLabels = Set<Int>()
        for p in pairs {
            if usedNums.contains(p.num) || usedLabels.contains(p.label) || counts[p.name] != nil { continue }
            usedNums.insert(p.num); usedLabels.insert(p.label)
            let txt = tokens[p.num].text.trimmingCharacters(in: .whitespacesAndNewlines)
            counts[p.name] = Int(countRx.find(txt)![1]!)!
        }
        return ParsedScreen(counts: counts, used: used, capacity: cap)
    }
}
