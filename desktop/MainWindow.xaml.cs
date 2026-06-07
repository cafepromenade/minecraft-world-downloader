using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace WorldDownloaderManager;

public partial class MainWindow : Window
{
    private readonly Settings _settings;
    private readonly DockerService _docker = new();

    public MainWindow()
    {
        InitializeComponent();

        _settings = Settings.Load();
        _docker.OnOutput = AppendLog;

        FolderBox.Text = _settings.DataFolder;
        WebPortBox.Text = _settings.WebPort.ToString();
        ProxyPortBox.Text = _settings.ProxyPort.ToString();
        ImageBox.Text = _settings.Image;
        LoginCheck.IsChecked = _settings.RequireLogin;
        UserBox.Text = _settings.Username;
        PassBox.Password = _settings.Password;
        LoginPanel.Visibility = _settings.RequireLogin ? Visibility.Visible : Visibility.Collapsed;

        Loaded += async (_, _) => await InitAsync();
    }

    private async Task InitAsync()
    {
        if (!await _docker.IsDockerAvailableAsync())
        {
            SetStatus("error", "Docker not found. Install Docker Desktop and make sure it is running, then reopen this app.");
            return;
        }
        await RefreshStatusAsync();
    }

    private static int ParsePort(string text, int fallback) =>
        int.TryParse(text, out var v) && v is > 0 and < 65536 ? v : fallback;

    private void SaveFromUi()
    {
        _settings.DataFolder = FolderBox.Text.Trim();
        _settings.WebPort = ParsePort(WebPortBox.Text, 8080);
        _settings.ProxyPort = ParsePort(ProxyPortBox.Text, 25565);
        if (!string.IsNullOrWhiteSpace(ImageBox.Text)) _settings.Image = ImageBox.Text.Trim();
        _settings.RequireLogin = LoginCheck.IsChecked == true;
        _settings.Username = UserBox.Text.Trim();
        _settings.Password = PassBox.Password;
        _settings.Save();
    }

    private void SetStatus(string kind, string text)
    {
        string bg = kind switch
        {
            "success" => "#16291E",
            "error" => "#2A1718",
            "warn" => "#2A2516",
            _ => "#1B2733",
        };
        string fg = kind switch
        {
            "success" => "#3DDC84",
            "error" => "#FF8A85",
            "warn" => "#FFCA52",
            _ => "#E9EEF5",
        };
        StatusBorder.Background = Brush(bg);
        StatusText.Foreground = Brush(fg);
        StatusText.Text = text;
    }

    private static SolidColorBrush Brush(string hex) =>
        new((Color)ColorConverter.ConvertFromString(hex));

    private void Busying(bool on)
    {
        Busy.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
        StartBtn.IsEnabled = !on;
        StopBtn.IsEnabled = !on;
        PullBtn.IsEnabled = !on;
        BrowseBtn.IsEnabled = !on;
    }

    private void AppendLog(string line) => Dispatcher.Invoke(() =>
    {
        LogText.AppendText(line + Environment.NewLine);
        LogText.ScrollToEnd();
    });

    private async Task RefreshStatusAsync()
    {
        bool running = await _docker.IsRunningAsync(_settings.ContainerName);
        StartBtn.IsEnabled = !running;
        StopBtn.IsEnabled = running;
        if (running)
            SetStatus("success", $"Running — console at http://localhost:{_settings.WebPort}   •   Minecraft proxy on localhost:{_settings.ProxyPort}");
        else
            SetStatus("info", "Stopped. Press Start to launch the console.");
    }

