import Foundation

/// PvP IV rank among all 4096 spreads of a species under a CP cap.
enum Pvp {
    struct Rank { var rank: Int; var pctOfBest: Double; var level: Double }

    private static var cache: [String: [Iv: Rank]?] = [:]
    private static let lock = NSLock()

    static func clearCache() { lock.lock(); cache.removeAll(); lock.unlock() }

    static func table(_ sp: Species, capCp: Int, maxLevel: Double = 50.0) -> [Iv: Rank]? {
        let key = "\(sp.id)-\(sp.form)-\(capCp)-\(maxLevel)"
        lock.lock(); defer { lock.unlock() }
        if let hit = cache[key] { return hit }
        let built = build(sp, capCp, maxLevel)
        cache[key] = .some(built)
        return built
    }

    private static func build(_ sp: Species, _ capCp: Int, _ maxLevel: Double) -> [Iv: Rank]? {
        let levels = Cpm.levels(maxLevel)
        struct Row { var iv: Iv; var level: Double; var product: Double }
        var rows: [Row] = []; rows.reserveCapacity(4096)
        for a in 0...15 { for d in 0...15 { for s in 0...15 {
            let iv = Iv(a: a, d: d, s: s)
            if Cpm.cp(sp, iv, levels[0]) > capCp { return nil } // species can't fit the league
            var lo = 0, hi = levels.count - 1
            while lo < hi {
                let mid = (lo + hi + 1) / 2
                if Cpm.cp(sp, iv, levels[mid]) <= capCp { lo = mid } else { hi = mid - 1 }
            }
            let lv = levels[lo]; let c = Cpm.at(lv)
            rows.append(Row(iv: iv, level: lv,
                            product: Double(sp.atk + a) * c * Double(sp.def + d) * c * (Double(sp.hp + s) * c).rounded(.down)))
        } } }
        rows = stableSorted(rows) { $0.product > $1.product }
        let best = rows[0].product
        var out: [Iv: Rank] = [:]; out.reserveCapacity(4096)
        for (i, r) in rows.enumerated() { out[r.iv] = Rank(rank: i + 1, pctOfBest: r.product / best * 100.0, level: r.level) }
        return out
    }
}
