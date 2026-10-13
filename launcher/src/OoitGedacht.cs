// OoitGedacht SMP installer/updater.
// Zet Fabric + de mods uit het modpack (pack/ooitgedacht.mrpack op GitHub) klaar in een eigen map
// (%APPDATA%\.ooitgedacht) en maakt een profiel "OoitGedacht SMP" in de officiele Minecraft Launcher.
// Inloggen en het spel starten doet de officiele launcher zelf.
// Bouwen: build.ps1 (gebruikt de C#-compiler die in Windows zit, .NET Framework 4.8).
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("OoitGedacht SMP")]
[assembly: AssemblyProduct("OoitGedacht SMP")]
[assembly: AssemblyVersion("1.0.0.0")]

namespace OoitGedacht
{
    static class Config
    {
        public const string PackUrl = "https://raw.githubusercontent.com/Falcone991/ooitgedacht-plugins/main/launcher/pack/ooitgedacht.mrpack";
        public const string ServerName = "OoitGedacht SMP";
        public const string ServerIp = "play.ooitgedacht.nl";
        public const string ProfileId = "ooitgedacht";
        public const string ProfileName = "OoitGedacht SMP";
        public const string GameDirName = ".ooitgedacht";
        public const string UserAgent = "Falcone991/ooitgedacht-installer/1.0";
    }

    class UserError : Exception
    {
        public UserError(string message) : base(message) { }
    }

    class LauncherMissing : UserError
    {
        public LauncherMissing() : base("De Minecraft Launcher is niet gevonden (of nog nooit gestart).\n\n"
            + "Installeer de officiele Minecraft Launcher, start hem een keer en log in. "
            + "Druk daarna hier opnieuw op Spelen.\n\nDownloadpagina nu openen?") { }
    }

    class Installer
    {
        public Action<string> Status = delegate { };
        public Action<int> Progress = delegate { };
        public string LocalPack; // voor testen: lokaal .mrpack in plaats van downloaden

        readonly string mcDir;
        readonly string gameDir;
        readonly JavaScriptSerializer json = new JavaScriptSerializer { MaxJsonLength = int.MaxValue };

        public Installer(string appData)
        {
            mcDir = Path.Combine(appData, ".minecraft");
            gameDir = Path.Combine(appData, Config.GameDirName);
        }

        List<string> ProfileFiles()
        {
            var list = new List<string>();
            foreach (var name in new[] { "launcher_profiles.json", "launcher_profiles_microsoft_store.json" })
            {
                var p = Path.Combine(mcDir, name);
                if (File.Exists(p)) list.Add(p);
            }
            return list;
        }

