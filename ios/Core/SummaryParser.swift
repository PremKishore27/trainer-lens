import Foundation

struct SummaryRead { var cp: Int; var hp: Int; var dust: Int? }

/// Reads CP, max HP and power-up stardust from the Pokémon summary screen (English or German labels).
enum SummaryParser {
    // OCR often confuses digits with look-alike letters.
    private static let D = "[0-9OoDQlI|!SsBZzg]"
    private static let cpRx = Rx("(?:CP|WP|cp|C\\s?P)\\s*(\(D){2,5})\\b")
    private static let cpShortRx = Rx("^[cC©(]\\s?(\(D){3,5})$")
    private static let hpRx1 = Rx("(\(D){1,4})\\s*/\\s*(\(D){1,4})\\s*(?:HP|KP)", ignoreCase: true)
    private static let hpRx2 = Rx("(?:HP|KP)\\s*(\(D){1,4})\\s*/\\s*(\(D){1,4})", ignoreCase: true)
    private static let powerUpRx = Rx("power[\\s-]*up", ignoreCase: true)
    private static let pageWordRx = Rx("^(?:weight|height|dust|stardust|gewicht|größe|sternenstaub)$", ignoreCase: true)

    static func digits(_ s: String) -> Int? {
        let mapped = String(s.map { (c: Character) -> Character in
            switch c {
            case "O", "o", "D", "Q": return "0"
            case "l", "I", "|", "!": return "1"
            case "S", "s": return "5"
            case "B": return "8"
            case "Z", "z": return "2"
            case "g": return "9"
            default: return c
            }
        })
        return Int(mapped)
    }

    /// Max HP alone ("163 /163 HP").
    static func hp(_ tokens: [Token]) -> Int? {
        for t in tokens {
            if let g = hpRx1.find(t.text) ?? hpRx2.find(t.text), let s = g[2], let v = digits(s) {
                return (10...999).contains(v) ? v : nil // first readable one wins, then range-checked
            }
        }
        return nil
    }

    /// The CP number, printed in the top part of the screen.
    static func cp(_ tokens: [Token], screenH: Int) -> Int? {
        let top = tokens.filter { Double($0.box.centerY) < Double(screenH) * 0.35 }
        for t in top {
            if let g = cpRx.find(t.text), let s = g[1], let v = digits(s), (10...9999).contains(v) { return v }
        }
        for t in top where Double(t.box.centerY) < Double(screenH) * 0.15 {
            let tt = t.text.trimmingCharacters(in: .whitespaces)
            if cpShortRx.matches(tt), let g = cpShortRx.find(tt), let s = g[1], let v = digits(s), (10...9999).contains(v) { return v }
        }
        return nil
    }

    static func hasPowerUp(_ tokens: [Token]) -> Bool { tokens.contains { powerUpRx.contains($0.text) } }

    static func looksLikePokemonPage(_ tokens: [Token]) -> Bool {
        hasPowerUp(tokens) || tokens.contains { pageWordRx.matches($0.text.trimmingCharacters(in: .whitespaces)) }
    }

    static func parse(_ tokens: [Token], screenH: Int) -> SummaryRead? {
        guard let cp = cp(tokens, screenH: screenH), let hp = hp(tokens) else { return nil }
        let strip = Rx("[.,\\s]")
        var dustTokens: [(Token, Int)] = []
        for t in tokens where Double(t.box.centerY) > Double(screenH) * 0.55 {
            let ns = t.text as NSString
            let cleaned = strip.re.stringByReplacingMatches(in: t.text, range: NSRange(location: 0, length: ns.length), withTemplate: "")
            if let v = Int(cleaned), Cpm.validDust.contains(v), v != cp { dustTokens.append((t, v)) }
        }
        let button = tokens.first { powerUpRx.contains($0.text) }
        var dust: Int? = nil
        if let b = button {
            func d(_ t: Token) -> Double {
                hypot(Double(t.box.centerX - b.box.centerX), Double(t.box.centerY - b.box.centerY))
            }
            var bestI: Int? = nil
            for i in dustTokens.indices where bestI == nil || d(dustTokens[i].0) < d(dustTokens[bestI!].0) { bestI = i }
            dust = bestI.map { dustTokens[$0].1 }
        } else {
            dust = dustTokens.map { $0.1 }.max()
        }
        return SummaryRead(cp: cp, hp: hp, dust: dust)
    }
}
