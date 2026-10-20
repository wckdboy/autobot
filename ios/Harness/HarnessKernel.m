//
//  HarnessKernel.m
//  Autobot
//
//  Boot port of iSH's AppDelegate boot sequence (GPL-3.0, iSH project). Only the
//  parts the dsh guest needs are kept: no terminal window, no scene setup, no
//  clipboard/location character devices (deliberately removed by dsh-ios).
//

#include <resolv.h>
#include <arpa/inet.h>
#include <netdb.h>
#import <SystemConfiguration/SystemConfiguration.h>

#import "HarnessKernel.h"
#import "AppDelegate.h"
#import "CurrentRoot.h"
#import "LocationDevice.h"
#import "PasteboardDevice.h"
#import "Roots.h"
#import "SceneDelegate.h"
#import "Terminal.h"
#import "TerminalViewController.h"
#import "UserPreferences.h"

// iSH's iOSFS references the global terminal controller; without iSH's scene
// delegate it is simply absent.
TerminalViewController *currentTerminalViewController = NULL;
#include "kernel/init.h"
#include "kernel/calls.h"
#include "kernel/fs.h"
#include "kernel/task.h"
#include "fs/dyndev.h"
#include "fs/devices.h"
#include "fs/path.h"
#include "fs/tty.h"
#include "fs/sock.h"
#include "debug.h"

#if !ISH_LINUX
static void harness_handle_exit(struct task *task, int code) {
    // Only init and its children are interesting; this mirrors iSH's hook.
    if (task->parent != NULL && task->parent->parent != NULL) {
        return;
    }
    pid_t pid = task->pid;
    dispatch_async(dispatch_get_main_queue(), ^{
        [[NSNotificationCenter defaultCenter] postNotificationName:ProcessExitedNotification
                                                            object:nil
                                                          userInfo:@{@"pid": @(pid),
                                                                     @"code": @(code)}];
    });
}

static void harness_handle_die(const char *msg) {
    // iSH raises an NSException here; the harness just records the panic and
    // lets the supervisor restart the guest.
    NSLog(@"guest kernel died: %s", msg);
}
#endif

static int bootError;

// Defined here instead of iSH's AppDelegate, which Autobot does not compile.
NSString *const ProcessExitedNotification = @"ProcessExitedNotification";

@implementation HarnessKernel

+ (int)boot {
#if ISH_LINUX
    return -1;
#else
    NSURL *root = [Roots.instance rootUrl:Roots.instance.defaultRoot];
    int err = mount_root(&fakefs, [root URLByAppendingPathComponent:@"data"].fileSystemRepresentation);
    if (err < 0) {
        return err;
    }

    // become_first_process must come first so `current` is valid for mknod.
    err = become_first_process();
    if (err < 0) {
        return err;
    }

    FsInitialize();

    // Console and device nodes, exactly as iSH prepares them.
    generic_mknodat(AT_PWD, "/dev/tty1", S_IFCHR | 0666, dev_make(TTY_CONSOLE_MAJOR, 1));
    generic_mknodat(AT_PWD, "/dev/tty2", S_IFCHR | 0666, dev_make(TTY_CONSOLE_MAJOR, 2));
    generic_mknodat(AT_PWD, "/dev/tty3", S_IFCHR | 0666, dev_make(TTY_CONSOLE_MAJOR, 3));
    generic_mknodat(AT_PWD, "/dev/tty4", S_IFCHR | 0666, dev_make(TTY_CONSOLE_MAJOR, 4));
    generic_mknodat(AT_PWD, "/dev/tty5", S_IFCHR | 0666, dev_make(TTY_CONSOLE_MAJOR, 5));
    generic_mknodat(AT_PWD, "/dev/tty6", S_IFCHR | 0666, dev_make(TTY_CONSOLE_MAJOR, 6));
    generic_mknodat(AT_PWD, "/dev/tty7", S_IFCHR | 0666, dev_make(TTY_CONSOLE_MAJOR, 7));
    generic_mknodat(AT_PWD, "/dev/tty", S_IFCHR | 0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_TTY_MINOR));
    generic_mknodat(AT_PWD, "/dev/console", S_IFCHR | 0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_CONSOLE_MINOR));
    generic_mknodat(AT_PWD, "/dev/ptmx", S_IFCHR | 0666, dev_make(TTY_ALTERNATE_MAJOR, DEV_PTMX_MINOR));
    generic_mknodat(AT_PWD, "/dev/null", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_NULL_MINOR));
    generic_mknodat(AT_PWD, "/dev/zero", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_ZERO_MINOR));
    generic_mknodat(AT_PWD, "/dev/full", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_FULL_MINOR));
    generic_mknodat(AT_PWD, "/dev/random", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_RANDOM_MINOR));
    generic_mknodat(AT_PWD, "/dev/urandom", S_IFCHR | 0666, dev_make(MEM_MAJOR, DEV_URANDOM_MINOR));
    generic_mkdirat(AT_PWD, "/dev/pts", 0755);
    generic_setattrat(AT_PWD, "/", (struct attr) {.type = attr_mode, .mode = 0755}, false);

    // dsh-ios deliberately removes iSH's clipboard/location nodes: the gated
    // equivalents live on the host bridge. Unlinking absent nodes is harmless.
    (void) clipboard_dev;
    (void) location_dev;
    generic_unlinkat(AT_PWD, "/dev/clipboard");
    generic_unlinkat(AT_PWD, "/dev/location");

    do_mount(&procfs, "proc", "/proc", "", 0);
    do_mount(&devptsfs, "devpts", "/dev/pts", "", 0);

    [self configureDns];

    exit_hook = harness_handle_exit;
    die_handler = harness_handle_die;
