using System.Diagnostics;
using System.Reflection;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace CasaSuite;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(args));
    }
}

internal sealed record StartApp(string Name, string AppId);

internal sealed class MainForm : Form
{
    private const string LiveUrl = "https://casa-suite-event.apper.so";
    private const string TestHost = "casa.test";

    private readonly WebView2 _web = new() { Dock = DockStyle.Fill };
    private readonly bool _live;

    public MainForm(string[] args)
    {
        _live = args.Contains("--live");
        Text = "CASA Suite";
        Width = 1280;
        Height = 820;
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(_web);
        Load += async (_, _) => await InitAsync();
    }

    private static bool IsTrusted(string? url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var u)) return false;
        return u.Scheme == "https" && (u.Host == "casa-suite-event.apper.so" || u.Host == TestHost);
    }

    private async Task InitAsync()
    {
        var dataDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CASASuite", "WebView2");
        var env = await CoreWebView2Environment.CreateAsync(null, dataDir);
        await _web.EnsureCoreWebView2Async(env);
        var core = _web.CoreWebView2;

        // Bridge: injected on every page, but only answers trusted origins.
        await core.AddScriptToExecuteOnDocumentCreatedAsync(@"
(()=>{
  if(window.CasaNative||!window.chrome||!window.chrome.webview)return;
  const pending=new Map();let n=0;
  window.chrome.webview.addEventListener('message',e=>{
    const r=e.data;const f=pending.get(r.id);
    if(f){pending.delete(r.id);f(r.result)}
  });
  const call=(cmd,args)=>new Promise(res=>{
    const id=++n;pending.set(id,res);
    window.chrome.webview.postMessage({id:id,cmd:cmd,args:args||{}});
  });
  window.CasaNative={
    platform:'windows',version:1,
    listApps:()=>call('list'),
    isInstalled:a=>call('installed',{app:a}),
    open:a=>call('open',{app:a})
  };
  window.addEventListener('DOMContentLoaded',()=>window.dispatchEvent(new Event('casanativeready')));
})();");

        core.WebMessageReceived += OnWebMessage;

        // Untrusted sites never stay inside the app.
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
            OpenExternal(e.Uri);
        };

        if (_live)
        {
            core.Navigate(LiveUrl);
        }
        else
        {
            var dir = Path.Combine(Path.GetTempPath(), "CasaSuiteTest");
            Directory.CreateDirectory(dir);
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("test.html")!)
            using (var f = File.Create(Path.Combine(dir, "test.html")))
                s.CopyTo(f);
            core.SetVirtualHostNameToFolderMapping(TestHost, dir, CoreWebView2HostResourceAccessKind.Allow);
            core.Navigate($"https://{TestHost}/test.html");
        }
    }

    private static void OpenExternal(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); } catch { }
    }

    private async void OnWebMessage(object? sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        if (!IsTrusted(e.Source)) return;
        try
        {
            using var doc = JsonDocument.Parse(e.WebMessageAsJson);
            var root = doc.RootElement;
            var id = root.GetProperty("id").GetInt32();
            var cmd = root.GetProperty("cmd").GetString();
            var app = root.TryGetProperty("args", out var a) && a.TryGetProperty("app", out var ap)
                ? ap.GetString() ?? "" : "";

            object? result = cmd switch
            {
                "list" => (await GetStartAppsAsync()).Select(x => new { name = x.Name, id = x.AppId }).ToList(),
                "installed" => FindApp(await GetStartAppsAsync(), app) != null,
                "open" => await OpenAppAsync(app),
                _ => null
            };

            var json = JsonSerializer.Serialize(new { id, result });
            _web.CoreWebView2.PostWebMessageAsJson(json);
        }
        catch
        {
            // Malformed message from the page: ignore.
        }
    }

    // ---- Start Menu apps (UWP + classic) -------------------------------------------------

    private List<StartApp>? _cache;
    private DateTime _cacheTime;

    private async Task<List<StartApp>> GetStartAppsAsync()
    {
        if (_cache != null && (DateTime.UtcNow - _cacheTime).TotalSeconds < 60) return _cache;

        var psi = new ProcessStartInfo("powershell.exe",
            "-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command " +
            "\"[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-StartApps | Select-Object Name,AppID | ConvertTo-Json -Compress\"")
        {
            RedirectStandardOutput = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = System.Text.Encoding.UTF8
        };

        var list = new List<StartApp>();
        try
        {
            using var p = Process.Start(psi)!;
            var output = await p.StandardOutput.ReadToEndAsync();
            await p.WaitForExitAsync();
            if (!string.IsNullOrWhiteSpace(output))
            {
                using var doc = JsonDocument.Parse(output);
                var items = doc.RootElement.ValueKind == JsonValueKind.Array
                    ? doc.RootElement.EnumerateArray().ToList()
                    : new List<JsonElement> { doc.RootElement };
                foreach (var it in items)
                {
                    var name = it.GetProperty("Name").GetString();
                    var appId = it.GetProperty("AppID").GetString();
                    if (!string.IsNullOrWhiteSpace(name) && !string.IsNullOrWhiteSpace(appId))
                        list.Add(new StartApp(name!, appId!));
                }
            }
        }
        catch
        {
        }

        _cache = list.OrderBy(x => x.Name, StringComparer.OrdinalIgnoreCase).ToList();
        _cacheTime = DateTime.UtcNow;
        return _cache;
    }

    private static StartApp? FindApp(List<StartApp> apps, string query)
    {
        query = query.Trim();
        if (query.Length == 0) return null;

        // 1) exact name  2) exact AppID  3) shortest name that contains the query
        return apps.FirstOrDefault(a => a.Name.Equals(query, StringComparison.OrdinalIgnoreCase))
            ?? apps.FirstOrDefault(a => a.AppId.Equals(query, StringComparison.OrdinalIgnoreCase))
            ?? apps.Where(a => a.Name.Contains(query, StringComparison.OrdinalIgnoreCase))
                   .OrderBy(a => a.Name.Length).FirstOrDefault();
    }

    private async Task<bool> OpenAppAsync(string query)
    {
        var app = FindApp(await GetStartAppsAsync(), query);
        if (app == null) return false;
        try
        {
            Process.Start(new ProcessStartInfo("explorer.exe", $"shell:AppsFolder\\{app.AppId}") { UseShellExecute = false });
            return true;
        }
        catch
        {
            return false;
        }
    }
}
