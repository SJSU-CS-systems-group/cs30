<#
  harden.ps1 - self-contained kiosk installer. Run from an ADMIN PowerShell,
  with the target user SIGNED OUT.

  Installs the kiosk shell watchdog (embedded below) into the target user's home
  directory, sets it as that user's per-user shell (overrides the machine shell),
  and locks the session down. All registry writes go through reg.exe against the
  user's offline hive, which is the only reliable way to make them persist.

  Only the installed watchdog needs to persist; this installer can be deleted after.
#>
param(
    [string]   $KioskUser,
    [string]   $HomeUrl        = 'https://example.com/your-sign',
    [string[]] $AllowedDomains = @('example.com'),
    [switch]   $Help
)

function Show-Usage {
@"
Kiosk installer - sets up a locked-down fullscreen-browser kiosk for a user.

USAGE (elevated / Administrator PowerShell, with the kiosk user SIGNED OUT):
  harden.ps1 -KioskUser <name> [-HomeUrl <url>] [-AllowedDomains <d1>,<d2>,...]

PARAMETERS:
  -KioskUser        (required) Local account that runs the kiosk.
                    Must have logged in once (so its profile exists) and be
                    signed out when you run this.
  -HomeUrl          Page the sign displays.  Default: https://example.com/your-sign
  -AllowedDomains   Domains the browser may visit. A bare domain also allows its
                    subdomains; glob wildcards (*.x.com) are NOT supported.
                    Default: example.com

EXAMPLE:
  powershell -ExecutionPolicy Bypass -File .\harden.ps1 ``
      -KioskUser SignageUser ``
      -HomeUrl https://signage.corp/board ``
      -AllowedDomains signage.corp,cdn.corp
"@ | Write-Host
}

# ---- Validate invocation ----
if ($Help) { Show-Usage; exit 0 }
if ([string]::IsNullOrWhiteSpace($KioskUser)) {
    Write-Host "ERROR: -KioskUser is required.`n"; Show-Usage; exit 1
}
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
         ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host "ERROR: Run this from an elevated (Administrator) PowerShell."; exit 1
}

# --- turn off F9 and right click ---
$path = "HKLM:\SOFTWARE\Policies\Microsoft\Edge"
If (!(Test-Path $path)) {
    New-Item -Path $path -Force | Out-Null
}
New-ItemProperty -Path $path -Name "ConfigureKeyboardShortcuts" -Value '{"disabled":["immersive_reader_toggle"]}' -PropertyType String -Force

# Define the Registry Path for Edge Policies
$edgePolicyPath = "HKLM:\SOFTWARE\Policies\Microsoft\Edge"

# Create the key if it doesn't exist
if (-not (Test-Path $edgePolicyPath)) {
    New-Item -Path $edgePolicyPath -Force | Out-Null
}

# 1. Disable the standard right-click context menu in Edge
Set-ItemProperty -Path $edgePolicyPath -Name "DefaultContextMenuEnabled" -Value 0 -Type DWord

# 2. Disable the text-selection mini menu (highly recommended for Kiosks)
Set-ItemProperty -Path $edgePolicyPath -Name "QuickSearchShowMiniMenu" -Value 0 -Type DWord

Write-Host "Edge Kiosk context menus disabled. Please restart Microsoft Edge to apply." -ForegroundColor Green

# ================= Embedded kiosk watchdog (installed into the user's home) =================
# __HOME_URL__ is replaced with -HomeUrl at install time.
$KioskWatchdog = @'
# kiosk.ps1 - kiosk shell watchdog (installed by harden.ps1)
$ErrorActionPreference = 'SilentlyContinue'

$HomeUrl    = '__HOME_URL__'
$EdgeExe    = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
if (-not (Test-Path $EdgeExe)) { $EdgeExe = "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe" }
$ProfileDir = "$env:LOCALAPPDATA\KioskEdgeProfile"

function Clear-Session {
    Get-Process msedge -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 600
    Remove-Item -LiteralPath $ProfileDir -Recurse -Force -ErrorAction SilentlyContinue
}

