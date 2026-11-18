import Foundation
import UIKit
import Vision

/// Everything read from one screenshot.
struct ScreenRead {
    var tokens: [Token]
    var height: Int
    var pixels: PixelImage
}

/// On-device text recognition (Apple Vision) turned into the same tokens the core expects:
/// text + pixel box, origin top-left. Nothing leaves the phone.
enum OCR {
    static func read(_ image: UIImage) throws -> ScreenRead {
        guard let cg = normalized(image) else { throw NSError(domain: "OCR", code: 1, userInfo: [NSLocalizedDescriptionKey: "Could not open the picture"]) }
        let w = cg.width, h = cg.height
        let req = VNRecognizeTextRequest()
        req.recognitionLevel = .accurate
        req.usesLanguageCorrection = false // names and numbers, not sentences
        req.recognitionLanguages = ["en-US", "de-DE"]
        try VNImageRequestHandler(cgImage: cg, options: [:]).perform([req])
        var tokens: [Token] = []
        for o in req.results ?? [] {
            guard let cand = o.topCandidates(1).first else { continue }
            let r = o.boundingBox // normalized, origin bottom-left
            let box = Box(left: Int(r.minX * CGFloat(w)), top: Int((1 - r.maxY) * CGFloat(h)),
                          right: Int(r.maxX * CGFloat(w)), bottom: Int((1 - r.minY) * CGFloat(h)))
            tokens.append(Token(text: cand.string, box: box))
        }
        return ScreenRead(tokens: tokens, height: h, pixels: pixels(cg))
    }

    /// Redraws the picture upright in plain RGBA so the pixel reading and the boxes agree.
    private static func normalized(_ image: UIImage) -> CGImage? {
        let size = image.size
        guard size.width > 0, size.height > 0 else { return nil }
        let f = UIGraphicsImageRendererFormat(); f.scale = image.scale; f.opaque = true
        let r = UIGraphicsImageRenderer(size: size, format: f)
        return r.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }.cgImage
    }

    private static func pixels(_ cg: CGImage) -> PixelImage {
        let w = cg.width, h = cg.height
        var buf = [UInt8](repeating: 0, count: w * h * 4)
        let space = CGColorSpaceCreateDeviceRGB()
        buf.withUnsafeMutableBytes { p in
            if let ctx = CGContext(data: p.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                                   bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            }
        }
        return PixelImage(width: w, height: h, rgba: buf)
    }
}
