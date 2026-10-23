package com.nexusrmm.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Detects whether the PawnIO kernel driver is installed and running, by
 * asking Windows directly (`sc query PawnIO`) rather than assuming.
 *
 * IMPORTANT — scope of this class: this only detects driver *presence*.
 * It does not implement the PawnIO IOCTL protocol (loading a compiled MSR
 * module such as IntelMSR.bin and executing it via DeviceIoControl) needed
 * to actually read Intel package thermal MSRs. That protocol was
 * identified during development (device type 41394, IOCTL_PIO_LOAD_BINARY
 * / IOCTL_PIO_EXECUTE_FN) but implementing the register-level read without
 * real hardware to validate against would risk silently returning a wrong
 * number, which this project's honesty rules treat as worse than reporting
 * "unsupported." See docs/hardware-monitoring.md for what a future
 * implementation needs. Until then, {@link CpuTemperatureProvider} reports
 * CPU_TEMPERATURE as UNAVAILABLE with this class's detection result folded
 * into the diagnostic message, so the frontend at least tells the operator
 * whether the driver is present.
 */
@Component
public class PawnIoDriverDetector {

    private static final Logger log = LoggerFactory.getLogger(PawnIoDriverDetector.class);

    public record DriverStatus(boolean applicable, boolean installed, boolean running, String detail) {
        static DriverStatus notApplicable(String detail) {
            return new DriverStatus(false, false, false, detail);
        }
    }

    public DriverStatus detect() {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase().contains("windows")) {
            return DriverStatus.notApplicable("PawnIO is Windows-only; this host is running " + osName + ".");
        }

        try {
            Process process = new ProcessBuilder("sc", "query", "PawnIO")
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new DriverStatus(true, false, false, "Timed out querying the PawnIO service.");
            }

            String text = output.toString();
            if (text.contains("1060") || text.toLowerCase().contains("does not exist")) {
                return new DriverStatus(true, false, false, "PawnIO driver is not installed on this machine.");
            }

            boolean running = text.toUpperCase().contains("RUNNING");
            String detail = running
                    ? "PawnIO driver is installed and running."
                    : "PawnIO driver is installed but not currently running.";
            return new DriverStatus(true, true, running, detail);
        } catch (Exception e) {
            log.debug("PawnIO driver detection failed", e);
            return new DriverStatus(true, false, false, "Could not query driver state: " + e.getMessage());
        }
    }
}