        public void Run()
        {
            var profileFiles = ProfileFiles();
            if (profileFiles.Count == 0) throw new LauncherMissing();

            Status("Modpack ophalen...");
            Progress(2);
            byte[] pack = LocalPack != null ? File.ReadAllBytes(LocalPack) : Download(Config.PackUrl);

            using (var zip = new ZipArchive(new MemoryStream(pack), ZipArchiveMode.Read))
            {
                var indexEntry = zip.GetEntry("modrinth.index.json");
                if (indexEntry == null) throw new UserError("Het modpack is beschadigd (geen modrinth.index.json).");
                Dictionary<string, object> index;
                using (var r = new StreamReader(indexEntry.Open(), Encoding.UTF8))
                    index = json.Deserialize<Dictionary<string, object>>(r.ReadToEnd());

                var deps = D(index["dependencies"]);
                string mc = (string)deps["minecraft"];
                if (!deps.ContainsKey("fabric-loader")) throw new UserError("Dit modpack gebruikt geen Fabric; dat kan deze installer niet.");
                string loader = (string)deps["fabric-loader"];

                Status("Fabric " + loader + " voor Minecraft " + mc + " klaarzetten...");
                Progress(5);
                string versionId = InstallFabric(mc, loader);

                Directory.CreateDirectory(gameDir);
                var managed = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

                var files = L(index["files"]).Select(D).Where(f =>
                {
                    object env;
                    return !(f.TryGetValue("env", out env) && env != null && (string)D(env)["client"] == "unsupported");
                }).ToList();

                for (int i = 0; i < files.Count; i++)
                {
                    var f = files[i];
                    string rel = SafeRelative((string)f["path"]);
                    string target = Path.Combine(gameDir, rel);
                    string sha1 = ((string)D(f["hashes"])["sha1"]).ToLowerInvariant();
                    managed.Add(rel);
                    Progress(10 + 80 * i / Math.Max(1, files.Count));
                    if (File.Exists(target) && Sha1(File.ReadAllBytes(target)) == sha1) continue;

                    Status("Downloaden: " + Path.GetFileName(rel));
                    byte[] data = null;
                    foreach (var url in L(f["downloads"]).Cast<string>())
                    {
                        try { data = Download(url); if (Sha1(data) == sha1) break; data = null; }
                        catch (WebException) { data = null; }
                    }
                    if (data == null) throw new UserError("Kon " + Path.GetFileName(rel) + " niet downloaden. Check je internet en probeer opnieuw.");
                    WriteFile(target, data);
                }

                // overrides: mods/resourcepacks/shaderpacks altijd bijwerken, instellingen (config) alleen de eerste keer
                Status("Instellingen klaarzetten...");
                Progress(92);
                foreach (var e in zip.Entries)
                {
                    string name = e.FullName.Replace('\\', '/');
                    string rel;
                    if (name.StartsWith("overrides/")) rel = name.Substring("overrides/".Length);
                    else if (name.StartsWith("client-overrides/")) rel = name.Substring("client-overrides/".Length);
                    else continue;
                    if (rel.Length == 0 || rel.EndsWith("/")) continue;
                    rel = SafeRelative(rel);
                    string target = Path.Combine(gameDir, rel);
                    bool content = rel.StartsWith("mods" + Path.DirectorySeparatorChar) || rel.StartsWith("resourcepacks" + Path.DirectorySeparatorChar)
                        || rel.StartsWith("shaderpacks" + Path.DirectorySeparatorChar);
                    if (content) managed.Add(rel);
                    else if (File.Exists(target)) continue;
                    using (var s = e.Open())
                    using (var ms = new MemoryStream())
                    {
                        s.CopyTo(ms);
                        WriteFile(target, ms.ToArray());
                    }
                }

                // bestanden die eerder door ons zijn neergezet maar niet meer in het pack zitten: weg
                string listFile = Path.Combine(gameDir, ".ooitgedacht-bestanden.txt");
                if (File.Exists(listFile))
                {
                    foreach (var old in File.ReadAllLines(listFile))
                    {
                        if (old.Trim().Length == 0 || managed.Contains(old)) continue;
                        string p = Path.Combine(gameDir, SafeRelative(old));
                        if (File.Exists(p)) File.Delete(p);
                    }
                }
                File.WriteAllLines(listFile, managed.OrderBy(x => x).ToArray());

                string serversDat = Path.Combine(gameDir, "servers.dat");
                if (!File.Exists(serversDat)) File.WriteAllBytes(serversDat, ServersDat());

                Status("Profiel in de Minecraft Launcher zetten...");
                Progress(96);
                foreach (var p in profileFiles) UpdateProfile(p, versionId);

                string packVersion = index.ContainsKey("versionId") ? (string)index["versionId"] : "?";
                Progress(100);
                Status("Klaar! (modpack " + packVersion + ")");
            }
        }

        string InstallFabric(string mc, string loader)
        {
            string url = "https://meta.fabricmc.net/v2/versions/loader/" + Uri.EscapeDataString(mc) + "/" + Uri.EscapeDataString(loader) + "/profile/json";
            string text = Encoding.UTF8.GetString(Download(url));
            string id = (string)json.Deserialize<Dictionary<string, object>>(text)["id"];
            string dir = Path.Combine(mcDir, "versions", id);
            Directory.CreateDirectory(dir);
            File.WriteAllText(Path.Combine(dir, id + ".json"), text, new UTF8Encoding(false));
            string jar = Path.Combine(dir, id + ".jar");
            if (!File.Exists(jar)) File.WriteAllBytes(jar, new byte[0]); // zo doet de Fabric-installer het ook
            return id;
        }

