#!/usr/bin/env swift
// Generates the iOS brand assets from the Android vector drawables (single source of truth):
//
//   core/designsystem/src/main/res/drawable/ic_autobot_mark.xml  → AutobotMark.imageset (SVG, the pixel a)
//   app/src/main/res/drawable/ic_launcher_foreground.xml
//     + ic_launcher_background colour                            → AppIcon.appiconset (1024 PNG)
//
//   swift ios/scripts/gen-icons.swift      (from the repository root; output is committed)

import AppKit
import Foundation

let scriptURL = URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
let root = scriptURL.deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
let assets = root.appendingPathComponent("ios/App/Resources/Assets.xcassets")

struct Vector {
    var viewportWidth = 24.0
    var viewportHeight = 24.0
    var paths: [(fill: String, data: String)] = []
}

final class VectorParser: NSObject, XMLParserDelegate {
    var vector = Vector()
    func parser(_ parser: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String]) {
        if name == "vector" {
            vector.viewportWidth = Double(attributes["android:viewportWidth"] ?? "24") ?? 24
            vector.viewportHeight = Double(attributes["android:viewportHeight"] ?? "24") ?? 24
        } else if name == "path", let data = attributes["android:pathData"] {
            vector.paths.append((attributes["android:fillColor"] ?? "#FF000000", data))
        }
    }
}

func parse(_ relative: String) -> Vector {
    let parser = XMLParser(contentsOf: root.appendingPathComponent(relative))!
    let delegate = VectorParser()
    parser.delegate = delegate
    guard parser.parse() else { fatalError("cannot parse \(relative)") }
    return delegate.vector
}

/// `#AARRGGBB` → `#RRGGBB` plus opacity.
func svgFill(_ argb: String) -> String {
    let hex = argb.dropFirst()
    if hex.count == 8 {
        let alpha = Double(Int(hex.prefix(2), radix: 16)!) / 255
        return "fill=\"#\(hex.dropFirst(2))\"" + (alpha < 1 ? " fill-opacity=\"\(alpha)\"" : "")
    }
    return "fill=\"#\(hex)\""
}

func svg(_ v: Vector, viewBox: String, size: Int, background: String? = nil) -> String {
    var s = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"\(size)\" height=\"\(size)\" viewBox=\"\(viewBox)\">\n"
    if let background { s += "<rect x=\"-1000\" y=\"-1000\" width=\"3000\" height=\"3000\" fill=\"\(background)\"/>\n" }
    for p in v.paths { s += "<path \(svgFill(p.fill)) d=\"\(p.data)\"/>\n" }
    return s + "</svg>\n"
}

func write(_ text: String, _ url: URL) {
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! text.write(to: url, atomically: true, encoding: .utf8)
}

// Brand mark (vector, scales with its frame).
let mark = parse("core/designsystem/src/main/res/drawable/ic_autobot_mark.xml")
let markDir = assets.appendingPathComponent("AutobotMark.imageset")
write(svg(mark, viewBox: "0 0 \(Int(mark.viewportWidth)) \(Int(mark.viewportHeight))", size: 24), markDir.appendingPathComponent("AutobotMark.svg"))
write("""
{
  "images" : [ { "filename" : "AutobotMark.svg", "idiom" : "universal" } ],
  "info" : { "author" : "gen-icons", "version" : 1 },
  "properties" : { "preserves-vector-representation" : true }
}

""", markDir.appendingPathComponent("Contents.json"))

