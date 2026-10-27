package com.nexusrmm.advisor.process;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Small, deliberately conservative catalogue of well-known Windows and
 * desktop processes: what they are and the first thing worth checking.
 * Only entries whose description is certain are included; an unknown name
 * simply gets no annotation (the advisor then says so rather than guess).
 */
public final class ProcessKnowledge {

    public record Entry(String description, String advice, boolean systemComponent) {
    }

    private static final Map<String, Entry> ENTRIES = new HashMap<>();

    static {
        app("chrome", "Google Chrome (all tabs, extensions and helper processes).",
                "Close tabs and windows you no longer need, turn on Memory Saver in chrome://settings/performance, and remove unused extensions at chrome://extensions.");
        app("msedge", "Microsoft Edge (all tabs, extensions and helper processes).",
                "Enable sleeping tabs in edge://settings/system, close unused tabs, and review extensions at edge://extensions.");
        app("firefox", "Mozilla Firefox (all tabs and helper processes).",
                "Open about:processes to see which tab or extension is heavy, then close or disable it.");
        app("code", "Visual Studio Code (editor, extensions and language servers).",
                "Run \"Developer: Show Running Extensions\" from the command palette and disable the heaviest extensions you do not need.");
        app("onedrive", "Microsoft OneDrive sync client.",
                "A large initial or bulk sync is the usual cause; pause syncing from the tray icon and resume later if it is in the way.");
        app("ms-teams", "Microsoft Teams.", "Quit Teams fully (tray icon > Quit) when you are not in meetings.");
        app("teams", "Microsoft Teams (classic).", "Quit Teams fully (tray icon > Quit) when you are not in meetings.");
        app("slack", "Slack desktop app.", "Quit it when not needed, or use the web version in a browser tab you can close.");
        app("discord", "Discord.", "Quit it fully (tray icon > Quit) when not in use; hardware acceleration can be toggled in its settings.");
        app("spotify", "Spotify.", "Close it when not listening; it keeps a cache and background processes running.");
        app("docker desktop", "Docker Desktop.",
                "Limit CPU and memory in Docker Desktop > Settings > Resources, and stop containers you are not using (docker ps, docker stop).");
        app("com.docker.backend", "Docker Desktop backend service.",
                "Stop idle containers (docker ps, docker stop) and limit resources in Docker Desktop > Settings > Resources.");
        app("vmmem", "Memory and CPU used by a virtual machine such as WSL 2 or Hyper-V.",
                "If it is WSL 2, run \"wsl --shutdown\" when you are done, and cap its memory with a .wslconfig file (memory=... under [wsl2]).");
        app("vmmemwsl", "Memory and CPU used by the WSL 2 virtual machine.",
                "Run \"wsl --shutdown\" when done, and cap its memory with a .wslconfig file (memory=... under [wsl2]).");
        app("java", "A Java program (this can include Nexus-RMM itself).",
                "Task Manager > Details > right-click the column header > Select columns > Command line shows which program started it.");
        app("javaw", "A Java program (this can include Nexus-RMM itself).",
                "Task Manager > Details > Select columns > Command line shows which program started it.");

        system("msmpeng", "Microsoft Defender Antivirus scanning engine.",
                "Usually temporary during a scan or update. Check Windows Security > Virus & threat protection for a running scan. Do not turn protection off to save CPU.");
        system("searchindexer", "Windows Search indexer.",
                "Normally settles by itself after indexing. Settings > Privacy & security > Searching Windows lets you narrow what is indexed.");
        system("tiworker", "Windows Modules Installer Worker (Windows Update servicing).",
                "Let Windows Update finish and restart when it asks; this load ends once updates are installed.");
        system("svchost", "Windows service host; many system services share this process name.",
                "Task Manager > Services tab shows which service sits behind a heavy svchost. Do not end svchost.exe; stop or reconfigure the specific service instead.");
        system("system", "The Windows kernel and device drivers.",
                "Sustained high use usually points to a driver. Check the Events page for hardware errors and update drivers for recently changed hardware.");
        system("memory compression", "Windows memory compression.",
                "This is normal: Windows compresses rarely used memory instead of paging it to disk. It is not a leak.");
        system("dwm", "Desktop Window Manager (draws the Windows desktop).",
                "High use can point to a graphics driver issue or very many or high-resolution monitors; update the graphics driver.");
        system("explorer", "Windows Explorer (desktop, taskbar and file windows).",
                "Close unused File Explorer windows; a misbehaving shell extension can be found by restarting Explorer from Task Manager.");
    }

    private ProcessKnowledge() {
    }

    private static void app(String name, String description, String advice) {
        ENTRIES.put(name, new Entry(description, advice, false));
    }

    private static void system(String name, String description, String advice) {
        ENTRIES.put(name, new Entry(description, advice, true));
    }

    /** Canonical grouping key: lower case, without a trailing {@code .exe}. */
    public static String normalize(String processName) {
        if (processName == null) {
            return "";
        }
        String n = processName.trim().toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") ? n.substring(0, n.length() - 4) : n;
    }

    public static Optional<Entry> lookup(String processName) {
        return Optional.ofNullable(ENTRIES.get(normalize(processName)));
    }
}