function Start-Kiosk {
    $a = @(
        '--kiosk', $HomeUrl,
        '--edge-kiosk-type=fullscreen',
        "--user-data-dir=$ProfileDir",
        '--no-first-run','--no-default-browser-check',
        '--disable-session-crashed-bubble','--hide-crash-restore-bubble',
        '--kiosk-idle-timeout-minutes=0','--overscroll-history-navigation=0'
    )
    return Start-Process -FilePath $EdgeExe -ArgumentList $a -PassThru
}

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Hk {
  [DllImport("user32.dll")] public static extern bool RegisterHotKey(IntPtr h,int id,uint mod,uint vk);
  [DllImport("user32.dll")] public static extern bool UnregisterHotKey(IntPtr h,int id);
  [StructLayout(LayoutKind.Sequential)] public struct MSG {
    public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam;
    public uint time; public int x; public int y; }
  [DllImport("user32.dll")] public static extern bool PeekMessage(out MSG m,IntPtr h,uint a,uint b,uint c);
}
public class KioskHook {
  const int WH_KEYBOARD_LL=13, WM_KEYDOWN=0x100, WM_SYSKEYDOWN=0x104;
  const int VK_TAB=0x09, VK_ESCAPE=0x1B, VK_LWIN=0x5B, VK_RWIN=0x5C, VK_F4=0x73, VK_CONTROL=0x11, VK_MENU=0x12;
  [StructLayout(LayoutKind.Sequential)] public struct KBD { public uint vkCode; public uint scanCode; public uint flags; public uint time; public IntPtr extra; }
  public delegate IntPtr Proc(int n, IntPtr w, IntPtr l);
  static Proc _proc = HookCallback;
  static IntPtr _hook = IntPtr.Zero;
  [DllImport("user32.dll")] static extern IntPtr SetWindowsHookEx(int id, Proc cb, IntPtr hMod, uint th);
  [DllImport("user32.dll")] static extern bool UnhookWindowsHookEx(IntPtr hk);
  [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr hk, int n, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] static extern short GetAsyncKeyState(int v);
  [DllImport("kernel32.dll")] static extern IntPtr GetModuleHandle(string s);
  [DllImport("kernel32.dll")] static extern IntPtr GetConsoleWindow();
  [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr h, int c);
  public static void HideConsole() { IntPtr h=GetConsoleWindow(); if (h!=IntPtr.Zero) ShowWindow(h,0); }
  public static void Install() { if (_hook==IntPtr.Zero) _hook=SetWindowsHookEx(WH_KEYBOARD_LL,_proc,GetModuleHandle(null),0); }
  public static void Uninstall() { if (_hook!=IntPtr.Zero){ UnhookWindowsHookEx(_hook); _hook=IntPtr.Zero; } }
  static IntPtr HookCallback(int n, IntPtr w, IntPtr l) {
    if (n>=0) {
      int m=w.ToInt32();
      if (m==WM_KEYDOWN || m==WM_SYSKEYDOWN) {
        KBD k=(KBD)Marshal.PtrToStructure(l, typeof(KBD));
        int vk=(int)k.vkCode;
        bool alt=(GetAsyncKeyState(VK_MENU)&0x8000)!=0;
        bool ctrl=(GetAsyncKeyState(VK_CONTROL)&0x8000)!=0;
        if (vk==VK_LWIN || vk==VK_RWIN) return (IntPtr)1;        // Windows keys
        if (alt && vk==VK_TAB) return (IntPtr)1;                 // Alt+Tab
        if (alt && !ctrl && vk==VK_ESCAPE) return (IntPtr)1;     // Alt+Esc
        if (ctrl && !alt && vk==VK_ESCAPE) return (IntPtr)1;     // Ctrl+Esc (Ctrl+Alt+Esc still passes through)
        if (alt && vk==VK_F4) return (IntPtr)1;                  // Alt+F4
      }
    }
    return CallNextHookEx(_hook, n, w, l);
  }
}
"@

[KioskHook]::HideConsole()

$MOD_ALT=0x1; $MOD_CTRL=0x2; $VK_ESC=0x1B; $VK_W=0x57
$WM_HOTKEY=0x0312; $PM_REMOVE=0x1
$HK_SIGNOUT=1; $HK_RESET=2

[Hk]::RegisterHotKey([IntPtr]::Zero, $HK_SIGNOUT, ($MOD_ALT -bor $MOD_CTRL), $VK_ESC) | Out-Null  # Ctrl+Alt+Esc
[Hk]::RegisterHotKey([IntPtr]::Zero, $HK_RESET,   $MOD_CTRL,                 $VK_W)   | Out-Null  # Ctrl+W
[KioskHook]::Install()                                                                            # block Alt+Tab / Alt+Esc / Ctrl+Esc / Win / Alt+F4

Clear-Session
$edge = Start-Kiosk

$msg = New-Object Hk+MSG
$tick = 0
while ($true) {
    while ([Hk]::PeekMessage([ref]$msg, [IntPtr]::Zero, 0, 0, $PM_REMOVE)) {
        if ($msg.message -eq $WM_HOTKEY) {
            switch ([int]$msg.wParam) {
                $HK_SIGNOUT {                       # Ctrl+Alt+Esc: clear + sign out
                    Clear-Session
                    # [KioskHook]::Uninstall()
                    # [Hk]::UnregisterHotKey([IntPtr]::Zero, $HK_SIGNOUT) | Out-Null
                    # [Hk]::UnregisterHotKey([IntPtr]::Zero, $HK_RESET)   | Out-Null
                    # Start-Process shutdown -ArgumentList '/l' -WindowStyle Hidden
                    break
                }
                $HK_RESET {                         # Ctrl+W: clear + sign out
                    Clear-Session
                    # [KioskHook]::Uninstall()
                    # [Hk]::UnregisterHotKey([IntPtr]::Zero, $HK_SIGNOUT) | Out-Null
                    # [Hk]::UnregisterHotKey([IntPtr]::Zero, $HK_RESET)   | Out-Null
                    # Start-Process shutdown -ArgumentList '/l' -WindowStyle Hidden
                    break
                }
            }
        }
    }
    # Pump messages every ~10ms so the low-level hook stays responsive (well under the
    # ~300ms LowLevelHooksTimeout); only poll Edge for exit about twice a second.
    if (($tick % 50) -eq 0 -and ($null -eq $edge -or $edge.HasExited)) { $edge = Start-Kiosk }
    $tick++
    Start-Sleep -Milliseconds 10
}
'@
# ==========================================================================================