// App icon: adaptive foreground on the launcher background colour. The visible part of an
// Android adaptive icon is the centre 72×72 of its 108×108 canvas.
let colors = try! String(contentsOf: root.appendingPathComponent("app/src/main/res/values/colors.xml"), encoding: .utf8)
let bgRange = colors.range(of: #"ic_launcher_background">#([0-9A-Fa-f]{8})<"#, options: .regularExpression)!
let bg = "#" + colors[bgRange].dropLast().suffix(6)
let foreground = parse("app/src/main/res/drawable/ic_launcher_foreground.xml")
let inset = (foreground.viewportWidth - 72) / 2
// Launcher paths are dots drawn as two arcs (`Mx,y a r,r 0 1,0 2r,0 …`) or pixel runs drawn as
// rectangles (`Mx,y h w v h h-w z`).
let circle = try! NSRegularExpression(pattern: #"M([0-9.]+),([0-9.]+)a([0-9.]+),[0-9.]+ 0 1,0"#)
let rect = try! NSRegularExpression(pattern: #"M([0-9.]+),([0-9.]+)h([0-9.]+)v([0-9.]+)h-[0-9.]+z"#)
// No alpha channel: App Store icons must be opaque.
let context = CGContext(data: nil, width: 1024, height: 1024, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
func cgColor(_ hex: Substring) -> CGColor {
    let v = Int(hex, radix: 16)!
    return CGColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
}
context.setFillColor(cgColor(bg.dropFirst()))
context.fill(CGRect(x: 0, y: 0, width: 1024, height: 1024))
let scale = 1024 / 72.0
for path in foreground.paths {
    context.setFillColor(cgColor(path.fill.dropFirst().suffix(6)))
    let ns = path.data as NSString
    for match in circle.matches(in: path.data, range: NSRange(location: 0, length: ns.length)) {
        let x = Double(ns.substring(with: match.range(at: 1)))!
        let y = Double(ns.substring(with: match.range(at: 2)))!
        let r = Double(ns.substring(with: match.range(at: 3)))!
        let cx = (x + r - inset) * scale
        let cy = 1024 - (y - inset) * scale
        context.fillEllipse(in: CGRect(x: cx - r * scale, y: cy - r * scale, width: 2 * r * scale, height: 2 * r * scale))
    }
    for match in rect.matches(in: path.data, range: NSRange(location: 0, length: ns.length)) {
        let n = (1...4).map { Double(ns.substring(with: match.range(at: $0)))! }
        let x = (n[0] - inset) * scale, w = n[2] * scale, h = n[3] * scale
        let y = 1024 - (n[1] - inset) * scale - h
        context.setShouldAntialias(false)   // pixels meet without seams
        context.fill(CGRect(x: x.rounded(), y: y.rounded(), width: w.rounded(), height: h.rounded()))
    }
}
let iconDir = assets.appendingPathComponent("AppIcon.appiconset")
try! FileManager.default.createDirectory(at: iconDir, withIntermediateDirectories: true)
let destination = CGImageDestinationCreateWithURL(iconDir.appendingPathComponent("AppIcon.png") as CFURL, "public.png" as CFString, 1, nil)!
CGImageDestinationAddImage(destination, context.makeImage()!, nil)
guard CGImageDestinationFinalize(destination) else { fatalError("cannot write AppIcon.png") }
write("""
{
  "images" : [ { "filename" : "AppIcon.png", "idiom" : "universal", "platform" : "ios", "size" : "1024x1024" } ],
  "info" : { "author" : "gen-icons", "version" : 1 }
}

""", iconDir.appendingPathComponent("Contents.json"))

// Launch screen colour: the ink / paper background from the design tokens.
let tokens = try! JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("shared/design/tokens.json"))) as! [String: Any]
func components(_ hex: String) -> String {
    let v = Int(hex.dropFirst(), radix: 16)!
    return String(format: #""red" : "0x%02X", "green" : "0x%02X", "blue" : "0x%02X", "alpha" : "1.000""#, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF)
}
let light = (tokens["light"] as! [String: String])["background"]!
let dark = (tokens["dark"] as! [String: String])["background"]!
write("""
{
  "colors" : [
    { "color" : { "color-space" : "srgb", "components" : { \(components(light)) } }, "idiom" : "universal" },
    { "appearances" : [ { "appearance" : "luminosity", "value" : "dark" } ],
      "color" : { "color-space" : "srgb", "components" : { \(components(dark)) } }, "idiom" : "universal" }
  ],
  "info" : { "author" : "gen-icons", "version" : 1 }
}

""", assets.appendingPathComponent("LaunchBackground.colorset/Contents.json"))

write("""
{
  "info" : { "author" : "gen-icons", "version" : 1 }
}

""", assets.appendingPathComponent("Contents.json"))
print("gen-icons: wrote \(assets.path)")
