import Foundation

/// Level / CP-multiplier / stardust tables. Same numbers as the Android app (pogoapi.net game master).
enum Cpm {
    private static let table: [Double] = [
        0.094, 0.13513743, 0.16639787, 0.19265091, 0.21573247, 0.23657265,
        0.25572005, 0.27353038, 0.29024988, 0.30605738, 0.3210876, 0.33544503,
        0.34921268, 0.36245774, 0.37523559, 0.38759241, 0.39956728, 0.41119355,
        0.42250001, 0.43292641, 0.44310755, 0.45305995, 0.46279839, 0.47233608,
        0.48168495, 0.49085581, 0.49985844, 0.50870176, 0.51739395, 0.52594251,
        0.53435433, 0.54263576, 0.55079269, 0.5588306, 0.56675452, 0.57456915,
        0.58227891, 0.58988791, 0.59740001, 0.60482366, 0.61215729, 0.61940411,
        0.62656713, 0.63364918, 0.64065295, 0.64758097, 0.65443563, 0.66121927,
        0.667934, 0.6745819, 0.68116492, 0.68768491, 0.69414365, 0.7005429,
        0.70688421, 0.7131691, 0.71939909, 0.72557561, 0.7317, 0.73474102,
        0.73776948, 0.74078558, 0.74378943, 0.74678122, 0.74976104, 0.7527291,
        0.75568551, 0.75863037, 0.76156384, 0.76448607, 0.76739717, 0.77029727,
        0.7731865, 0.77606495, 0.77893275, 0.78179006, 0.78463697, 0.78747358,
        0.79030001, 0.79280001, 0.79530001, 0.79780001, 0.8003, 0.8028,
        0.8053, 0.8078, 0.81029999, 0.81279999, 0.81529999, 0.81779999,
        0.82029999, 0.82279999, 0.82529999, 0.82779999, 0.83029999, 0.83279999,
        0.83529999, 0.83779999, 0.84029999, 0.84279999, 0.84529999,
    ]
    static let maxLevel = 51.0

    private static func rounded(_ x: Double) -> Int { Int(x.rounded(.toNearestOrAwayFromZero)) } // Kotlin roundToInt

    static func at(_ level: Double) -> Double {
        table[min(max(rounded((level - 1.0) * 2), 0), table.count - 1)]
    }

    static func levels(_ maxLevel: Double = Cpm.maxLevel) -> [Double] {
        var out: [Double] = []; var l = 1.0
        while l <= maxLevel { out.append(l); l += 0.5 }
        return out
    }

    /// Stardust for one power-up starting at `level` (normal Pokémon). nil at level 50+.
    static func baseDust(_ level: Double) -> Int? {
        if level < 1.0 || level >= 50.0 { return nil }
        if level < 41.0 {
            let t = [200, 400, 600, 800, 1000, 1300, 1600, 1900, 2200, 2500,
                     3000, 3500, 4000, 4500, 5000, 6000, 7000, 8000, 9000, 10000]
            return t[Int((level - 1) / 2)]
        }
        if level < 43.0 { return 11000 }
        if level < 45.0 { return 12000 }
        if level < 47.0 { return 13000 }
        if level < 49.0 { return 14000 }
        return 15000
    }

    /// normal, shadow (+20%), purified (-10%), lucky (-50%)
    static let dustMultipliers: [Double] = [1.0, 1.2, 0.9, 0.5]

    private static let dustToLevels: [Int: [Double]] = {
        var m: [Int: [Double]] = [:]
        for lv in levels(49.5) {
            guard let base = baseDust(lv) else { continue }
            for mult in dustMultipliers { m[rounded(Double(base) * mult), default: []].append(lv) }
        }
        return m.mapValues { Array(Set($0)).sorted() }
    }()

    static let validDust: Set<Int> = Set(dustToLevels.keys)

    static func levelsForDust(_ dust: Int?, all: [Double]) -> [Double] {
        guard let d = dust, let l = dustToLevels[d] else { return all }
        let f = l.filter { all.contains($0) }
        return f.isEmpty ? all : f
    }

    static func cp(_ base: Species, _ iv: Iv, _ level: Double) -> Int {
        let c = at(level)
        let raw = Double(base.atk + iv.a) * Double(base.def + iv.d).squareRoot() * Double(base.hp + iv.s).squareRoot() * c * c / 10
        return max(10, Int(raw.rounded(.down)))
    }

    static func hp(_ base: Species, _ iv: Iv, _ level: Double) -> Int {
        max(10, Int((Double(base.hp + iv.s) * at(level)).rounded(.down)))
    }
}
