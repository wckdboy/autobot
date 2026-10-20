//
//  HarnessKernel.h
//  Autobot
//
//  Boots the iSH-ARM64 emulator kernel for the dsh guest. This is a port of the
//  boot path from iSH's AppDelegate, kept here so the Autobot app can boot the
//  Linux guest without adopting iSH's UIKit application delegate.
//

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface HarnessKernel : NSObject

/// Mounts the guest root, starts init and execs the configured boot command.
/// Returns 0 on success or a negative errno-style code. Must be called exactly
/// once, from a non-main thread (the kernel runs on the calling thread).
+ (int)boot;

/// Writes the host's DNS resolvers into the guest /etc/resolv.conf.
+ (void)configureDns;

@end

NS_ASSUME_NONNULL_END