    private void Browse_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "Choose the data folder" };
        if (!string.IsNullOrWhiteSpace(FolderBox.Text) && Directory.Exists(FolderBox.Text))
            dlg.InitialDirectory = FolderBox.Text;
        if (dlg.ShowDialog() == true)
            FolderBox.Text = dlg.FolderName;
    }

    private void Login_Changed(object sender, RoutedEventArgs e) =>
        LoginPanel.Visibility = LoginCheck.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        SaveFromUi();
        if (string.IsNullOrWhiteSpace(_settings.DataFolder))
        {
            SetStatus("warn", "Pick a data folder first — choose where worlds should be stored.");
            return;
        }
        Busying(true);
        try
        {
            Directory.CreateDirectory(_settings.DataFolder);
            await _docker.RemoveAsync(_settings.ContainerName);
            var (code, _) = await _docker.RunContainerAsync(_settings);
            if (code == 0)
            {
                SetStatus("success", $"Started. Opening http://localhost:{_settings.WebPort} …");
                OpenConsole();
            }
            else
            {
                SetStatus("error", "Failed to start — see the output below. If the image is missing, press “Update image” first.");
            }
        }
        finally
        {
            Busying(false);
            await RefreshStatusAsync();
        }
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        Busying(true);
        try { await _docker.RemoveAsync(_settings.ContainerName); AppendLog("Stopped."); }
        finally { Busying(false); await RefreshStatusAsync(); }
    }

    private async void Pull_Click(object sender, RoutedEventArgs e)
    {
        SaveFromUi();
        Busying(true);
        try { await _docker.PullAsync(_settings.Image); }
        finally { Busying(false); }
    }

    private void GenerateCompose_Click(object sender, RoutedEventArgs e)
    {
        SaveFromUi();
        if (string.IsNullOrWhiteSpace(_settings.DataFolder))
        {
            SetStatus("warn", "Pick a data folder first — the docker-compose.yml is written there.");
            return;
        }
        try
        {
            var path = _settings.WriteDockerCompose();
            AppendLog("Wrote " + path);
            SetStatus("success", "docker-compose.yml written to the data folder. Run it with: docker compose up -d");
            try { Process.Start(new ProcessStartInfo(_settings.DataFolder) { UseShellExecute = true }); } catch { /* ignore */ }
        }
        catch (Exception ex) { SetStatus("error", "Could not write docker-compose.yml: " + ex.Message); }
    }

    private void Open_Click(object sender, RoutedEventArgs e) => OpenConsole();

    private void OpenConsole()
    {
        try
        {
            int port = ParsePort(WebPortBox.Text, 8080);
            Process.Start(new ProcessStartInfo($"http://localhost:{port}") { UseShellExecute = true });
        }
        catch (Exception ex) { AppendLog("Could not open browser: " + ex.Message); }
    }

    // ---- Accessibility / theme ----------------------------------------------------------------
    private void Theme_Changed(object sender, SelectionChangedEventArgs e)
    {
        if (ThemeBox == null) return;
        var name = (ThemeBox.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "Dark";
        ApplyTheme(name);
    }

    private void LargeText_Changed(object sender, RoutedEventArgs e)
    {
        double s = LargeTextBox.IsChecked == true ? 1.25 : 1.0;
        if (RootScale != null) { RootScale.ScaleX = s; RootScale.ScaleY = s; }
    }

    private void SetBrush(string key, string hex)
    {
        if (Resources[key] is SolidColorBrush b)
            b.Color = (Color)ColorConverter.ConvertFromString(hex);
    }

    private void ApplyTheme(string name)
    {
        switch (name)
        {
            case "Light":
                SetBrush("Bg", "#F4F6FB"); SetBrush("Surface", "#FFFFFF"); SetBrush("Surface2", "#EEF2F8");
                SetBrush("Outline", "#C7D0DE"); SetBrush("Text", "#16202E"); SetBrush("Muted", "#51607A");
                SetBrush("Accent", "#1A9E5B"); SetBrush("Danger", "#C4322D");
                break;
            case "High contrast":
                SetBrush("Bg", "#000000"); SetBrush("Surface", "#000000"); SetBrush("Surface2", "#0A0A0A");
                SetBrush("Outline", "#FFFFFF"); SetBrush("Text", "#FFFFFF"); SetBrush("Muted", "#F0F0F0");
                SetBrush("Accent", "#00E676"); SetBrush("Danger", "#FF6B6B");
                break;
            default: // Dark
                SetBrush("Bg", "#0F1419"); SetBrush("Surface", "#1A2029"); SetBrush("Surface2", "#222A35");
                SetBrush("Outline", "#313B49"); SetBrush("Text", "#E9EEF5"); SetBrush("Muted", "#93A1B5");
                SetBrush("Accent", "#3DDC84"); SetBrush("Danger", "#FF5C57");
                break;
        }
        if (Resources["Bg"] is SolidColorBrush bg) Background = bg;
    }

    // ---- BlueMap 3D map ----------------------------------------------------------------------
    private void BrowseJar_Click(object sender, RoutedEventArgs e)
    {
        var dlg = new Microsoft.Win32.OpenFileDialog { Title = "Choose a server jar (Paper/vanilla)", Filter = "Jar files (*.jar)|*.jar|All files (*.*)|*.*" };
        if (dlg.ShowDialog() == true) ServerJarBox.Text = dlg.FileName;
    }

    private static string? FindPipeline(string dataFolder)
    {
        foreach (var c in new[]
        {
            Path.Combine(AppContext.BaseDirectory, "bluemap", "pipeline.py"),
            Path.Combine(AppContext.BaseDirectory, "..", "bluemap", "pipeline.py"),
            Path.Combine(dataFolder ?? "", "bluemap", "pipeline.py"),
        })
        {
            try { if (File.Exists(c)) return Path.GetFullPath(c); } catch { /* ignore */ }
        }
        return null;
    }

    private async void RenderMap_Click(object sender, RoutedEventArgs e)
    {
        SaveFromUi();
        if (string.IsNullOrWhiteSpace(_settings.DataFolder))
        {
            SetStatus("warn", "Pick a data folder first — the world to render lives there.");
            return;
        }
        var pipeline = FindPipeline(_settings.DataFolder);
        if (pipeline == null)
        {
            SetStatus("error", "Could not find bluemap/pipeline.py (expected next to the app or in the data folder).");
            return;
        }

        // world is <data>/world by default; BlueMap output + workdir live under <data>/bluemap
        string world = Path.Combine(_settings.DataFolder, "world");
        string workdir = Path.Combine(_settings.DataFolder, "bluemap");
        string webroot = Path.Combine(workdir, "web");
        Directory.CreateDirectory(workdir);

        // write settings.json from the UI fields
        var dims = new System.Collections.Generic.List<string>();
        if (BmOverworld.IsChecked == true) dims.Add("overworld");
        if (BmNether.IsChecked == true) dims.Add("nether");
        if (BmEnd.IsChecked == true) dims.Add("end");
        string settingsPath = Path.Combine(workdir, "settings.json");
        var sj = new System.Text.StringBuilder();
        sj.Append("{\"acceptDownload\":true,");
        sj.Append("\"renderThreadCount\":").Append(ParsePort(BmThreads.Text, 0)).Append(',');
        sj.Append("\"webserverEnabled\":true,");
        sj.Append("\"webserverPort\":").Append(ParsePort(BmPort.Text, 8100)).Append(',');
        sj.Append("\"dimensions\":[").Append(string.Join(",", dims.ConvertAll(d => "\"" + d + "\""))).Append("]}");
        try { File.WriteAllText(settingsPath, sj.ToString()); } catch (Exception ex) { AppendLog("Could not write BlueMap settings: " + ex.Message); }

        var args = new System.Collections.Generic.List<string> { pipeline, "all", "--world", world,
            "--out", webroot, "--workdir", workdir, "--settings", settingsPath };
        if (!string.IsNullOrWhiteSpace(ServerJarBox.Text)) { args.Add("--server-jar"); args.Add(ServerJarBox.Text); }

        Busying(true);
        SetStatus("info", "Rendering 3D map with BlueMap — this can take a while (and downloads textures the first time).");
        try
        {
            int code = await RunPythonAsync(args);
            if (code == 0) { SetStatus("success", $"3D map rendered. Open it at http://localhost:{ParsePort(BmPort.Text, 8100)} (start the web map server) or via 'Open 3D map'."); }
            else SetStatus("error", "BlueMap render failed — see the output below.");
        }
        catch (Exception ex) { SetStatus("error", "BlueMap render error: " + ex.Message); }
        finally { Busying(false); }
    }

    private void OpenMap_Click(object sender, RoutedEventArgs e)
    {
        try { Process.Start(new ProcessStartInfo($"http://localhost:{ParsePort(BmPort.Text, 8100)}") { UseShellExecute = true }); }
        catch (Exception ex) { AppendLog("Could not open 3D map: " + ex.Message); }
    }

    private async Task<int> RunPythonAsync(System.Collections.Generic.List<string> args)
    {
        foreach (var exe in new[] { "python", "python3", "py" })
        {
            var psi = new ProcessStartInfo(exe)
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            foreach (var a in args) psi.ArgumentList.Add(a);
            Process proc;
            try { proc = Process.Start(psi)!; }
            catch { continue; } // try the next python launcher
            proc.OutputDataReceived += (_, ev) => { if (ev.Data != null) AppendLog(ev.Data); };
            proc.ErrorDataReceived += (_, ev) => { if (ev.Data != null) AppendLog(ev.Data); };
            proc.BeginOutputReadLine();
            proc.BeginErrorReadLine();
            await proc.WaitForExitAsync();
            return proc.ExitCode;
        }
        AppendLog("Python was not found on PATH (tried python, python3, py).");
        return -1;
    }
}
