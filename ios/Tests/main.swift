import Foundation

// Plain-Swift checks of the core (no XCTest needed, so they run with just `swiftc`). Run: ios/Tests/run.sh
var failures = 0, checks = 0
func check(_ ok: Bool, _ msg: String, line: Int = #line) { checks += 1; if !ok { failures += 1; print("FAIL line \(line): \(msg)") } }
func eq<T: Equatable>(_ a: T, _ b: T, _ msg: String, line: Int = #line) { check(a == b, "\(msg): \(a) != \(b)", line: line) }

let dir = URL(fileURLWithPath: CommandLine.arguments[1])
func res(_ n: String) -> Data { try! Data(contentsOf: dir.appendingPathComponent(n)) }
let db = try! SpeciesDb.build(stats: res("pokemon_stats.json"), types: res("pokemon_types.json"),
                              evolutions: res("pokemon_evolutions.json"), sizes: res("pokemon_height_weight_scale.json"))

// CP multiplier table equals the game master
for o in try! JSONSerialization.jsonObject(with: res("cp_multiplier.json")) as! [[String: Any]] {
    let lv = (o["level"] as! NSNumber).doubleValue, m = (o["multiplier"] as! NSNumber).doubleValue
    check(abs(Cpm.at(lv) - m) < 1e-7, "CPM at \(lv)")
}
// stardust table
let up = try! JSONSerialization.jsonObject(with: res("pokemon_powerup_requirements.json")) as! [String: [String: Any]]
for (_, row) in up {
    let lv = (row["current_level"] as! NSNumber).doubleValue, dust = (row["stardust_to_upgrade"] as! NSNumber).intValue
    eq(Cpm.baseDust(lv), dust, "dust at level \(lv)")
    for m in Cpm.dustMultipliers {
        let shown = Int((Double(dust) * m).rounded(.toNearestOrAwayFromZero))
        check(Cpm.levelsForDust(shown, all: Cpm.levels()).contains(lv), "level \(lv) allowed for dust \(shown)")
    }
}

// known values (same as the Android tests)
let mewtwo = db.byName["mewtwo"]![0]
eq(Cpm.cp(mewtwo, Iv(a: 15, d: 15, s: 15), 40.0), 4178, "mewtwo cp 40")
eq(Cpm.cp(mewtwo, Iv(a: 15, d: 15, s: 15), 50.0), 4724, "mewtwo cp 50")
let k = IvAnalyzer.analyze(candidates: db.byName["kingdra"]!, cp: 2578, hp: 152, dust: 10000, appraisal: Iv(a: 15, d: 13, s: 13))!
eq(k.exact, Iv(a: 15, d: 13, s: 13), "kingdra exact"); eq(k.levelMin, 39.0, "kingdra level")
let emp = db.byName["empoleon"]![0]
let ecp = Cpm.cp(emp, Iv(a: 14, d: 14, s: 13), 38.0), ehp = Cpm.hp(emp, Iv(a: 14, d: 14, s: 13), 38.0)
eq(ecp, 2785, "empoleon cp"); eq(ehp, 163, "empoleon hp")
let e = IvAnalyzer.analyze(candidates: [emp], cp: ecp, hp: ehp, dust: Cpm.baseDust(38.0), appraisal: Iv(a: 14, d: 14, s: 13))!
eq(e.exact, Iv(a: 14, d: 14, s: 13), "empoleon exact")

// every Pokémon and form: build the screen values, solve them back, the true IVs must come out
var species = 0
for sp in db.all {
    for (iv, lv) in [(Iv(a: 15, d: 15, s: 15), 40.0), (Iv(a: 0, d: 0, s: 0), 20.5), (Iv(a: 3, d: 14, s: 9), 33.0), (Iv(a: 12, d: 1, s: 7), 8.0)] {
        let cp = Cpm.cp(sp, iv, lv), hp = Cpm.hp(sp, iv, lv)
        let fits = IvSolver.solve(sp, cp: cp, hp: hp, levels: Cpm.levelsForDust(Cpm.baseDust(lv), all: Cpm.levels()))
        check(fits.contains { $0.iv == iv && $0.level == lv }, "\(sp.label) \(iv) lv \(lv) not recovered")
    }
    species += 1
}
print("round-trip over \(species) forms")

// PvP: a Great League pick ranks #1 for the best spread
let azu = db.byName["azumarill"]![0]
let tbl = Pvp.table(azu, capCp: 1500)!
eq(tbl.values.map { $0.rank }.min(), 1, "rank 1 exists"); eq(tbl.count, 4096, "4096 spreads")

// the real phone dump (place line already replaced): Dragonite named "CutieFly"
func tok(_ t: String, _ l: Int, _ top: Int, _ r: Int, _ b: Int) -> Token { Token(text: t, box: Box(left: l, top: top, right: r, bottom: b)) }
let H = 2589
let dump: [Token] = [
    tok("c3259", 389, 134, 738, 229), tok("CutieFly/", 541, 1041, 854, 1126), tok("LUCKY POKÉMON", 469, 1141, 794, 1176),
    tok("163 /163 HP", 537, 1246, 726, 1276), tok("314.45kg", 181, 1420, 399, 1474), tok("66m", 1007, 1420, 1091, 1466),
    tok("EST", 1031, 1494, 1077, 1523), tok("HEAVIEST", 213, 1496, 367, 1520), tok("DRAGON / FLY", 515, 1498, 761, 1523),
    tok("5,657", 369, 1648, 485, 1704), tok("DUST", 400, 1723, 470, 1756), tok("Attack", 183, 1952, 298, 1981),
    tok("Defense", 182, 2060, 323, 2089), tok("HP", 182, 2168, 232, 2197),
    tok("This Dragonite was caught on 13/11/2026", 123, 2398, 1123, 2445),
]
let sr = SummaryParser.parse(dump, screenH: H)!
eq(sr.cp, 3259, "dump cp"); eq(sr.hp, 163, "dump hp")
let c = SpeciesDb.readClues(dump, screenH: H, db)
eq(c.caught.first?.name, "Dragonite", "caught"); eq(c.types, ["dragon", "flying"], "types")
eq(c.weightKg, 314.45, "weight"); check(c.heightM == nil, "66m is not trusted"); eq(c.names.first?.name, "Cutiefly", "nickname")
let g = SpeciesDb.candidates(c, db)!
eq(g.candidates.first?.name, "Dragonite", "picks Dragonite"); check(g.nickname, "nickname flag")

// nicknamed Dragonite from the candy label + types (layout like a 1080x2400 phone)
func t2(_ text: String, _ l: Int, _ top: Int) -> Token { tok(text, l, top, l + 40 + text.count * 22, top + 44) }
let dr = db.byName["dragonite"]![0]; let div = Iv(a: 14, d: 15, s: 13)
let dcp = Cpm.cp(dr, div, 41.5), dhp = Cpm.hp(dr, div, 41.5), ddust = Cpm.baseDust(41.5)!
let page: [Token] = [t2("CP \(dcp)", 420, 250), t2("Dino", 400, 1250), t2("\(dhp) / \(dhp) HP", 400, 1330),
                     t2("245 DRATINI CANDY", 300, 1700), t2("DRAGON / FLYING", 460, 1450), t2("\(ddust)", 300, 2030), t2("POWER UP", 300, 2000)]
let pr = SummaryParser.parse(page, screenH: 2400)!
eq(pr.cp, dcp, "page cp"); eq(pr.hp, dhp, "page hp"); eq(pr.dust, ddust, "page dust")
let ar = Identify.analyze(clues: SpeciesDb.readClues(page, screenH: 2400, db), cp: pr.cp, hp: pr.hp, dust: pr.dust, appraisal: nil, db)!
eq(ar.species.name, "Dragonite", "nickname resolved by candy"); check(ar.nickname, "nickname")
check(ar.candidates.contains(div), "true IVs among candidates")
let dl = SpeciesDb.readClues([t2("245 DRATIN1 CANDY", 300, 1700)], screenH: 2400, db)
eq(dl.family?.first?.name, "Dratini", "candy with one wrong letter")
let card = CardModels.from(ar, cp: pr.cp, hp: pr.hp, appraised: nil)
check(!card.pick.isEmpty && card.stats.count == 3 && card.leagues.count == 4, "card shape")

// bag
let bagTokens: [Token] = [tok("342/350", 800, 200, 1000, 260), tok("Poke Ball", 200, 500, 500, 560), tok("84", 900, 500, 960, 560),
                          tok("Potion", 200, 800, 500, 860), tok("40", 900, 800, 960, 860), tok("Revive x10", 200, 1100, 600, 1160)]
let ps = ItemParser.parse(bagTokens, screenH: 2400)
eq(ps.counts["Poke Ball"], 84, "poke ball"); eq(ps.counts["Potion"], 40, "potion"); eq(ps.counts["Revive"], 10, "revive")
eq(ps.used, 342, "used"); eq(ps.capacity, 350, "capacity")
let rev = Recommender.review(ps.counts)
check(rev.remove.contains { $0.item == "Poke Ball" && $0.amount == 34 }, "34 Poke Balls over the limit")
check(rev.remove.contains { $0.item == "Potion" && $0.amount == 40 }, "all Potions")
check(rev.keep.contains { $0.name == "Revive" && $0.count == 10 }, "keep revives")
let plan = Recommender.plan(ps.counts, used: 342, capacity: 350, targetFree: 20)
eq(plan.needed, 12, "needs 12 slots"); eq(plan.freed, 12, "frees 12")
let mem = BagMemory(); mem.observe(["Potion": 40]); mem.observe(["Potion": 40]); mem.observe(["Revive": 10])
eq(mem.confirmed()["Potion"], 40, "confirmed"); eq(mem.unconfirmed()["Revive"], 10, "unconfirmed"); eq(mem.size, 2, "size")

print("\(checks) checks, \(failures) failures")
exit(failures == 0 ? 0 : 1)