# ---- Resolve the kiosk user's SID and home directory ----
try {
    $sid  = (New-Object System.Security.Principal.NTAccount($KioskUser)
            ).Translate([System.Security.Principal.SecurityIdentifier]).Value
    $hdir = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\$sid" -ErrorAction Stop).ProfileImagePath
} catch { Write-Host "ERROR: Could not resolve '$KioskUser' or its profile. Has it logged in once?"; exit 1 }

# ---- Require the user to be signed out (writing to a live hive does not persist) ----
if (Test-Path "Registry::HKEY_USERS\$sid") {
    Write-Host "ERROR: '$KioskUser' is signed in. Sign them out, then rerun."; exit 1
}

# ---- Install the watchdog into the user's home directory ----
$dest = Join-Path $hdir 'kiosk.ps1'
$KioskWatchdog.Replace('__HOME_URL__', $HomeUrl) | Set-Content -LiteralPath $dest -Encoding UTF8
Write-Host "Installed watchdog -> $dest"

# ---- Apply registry policies via reg.exe against the offline hive ----
$ErrorActionPreference = 'Continue'   # reg.exe writes benign stderr; don't let it abort us
$m   = 'HKU\KHrd'
$dat = Join-Path $hdir 'NTUSER.DAT'
function Unload-Hive { if (Test-Path 'Registry::HKEY_USERS\KHrd') { reg unload $m 2>&1 | Out-Null } }
function RA($subkey, $name, $type, $data) { reg add "$m\$subkey" /v $name /t $type /d $data /f 2>&1 | Out-Null }

Unload-Hive
reg load $m "$dat" 2>&1 | Out-Null
if (-not (Test-Path 'Registry::HKEY_USERS\KHrd')) { Write-Host "ERROR: reg load failed for $dat"; exit 1 }

try {
    # ---- Edge: lock navigation to your domain(s), kill launch/escape features ----
    $edge = 'Software\Policies\Microsoft\Edge'
    RA "$edge\URLBlocklist" '1' REG_SZ '*'                          # block everything...
    $i = 1; foreach ($d in $AllowedDomains) { RA "$edge\URLAllowlist" "$i" REG_SZ $d; $i++ }   # ...except your site
    RA $edge 'DownloadRestrictions'       REG_DWORD 3               # block all downloads
    RA $edge 'DeveloperToolsAvailability' REG_DWORD 2               # no F12 / DevTools
    RA $edge 'InPrivateModeAvailability'  REG_DWORD 1               # no InPrivate
    RA $edge 'BrowserGuestModeEnabled'    REG_DWORD 0
    RA $edge 'DefaultPopupsSetting'       REG_DWORD 1               # ALLOW popups
    RA $edge 'PrintingEnabled'            REG_DWORD 0
    RA $edge 'PasswordManagerEnabled'     REG_DWORD 0
    RA $edge 'AutofillAddressEnabled'     REG_DWORD 0

    # ---- Windows: per-user shell replacement + close escape hatches ----
    $sys = 'Software\Microsoft\Windows\CurrentVersion\Policies\System'
    $shellCmd = 'powershell.exe -ExecutionPolicy Bypass -WindowStyle Hidden -File \"' + $dest + '\"'
    RA $sys 'Shell' REG_SZ $shellCmd
    RA $sys 'DisableTaskMgr'         REG_DWORD 1                    # no Task Manager (also from Ctrl+Alt+Del)
    RA $sys 'DisableLockWorkstation' REG_DWORD 1                    # no Win+L lock
    RA $sys 'DisableChangePassword'  REG_DWORD 1

    $exp = 'Software\Microsoft\Windows\CurrentVersion\Policies\Explorer'
    RA $exp 'NoWinKeys' REG_DWORD 1                                 # disable Win-key combos

    # ---- Verify the shell policy actually landed ----
    $check = reg query "$m\$sys" /v Shell 2>&1 | Out-String
    if ($check -match [regex]::Escape($dest)) {
        Write-Host "Verified: per-user shell set for '$KioskUser'."
    } else {
        Write-Host "WARNING: shell policy readback did not match. Output:"; Write-Host $check
    }
}
finally {
    [gc]::Collect(); [gc]::WaitForPendingFinalizers()
    Unload-Hive
}

Write-Host "`nDone. Sign in as '$KioskUser' to start the kiosk (HomeUrl: $HomeUrl)."
Write-Host "Allowed domains: $($AllowedDomains -join ', ')"
