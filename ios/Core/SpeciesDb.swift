import Foundation

/// Pokémon data from pogoapi.net: base stats, types, sizes, evolution families.
enum SpeciesDb {
    static let base = "https://pogoapi.net/api/v1/"
    static let types: Set<String> = ["normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground",
                                     "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"]

    struct DexForm: Hashable { var dex: Int; var form: String }

    final class Data {
        let byName: [String: [Species]]
        let nameOrder: [String]
        let family: [Int: [Species]]
        let size: [DexForm: (Double, Double)]
        let evolveCosts: [Int: Set<Int>]
        let namesLongestFirst: [String]
        let all: [Species]
        let allEvolveCosts: Set<Int>
        init(byName: [String: [Species]], nameOrder: [String], family: [Int: [Species]],
             size: [DexForm: (Double, Double)], evolveCosts: [Int: Set<Int>]) {
            self.byName = byName; self.nameOrder = nameOrder; self.family = family; self.size = size; self.evolveCosts = evolveCosts
            namesLongestFirst = stableSorted(nameOrder) { $0.count > $1.count }
            all = nameOrder.flatMap { byName[$0]! }
            allEvolveCosts = Set(evolveCosts.values.flatMap { $0 })
        }
    }

    private static func array(_ data: Foundation.Data) throws -> [[String: Any]] {
        guard let a = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            throw NSError(domain: "SpeciesDb", code: 1, userInfo: [NSLocalizedDescriptionKey: "unexpected data"])
        }
        return a
    }
    private static func int(_ v: Any?) -> Int { (v as? NSNumber)?.intValue ?? 0 }

    /// Builds the lookup tables from the pogoapi.net JSON files.
    static func build(stats: Foundation.Data, types typesJson: Foundation.Data, evolutions: Foundation.Data,
                      sizes: Foundation.Data? = nil) throws -> Data {
        var typeMap: [DexForm: Set<String>] = [:]
        for o in try array(typesJson) {
            let t = Set((o["type"] as? [String] ?? []).map { $0.lowercased() })
            typeMap[DexForm(dex: int(o["pokemon_id"]), form: (o["form"] as? String ?? "Normal").lowercased())] = t
        }
        var byName: [String: [Species]] = [:]
        var order: [String] = []
        for o in try array(stats) {
            let name = o["pokemon_name"] as? String ?? ""
            let dex = int(o["pokemon_id"])
            let form = o["form"] as? String ?? "Normal"
            let key = Rules.norm(name)
            let t = typeMap[DexForm(dex: dex, form: form.lowercased())] ?? typeMap[DexForm(dex: dex, form: "normal")] ?? []
            let sp = Species(id: key, name: name, atk: int(o["base_attack"]), def: int(o["base_defense"]),
                             hp: int(o["base_stamina"]), form: form, dex: dex, types: t)
            if byName[key] == nil { byName[key] = []; order.append(key) }
            // Costumes share stats and types with another form; keep one entry per stat line + types
            if let same = byName[key]!.firstIndex(where: { $0.atk == sp.atk && $0.def == sp.def && $0.hp == sp.hp && $0.types == sp.types }) {
                if sp.isNormal { byName[key]![same] = sp }
            } else { byName[key]!.append(sp) }
        }
        for k in order { byName[k] = stableSorted(byName[k]!) { ($0.isNormal ? 0 : 1) < ($1.isNormal ? 0 : 1) } }
        var byDex: [Int: [Species]] = [:]
        for k in order { for sp in byName[k]! { byDex[sp.dex, default: []].append(sp) } }

        // evolution families by union-find over dex numbers, plus the candy cost of each evolution
        var parent: [Int: Int] = [:]
        func root(_ x: Int) -> Int { var r = x; while let p = parent[r], p != r { r = p }; return r }
        func union(_ a: Int, _ b: Int) { let ra = root(a), rb = root(b); if ra != rb { parent[max(ra, rb)] = min(ra, rb) } }
        var evolveCosts: [Int: Set<Int>] = [:]
        for o in try array(evolutions) {
            let from = int(o["pokemon_id"])
            guard let evos = o["evolutions"] as? [[String: Any]] else { continue }
            for e in evos {
                union(from, int(e["pokemon_id"]))
                let cost = int(e["candy_required"])
                if cost > 0 { evolveCosts[from, default: []].insert(cost) }
            }
        }
        var members: [Int: [Int]] = [:]
        for dex in byDex.keys { members[root(dex), default: []].append(dex) }
        var family: [Int: [Species]] = [:]
        for (_, dexes) in members {
            let all = dexes.sorted().flatMap { byDex[$0]! }
            for d in dexes { family[d] = all }
        }
        var size: [DexForm: (Double, Double)] = [:]
        if let sj = sizes {
            for o in try array(sj) {
                size[DexForm(dex: int(o["pokemon_id"]), form: (o["form"] as? String ?? "Normal").lowercased())] =
                    ((o["pokedex_height"] as? NSNumber)?.doubleValue ?? 0, (o["pokedex_weight"] as? NSNumber)?.doubleValue ?? 0)
            }
        }
        return Data(byName: byName, nameOrder: order, family: family, size: size, evolveCosts: evolveCosts)
    }

    // MARK: - reading the clues from one screenshot

    private static let candyRx = Rx("candy|bonbon", ignoreCase: true)
    private static let evolveRx = Rx("evolve|entwickel", ignoreCase: true)
    private static let powerUpRx = Rx("power[\\s-]*up", ignoreCase: true)
    private static let kgRx = Rx("(\\d{1,4}[.,]\\d{1,2})\\s*kg", ignoreCase: true)
    private static let mRx = Rx("(\\d{1,2}[.,]\\d{1,2})\\s*m(?![a-zA-Z])", ignoreCase: true)
    private static let caughtRx = Rx("\\b(?:this|dieses|diese|dieser)\\s+(.+?)\\s+(?:was|wurde)\\b", ignoreCase: true)
    private static let caughtStartRx = Rx("^\\s*(?:this|dieses|diese|dieser)\\s+(.+?)\\s*$", ignoreCase: true)
    private static let wasRx = Rx("^(?:was|wurde)\\b", ignoreCase: true)
    private static let ws = Rx("\\s+")
    private static let regionalWords = ["alolan": "alol", "alola": "alol", "galarian": "galar", "galar": "galar",
                                        "hisuian": "hisui", "hisui": "hisui", "paldean": "palde", "paldea": "palde"]
    private static let prefixWords: Set<String> = ["mega", "shadow", "purified", "primal", "gigantamax", "dynamax"]

    private static func split(_ s: String, _ rx: Rx) -> [String] {
        let ns = s as NSString; var out: [String] = []; var last = 0
        for m in rx.re.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            out.append(ns.substring(with: NSRange(location: last, length: m.range.location - last)))
            last = m.range.location + m.range.length
        }
        out.append(ns.substring(from: last))
        return out
    }

    static func readClues(_ tokens: [Token], screenH: Int, _ d: Data) -> Clues {
        let (family, consumed) = candyFamily(tokens, d)
        var names: [Species] = []
        for (i, t) in tokens.enumerated() where !consumed.contains(i) {
            if let s = d.byName[Rules.norm(t.text)] { for x in s where !names.contains(x) { names.append(x) } }
        }
        if names.isEmpty {
            for (i, t) in tokens.enumerated() where !consumed.contains(i) {
                let n = Rules.norm(t.text)
                if let k = d.namesLongestFirst.first(where: { $0.count >= 5 && n.contains($0) && n.count <= $0.count + 6 }) {
                    for x in d.byName[k]! where !names.contains(x) { names.append(x) }
                }
            }
        }
        func num(_ rx: Rx) -> Double? {
            for t in tokens {
                if let g = rx.find(t.text), let s = g[1], let v = Double(s.replacingOccurrences(of: ",", with: ".")) { return v }
            }
            return nil
        }
        return Clues(names: names, family: family, types: typesOnScreen(tokens, screenH: screenH),
                     heightM: num(mRx), weightKg: num(kgRx), evolveCost: evolveCost(tokens, screenH: screenH, d),
                     evolveButton: tokens.contains { evolveRx.contains($0.text) }, caught: readCaught(tokens, d))
    }

    /// The species named in "This Dragonite was caught on …".
    static func readCaught(_ tokens: [Token], _ d: Data) -> [Species] {
        for (ti, t) in tokens.enumerated() {
            var phrase = caughtRx.find(t.text)?[1] ?? nil
            if phrase == nil {
                guard let start = caughtStartRx.find(t.text)?[1], split(start, ws).count <= 5 else { continue }
                let h = max(t.box.height, 1)
                let wrapped = tokens.enumerated().contains { oi, o in
                    oi != ti && o.box.top >= t.box.top && o.box.top - t.box.bottom <= h * 2
                        && wasRx.contains(o.text.trimmingCharacters(in: .whitespaces))
                }
                if !wrapped { continue }
                phrase = start
            }
            let found = resolveCaught(phrase!, d)
            if !found.isEmpty { return found }
        }
        return []
    }

    private static func resolveCaught(_ phrase: String, _ d: Data) -> [Species] {
        var words = split(phrase.trimmingCharacters(in: .whitespacesAndNewlines), ws)
        var region: String? = nil
        while words.count > 1 {
            let w = String(words[0].lowercased().filter { $0.isLetter })
            if let r = regionalWords[w] { region = r } else if !prefixWords.contains(w) { break }
            words.removeFirst()
        }
        let n = Rules.norm(words.joined())
        var found = lookup(n, d)
        if found.isEmpty, let k = d.namesLongestFirst.first(where: { $0.count >= 5 && n.contains($0) && n.count <= $0.count + 8 }) {
            found = d.byName[k]!
        }
        if let r = region {
            let f = found.filter { $0.form.lowercased().hasPrefix(r) }
            if !f.isEmpty { found = f }
        }
        return found
    }

    private static func dist(_ a: Box, _ b: Box) -> Double {
        hypot(Double(a.centerX - b.centerX), Double(a.centerY - b.centerY))
    }

    private static func evolveCost(_ tokens: [Token], screenH: Int, _ d: Data) -> Int? {
        guard let ev = tokens.first(where: { evolveRx.contains($0.text) }) else { return nil }
        for g in Rx("\\d{2,3}").all(ev.text) {
            if let s = g[0], let v = Int(s), d.allEvolveCosts.contains(v) { return v }
        }
        let reach = screenH > 0 ? Double(screenH) * 0.12 : Double.greatestFiniteMagnitude
        var best: (Int, Double)? = nil
        for t in tokens where t.box != ev.box || t.text != ev.text {
            guard let v = Int(t.text.trimmingCharacters(in: .whitespaces)), d.allEvolveCosts.contains(v) else { continue }
            let dd = dist(t.box, ev.box)
            if dd <= reach, best == nil || dd < best!.1 { best = (v, dd) }
        }
        return best?.0
    }

    /// Finds the evolution family from a "<SPECIES> CANDY" label.
    private static func candyFamily(_ tokens: [Token], _ d: Data) -> ([Species]?, Set<Int>) {
        var consumed = Set<Int>()
        var family: [Species]? = nil
        for (i, t) in tokens.enumerated() {
            guard let r = candyRx.firstMatchRange(t.text) else { continue }
            consumed.insert(i)
            if family != nil { continue }
            let before = (t.text as NSString).substring(to: r.location)
            let words = split(before, ws).map { w in String(w.filter { $0.isLetter || "'.-♀♂".contains($0) || $0.isNumber }) }
                .filter { w in w.contains { $0.isLetter } && w.lowercased() != "xl" }
            var found: [Species] = []
            if !words.isEmpty {
                for k in stride(from: min(3, words.count), through: 1, by: -1) {
                    found = lookup(Rules.norm(words.suffix(k).joined()), d)
                    if !found.isEmpty { break }
                }
            }
            if found.isEmpty && words.isEmpty {
                let h = max(t.box.height, 1)
                var neighbours: [(Int, Token)] = []
                for (j, o) in tokens.enumerated() {
                    if j == i || candyRx.contains(o.text) { continue }
                    let dy = o.box.centerY - t.box.centerY; let gap = t.box.left - o.box.right
                    let sameRow = abs(dy) <= h && gap >= -h && gap <= h * 8
                    let above = dy < 0 && -dy <= h * 3 && abs(o.box.centerX - t.box.centerX) <= h * 6
                    if sameRow || above { neighbours.append((j, o)) }
                }
                neighbours = stableSorted(neighbours) { dist($0.1.box, t.box) < dist($1.1.box, t.box) }
                for (j, o) in neighbours {
                    let n = Rules.norm(String(o.text.filter { !$0.isNumber }))
                    let l = lookup(n, d)
                    if !l.isEmpty { found = l; consumed.insert(j) }
                    if !found.isEmpty { break }
                }
            }
            if !found.isEmpty {
                family = distinct(distinct(found.map { $0.dex }).flatMap { d.family[$0] ?? [] })
            }
        }
        return (family, consumed)
    }

    /// Exact name, or (for names of 5+ letters) every name that is one letter off.
    private static func lookup(_ n: String, _ d: Data) -> [Species] {
        if n.isEmpty { return [] }
        if let s = d.byName[n] { return s }
        if n.count < 5 { return [] }
        var out: [Species] = []
        for k in d.nameOrder where abs(k.count - n.count) <= 1 && lev(k, n) == 1 { out += d.byName[k]! }
        return out
    }

    private static func lev(_ a: String, _ b: String) -> Int {
        let a = Array(a), b = Array(b)
        var prev = Array(0...b.count)
        if a.isEmpty { return b.count }
        for i in 1...a.count {
            var cur = [Int](repeating: 0, count: b.count + 1); cur[0] = i
            if b.count > 0 { for j in 1...b.count {
                cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
            } }
            prev = cur
        }
        return prev[b.count]
    }

    /// Type words on the page, e.g. "GRASS / DRAGON" -> {grass, dragon}.
    static func typesOnScreen(_ tokens: [Token], screenH: Int) -> Set<String> {
        let powerUp = tokens.first { powerUpRx.contains($0.text) }
        let limit = powerUp?.box.top ?? Int(Double(screenH) * 0.62)
        var out = Set<String>()
        let nonLetters = Rx("[^a-z]+")
        for t in tokens {
            if screenH > 0 && t.box.centerY >= limit { continue }
            let words = split(t.text.lowercased(), nonLetters).filter { !$0.isEmpty }.map { typeWord($0) }
            if !words.isEmpty && words.count <= 2 && words.allSatisfy({ $0 != nil }) { for w in words { out.insert(w!) } }
        }
        return out.count <= 2 ? out : []
    }

    private static func typeWord(_ w: String) -> String? {
        if types.contains(w) { return w }
        let starts = types.filter { w.count >= 3 && $0.hasPrefix(w) }
        if starts.count == 1 { return starts.first }
        if w.count >= 5 {
            let near = types.filter { abs($0.count - w.count) <= 1 && lev($0, w) == 1 }
            if near.count == 1 { return near.first }
        }
        return nil
    }

    // MARK: - turning clues into candidates

    static func score(_ sp: Species, _ c: Clues, _ d: Data) -> Double {
        var s = 0.0
        let evolvable = !(d.evolveCosts[sp.dex] ?? []).isEmpty
        if let ec = c.evolveCost { s += (d.evolveCosts[sp.dex]?.contains(ec) == true) ? 10.0 : -10.0 }
        else if c.evolveButton && !evolvable { s -= 2.0 }
        else if !c.evolveButton && evolvable { s -= 1.0 }
        if let sz = d.size[DexForm(dex: sp.dex, form: sp.form.lowercased())] ?? d.size[DexForm(dex: sp.dex, form: "normal")] {
            if let h = c.heightM, h > 0, sz.0 > 0 { s -= abs(log(h / sz.0)) }
            if let w = c.weightKg, w > 0, sz.1 > 0 { s -= abs(log(w / sz.1)) / 3 }
        }
        return s
    }

    private static func wrongSize(_ sp: Species, _ c: Clues, _ d: Data) -> Bool {
        guard let w = c.weightKg else { return false }
        guard let avg = (d.size[DexForm(dex: sp.dex, form: sp.form.lowercased())] ?? d.size[DexForm(dex: sp.dex, form: "normal")])?.1 else { return false }
        return w > 0 && avg > 0 && abs(log(w / avg)) > log(8.0)
    }

    /// The likely species, most likely first, from name / candy family / types, ordered by size and evolve cost.
    static func candidates(_ c: Clues, _ d: Data) -> SpeciesGuess? {
        if !c.caught.isEmpty {
            let forms = distinct(c.caught)
            var byType = forms
            if !c.types.isEmpty {
                byType = forms.filter { $0.types == c.types }
                if byType.isEmpty { byType = forms.filter { $0.types.isSuperset(of: c.types) } }
            }
            let nick = !c.names.contains { n in forms.contains { $0.dex == n.dex } } || c.names.isEmpty
            return SpeciesGuess(candidates: byType.isEmpty ? forms : byType, nickname: nick)
        }
        let fam = c.family
        var nickname = false
        var cands: [Species]
        if let f = fam {
            if c.names.contains(where: { f.contains($0) }) {
                cands = c.names.filter { f.contains($0) } + f.filter { !c.names.contains($0) }
            } else { nickname = true; cands = f }
        } else { cands = c.names }
        if cands.isEmpty { return nil }
        if !c.types.isEmpty {
            let exact = cands.filter { $0.types == c.types }
            let partial = cands.filter { $0.types.isSuperset(of: c.types) }
            if !exact.isEmpty { cands = exact }
            else if !partial.isEmpty { cands = partial }
            else if let f = fam { cands = f } else { return nil }
        }
        if fam == nil {
            cands = cands.filter { !wrongSize($0, c, d) }
            if cands.isEmpty { return nil }
        }
        let named = Set(c.names)
        cands = stableSorted(cands) { a, b in
            let an = named.contains(a), bn = named.contains(b)
            if an != bn { return an }
            return score(a, c, d) > score(b, c, d)
        }
        return SpeciesGuess(candidates: cands, nickname: nickname)
    }

    /// Last resort: every Pokémon with the types on screen, best size / evolve-cost evidence first.
    static func widened(_ c: Clues, _ d: Data) -> [Species]? {
        if c.types.isEmpty && c.heightM == nil && c.weightKg == nil && c.evolveCost == nil { return nil }
        var pool = d.all
        if !c.types.isEmpty {
            pool = d.all.filter { $0.types == c.types }
            if pool.isEmpty { pool = d.all.filter { $0.types.isSuperset(of: c.types) } }
        }
        return stableSorted(pool) { score($0, c, d) > score($1, c, d) }
    }
}

/// The species decision for one Pokémon.
enum Identify {
    static func analyze(clues: Clues, cp: Int, hp: Int, dust: Int?, appraisal: Iv?, _ d: SpeciesDb.Data) -> IvResult? {
        var r: IvResult? = nil
        if let g = SpeciesDb.candidates(clues, d), var x = IvAnalyzer.analyze(candidates: g.candidates, cp: cp, hp: hp, dust: dust, appraisal: appraisal) {
            x.nickname = g.nickname; r = x
        }
        if r == nil, let w = SpeciesDb.widened(clues, d), var x = IvAnalyzer.analyze(candidates: w, cp: cp, hp: hp, dust: dust, appraisal: appraisal) {
            x.guessed = true; r = x
        }
        return r
    }
}
