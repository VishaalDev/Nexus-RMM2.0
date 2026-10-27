package com.nexusrmm.advisor.engine;

import com.nexusrmm.advisor.model.Effort;
import com.nexusrmm.advisor.model.Recommendation;

import java.util.List;

/**
 * The advice itself, kept apart from the rules that decide when it applies.
 * Every entry is a manual step for the person; none is executed by Nexus-RMM.
 * Wording is deliberately modest about benefits: the advisor cannot measure
 * the effect of a step before it is taken.
 */
final class RecommendationCatalog {

    private RecommendationCatalog() {
    }

    private static Recommendation r(String code, String action, String why, Effort e, String benefit) {
        return Recommendation.of(code, action, why, e, benefit);
    }

    // ---- CPU ---------------------------------------------------------------------------------

    static Recommendation cpuIdentify() {
        return r("cpu.identify",
                "Open Task Manager (Ctrl+Shift+Esc), go to Processes and sort by CPU. Close or restart programs you are not using.",
                "Sustained load almost always comes from one or two programs, and seeing which is the fastest way to find out.",
                Effort.EASY, "Frees CPU right away if a program you do not need is the cause.");
    }

    static Recommendation cpuStartup() {
        return r("cpu.startup",
                "Open Task Manager > Startup apps and disable programs you do not need at sign-in.",
                "Programs that start with Windows keep running in the background all day.",
                Effort.MODERATE, "Lower baseline CPU use and faster sign-in.");
    }

    static Recommendation unknownProcess(String name) {
        return r("cpu.unknown-process",
                "Nexus-RMM does not recognise \"" + name + "\". In Task Manager, right-click it > Open file location to confirm it belongs to software you installed; run a Windows Security full scan if you cannot tell.",
                "A heavy process you cannot account for is worth a quick check.",
                Effort.EASY, "Confirms whether the load is expected.");
    }

    static Recommendation processAdvice(String name, String advice, boolean system) {
        Recommendation rec = r("process." + com.nexusrmm.advisor.process.ProcessKnowledge.normalize(name),
                advice,
                "This is currently the biggest contributor Nexus-RMM can see.",
                system ? Effort.MODERATE : Effort.EASY,
                "Targets the main contributor instead of guessing.");
        return system ? rec.withCaution("This is part of Windows or its security tooling: do not end it from Task Manager.") : rec;
    }

    // ---- Memory ------------------------------------------------------------------------------

    static Recommendation memIdentify() {
        return r("mem.identify",
                "Open Task Manager > Processes and sort by Memory. Close what you are not using; for browsers, close tabs or windows.",
                "Memory pressure usually comes from a few large applications.",
                Effort.EASY, "Immediately returns RAM to the system.");
    }

    static Recommendation memRestartApps() {
        return r("mem.restart-apps",
                "Restart the largest long-running applications (and your browser) now and then.",
                "Applications that run for days can gradually accumulate memory they no longer need.",
                Effort.EASY, "Resets their memory use to a fresh baseline.");
    }

    static Recommendation memRestartWindows(long uptimeDays) {
        return r("mem.restart-windows",
                "Restart Windows (choose Restart, not Shut down: Fast Startup can keep the previous session alive). It has been running for about " + uptimeDays + " days.",
                "A long uptime lets drivers and services accumulate memory; a restart clears all of it.",
                Effort.EASY, "Clears accumulated memory use and applies pending updates.");
    }

    static Recommendation memMoreRam() {
        return r("mem.more-ram",
                "If memory stays high with ordinary work and no single program explains it, adding RAM (or closing background tools you rely on less) is the lasting fix.",
                "Spreading usage across many normal programs cannot be fixed by closing one of them.",
                Effort.ADVANCED, "Removes the pressure instead of managing around it.");
    }

    static Recommendation trendWatch(String what) {
        return r("trend.watch",
                "Note any software installed or changed around when " + what + " began rising, and review startup apps and browser extensions.",
                "A steady upward drift usually follows a specific change.",
                Effort.MODERATE, "Finds the cause before it becomes a problem.");
    }

    // ---- Thermal -----------------------------------------------------------------------------

    static Recommendation thermalReduceLoad() {
        return r("thermal.reduce-load",
                "Reduce sustained CPU load (see the CPU advice) and close heavy programs while the machine is hot.",
                "CPU load was high over the same period, so the heat is most likely workload-driven.",
                Effort.EASY, "Lower load directly lowers temperature.");
    }

    static Recommendation thermalAirflow() {
        return r("thermal.airflow",
                "Make sure vents are not blocked. Laptops should sit on a hard, flat surface, not a bed or sofa.",
                "Blocked airflow raises temperatures at any load.",
                Effort.EASY, "Often lowers temperature noticeably.");
    }

