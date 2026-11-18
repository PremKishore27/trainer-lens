import Foundation

enum IvSolver {
    /// All IV spreads (0-15 each) that reproduce the CP and HP shown on screen at one of the given levels.
    static func solve(_ sp: Species, cp: Int, hp: Int, levels: [Double]) -> [Fit] {
        var out: [Fit] = []
        for lv in levels {
            for s in 0...15 {
                if Cpm.hp(sp, Iv(a: 0, d: 0, s: s), lv) != hp { continue }
                for a in 0...15 { for d in 0...15 {
                    let iv = Iv(a: a, d: d, s: s)
                    if Cpm.cp(sp, iv, lv) == cp { out.append(Fit(iv: iv, level: lv)) }
                } }
            }
        }
        return out
    }
}

enum IvAnalyzer {
    static func analyze(candidates: [Species], cp: Int, hp: Int, dust: Int?,
                        appraisal: Iv? = nil, arcLevel: Double? = nil) -> IvResult? {
        let all = Cpm.levels()
        func solveAll(_ levels: [Double]) -> [(Species, [Fit])] {
            candidates.compactMap { sp in
                let f = IvSolver.solve(sp, cp: cp, hp: hp, levels: levels)
                return f.isEmpty ? nil : (sp, f)
            }
        }
        var matched = solveAll(Cpm.levelsForDust(dust, all: all))
        if matched.isEmpty && dust != nil { matched = solveAll(all) } // a misread stardust must not hide the answer
        if matched.isEmpty { return nil }

        var arcUsed = false
        if let arc = arcLevel {
            let near: [(Species, [Fit])] = matched.compactMap { sp, fits in
                let f = fits.filter { abs($0.level - arc) <= 0.75 }
                return f.isEmpty ? nil : (sp, f)
            }
            if !near.isEmpty { matched = near; arcUsed = true }
        }

        var pick = matched[0]
        if let ap = appraisal {
            func dist(_ iv: Iv) -> Int { abs(iv.a - ap.a) + abs(iv.d - ap.d) + abs(iv.s - ap.s) }
            var best: (Species, [Fit], Int)? = nil
            for (sp, fits) in matched {
                let d = fits.map { dist($0.iv) }.min()!
                if d <= 2, best == nil || d < best!.2 { best = (sp, fits.filter { dist($0.iv) == d }, d) } // ties keep order
            }
            if let b = best { pick = (b.0, b.1) }
        }
        let (sp, fits) = pick
        let ivs = distinct(fits.map { $0.iv })
        return IvResult(
            species: sp, fits: fits,
            little: range(sp, 500, ivs), great: range(sp, 1500, ivs), ultra: range(sp, 2500, ivs),
            others: matched.map { $0.0 }.filter { $0 != sp }, arcUsed: arcUsed)
    }

    private static func range(_ sp: Species, _ cap: Int, _ cands: [Iv]) -> RankRange? {
        guard let t = Pvp.table(sp, capCp: cap) else { return nil }
        let ranks = cands.compactMap { t[$0] }
        if ranks.isEmpty { return nil }
        return RankRange(best: ranks.map { $0.rank }.min()!, worst: ranks.map { $0.rank }.max()!,
                         pctOfBest: ranks.map { $0.pctOfBest }.max()!)
    }
}
