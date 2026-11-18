import Foundation

/// RGBA pixels of a screenshot (row-major, origin top-left).
struct PixelImage {
    let width: Int, height: Int
    let rgba: [UInt8]
    func rgb(_ x: Int, _ y: Int) -> (Int, Int, Int) {
        let i = (y * width + x) * 4
        return (Int(rgba[i]), Int(rgba[i + 1]), Int(rgba[i + 2]))
    }
}

/// Reads the three appraisal bars (Attack / Defense / HP): OCR finds the labels, then each bar's fill is measured in pixels.
enum AppraisalReader {
    private static let atkRx = Rx("^(attack|angriff|atk)$")
    private static let defRx = Rx("^(defense|defence|verteidigung|def)$")
    private static let hpRx = Rx("^(hp|kp|stamina)$")

    static func read(_ tokens: [Token], _ img: PixelImage) -> Iv? {
        func find(_ rx: Rx) -> Token? { tokens.first { rx.matches(Rules.norm($0.text)) } }
        guard let a = find(atkRx), let d = find(defRx) else { return nil }
        guard let h = tokens.filter({ hpRx.matches(Rules.norm($0.text)) }).first(where: { $0.box.top > d.box.top }) else { return nil }
        if !(a.box.top < d.box.top && d.box.top < h.box.top) { return nil }
        let w = img.width
        if Double(abs(a.box.left - d.box.left)) > Double(w) * 0.08 || Double(abs(d.box.left - h.box.left)) > Double(w) * 0.08 { return nil }

        var bars: [(Int, Int)] = []
        for t in [a, d, h] { guard let b = bar(t, img) else { return nil }; bars.append(b) }
        let widths = bars.map { $0.1 }
        if Double(widths.min()!) < Double(w) * 0.2 || Double(widths.max()!) > Double(widths.min()!) * 1.15 { return nil }
        let v = bars.map { (fill, width) in min(max(Int((Double(fill) / Double(width) * 15).rounded(.toNearestOrAwayFromZero)), 0), 15) }
        return Iv(a: v[0], d: v[1], s: v[2])
    }

    private static func bar(_ label: Token, _ img: PixelImage) -> (Int, Int)? {
        let lh = max(label.box.height, 10)
        let y0 = min(label.box.bottom + 2, img.height - 1)
        let y1 = min(label.box.bottom + lh * 3, img.height - 1)
        let xMin = max(label.box.left - lh * 2, 0)
        let xMaxStart = label.box.left + lh * 2
        let gapTol = max(Int(Double(img.width) * 0.012), 4)
        var best: (y: Int, start: Int, end: Int)? = nil
        if y0 > y1 { return nil }
        for y in y0...y1 {
            var x = xMin
            while x < img.width {
                if !isBar(img.rgb(x, y)) { x += 1; continue }
                let start = x
                var end = x; var gap = 0
                while x < img.width && gap <= gapTol {
                    if isBar(img.rgb(x, y)) { end = x; gap = 0 } else { gap += 1 }
                    x += 1
                }
                if start <= xMaxStart && (best == nil || end - start > best!.end - best!.start) { best = (y, start, end) }
            }
        }
        guard let b = best else { return nil }
        let width = b.end - b.start + 1
        var fillEnd = b.start - 1
        for x in b.start...b.end where isFill(img.rgb(x, b.y)) { fillEnd = x }
        return (max(fillEnd - b.start + 1, 0), width)
    }

    /// Anything that isn't the white card background: grey empty bar or coloured fill.
    private static func isBar(_ c: (Int, Int, Int)) -> Bool {
        let mx = max(c.0, c.1, c.2), mn = min(c.0, c.1, c.2)
        return (mx - mn > 40) || ((150...242).contains(mx) && mx - mn < 20)
    }

    /// Orange (or red for 15) fill: strongly coloured, bright enough.
    private static func isFill(_ c: (Int, Int, Int)) -> Bool {
        let mx = max(c.0, c.1, c.2), mn = min(c.0, c.1, c.2)
        return mx > 120 && Double(mx - mn) / Double(mx) > 0.3
    }
}