    static Recommendation thermalCooling() {
        return r("thermal.cooling",
                "Check that fans spin and clean dust from fans and heatsinks. On a desktop with an older CPU cooler, thermal paste that has not been renewed in years can also be the cause.",
                "CPU load was modest, so workload does not explain the heat; cooling is the more likely cause.",
                Effort.ADVANCED, "Restores the machine's designed cooling.")
                .withCaution("Power off and unplug before opening a case; opening a laptop may void its warranty.");
    }

    static Recommendation thermalPowerMode() {
        return r("thermal.power-mode",
                "Open Settings > System > Power & battery and try the Balanced power mode instead of Best performance.",
                "High-performance modes allow the CPU to boost harder, which raises temperature.",
                Effort.EASY, "Cooler, quieter running for a small speed trade-off.");
    }

    // ---- Storage -----------------------------------------------------------------------------

    static Recommendation storageUrgent(String volume) {
        return r("storage.urgent",
                "Free space on " + volume + " now. Aim for at least 15% free.",
                "A nearly full system volume can make Windows Update, hibernation and virtual memory start to fail.",
                Effort.EASY, "Prevents failures and slowdowns.");
    }

    static Recommendation storageCleanup() {
        return r("storage.cleanup",
                "Open Settings > System > Storage, review Temporary files and Cleanup recommendations, and turn on Storage Sense.",
                "Temporary files, update leftovers and the Recycle Bin are safe, quick wins.",
                Effort.EASY, "Often frees several GB with no downside.");
    }

    static Recommendation storageLargeFiles() {
        return r("storage.large-files",
                "Find the largest folders with Settings > System > Storage, or a disk-usage viewer such as WinDirStat or TreeSize. Downloads, old installers, videos and game libraries are the usual culprits.",
                "A few large items normally account for most of the used space.",
                Effort.MODERATE, "Frees the most space for the least effort.");
    }

    static Recommendation storageUninstall() {
        return r("storage.uninstall",
                "Open Settings > Apps > Installed apps, sort by size, and uninstall what you no longer use.",
                "Unused applications and games can occupy tens of GB.",
                Effort.EASY, "Frees space and removes background software.");
    }

    static Recommendation storageMove() {
        return r("storage.move",
                "Move photos, videos and archives you rarely open to another drive or cloud storage.",
                "Bulk media is the largest and least performance-sensitive data.",
                Effort.MODERATE, "Frees space without deleting anything.");
    }

    // ---- Storage I/O -------------------------------------------------------------------------

    static Recommendation ioIdentify() {
        return r("io.identify",
                "Open Resource Monitor (Start > type resmon) > Disk tab to see which processes and files are busy. Nexus-RMM does not collect per-process disk activity yet.",
                "The surge is visible here, but the cause is not.",
                Effort.MODERATE, "Names the program responsible.");
    }

    static Recommendation ioBackground() {
        return r("io.background",
                "Check whether a backup, cloud sync, search indexing, antivirus scan or Windows Update is running; these cause temporary disk surges.",
                "Most unusual disk activity is routine maintenance.",
                Effort.EASY, "Confirms whether it will pass on its own.");
    }

    // ---- Network -----------------------------------------------------------------------------

    static Recommendation netReconnect() {
        return r("net.reconnect",
                "Check the cable or Wi-Fi connection and that the adapter is enabled in Settings > Network & internet.",
                "No adapter reported being connected.",
                Effort.EASY, "Restores connectivity.");
    }

    static Recommendation netDriver() {
        return r("net.driver",
                "If the connection looks fine, update or reinstall the network adapter driver in Device Manager.",
                "A faulty driver can make a working adapter appear down.",
                Effort.ADVANCED, "Fixes adapter detection problems.");
    }

    static Recommendation netIdentify() {
        return r("net.identify",
                "Open Task Manager > Performance and Resource Monitor > Network tab to see which processes are sending or receiving data.",
                "Nexus-RMM measures traffic per adapter, not per program.",
                Effort.MODERATE, "Names the program responsible.");
    }

    static Recommendation netBackground() {
        return r("net.background",
                "Check for cloud sync, backups, updates or large downloads.",
                "Most traffic surges are routine background transfers.",
                Effort.EASY, "Confirms whether it is expected.");
    }

    static Recommendation netUnexpectedUpload() {
        return r("net.unexpected-upload",
                "If sustained upload traffic cannot be explained, run a Windows Security full scan.",
                "Unexplained outbound traffic is the one pattern here worth ruling out as malware.",
                Effort.EASY, "Rules out the serious explanation.");
    }

