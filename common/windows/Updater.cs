using System.Diagnostics;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text.Json;

namespace CasaApps;

/// <summary>
/// "New update available" card for the CASA Windows apps (CASA Suite, Casana).
///
/// Every build publishes versions.json next to the installers (GitHub release
/// "latest"). On start and every 6 hours the app compares its own version with
/// the one listed for it. If newer: "Update now" downloads the installer, runs it
/// quietly (progress bar only) and the app reopens by itself; "Remind me later"
/// waits a day. The login and data stay, they live in the user's AppData.
///
/// Only the app shell needs this: the screens inside come from the live web app,
/// so a Publish on Apper reaches everyone without any update.
/// </summary>
internal sealed class Updater
{
    public const string VersionsUrl =
        "https://github.com/caai-dev/casa-suite-apps/releases/latest/download/versions.json";

    private readonly Form _owner;
    private readonly string _appKey;   // "casa-suite" or "casana" in versions.json
    private readonly string _appName;
    private readonly string _stateFile;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 6 * 60 * 60 * 1000 };
    private bool _busy;

    public Updater(Form owner, string appKey, string appName, string dataFolder)
    {
        _owner = owner;
        _appKey = appKey;
        _appName = appName;
        _stateFile = Path.Combine(dataFolder, "update.json");
        _timer.Tick += async (_, _) => await CheckAsync();
        _timer.Start();
    }

    public async Task CheckAsync()
    {
        if (_busy || DateTime.UtcNow < SnoozedUntil()) return;
        _busy = true;
        try
        {
            using var http = NewClient(TimeSpan.FromSeconds(15));
            var json = await http.GetStringAsync(VersionsUrl);
            using var doc = JsonDocument.Parse(json);
            var info = doc.RootElement.GetProperty(_appKey).GetProperty("windows");
            var latest = Normalize(Version.Parse(info.GetProperty("version").GetString() ?? "0.0.0"));
            var url = info.GetProperty("url").GetString() ?? "";
            if (!url.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) return;

            var current = Normalize(Assembly.GetEntryAssembly()?.GetName().Version ?? new Version(0, 0, 0));
            if (latest <= current) return;

            using var dialog = new UpdateDialog(_appName, latest.ToString(3));
            if (dialog.ShowDialog(_owner) != DialogResult.OK)
            {
                Snooze(DateTime.UtcNow.AddDays(1));
                return;
            }
            await InstallAsync(url, latest);
        }
        catch
        {
            // Offline or versions.json missing: try again later.
        }
        finally
        {
            _busy = false;
        }
    }

    private async Task InstallAsync(string url, Version version)
    {
        var oldTitle = _owner.Text;
        _owner.Text = $"{_appName} - downloading update...";
        _owner.UseWaitCursor = true;
        try
        {
            var file = Path.Combine(Path.GetTempPath(), $"{_appKey}-setup-{version.ToString(3)}.exe");
            using (var http = NewClient(TimeSpan.FromMinutes(15)))
            using (var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead))
            {
                resp.EnsureSuccessStatusCode();
                await using var fs = File.Create(file);
                await resp.Content.CopyToAsync(fs);
            }

            // Quiet install (progress bar only); the installer reopens the app when done.
            Process.Start(new ProcessStartInfo(file, "/SILENT /SUPPRESSMSGBOXES /NORESTART /CLOSEAPPLICATIONS")
            {
                UseShellExecute = true
            });
            Application.Exit();
        }
        catch
        {
            _owner.Text = oldTitle;
            _owner.UseWaitCursor = false;
            MessageBox.Show(_owner,
                "The update could not be downloaded. Check the internet connection and try again later.",
                _appName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private static HttpClient NewClient(TimeSpan timeout)
    {
        var http = new HttpClient { Timeout = timeout };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("CASA-Apps-Updater/1.0");
        http.DefaultRequestHeaders.CacheControl = new CacheControlHeaderValue { NoCache = true };
        return http;
    }

    private static Version Normalize(Version v) =>
        new(Math.Max(v.Major, 0), Math.Max(v.Minor, 0), Math.Max(v.Build, 0));

    private DateTime SnoozedUntil()
    {
        try
        {
            if (!File.Exists(_stateFile)) return DateTime.MinValue;
            using var doc = JsonDocument.Parse(File.ReadAllText(_stateFile));
            return new DateTime(doc.RootElement.GetProperty("snoozeUntilTicks").GetInt64(), DateTimeKind.Utc);
        }
        catch
        {
            return DateTime.MinValue;
        }
    }

    private void Snooze(DateTime untilUtc)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_stateFile)!);
            File.WriteAllText(_stateFile, JsonSerializer.Serialize(new { snoozeUntilTicks = untilUtc.Ticks }));
        }
        catch
        {
        }
    }
}

internal sealed class UpdateDialog : Form
{
    public UpdateDialog(string appName, string version)
    {
        Text = "Update available";
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        ShowInTaskbar = false;
        StartPosition = FormStartPosition.CenterParent;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Segoe UI", 10f);
        ClientSize = new Size(440, 175);

        var title = new Label
        {
            Text = $"A new version of {appName} ({version}) is ready.",
            Font = new Font("Segoe UI Semibold", 11f),
            AutoSize = false,
            Location = new Point(20, 18),
            Size = new Size(400, 28)
        };
        var body = new Label
        {
            Text = "Update now downloads and installs it, then reopens the app. " +
                   "Your login and data stay as they are.",
            AutoSize = false,
            Location = new Point(20, 52),
            Size = new Size(400, 50)
        };
        var update = new Button
        {
            Text = "Update now",
            DialogResult = DialogResult.OK,
            Location = new Point(160, 120),
            Size = new Size(120, 36)
        };
        var later = new Button
        {
            Text = "Remind me later",
            DialogResult = DialogResult.Cancel,
            Location = new Point(290, 120),
            Size = new Size(130, 36)
        };

        Controls.AddRange(new Control[] { title, body, update, later });
        AcceptButton = update;
        CancelButton = later;
    }
}
