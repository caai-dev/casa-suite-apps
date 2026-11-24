using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using CasaApps;
using Microsoft.Toolkit.Uwp.Notifications;
using Microsoft.Win32;

namespace Casana;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // One Casana window: opening it again (Start menu, desktop, or a casana://
        // link from CASA Suite) brings the running window to the front.
        using var single = new Mutex(true, @"Local\CasaAuditGroup.Casana", out var first);
        if (!first)
        {
            // The running Casana may be hidden in the tray (no window handle to bring forward): ask it to show itself.
            try
            {
                using var show = EventWaitHandle.OpenExisting(MainForm.ShowSignalName);
                show.Set();
            }
            catch
            {
                Native.ShowExisting();
            }
            return;
        }

        // A click on a Casana toast (popup or Notification Center) opens its link in Casana (19 Nov 2026).
        // Windows calls this in the running app; if Casana was quit, Windows starts it and then calls this.
        ToastNotificationManagerCompat.OnActivated += toast =>
        {
            string? url = null;
            try { ToastArguments.Parse(toast.Argument).TryGetValue("url", out url); } catch { }
            MainForm.OpenFromToast(url);
        };

        // An error never shows .NET's "Unhandled exception" box (24 Nov 2026: Continue there left Casana stuck in the
        // tray with no page). It is written to %LocalAppData%\Casana\errors.log instead.
        Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);
        Application.ThreadException += (_, e) => MainForm.LogError("ui", e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => MainForm.LogError("app", e.ExceptionObject as Exception);

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(args));
    }
}

internal static class Native
{
    [DllImport("user32.dll")] private static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] private static extern bool IsIconic(IntPtr hWnd);

    public static void ShowExisting()
    {
        var me = Process.GetCurrentProcess();
        foreach (var p in Process.GetProcessesByName(me.ProcessName))
        {
            if (p.Id == me.Id || p.MainWindowHandle == IntPtr.Zero) continue;
            if (IsIconic(p.MainWindowHandle)) ShowWindow(p.MainWindowHandle, 9); // SW_RESTORE
            SetForegroundWindow(p.MainWindowHandle);
            return;
        }
    }
}

internal sealed class MainForm : Form
{
    private const string Host = "apex-casa-workdesk.apper.so";
    private const string LiveUrl = "https://" + Host;

    public const string ShowSignalName = @"Local\CasaAuditGroup.Casana.Show";

    private WebView2 _web = new() { Dock = DockStyle.Fill };
    // The page area is ready (WebView2 started). Start is tried up to 3 times (24 Nov 2026).
    private bool _webReady;
    private bool _starting;
    // The person opened the window (tray, Start menu, toast) while Casana was still starting hidden.
    private bool _userShown;
    private readonly string _startUrl;
    private Updater? _updater;

    // Tray icon: closing the window keeps Casana running here, so notifications still arrive (18 Nov 2026).
    private readonly NotifyIcon _tray = new();
    private string _notifyUrl = LiveUrl;
    private bool _quitting;
    private bool _toldAboutTray;
    private readonly EventWaitHandle _showSignal = new(false, EventResetMode.AutoReset, ShowSignalName);

    // The running window, for toast clicks (they arrive on another thread). A link clicked before the page is ready waits here.
    private static MainForm? _instance;

    // Started by Windows at sign-in ("--tray"): open hidden in the tray, no window (19 Nov 2026).
    private readonly bool _startHidden;
    private static string? _pendingUrl;

    public MainForm(string[] args)
    {
        _startUrl = StartUrlFrom(args);
        _instance = this;
        _startHidden = args.Any(a => string.Equals(a, "--tray", StringComparison.OrdinalIgnoreCase));
        // 24 Nov 2026 (launch day): the window used to be hidden and ShowInTaskbar switched while WebView2 was still
        // starting. Changing ShowInTaskbar makes Windows build the window again, which aborted WebView2's start
        // (Operation aborted, E_ABORT): .NET's error box, then a tray icon with no page. Now the window only goes
        // invisible (Opacity 0) and is hidden after WebView2 has started; ShowInTaskbar is never changed.
        if (_startHidden) Opacity = 0;
        RegisterStartup();
        Text = "Casana";
        Width = 1360;
        Height = 860;
        StartPosition = FormStartPosition.CenterScreen;
        // Full-size window icon (the taskbar shows it at 32-48 px); the small 32 px one looked blurry.
        try { Icon = Icon.ExtractIcon(Environment.ProcessPath!, 0, 256) ?? Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }
        Controls.Add(_web);
        Load += async (_, _) => await StartWebAsync();
        SetUpTray();
        FormClosing += OnFormClosing;
        // A second start of Casana (Start menu, CASA Suite) brings this window back.
        var waiter = new Thread(() =>
        {
            while (_showSignal.WaitOne())
            {
                try { BeginInvoke(ShowFromTray); } catch { return; }
            }
        }) { IsBackground = true };
        waiter.Start();
    }

