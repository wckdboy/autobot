#!/usr/bin/env ruby
# Generates ios/Harness/HarnessSpec.yml, the XcodeGen fragment that compiles the
# dsh-ios harness (iSH-ARM64 emulator sources + the dsh supervisor) into the
# Autobot app target.
#
# The iSH-ARM64 file list and its MIG build rule are read from the vendored
# iSH.xcodeproj so the integration follows upstream without manual bookkeeping.
#
# Usage: ruby ios/Harness/gen-harness-spec.rb   (run before `xcodegen generate`)

require 'xcodeproj'
require 'yaml'

HERE = File.expand_path(__dir__)
IOS = File.expand_path('..', HERE)
DSH = File.join(HERE, 'dsh-ios')
ISH = File.join(DSH, 'ish-arm64')
OUT = File.join(HERE, 'HarnessSpec.yml')

abort "iSH-ARM64 not vendored at #{ISH}" unless File.exist?(File.join(ISH, 'iSH.xcodeproj'))

project = Xcodeproj::Project.open(File.join(ISH, 'iSH.xcodeproj'))
target = project.targets.find { |t| t.name == 'iSH-ARM64' } or abort 'iSH-ARM64 target missing'

# Everything the iSH-ARM64 app target compiles, minus its entry points and the
# UIKit pieces Autobot replaces with its own SwiftUI surface.
ISH_EXCLUDES = %w[
  app/main.m
  app/AppDelegate.m
  app/SceneDelegate.m
  app/TerminalViewController.m
].freeze

rel = lambda do |ref|
  Pathname.new(ref.real_path.to_s).relative_path_from(Pathname.new(ISH)).to_s
end

ish_sources = target.source_build_phase.files.reject { |bf| bf.file_ref.source_tree == 'DEVELOPER_DIR' }
  .map { |bf| rel.call(bf.file_ref) }
  .reject { |p| ISH_EXCLUDES.include?(p) || p.end_with?('main.m') }
ish_sources << 'app/ISHShellExecutor.m' unless ish_sources.include?('app/ISHShellExecutor.m')

ish_resources = target.resources_build_phase.files.flat_map do |bf|
  ref = bf.file_ref
  if ref.isa == 'PBXVariantGroup'
    ref.children.map { |c| rel.call(c) }
  else
    [rel.call(ref)]
  end
end.uniq
ish_resources << 'app/RootfsPatch.bundle' unless ish_resources.include?('app/RootfsPatch.bundle')

frameworks = target.frameworks_build_phase.files.map(&:file_ref)
  .select { |r| r.isa == 'PBXFileReference' }.map(&:path).uniq

# dsh supervisor files that Autobot's SwiftUI harness uses. The ObjC UIKit
# screens of dsh-ios (root controller, overlays, log view, capabilities list)
# are replaced by the native Liquid Glass screen.
DSH_EXCLUDES = %w[
  DSHMain.m DSHAppDelegate.h DSHAppDelegate.m DSHSceneDelegate.h DSHSceneDelegate.m
  DSHRootViewController.h DSHRootViewController.m DSHStatusOverlayView.h DSHStatusOverlayView.m
  DSHLogViewController.h DSHLogViewController.m
  DSHActivityViewController.h DSHActivityViewController.m DSHCapabilitiesViewController.h
  DSHCapabilitiesViewController.m
  # HealthKit requires the com.apple.developer.healthkit capability, which this app
  # does not declare; linking it makes App Store processing reject the binary.
  # The harness works without the health capability.
  DSHHealthCapability.h DSHHealthCapability.m
  Info.plist DSH.entitlements AppDSH.xcconfig
  DSHAssets.xcassets DSHLaunchScreen.storyboard PrivacyInfo.xcprivacy
].freeze

# mach_exc exception server: pre-generated from the SDK's mach_exc.defs with
# `mig -sheader mach_excServer.h -server mach_excServer.c -isysroot $(macosx sdk)`.
# Kept as sources instead of a MIG build rule so the ordering is deterministic.

