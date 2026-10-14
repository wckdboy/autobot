// swift-tools-version: 6.0
// On-device engines for the Autobot iOS app: llama.cpp (chat, Metal), whisper.cpp (dictation,
// Metal + Core ML encoder) and Core ML Stable Diffusion (images, Neural Engine). The native
// xcframeworks are built locally by scripts/build-xcframeworks.sh before the package resolves.
import PackageDescription

let package = Package(
    name: "AutobotEngines",
    platforms: [.iOS(.v18)],
    products: [
        .library(name: "AutobotEngines", targets: ["AutobotEngines"]),
    ],
    dependencies: [
        // MIT (Apple). Pinned: model folders and APIs change between releases.
        .package(url: "https://github.com/apple/ml-stable-diffusion.git", exact: "1.1.1"),
    ],
    targets: [
        .binaryTarget(name: "AutobotLlama", path: "Frameworks/AutobotLlama.xcframework"),
        .binaryTarget(name: "AutobotWhisper", path: "Frameworks/AutobotWhisper.xcframework"),
        .target(
            name: "AutobotEngines",
            dependencies: [
                "AutobotLlama",
                "AutobotWhisper",
                .product(name: "StableDiffusion", package: "ml-stable-diffusion"),
            ],
        ),
        .testTarget(
            name: "AutobotEnginesTests",
            dependencies: ["AutobotEngines"],
        ),
    ],
)
