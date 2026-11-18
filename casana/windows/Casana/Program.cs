using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using CasaApps;

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

    private readonly WebView2 _web = new() { Dock = DockStyle.Fill };
    private readonly string _startUrl;
    private Updater? _updater;

    // Tray icon: closing the window keeps Casana running here, so notifications still arrive (18 Nov 2026).
    private readonly NotifyIcon _tray = new();
    private string _notifyUrl = LiveUrl;
    private bool _quitting;
    private bool _toldAboutTray;
    private readonly EventWaitHandle _showSignal = new(false, EventResetMode.AutoReset, ShowSignalName);

    public MainForm(string[] args)
    {
        _startUrl = StartUrlFrom(args);
        Text = "Casana";
        Width = 1360;
        Height = 860;
        StartPosition = FormStartPosition.CenterScreen;
        try { Icon = Icon.ExtractAssociatedIcon(Environment.ProcessPath!); } catch { }
        Controls.Add(_web);
        Load += async (_, _) => await InitAsync();
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
        try { _tray.Icon = Icon ?? SystemIcons.Application; } catch { _tray.Icon = SystemIcons.Application; }
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

    private void ShowFromTray()
    {
        if (!Visible) Show();
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
            _tray.ShowBalloonTip(5000, "Casana is still running", "You will keep getting notifications. Right-click the Casana icon here to quit.", ToolTipIcon.Info);
            _notifyUrl = LiveUrl;
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
            _tray.ShowBalloonTip(8000, title, string.IsNullOrWhiteSpace(body) ? "Open Casana to see it." : body, ToolTipIcon.None);
        }
        catch
        {
            // Not a notification message: ignore.
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
            env.CreateWebResourceRequest(_startUrl, "GET", null, "Cache-Control: no-cache"));

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