spec = {
  'projectReferences' => {
    'iSHLibarchive' => { 'path' => 'Harness/dsh-ios/ish-arm64/deps/libarchive.xcodeproj' },
  },
  'targets' => {
    'Autobot' => {
      'sources' => [
        { 'path' => 'Harness/dsh-ios/app', 'excludes' => DSH_EXCLUDES },
        { 'path' => 'Harness/HarnessKernel.h' },
        { 'path' => 'Harness/HarnessKernel.m' },
        { 'path' => 'Harness/mach_excServer.c' },
        { 'path' => 'Harness/mach_excServer.h' },
      ] + ish_sources.map { |p| { 'path' => "Harness/dsh-ios/ish-arm64/#{p}" } } + ish_resources.map { |p| { 'path' => "Harness/dsh-ios/ish-arm64/#{p}", 'buildPhase' => 'resources' } },
      'dependencies' => [
        { 'target' => 'iSHLibarchive/libarchive', 'link' => true, 'embed' => false },
        { 'sdk' => 'WebKit.framework' },
        { 'sdk' => 'EventKit.framework' },
        { 'sdk' => 'Contacts.framework' },
        { 'sdk' => 'UserNotifications.framework' },
        { 'sdk' => 'UniformTypeIdentifiers.framework' },
        { 'sdk' => 'PhotosUI.framework' },
        { 'sdk' => 'Photos.framework' },
      ] + frameworks.map { |f| { 'sdk' => f } },
      'preBuildScripts' => [
        {
          'name' => 'Build Meson (iSH-ARM64)',
          'basedOnDependencyAnalysis' => false,
          'script' => <<~SH,
            export ISH_SRC="$SRCROOT/Harness/dsh-ios/ish-arm64"
            export SRCROOT="$ISH_SRC"
            export MESON_BUILD_DIR="$CONFIGURATION_BUILD_DIR/meson"
            export GUEST_ARCH=arm64
            export MESON_GUEST_OPTION="-Dguest_arch=arm64"
            export ISH_LOGGER=nslog
            if [ ! -d "$ISH_SRC" ]; then echo "warning: iSH sources missing, skipping harness emulator"; exit 0; fi
            "$ISH_SRC/app/xcode-meson.sh"
            cd "$MESON_BUILD_DIR"
            "$ISH_SRC/app/xcode-ninja.sh" $NINJA_TARGETS
          SH
        },
        {
          'name' => 'Compile hterm JavaScript',
          'basedOnDependencyAnalysis' => false,
          'script' => <<~SH,
            cd "$SRCROOT/Harness/dsh-ios/ish-arm64/deps/libapps" && ./hterm/bin/mkdist
          SH
        },
      ],
      'postCompileScripts' => [
        {
          'name' => 'Copy DSH Root',
          'basedOnDependencyAnalysis' => false,
          'script' => <<~SH,
            set -e
            if [ ! -f "$DSH_ROOTFS_TARBALL" ]; then
              echo "warning: $DSH_ROOTFS_TARBALL not found. Run: make -C $SRCROOT/Harness/dsh-ios rootfs" >&2
              exit 0
            fi
            cp "$DSH_ROOTFS_TARBALL" "$BUILT_PRODUCTS_DIR/$CONTENTS_FOLDER_PATH/root.tar.gz"
            shasum -a 256 "$DSH_ROOTFS_TARBALL" | cut -d' ' -f1 > "$BUILT_PRODUCTS_DIR/$CONTENTS_FOLDER_PATH/root.tar.gz.sha256"
          SH
        },
        {
          'name' => 'Generate APK Repositories File',
          'basedOnDependencyAnalysis' => false,
          'script' => 'cd "$SRCROOT/Harness/dsh-ios" && python3 ish-arm64/app/gen_apk_repositories.py',
        },
      ],
      'settings' => {
        'base' => {
          'SWIFT_OBJC_BRIDGING_HEADER' => 'App/Harness/AutobotHarness-Bridging-Header.h',
          # AppGroup reads _mh_execute_header, which only exists in a real
          # executable; the debug-dylib stub has none.
          'ENABLE_DEBUG_DYLIB' => 'NO',
          'GCC_PREPROCESSOR_DEFINITIONS' => ['$(inherited)', 'DSH_APP=1', 'GUEST_ARM64=1'],
          'HEADER_SEARCH_PATHS' => [
            '$(inherited)',
            'Harness/dsh-ios/ish-arm64',
            'Harness/dsh-ios/ish-arm64/app',
            'Harness/dsh-ios/ish-arm64/deps/libarchive/libarchive',
            'Harness/dsh-ios/app',
            'Harness',
          ],
          'OTHER_LDFLAGS' => [
            '$(inherited)',
            '$(MESON_BUILD_DIR)/libish.a',
            '$(MESON_BUILD_DIR)/libish_emu.a',
            '$(MESON_BUILD_DIR)/libfakefs.a',
            '-ObjC',
            '-u',
            '_accessibilityfixes_init',
          ],
          'MESON_BUILD_DIR' => '$(CONFIGURATION_BUILD_DIR)/meson',
          'NINJA_TARGETS' => 'libish.a libish_emu.a libfakefs.a',
          'GUEST_ARCH' => 'arm64',
          'MESON_GUEST_OPTION' => '-Dguest_arch=arm64',
          'DSH_ROOTFS_TARBALL' => '$(SRCROOT)/Harness/dsh-ios/build/root.tar.gz',
          'CLANG_CXX_LANGUAGE_STANDARD' => 'gnu++14',
          'CLANG_CXX_LIBRARY' => 'libc++',
          'GCC_C_LANGUAGE_STANDARD' => 'gnu11',
          'CLANG_ENABLE_MODULES' => 'YES',
          'CLANG_ENABLE_OBJC_ARC' => 'YES',
          'CLANG_ENABLE_OBJC_WEAK' => 'YES',
        },
        'configs' => {
          'Debug' => {
            'GCC_PREPROCESSOR_DEFINITIONS' => ['$(inherited)', 'DSH_APP=1', 'GUEST_ARM64=1', 'DEBUG=1'],
          },
        },
      },
    },
  },
}

header = <<~YAML
  # GENERATED by ios/Harness/gen-harness-spec.rb — do not edit by hand.
  # Sources: ios/Harness/dsh-ios (GPL-3.0, everettjf/dsh-ios + iSH-ARM64).
YAML
File.write(OUT, header + YAML.dump(spec))
puts "wrote #{OUT}: #{ish_sources.size} iSH sources, #{ish_resources.size} iSH resources, #{frameworks.size} system libs"
