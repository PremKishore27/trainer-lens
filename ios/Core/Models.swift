import Foundation

/// A rectangle on screen (pixels, origin top-left).
struct Box: Hashable {
    var left: Int, top: Int, right: Int, bottom: Int
    var centerX: Int { (left + right) / 2 }
    var centerY: Int { (top + bottom) / 2 }
    var width: Int { right - left }
    var height: Int { bottom - top }
}

/// One OCR text element with its position on screen.
struct Token {
    var text: String
    var box: Box = Box(left: 0, top: 0, right: 0, bottom: 0)
}

/// One Pokémon form with its own base stats. `form` is "Normal" for the default form.
struct Species: Hashable {
    var id: String, name: String, atk: Int, def: Int, hp: Int
    var form: String = "Normal"
    var dex: Int = 0
    var types: Set<String> = []

    var isNormal: Bool { form.lowercased() == "normal" }
    var label: String { isNormal ? name : "\(name) (\(Species.formLabel(form)))" }

    private static func formLabel(_ f: String) -> String {
        switch f.lowercased() {
        case "alola": return "Alolan"
        case "galarian": return "Galarian"
        case "hisuian": return "Hisuian"
        case "paldea": return "Paldean"
        default:
            let s = f.replacingOccurrences(of: "_", with: " ").lowercased()
            return s.prefix(1).uppercased() + s.dropFirst()
        }
    }
}

struct Iv: Hashable {
    var a: Int, d: Int, s: Int
    var total: Int { a + d + s }
    var pct: Double { Double(total) * 100.0 / 45.0 }
}

/// Everything one screenshot of a Pokémon page says about which Pokémon it is.
struct Clues {
    var names: [Species] = []
    var family: [Species]? = nil
    var types: Set<String> = []
    var heightM: Double? = nil
    var weightKg: Double? = nil
    var evolveCost: Int? = nil
    var evolveButton = false
    var caught: [Species] = []
}

struct SpeciesGuess {
    var candidates: [Species]
    var nickname: Bool
}

struct Fit { var iv: Iv; var level: Double }

struct RankRange { var best: Int; var worst: Int; var pctOfBest: Double }

struct IvResult {
    var species: Species
    var fits: [Fit]
    var little: RankRange?
    var great: RankRange?
    var ultra: RankRange?
    var others: [Species]
    var arcUsed = false
    var guessed = false
    var nickname = false

    var candidates: [Iv] {
        var seen = Set<Iv>(); var out: [Iv] = []
        for f in fits where seen.insert(f.iv).inserted { out.append(f.iv) }
        return out
    }
    var pctMin: Double { candidates.map { $0.pct }.min() ?? 0 }
    var pctMax: Double { candidates.map { $0.pct }.max() ?? 0 }
    var exact: Iv? { let c = candidates; return c.count == 1 ? c[0] : nil }
    var levelMin: Double { fits.map { $0.level }.min() ?? 0 }
    var levelMax: Double { fits.map { $0.level }.max() ?? 0 }
}

// MARK: - small helpers shared by the whole core

/// Stable sort (Swift's `sorted` makes no promise about equal elements; the Kotlin original relies on it).
func stableSorted<T>(_ a: [T], by less: (T, T) -> Bool) -> [T] {
    a.enumerated().sorted { l, r in
        if less(l.element, r.element) { return true }
        if less(r.element, l.element) { return false }
        return l.offset < r.offset
    }.map { $0.element }
}

func distinct<T: Hashable>(_ a: [T]) -> [T] {
    var seen = Set<T>(); return a.filter { seen.insert($0).inserted }
}

/// Regex helpers on top of NSRegularExpression (same dialect as Java's, which the original used).
struct Rx {
    let re: NSRegularExpression
    init(_ pattern: String, ignoreCase: Bool = false) {
        re = try! NSRegularExpression(pattern: pattern, options: ignoreCase ? [.caseInsensitive] : [])
    }
    func find(_ s: String) -> [String?]? {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i); return r.location == NSNotFound ? nil : ns.substring(with: r)
        }
    }
    func contains(_ s: String) -> Bool { find(s) != nil }
    /// The whole string must match.
    func matches(_ s: String) -> Bool {
        let ns = s as NSString
        guard let m = re.firstMatch(in: s, options: [.anchored], range: NSRange(location: 0, length: ns.length)) else { return false }
        return m.range.length == ns.length
    }
    func firstMatchRange(_ s: String) -> NSRange? {
        re.firstMatch(in: s, range: NSRange(location: 0, length: (s as NSString).length))?.range
    }
    func all(_ s: String) -> [[String?]] {
        let ns = s as NSString
        return re.matches(in: s, range: NSRange(location: 0, length: ns.length)).map { m in
            (0..<m.numberOfRanges).map { i in
                let r = m.range(at: i); return r.location == NSNotFound ? nil : ns.substring(with: r)
            }
        }
    }
}

enum Rules {
    /// lower-case letters and digits only, accents removed.
    static func norm(_ s: String) -> String {
        var out = ""
        for u in s.decomposedStringWithCanonicalMapping.unicodeScalars {
            switch u.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark: continue
            default: break
            }
            for l in String(u).lowercased().unicodeScalars {
                if (l.value >= 97 && l.value <= 122) || (l.value >= 48 && l.value <= 57) { out.unicodeScalars.append(l) }
            }
        }
        return out
    }
}