        void UpdateProfile(string file, string versionId)
        {
            string text = File.ReadAllText(file, Encoding.UTF8);
            var root = json.Deserialize<Dictionary<string, object>>(text);
            object po;
            Dictionary<string, object> profiles = root.TryGetValue("profiles", out po) && po != null ? D(po) : new Dictionary<string, object>();
            root["profiles"] = profiles;

            object existing;
            Dictionary<string, object> profile;
            string now = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
            if (profiles.TryGetValue(Config.ProfileId, out existing) && existing != null) profile = D(existing);
            else
            {
                profile = new Dictionary<string, object>();
                profile["created"] = now;
                profile["javaArgs"] = JavaArgs();
            }
            profile["name"] = Config.ProfileName;
            profile["type"] = "custom";
            profile["lastVersionId"] = versionId;
            profile["gameDir"] = gameDir;
            profile["icon"] = IconDataUri();
            profile["lastUsed"] = now; // zo staat dit profiel bovenaan/geselecteerd
            profiles[Config.ProfileId] = profile;

            File.Copy(file, file + ".ooitgedacht-backup", true);
            File.WriteAllText(file, json.Serialize(root), new UTF8Encoding(false));
        }

        static string JavaArgs()
        {
            ulong gb = new Microsoft.VisualBasic.Devices.ComputerInfo().TotalPhysicalMemory / (1024UL * 1024 * 1024);
            int xmx = gb >= 15 ? 4 : gb >= 7 ? 3 : 2;
            return "-Xmx" + xmx + "G -XX:+UnlockExperimentalVMOptions -XX:+UseG1GC -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=32M";
        }

