package formatfactory;

import com.google.gson.*;
import java.net.InetAddress;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Deterministic threshold checks without stressing the user's CPU or waiting for timers. */
public final class ResourceMonitorTest {
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static ResourceMonitor.Config config(Path directory) { return new ResourceMonitor.Config(directory, 5, 80, 85, 85, 3, 10, 7, 1024); }
    static ResourceMonitor.Reading reading(Double cpu, Long memory, long heap) {
        return new ResourceMonitor.Reading(cpu, 2.0, memory, memory == null ? null : 100L, heap, 100, 10, 8, "test", 1, 2);
    }
    static JsonObject settings() { return JsonParser.parseString("{\"cpuPercent\":81,\"memoryPercent\":86,\"heapPercent\":87,\"intervalSeconds\":2,\"consecutiveSamples\":4}").getAsJsonObject(); }
    static void rejected(ResourceMonitor monitor, JsonObject invalid) throws Exception {
        var before = monitor.config;
        try { monitor.update(invalid); throw new AssertionError("Invalid settings accepted: " + invalid); }
        catch (IllegalArgumentException expected) { check(monitor.config.equals(before), "Rejected settings changed monitoring"); }
    }
    static List<ResourceMonitor.Event> events(ResourceMonitor monitor, String type) {
        return monitor.events.stream().filter(e -> e.type().equals(type)).toList();
    }
    static long journals(Path directory, String kind) throws Exception {
        try (var files = Files.list(directory)) { return files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().startsWith(kind + "-") && p.toString().endsWith(".jsonl")).count(); }
    }
    public static void main(String[] args) throws Exception {
        Path parent = Path.of("build", "monitor-test"); Files.createDirectories(parent);
        Path directory = Files.createTempDirectory(parent, "run-");
        var clock = new TestClock(); var input = new AtomicReference<>(reading(80.0, 85L, 85));
        try (var monitor = new ResourceMonitor(config(directory), input::get, clock, false)) {
            for (int i = 0; i < 2; i++) { monitor.tick(); clock.advance(5); }
            check(events(monitor, "ALERT").isEmpty(), "Alert before three consecutive samples");
            monitor.tick(); clock.advance(5);
            check(events(monitor, "ALERT").size() == 3 && monitor.view(10).get("status").equals("alerting"), "Three metrics must alert at equality");
            monitor.tick(); clock.advance(5);
            check(events(monitor, "REMINDER").isEmpty(), "Reminder before cooldown");
            monitor.tick(); clock.advance(5);
            check(events(monitor, "REMINDER").size() == 3, "Missing repeated alerts after cooldown");
            input.set(reading(Double.NaN, null, -1)); monitor.tick(); clock.advance(5);
            check(monitor.rules.stream().allMatch(r -> r.active && r.value == null && r.consecutive == 0), "Missing measurements must not mean zero/recovery");
            input.set(reading(79.0, 84L, 84)); monitor.tick(); clock.advance(5);
            check(events(monitor, "RECOVERED").isEmpty(), "Noisy values near threshold must not recover");
            input.set(reading(75.0, 80L, 80)); monitor.tick(); clock.advance(5);
            check(events(monitor, "RECOVERED").size() == 3 && monitor.rules.stream().noneMatch(r -> r.active), "Recovery at five percentage points below threshold");
            input.set(reading(90.0, 90L, 90)); monitor.tick(); clock.advance(5); monitor.tick(); clock.advance(5);
            input.set(reading(null, null, -1)); monitor.tick(); clock.advance(5);
            input.set(reading(90.0, 90L, 90)); monitor.tick(); clock.advance(5); monitor.tick(); clock.advance(5);
            check(events(monitor, "ALERT").size() == 3, "Interrupted streak was not reset");
            monitor.tick(); clock.advance(5); check(events(monitor, "ALERT").size() == 6, "A second episode should alert again");
            check(journals(directory, "metrics") > 1, "Log size rollover did not occur");
            var original = settings();
            for (String key : List.of("cpuPercent", "memoryPercent", "heapPercent", "intervalSeconds", "consecutiveSamples")) {
                JsonObject invalid = original.deepCopy(); invalid.remove(key); rejected(monitor, invalid);
                invalid = original.deepCopy(); invalid.addProperty(key, "80"); rejected(monitor, invalid);
                invalid = original.deepCopy(); invalid.addProperty(key, 0); rejected(monitor, invalid);
            }
            for (String key : List.of("intervalSeconds", "consecutiveSamples")) {
                JsonObject invalid = original.deepCopy(); invalid.addProperty(key, 1.5); rejected(monitor, invalid);
            }
            JsonObject invalid = original.deepCopy(); invalid.addProperty("cpuPercent", 101); rejected(monitor, invalid);
            monitor.update(original);
            check(monitor.config.intervalSeconds() == 2 && monitor.config.cpuPercent() == 81 && monitor.rules.stream().noneMatch(r -> r.active), "New settings/reset not applied");
            check(ResourceMonitor.Config.load(directory).equals(monitor.config), "Settings not persisted for restart");
            check(events(monitor, "CONFIG_CHANGED").size() == 1, "Missing settings audit event");
            clock.advance(86400); monitor.tick();
            check(monitor.alerts.writer == null, "Idle alert journal kept yesterday's file locked on Windows");
            check(monitor.history.size() == 1 && ((List<?>)monitor.view(1).get("samples")).size() == 1, "History older than one hour not removed");
            String id = UUID.randomUUID().toString();
            Path expired = directory.resolve("metrics-2026-09-01-" + id + "-0.jsonl"), unrelated = directory.resolve("important.txt");
            Files.writeString(expired, "{}"); Files.writeString(unrelated, "keep");
            var old = FileTime.from(clock.instant().minusSeconds(8 * 86400)); Files.setLastModifiedTime(expired, old); Files.setLastModifiedTime(unrelated, old);
            monitor.cleanup(clock.instant());
            check(!Files.exists(expired) && Files.exists(unrelated) && Files.exists(directory.resolve("settings.json")), "Retention cleanup removed unrelated/config files");
        }
        Path faultDirectory = Files.createTempDirectory(parent, "fault-"); var faultClock = new TestClock();
        var faultConfig = new ResourceMonitor.Config(faultDirectory, 5, 80, 85, 85, 1, 10, 7, 1024);
        var failProbe = new boolean[]{false};
        try (var monitor = new ResourceMonitor(faultConfig, () -> {
            if (failProbe[0]) throw new IllegalStateException("probe failure"); return reading(90.0, 90L, 90);
        }, faultClock, false)) {
            // Block only the first metric log file; alert journal must still succeed.
            Files.createDirectory(faultDirectory.resolve("metrics-2026-10-08-" + monitor.metrics.instance + "-0.jsonl"));
            monitor.tick(); faultClock.advance(5);
            check(monitor.recordingError != null && journals(faultDirectory, "alerts") == 1, "Metric journal failure prevented alert recording");
            monitor.tick(); faultClock.advance(5); check(monitor.recordingError == null, "Journal failed to retry and recover");
            failProbe[0] = true; monitor.tick(); faultClock.advance(5);
            check(monitor.samplingError != null && monitor.rules.stream().allMatch(r -> r.active && r.value == null), "Sampler failure caused false recovery");
            failProbe[0] = false; monitor.tick(); check(monitor.samplingError == null, "Sampler did not retry");
            Files.createDirectory(faultDirectory.resolve("settings.json")); var before = monitor.config;
            try { monitor.update(settings()); throw new AssertionError("Failed settings write was accepted"); }
            catch (java.io.IOException expected) { check(monitor.config.equals(before), "Failed settings write changed live configuration"); }
        }
        var local = InetAddress.getByName("127.0.0.1"); var remote = InetAddress.getByName("192.0.2.10");
        String token = "test-admin-token-with-at-least-32-characters";
        check(Server.monitorAuthorized("development", local, "", null), "Local development should allow access");
        check(!Server.monitorAuthorized("development", remote, "", null), "Remote development access leaked metrics");
        check(!Server.monitorAuthorized("wechat", local, "", null), "Production without admin token allowed access");
        check(!Server.monitorAuthorized("development", local, token, null), "Configured token should also protect localhost");
        check(!Server.monitorAuthorized("wechat", remote, token, "Bearer normal-user-token"), "Normal user token allowed admin access");
        check(Server.monitorAuthorized("wechat", remote, token, "Bearer " + token), "Correct admin token rejected");
        var real = ResourceMonitor.systemReading(new int[]{1, 2});
        check(real.heapMaxBytes() > 0 && real.heapUsedBytes() >= 0 && real.processors() > 0 && real.runningJobs() == 1, "Actual JVM sample invalid");
        check(ResourceMonitor.percent(-1.0) == null && ResourceMonitor.percent(Double.POSITIVE_INFINITY) == null, "CPU sentinel treated as real usage");
        System.out.println("PASS: resource sampling, three-sample alerts, cooldown, hysteresis, unavailable readings, persistence, validation, rotation, retention, I/O retry and admin authorization");
    }
}