    private void SetUpTray()
    {
        try { _tray.Icon = Icon.ExtractIcon(Environment.ProcessPath!, 0, SystemInformation.SmallIconSize.Width) ?? Icon ?? SystemIcons.Application; }
        catch { _tray.Icon = Icon ?? SystemIcons.Application; }
        _tray.Text = "Casana";
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Casana", null, (_, _) => ShowFromTray());
        menu.Items.Add("Quit Casana", null, (_, _) => Quit());
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => ShowFromTray();
        _tray.BalloonTipClicked += (_, _) =>
        {
            ShowFromTray();
            if (IsTrusted(_notifyUrl)) _web.CoreWebView2?.Navigate(_notifyUrl);
        };
        _tray.Visible = true;
    }

    // Casana starts with Windows, quietly in the tray (like Teams), so laptop notifications arrive without opening it.
    // Written by the app too (not only the installer), so copies started from another folder also get it. If the
    // person switched Casana off in Windows Settings > Apps > Startup, Windows keeps it off (a separate flag).
    private static void RegisterStartup()
    {
        try
        {
            var exe = Environment.ProcessPath;
            if (string.IsNullOrEmpty(exe)) return;
            var value = "\"" + exe + "\" --tray";
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            if (key == null) return;
            if (!string.Equals(key.GetValue("Casana") as string, value, StringComparison.OrdinalIgnoreCase))
                key.SetValue("Casana", value);
        }
        catch
        {
            // No access to the user's startup list: Casana just does not start with Windows.
        }
    }

    private void ShowFromTray()
    {
        _userShown = true;
        if (Opacity < 1) Opacity = 1;
        if (!Visible) Show();
        // The page did not start earlier (all tries failed): try again now that the person is looking.
        if (!_webReady && !_starting) _ = StartWebAsync();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
        BringToFront();
    }

    private void Quit()
    {
        _quitting = true;
        _tray.Visible = false;
        Application.Exit();
    }

    // The X button hides Casana to the tray (like WhatsApp desktop). Quit from the tray menu, a shutdown or an update closes it.
    private void OnFormClosing(object? sender, FormClosingEventArgs e)
    {
        if (_quitting || e.CloseReason != CloseReason.UserClosing)
        {
            _tray.Visible = false;
            return;
        }
        e.Cancel = true;
        Hide();
        if (!_toldAboutTray)
        {
            _toldAboutTray = true;
            ShowToast("Casana is still running", "You will keep getting notifications. Right-click the Casana icon in the tray to quit.", null);
        }
    }