        public static byte[] IconPng()
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("OoitGedacht.icon.png"))
            using (var ms = new MemoryStream())
            {
                s.CopyTo(ms);
                return ms.ToArray();
            }
        }

        static string IconDataUri()
        {
            return "data:image/png;base64," + Convert.ToBase64String(IconPng());
        }

        // servers.dat (NBT, ongecomprimeerd) met alleen onze server
        static byte[] ServersDat()
        {
            var ms = new MemoryStream();
            var w = new BinaryWriter(ms);
            w.Write((byte)10); NbtString(w, "");
            w.Write((byte)9); NbtString(w, "servers"); w.Write((byte)10);
            w.Write(new byte[] { 0, 0, 0, 1 });
            w.Write((byte)8); NbtString(w, "name"); NbtString(w, Config.ServerName);
            w.Write((byte)8); NbtString(w, "ip"); NbtString(w, Config.ServerIp);
            w.Write((byte)1); NbtString(w, "hidden"); w.Write((byte)0);
            w.Write((byte)0);
            w.Write((byte)0);
            w.Flush();
            return ms.ToArray();
        }

        static void NbtString(BinaryWriter w, string s)
        {
            byte[] b = Encoding.UTF8.GetBytes(s);
            w.Write((byte)(b.Length >> 8));
            w.Write((byte)(b.Length & 0xFF));
            w.Write(b);
        }

        static string SafeRelative(string path)
        {
            string p = path.Replace('/', Path.DirectorySeparatorChar).Replace('\\', Path.DirectorySeparatorChar);
            if (Path.IsPathRooted(p) || p.Split(Path.DirectorySeparatorChar).Any(part => part == ".."))
                throw new UserError("Ongeldig pad in het modpack: " + path);
            return p;
        }

        static void WriteFile(string target, byte[] data)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(target));
            string tmp = target + ".download";
            File.WriteAllBytes(tmp, data);
            if (File.Exists(target)) File.Delete(target);
            File.Move(tmp, target);
        }

        static byte[] Download(string url)
        {
            using (var wc = new WebClient())
            {
                wc.Headers[HttpRequestHeader.UserAgent] = Config.UserAgent;
                return wc.DownloadData(url);
            }
        }

        static string Sha1(byte[] data)
        {
            using (var sha = SHA1.Create())
                return BitConverter.ToString(sha.ComputeHash(data)).Replace("-", "").ToLowerInvariant();
        }

        static Dictionary<string, object> D(object o) { return (Dictionary<string, object>)o; }
        static List<object> L(object o) { return ((IEnumerable)o).Cast<object>().ToList(); }
    }

    class MainForm : Form
    {
        readonly Label status;
        readonly ProgressBar bar;
        readonly Button play;
        readonly Image logo;

        static readonly Color Bg = Color.FromArgb(30, 31, 34);
        static readonly Color Green = Color.FromArgb(59, 165, 93);

        public MainForm()
        {
            Text = "OoitGedacht SMP";
            ClientSize = new Size(460, 300);
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Bg;
            ForeColor = Color.White;
            Font = new Font("Segoe UI", 10f);
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);
            logo = Image.FromStream(new MemoryStream(Installer.IconPng()));
            DoubleBuffered = true;

            Controls.Add(new Label
            {
                Text = "OoitGedacht SMP",
                Font = new Font("Segoe UI", 20f, FontStyle.Bold),
                AutoSize = true,
                Location = new Point(124, 34)
            });
            Controls.Add(new Label
            {
                Text = Config.ServerIp,
                ForeColor = Color.FromArgb(170, 175, 185),
                AutoSize = true,
                Location = new Point(128, 76)
            });

            status = new Label
            {
                Text = "Druk op Spelen om alles klaar te zetten.",
                ForeColor = Color.FromArgb(200, 205, 215),
                Location = new Point(24, 140),
                Size = new Size(412, 40)
            };
            Controls.Add(status);

            bar = new ProgressBar { Location = new Point(24, 184), Size = new Size(412, 8), Visible = false };
            Controls.Add(bar);

            play = new Button
            {
                Text = "Spelen",
                Font = new Font("Segoe UI", 14f, FontStyle.Bold),
                Location = new Point(24, 212),
                Size = new Size(412, 60),
                FlatStyle = FlatStyle.Flat,
                BackColor = Green,
                ForeColor = Color.White,
                Cursor = Cursors.Hand
            };
            play.FlatAppearance.BorderSize = 0;
            play.Click += delegate { Start(); };
            Controls.Add(play);
            AcceptButton = play;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            e.Graphics.InterpolationMode = InterpolationMode.NearestNeighbor;
            e.Graphics.PixelOffsetMode = PixelOffsetMode.Half;
            e.Graphics.DrawImage(logo, new Rectangle(24, 24, 88, 88));
        }

        void Start()
        {
            if (LauncherRunning())
            {
                MessageBox.Show(this, "Sluit eerst de Minecraft Launcher (en Minecraft zelf) en druk dan opnieuw op Spelen.",
                    "OoitGedacht SMP", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            play.Enabled = false;
            play.BackColor = Color.FromArgb(70, 75, 80);
            bar.Visible = true;
            bar.Value = 0;

            var installer = new Installer(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData));
            installer.Status = s => BeginInvoke((Action)(() => status.Text = s));
            installer.Progress = p => BeginInvoke((Action)(() => bar.Value = Math.Max(0, Math.Min(100, p))));

            new Thread(() =>
            {
                string error = null;
                try { installer.Run(); }
                catch (LauncherMissing ex) { BeginInvoke((Action)(() => AskDownloadLauncher(ex.Message))); return; }
                catch (UserError ex) { error = ex.Message; }
                catch (WebException ex) { error = "Kon niet downloaden:\n" + ex.Message + "\n\nCheck je internet en probeer het later opnieuw. Blijft dit gebeuren? Meld het in de Discord."; }
                catch (Exception ex) { error = "Er ging iets mis:\n" + ex.Message + "\n\nIs Minecraft nog open? Sluit het en probeer opnieuw."; }
                BeginInvoke((Action)(() => Done(error)));
            }) { IsBackground = true }.Start();
        }

        void AskDownloadLauncher(string message)
        {
            Done(null, false);
            status.Text = "Eerst de Minecraft Launcher installeren.";
            if (MessageBox.Show(this, message, "OoitGedacht SMP", MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
            {
                try { Process.Start("https://www.minecraft.net/download"); }
                catch (Exception) { }
            }
        }

        void Done(string error)
        {
            Done(error, true);
        }

        void Done(string error, bool finish)
        {
            play.Enabled = true;
            play.BackColor = Green;
            bar.Visible = false;
            if (!finish) return;
            if (error != null)
            {
                status.Text = "Niet gelukt. Probeer het opnieuw.";
                MessageBox.Show(this, error, "OoitGedacht SMP", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            Program.InstallShortcut();
            if (OpenLauncher())
            {
                status.Text = "Klaar! De Minecraft Launcher gaat open: kies \"" + Config.ProfileName + "\" en druk op Spelen.";
            }
            else
            {
                status.Text = "Klaar! Open de Minecraft Launcher, kies \"" + Config.ProfileName + "\" en druk op Spelen.";
            }
            play.Text = "Opnieuw";
        }

        static bool LauncherRunning()
        {
            return Process.GetProcessesByName("MinecraftLauncher").Length > 0
                || Process.GetProcessesByName("Minecraft").Length > 0;
        }

        static bool OpenLauncher()
        {
            var candidates = new List<string>();
            foreach (var env in new[] { "ProgramFiles(x86)", "ProgramFiles" })
            {
                var pf = Environment.GetEnvironmentVariable(env);
                if (!string.IsNullOrEmpty(pf)) candidates.Add(Path.Combine(pf, @"Minecraft Launcher\MinecraftLauncher.exe"));
            }
            // de Xbox-app-versie (C:\XboxGames\...\Minecraft.exe) kan niet direct gestart worden, die gaat via shell:AppsFolder hieronder
            foreach (var c in candidates)
            {
                if (!File.Exists(c)) continue;
                try { Process.Start(new ProcessStartInfo(c) { UseShellExecute = true }); return true; }
                catch (Exception) { }
            }
            try
            {
                // Microsoft Store / Xbox-app versie
                Process.Start("explorer.exe", @"shell:AppsFolder\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft");
                return true;
            }
            catch (Exception) { return false; }
        }
    }

    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12 | (SecurityProtocolType)12288; // TLS 1.2 + 1.3

            // Testmodus: OoitGedacht.exe --test <map> [--pack <bestand.mrpack>]  (map speelt %APPDATA%)
            int t = Array.IndexOf(args, "--test");
            if (t >= 0 && t + 1 < args.Length)
            {
                var installer = new Installer(args[t + 1]);
                int p = Array.IndexOf(args, "--pack");
                if (p >= 0 && p + 1 < args.Length) installer.LocalPack = args[p + 1];
                var log = new StringBuilder();
                installer.Status = s => log.AppendLine(s);
                int code = 0;
                try { installer.Run(); }
                catch (Exception ex) { log.AppendLine("FOUT: " + ex); code = 1; }
                File.WriteAllText(Path.Combine(args[t + 1], "test-log.txt"), log.ToString());
                return code;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
            return 0;
        }

        // Kopieert de exe naar %LOCALAPPDATA%\OoitGedacht en zet een snelkoppeling op het bureaublad,
        // zodat spelers hem voortaan daar starten (en dus altijd eerst bijwerken).
        public static void InstallShortcut()
        {
            try
            {
                string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "OoitGedacht");
                string exe = Path.Combine(dir, "OoitGedacht.exe");
                string self = Application.ExecutablePath;
                if (!string.Equals(Path.GetFullPath(self), Path.GetFullPath(exe), StringComparison.OrdinalIgnoreCase))
                {
                    Directory.CreateDirectory(dir);
                    File.Copy(self, exe, true);
                }

                string lnk = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "OoitGedacht SMP.lnk");
                Type shellType = Type.GetTypeFromProgID("WScript.Shell");
                object shell = Activator.CreateInstance(shellType);
                object sc = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { lnk });
                Type scType = sc.GetType();
                scType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, sc, new object[] { exe });
                scType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, sc, new object[] { dir });
                scType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, sc, new object[] { exe + ",0" });
                scType.InvokeMember("Description", BindingFlags.SetProperty, null, sc, new object[] { "OoitGedacht SMP bijwerken en spelen" });
                scType.InvokeMember("Save", BindingFlags.InvokeMethod, null, sc, null);
            }
            catch (Exception) { /* snelkoppeling is een extraatje */ }
        }
    }
}
