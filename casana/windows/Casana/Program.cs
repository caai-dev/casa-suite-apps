using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

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
            Native.ShowExisting();
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

    private readonly WebView2 _web = new() { Dock = DockStyle.Fill };
    private readonly string _startUrl;

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
        var dataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Casana", "WebView2");
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
        core.NewWindowRequested += (_, e) =>
        {
            e.Handled = true;
            if (IsTrusted(e.Uri)) core.Navigate(e.Uri);
            else OpenExternal(e.Uri);
        };

        // File pickers (attachments) and downloads (exports, backups) are handled by
        // WebView2 itself, like in Edge; downloads go to the user's Downloads folder.
        core.Navigate(_startUrl);
    }

    private static void OpenExternal(string url)
    {
        if (!url.StartsWith("http", StringComparison.OrdinalIgnoreCase) &&
            !url.StartsWith("mailto:", StringComparison.OrdinalIgnoreCase)) return;
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }
}
