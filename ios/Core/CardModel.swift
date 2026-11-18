import Foundation

enum Tone { case great, decent, weak, neutral }
enum Mark { case good, maybe, bad, none }

struct LeagueRow { var name: String; var value: String; var mark: Mark }
struct StatBar { var min: Int; var max: Int
    var text: String { min == max ? "\(min)" : "\(min)–\(max)" }
}

/// Everything one IV result card shows, as plain data.
struct CardModel {
    var title: String
    var badge: String?
    var tone: Tone
    var pct: String
    var pctSub: String
    var ring: Double
    var stats: [StatBar]
    var chips: [String]
    var leagues: [LeagueRow]
    var pick: String
    var pickMark: Mark
    var status: String
    var others: [String]
}

enum CardModels {
    static func rating(_ total: Int) -> String {
        if total == 45 { return "Perfect ★★★" }
        if total >= 37 { return "Excellent ★★★" }
        if total >= 30 { return "Good ★★" }
        if total >= 23 { return "Decent ★" }
        return "Weak"
    }
    static func pct0(_ x: Double) -> String { String(format: "%.0f", x) }
    static func lv(_ x: Double) -> String { x.truncatingRemainder(dividingBy: 1.0) == 0 ? String(format: "%.0f", x) : String(format: "%.1f", x) }

    static func tone(_ r: IvResult) -> Tone {
        let bestRank = [r.great?.best, r.ultra?.best].compactMap { $0 }.min() ?? Int.max
        if bestRank <= 100 || r.pctMin >= 91 { return .great }
        if bestRank <= 300 || r.pctMin >= 80 { return .decent }
        return .weak
    }

    static func leagues(_ r: IvResult, cp: Int) -> [LeagueRow] {
        func row(_ name: String, _ cap: Int, _ rk: RankRange?) -> LeagueRow {
            guard let rk = rk else { return LeagueRow(name: name, value: "—", mark: .none) }
            if cp > cap { return LeagueRow(name: name, value: "CP too high", mark: .none) }
            let rank = rk.best == rk.worst ? "#\(rk.best)" : "#\(rk.best)–\(rk.worst)"
            if rk.worst <= 100 { return LeagueRow(name: name, value: "\(rank) ✓", mark: .good) }
            if rk.best <= 100 { return LeagueRow(name: name, value: "\(rank) ?", mark: .maybe) }
            return LeagueRow(name: name, value: "\(rank) ✗", mark: .bad)
        }
        let master = (cp > 2500 && r.candidates.allSatisfy { $0.total >= 42 })
            ? LeagueRow(name: "Master", value: "Good IVs", mark: .good) : LeagueRow(name: "Master", value: "—", mark: .none)
        return [row("Little", 500, r.little), row("Great", 1500, r.great), row("Ultra", 2500, r.ultra), master]
    }

    static func leaguePick(_ l: [LeagueRow]) -> (String, Mark) {
        func names(_ m: Mark) -> String { l.filter { $0.mark == m }.map { $0.name }.joined(separator: " · ") }
        if l.contains(where: { $0.mark == .good }) { return ("Good for \(names(.good))", .good) }
        if l.contains(where: { $0.mark == .maybe }) { return ("Maybe \(names(.maybe))", .maybe) }
        if l.contains(where: { $0.mark == .bad }) { return ("No league fit", .bad) }
        return ("No league fit", .none)
    }

    static func from(_ r: IvResult, cp: Int, hp: Int, appraised: Iv?) -> CardModel {
        let e = r.exact; let cands = r.candidates
        let badge: String? = r.guessed ? "best guess" : (r.nickname ? "nickname" : nil)
        let pct: String
        if let e = e { pct = pct0(e.pct) + "%" } else {
            let a = pct0(r.pctMin), b = pct0(r.pctMax)
            pct = (a == b ? a : "\(a)–\(b)") + "%"
        }
        var status: String
        if let ap = appraised, let e = e { status = "\(rating(e.total)) · exact"; _ = ap }
        else if let ap = appraised { status = "Appraisal \(ap.a)/\(ap.d)/\(ap.s) didn't match CP" }
        else { status = "Add the appraisal screenshot for exact IVs" }
        let lg = leagues(r, cp: cp)
        let (pick, mark) = leaguePick(lg)
        return CardModel(
            title: r.species.label, badge: badge, tone: tone(r), pct: pct,
            pctSub: e != nil ? "\(e!.total)/45" : "\(cands.count) possible",
            ring: (e?.pct ?? ((r.pctMin + r.pctMax) / 2)) / 100.0,
            stats: [StatBar(min: cands.map { $0.a }.min()!, max: cands.map { $0.a }.max()!),
                    StatBar(min: cands.map { $0.d }.min()!, max: cands.map { $0.d }.max()!),
                    StatBar(min: cands.map { $0.s }.min()!, max: cands.map { $0.s }.max()!)],
            chips: [r.levelMin == r.levelMax ? "Lv \(lv(r.levelMin))" : "Lv \(lv(r.levelMin))–\(lv(r.levelMax))", "CP \(cp)", "HP \(hp)"],
            leagues: lg, pick: pick, pickMark: mark, status: status,
            others: Array(distinct(r.others.map { $0.label }).prefix(2)))
    }
}