    // ---- Battery -----------------------------------------------------------------------------

    static Recommendation batteryPlugIn() {
        return r("battery.plug-in", "Plug in the charger now and save your work.",
                "The battery is critically low.", Effort.EASY, "Avoids an abrupt shutdown and lost work.");
    }

    static Recommendation batteryRecharge() {
        return r("battery.recharge", "Plug in the charger soon.",
                "The battery is low.", Effort.EASY, "Keeps the machine running.");
    }

    static Recommendation batteryHabits() {
        return r("battery.habits",
                "Recharge before the battery gets very low, and run \"powercfg /batteryreport\" in a Command Prompt for the battery's health history.",
                "Repeated deep discharges wear a battery faster.",
                Effort.EASY, "Slows battery wear.");
    }

    // ---- Reliability -------------------------------------------------------------------------

    static List<Recommendation> hardwareEvents() {
        return List.of(
                r("rel.hardware-temps",
                        "Check temperatures (see the Thermal section) and remove any overclock or undervolt, then update the BIOS and chipset drivers.",
                        "Hardware error reports (WHEA) are typically caused by instability or overheating of the CPU, memory or PCIe devices.",
                        Effort.ADVANCED, "Removes the most common causes of hardware errors."),
                r("rel.memory-test",
                        "Run Windows Memory Diagnostic (Start > type mdsched) to test your RAM.",
                        "Faulty memory is a frequent source of hardware error events.",
                        Effort.MODERATE, "Confirms or rules out failing RAM."),
                r("rel.backup",
                        "Make sure important files are backed up.",
                        "Hardware errors can corrupt data before any failure is obvious.",
                        Effort.EASY, "Protects your data regardless of the cause."));
    }

    static List<Recommendation> storageEvents() {
        return List.of(
                r("rel.backup-now",
                        "Back up important files now, before anything else.",
                        "Disk and file-system errors can come before a drive failure.",
                        Effort.EASY, "Protects your data while you investigate."),
                r("rel.disk-health",
                        "In PowerShell run \"Get-PhysicalDisk | Get-StorageReliabilityCounter\" and check the drive maker's tool for SMART status. Nexus-RMM does not collect SMART data yet, so it cannot confirm drive health itself.",
                        "This is the quickest way to see whether the drive itself reports problems.",
                        Effort.MODERATE, "Shows whether the drive is failing."),
                r("rel.chkdsk",
                        "Run \"chkdsk /scan\" from an administrator Command Prompt to check the file system.",
                        "File-system errors are often repairable.",
                        Effort.MODERATE, "Finds and can repair file-system problems."));
    }

    static List<Recommendation> powerEvents() {
        return List.of(
                r("rel.power-source",
                        "Check the power cable, wall outlet, surge protector or UPS, and on a laptop the battery and charger.",
                        "Kernel-Power events mean the machine lost power or restarted without a clean shutdown.",
                        Effort.EASY, "Rules out the simplest causes."),
                r("rel.power-overheat",
                        "Check temperatures and the BIOS for overheating shutdowns, and update the BIOS.",
                        "Machines also power off on their own to protect themselves from overheating.",
                        Effort.ADVANCED, "Addresses thermal and firmware causes."));
    }

    static List<Recommendation> driverEvents() {
        return List.of(r("rel.driver",
                "Open the Events page to see which component is involved, then update (or roll back) its driver in Device Manager.",
                "Driver events point at a specific device driver.",
                Effort.MODERATE, "Fixes the faulty driver."));
    }

    static List<Recommendation> otherEvents() {
        return List.of(r("rel.review-events",
                "Review the Events page for the details of these events.",
                "Minor system events are usually informational but worth a glance.",
                Effort.EASY, "Confirms nothing needs action."));
    }

    // ---- Generic -----------------------------------------------------------------------------

    static Recommendation reviewHealth(String component) {
        return r("generic.review-health",
                "Open the Health page and expand " + component + " to see the evidence behind this finding.",
                "Nexus-RMM has no specific advice for this finding yet.",
                Effort.EASY, "Shows the numbers behind it.");
    }

    static Recommendation letDataAccumulate() {
        return r("visibility.wait",
                "Leave Nexus-RMM running for about an hour; most analysis needs a sample window before it can score anything.",
                "The collector has only just started, or has been off.",
                Effort.EASY, "Unlocks health scores and advice.");
    }

    static Recommendation seeLimitations(String doc) {
        return r("visibility.docs",
                "See " + doc + " for why this is unavailable and what enables it.",
                "A signal is missing, so the advisor cannot judge this area.",
                Effort.MODERATE, "Restores monitoring for this area.");
    }
}