#if !TARGET_OS_SIMULATOR
    NSString *sockTmp = [NSTemporaryDirectory() stringByAppendingString:@"ishsock"];
    sock_tmp_prefix = strdup(sockTmp.UTF8String);
#endif

    tty_drivers[TTY_CONSOLE_MAJOR] = &ios_console_driver;
    set_console_device(TTY_CONSOLE_MAJOR, 1);
    err = create_stdio("/dev/console", TTY_CONSOLE_MAJOR, 1);
    if (err < 0) {
        return err;
    }

    NSArray<NSString *> *command = UserPreferences.shared.bootCommand;
    if (command.count == 0) {
        command = @[@"/sbin/init"];
    }
    char argv[4096];
    [Terminal convertCommand:command toArgs:argv limitSize:sizeof(argv)];
    const char *envp =
        "TERM=xterm-256color\0"
        "HOME=/root\0"
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\0"
#if defined(GUEST_ARM64)
        "PYTHONMALLOC=malloc\0"
#endif
        ;
    err = do_execve(command[0].UTF8String, command.count, argv, envp);
    if (err < 0) {
        return err;
    }
    task_start(current);
    bootError = 0;
    return 0;
#endif
}

+ (void)configureDns {
#if !ISH_LINUX
    struct __res_state res;
    if (EXIT_SUCCESS != res_ninit(&res)) {
        return;
    }
    NSMutableString *resolvConf = [NSMutableString new];
    if (res.dnsrch[0] != NULL) {
        [resolvConf appendString:@"search"];
        for (int i = 0; res.dnsrch[i] != NULL; i++) {
            [resolvConf appendFormat:@" %s", res.dnsrch[i]];
        }
        [resolvConf appendString:@"\n"];
    }
    union res_sockaddr_union servers[NI_MAXSERV];
    int serversFound = res_getservers(&res, servers, NI_MAXSERV);
    char address[NI_MAXHOST];
    for (int i = 0; i < serversFound; i++) {
        union res_sockaddr_union s = servers[i];
        if (s.sin.sin_len == 0) {
            continue;
        }
        getnameinfo((struct sockaddr *) &s.sin, s.sin.sin_len,
                    address, sizeof(address), NULL, 0, NI_NUMERICHOST);
        [resolvConf appendFormat:@"nameserver %s\n", address];
    }
    current = pid_get_task(1);
    struct fd *fd = generic_open("/etc/resolv.conf", O_WRONLY_ | O_CREAT_ | O_TRUNC_, 0666);
    if (!IS_ERR(fd)) {
        fd->ops->write(fd, resolvConf.UTF8String, [resolvConf lengthOfBytesUsingEncoding:NSUTF8StringEncoding]);
        fd_close(fd);
    }
#endif
}

@end