    // A notification asked for by the Casana page: {"type":"casana-notify","title":"...","body":"...","url":"https://..."}.
    private void OnWebMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (!root.TryGetProperty("type", out var type) || type.GetString() != "casana-notify") return;
            var title = root.TryGetProperty("title", out var t) ? t.GetString() ?? "Casana" : "Casana";
            var body = root.TryGetProperty("body", out var b) ? b.GetString() ?? "" : "";
            var url = root.TryGetProperty("url", out var u) ? u.GetString() : null;
            _notifyUrl = IsTrusted(url) ? url! : LiveUrl;
            if (title.Length > 63) title = title[..60] + "...";
            if (body.Length > 250) body = body[..247] + "...";
            ShowToast(title, string.IsNullOrWhiteSpace(body) ? "Open Casana to see it." : body, _notifyUrl);
        }
        catch
        {
            // Not a notification message: ignore.
        }
    }

    // A Windows toast: a popup with sound that stays in Notification Center (19 Nov 2026). The old tray balloon went
    // only to Notification Center on Windows 11 (no popup); it stays as the fallback if toasts are not available.
    private void ShowToast(string title, string body, string? url)
    {
        try
        {
            var toast = new ToastContentBuilder().AddText(title).AddText(body);
            if (IsTrusted(url)) toast.AddArgument("url", url!);
            toast.Show();
        }
        catch
        {
            try { _tray.ShowBalloonTip(8000, title, body, ToolTipIcon.None); } catch { }
        }
    }

    // Called by a toast click (any thread): bring Casana forward and open the link.
    public static void OpenFromToast(string? url)
    {
        var form = _instance;
        var target = IsTrusted(url) ? url : null;
        if (form == null || !form.IsHandleCreated)
        {
            _pendingUrl = target;
            return;
        }
        try
        {
            form.BeginInvoke(() =>
            {
                form.ShowFromTray();
                if (target == null) return;
                if (form._web.CoreWebView2 != null) form._web.CoreWebView2.Navigate(target);
                else _pendingUrl = target;
            });
        }
        catch
        {
        }
    }

    // A Casana https link opens that page; casana:// (from CASA Suite) opens the home page.
    private static string StartUrlFrom(string[] args)
    {
        foreach (var a in args)
        {
            if (Uri.TryCreate(a, UriKind.Absolute, out var u) && IsTrusted(u.ToString())) return u.ToString();
        }
        return LiveUrl;
    }

    private static bool IsTrusted(string? url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u)) return false;
        return u.Scheme == "https" && u.Host == Host;
    }

    // Starts the page area, up to 3 tries with a fresh WebView2 each time. Started hidden with Windows: the window
    // goes to the tray only after this, unless the person opened it meanwhile.
    private async Task StartWebAsync()
    {
        if (_starting || _webReady) return;
        _starting = true;
        try
        {
            for (var attempt = 1; attempt <= 3 && !_webReady; attempt++)
            {
                try
                {
                    if (attempt > 1)
                    {
                        await Task.Delay(2000 * attempt);
                        ResetWebView();
                    }
                    await InitAsync();
                    _webReady = true;
                }
                catch (Exception ex)
                {
                    LogError($"page start, try {attempt}", ex);
                }
            }
        }
        finally
        {
            _starting = false;
            if (_startHidden && !_userShown && Visible) Hide();
            if (Opacity < 1) Opacity = 1;
        }
    }

    private void ResetWebView()
    {
        try
        {
            Controls.Remove(_web);
            _web.Dispose();
        }
        catch
        {
        }
        _web = new WebView2 { Dock = DockStyle.Fill };
        Controls.Add(_web);
    }

    public static void LogError(string where, Exception? ex)
    {
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Casana");
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "errors.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} [{where}] {ex}\r\n");
        }
        catch
        {
        }
    }

    private async Task InitAsync()
    {
        var appDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Casana");
        var dataDir = Path.Combine(appDir, "WebView2");
        var env = await CoreWebView2Environment.CreateAsync(null, dataDir);
        await _web.EnsureCoreWebView2Async(env);
        var core = _web.CoreWebView2;

        // Other sites open in the normal browser; Casana pages stay in this window.
        core.NavigationStarting += (_, e) =>
        {
            if (!IsTrusted(e.Uri) && e.Uri.StartsWith("http", StringComparison.OrdinalIgnoreCase))
            {
                e.Cancel = true;
                OpenExternal(e.Uri);
            }
        };
        // New tabs (window.open / target=_blank):
        //  - Casana pages open in this window, other sites in the normal browser
        //  - file previews (blob:, data:, or about:blank that then loads the file's
        //    storage link) open in WebView2's own small window, like a browser tab
        core.NewWindowRequested += (_, e) =>
        {
            if (!e.Uri.StartsWith("http", StringComparison.OrdinalIgnoreCase)) return;
            e.Handled = true;
            if (IsTrusted(e.Uri)) core.Navigate(e.Uri);
            else OpenExternal(e.Uri);
        };

        // Notifications from the Casana page (see OnWebMessage). Only from Casana pages.
        core.WebMessageReceived += (_, e) =>
        {
            if (!IsTrusted(e.Source)) return;
            string? text = null;
            try { text = e.TryGetWebMessageAsString(); } catch { }
            if (text != null) OnWebMessage(text);
        };

        // File pickers (attachments), downloads (exports, backups) and copy to
        // clipboard are handled by WebView2 itself, like in Edge.
        // Always ask the server for the page itself, so a new Publish shows up at once.
        core.NavigateWithWebResourceRequest(
            env.CreateWebResourceRequest(_pendingUrl ?? _startUrl, "GET", null, "Cache-Control: no-cache"));
        _pendingUrl = null;

        // "New update available" card for this app (see common/windows/Updater.cs).
        _updater = new Updater(this, "casana", "Casana", appDir);
        _ = _updater.CheckAsync();
    }

    private static void OpenExternal(string url)
    {
        if (!url.StartsWith("http", StringComparison.OrdinalIgnoreCase) &&
            !url.StartsWith("mailto:", StringComparison.OrdinalIgnoreCase)) return;
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }
}
