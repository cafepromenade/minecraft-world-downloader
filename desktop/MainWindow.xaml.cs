using System;
using System.Diagnostics;
using System.IO;
using System.Threading.Tasks;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

namespace WorldDownloaderManager;

public sealed partial class MainWindow : Window
{
    private readonly Settings _settings;
    private readonly DockerService _docker = new();

    public MainWindow()
    {
        this.InitializeComponent();

        Title = "World Downloader Manager";
        try { this.SystemBackdrop = new MicaBackdrop(); } catch { /* unsupported on older builds */ }
        try { this.AppWindow?.Resize(new SizeInt32(860, 960)); } catch { }

        _settings = Settings.Load();
        _docker.OnOutput = AppendLog;

        FolderBox.Text = _settings.DataFolder;
        WebPortBox.Value = _settings.WebPort;
        ProxyPortBox.Value = _settings.ProxyPort;
        ImageBox.Text = _settings.Image;
        LoginSwitch.IsOn = _settings.RequireLogin;
        UserBox.Text = _settings.Username;
        PassBox.Password = _settings.Password;
        LoginPanel.Visibility = _settings.RequireLogin ? Visibility.Visible : Visibility.Collapsed;

        _ = InitAsync();
    }

    private async Task InitAsync()
    {
        if (!await _docker.IsDockerAvailableAsync())
        {
            SetStatus(InfoBarSeverity.Error, "Docker not found",
                "Install Docker Desktop and make sure it is running, then reopen this app.");
            return;
        }
        await RefreshStatusAsync();
    }

    private int Port(NumberBox box, int fallback) =>
        double.IsNaN(box.Value) ? fallback : (int)box.Value;

    private void SaveFromUi()
    {
        _settings.DataFolder = FolderBox.Text.Trim();
        _settings.WebPort = Port(WebPortBox, 8080);
        _settings.ProxyPort = Port(ProxyPortBox, 25565);
        _settings.Image = string.IsNullOrWhiteSpace(ImageBox.Text) ? _settings.Image : ImageBox.Text.Trim();
        _settings.RequireLogin = LoginSwitch.IsOn;
        _settings.Username = UserBox.Text.Trim();
        _settings.Password = PassBox.Password;
        _settings.Save();
    }

    private void SetStatus(InfoBarSeverity severity, string title, string message)
    {
        StatusBar.Severity = severity;
        StatusBar.Title = title;
        StatusBar.Message = message;
    }

    private void Busy(bool on)
    {
        Spinner.IsActive = on;
        StartBtn.IsEnabled = !on;
        StopBtn.IsEnabled = !on;
        PullBtn.IsEnabled = !on;
        BrowseBtn.IsEnabled = !on;
    }

    private void AppendLog(string line)
    {
        this.DispatcherQueue.TryEnqueue(() =>
        {
            LogText.Text += line + "\n";
            LogScroll.ChangeView(null, LogScroll.ScrollableHeight, null);
        });
    }

    private async Task RefreshStatusAsync()
    {
        bool running = await _docker.IsRunningAsync(_settings.ContainerName);
        StartBtn.IsEnabled = !running;
        StopBtn.IsEnabled = running;
        if (running)
            SetStatus(InfoBarSeverity.Success, "Running",
                $"Console at http://localhost:{_settings.WebPort}  •  Minecraft proxy on localhost:{_settings.ProxyPort}");
        else
            SetStatus(InfoBarSeverity.Informational, "Stopped", "Press Start to launch the console.");
    }

    private async void Browse_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var picker = new Windows.Storage.Pickers.FolderPicker();
            picker.FileTypeFilter.Add("*");
            var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
            var folder = await picker.PickSingleFolderAsync();
            if (folder != null) FolderBox.Text = folder.Path;
        }
        catch (Exception ex) { AppendLog("Folder picker error: " + ex.Message); }
    }

    private void LoginSwitch_Toggled(object sender, RoutedEventArgs e) =>
        LoginPanel.Visibility = LoginSwitch.IsOn ? Visibility.Visible : Visibility.Collapsed;

    private async void Start_Click(object sender, RoutedEventArgs e)
    {
        SaveFromUi();
        if (string.IsNullOrWhiteSpace(_settings.DataFolder))
        {
            SetStatus(InfoBarSeverity.Warning, "Pick a data folder", "Choose where worlds should be stored first.");
            return;
        }
        Busy(true);
        try
        {
            Directory.CreateDirectory(_settings.DataFolder);
            await _docker.RemoveAsync(_settings.ContainerName);
            var (code, _) = await _docker.RunContainerAsync(_settings);
            if (code == 0)
            {
                SetStatus(InfoBarSeverity.Success, "Started", $"Open http://localhost:{_settings.WebPort}");
                OpenConsole();
            }
            else
            {
                SetStatus(InfoBarSeverity.Error, "Failed to start",
                    "See the output below. If the image is missing, press “Update image” first.");
            }
        }
        finally
        {
            Busy(false);
            await RefreshStatusAsync();
        }
    }

    private async void Stop_Click(object sender, RoutedEventArgs e)
    {
        Busy(true);
        try { await _docker.RemoveAsync(_settings.ContainerName); AppendLog("Stopped."); }
        finally { Busy(false); await RefreshStatusAsync(); }
    }

    private async void Pull_Click(object sender, RoutedEventArgs e)
    {
        SaveFromUi();
        Busy(true);
        try { await _docker.PullAsync(_settings.Image); }
        finally { Busy(false); }
    }

    private void Open_Click(object sender, RoutedEventArgs e) => OpenConsole();

    private void OpenConsole()
    {
        try
        {
            Process.Start(new ProcessStartInfo($"http://localhost:{Port(WebPortBox, 8080)}") { UseShellExecute = true });
        }
        catch (Exception ex) { AppendLog("Could not open browser: " + ex.Message); }
    }
}
