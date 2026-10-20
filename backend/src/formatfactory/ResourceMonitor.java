package formatfactory;

import com.google.gson.*;
import com.sun.management.OperatingSystemMXBean;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Samples independently of conversion jobs; journals and administrator views contain no user data. */
final class ResourceMonitor implements AutoCloseable {
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    record Config(Path directory, int intervalSeconds, double cpuPercent, double memoryPercent,
                  double heapPercent, int consecutiveSamples, int cooldownSeconds, int retentionDays, long maxLogBytes) {
        Config {
            if (intervalSeconds < 1 || intervalSeconds > 300 || consecutiveSamples < 1 || consecutiveSamples > 60
                || cooldownSeconds < 10 || cooldownSeconds > 86400 || retentionDays < 1 || retentionDays > 90
                || maxLogBytes < 1024 || maxLogBytes > 100L * 1024 * 1024)
                throw new IllegalArgumentException("监控间隔应为 1–300 秒，连续次数为 1–60，提醒间隔为 10–86400 秒");
            for (double value : new double[]{cpuPercent, memoryPercent, heapPercent})
                if (!Double.isFinite(value) || value < 1 || value > 100) throw new IllegalArgumentException("告警阈值应为 1–100% 的有限数值");
            directory = directory.toAbsolutePath().normalize();
        }
        Map<String, Object> values() {
            return Map.of("intervalSeconds", intervalSeconds, "cpuPercent", cpuPercent, "memoryPercent", memoryPercent,
                "heapPercent", heapPercent, "consecutiveSamples", consecutiveSamples, "cooldownSeconds", cooldownSeconds,
                "retentionDays", retentionDays, "recoveryMarginPercent", 5, "maxLogMegabytes", maxLogBytes / (1024.0 * 1024));
        }
        static Config load(Path defaultDirectory) throws IOException {
            Path directory = Path.of(Server.env("MONITOR_DIR", defaultDirectory.toString())).toAbsolutePath().normalize();
            JsonObject saved = new JsonObject(); Path file = directory.resolve("settings.json");
            if (Files.isRegularFile(file)) {
                if (Files.size(file) > 8192) throw new IOException("Monitor settings file too large");
                saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            }
            return new Config(directory,
                wholeNumber(saved, "intervalSeconds", "MONITOR_INTERVAL_SECONDS", 5),
                number(saved, "cpuPercent", "MONITOR_CPU_PERCENT", 80),
                number(saved, "memoryPercent", "MONITOR_MEMORY_PERCENT", 85),
                number(saved, "heapPercent", "MONITOR_HEAP_PERCENT", 85),
                wholeNumber(saved, "consecutiveSamples", "MONITOR_CONSECUTIVE_SAMPLES", 3),
                wholeNumber(saved, "cooldownSeconds", "MONITOR_COOLDOWN_SECONDS", 300),
                wholeNumber(saved, "retentionDays", "MONITOR_RETENTION_DAYS", 7),
                (long)(number(saved, "maxLogMegabytes", "MONITOR_MAX_LOG_MB", 20) * 1024 * 1024));
        }
        static double number(JsonObject saved, String key, String env, double fallback) {
            String override = System.getenv(env);
            return override != null && !override.isBlank() ? Double.parseDouble(override)
                : saved.has(key) ? saved.get(key).getAsDouble() : fallback;
        }
        static int wholeNumber(JsonObject saved, String key, String env, int fallback) {
            double value = number(saved, key, env, fallback);
            if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
                throw new IllegalArgumentException("监控参数必须为整数：" + key);
            return (int)value;
        }
    }
    record Reading(Double cpuPercent, Double processCpuPercent, Long memoryUsedBytes, Long memoryTotalBytes,
                   long heapUsedBytes, long heapMaxBytes, long nonHeapUsedBytes, int processors, String memorySource,
                   int runningJobs, int queuedJobs) {}
    record Sample(String at, long uptimeSeconds, Double cpuPercent, Double processCpuPercent, Double memoryPercent,
                  Long memoryUsedBytes, Long memoryTotalBytes, Double heapPercent, long heapUsedBytes, long heapMaxBytes,
                  long nonHeapUsedBytes, int processors, String memorySource, int runningJobs, int queuedJobs) {}
    record Event(String at, String type, String metric, Double value, Double threshold, String message) {}
    static final class Rule {
        final String metric, label; final double threshold;
        int consecutive; boolean active; Double value; Instant since, notified;
        Rule(String metric, String label, double threshold) { this.metric = metric; this.label = label; this.threshold = threshold; }
        Event accept(Double next, Instant now, Config config) {
            value = next;
            if (next == null) { consecutive = 0; return null; } // Missing readings never mean zero or recovery.
            if (next >= threshold) {
                consecutive++;
                if (!active && consecutive >= config.consecutiveSamples) {
                    active = true; since = notified = now;
                    return event(now, "ALERT", label + "达到阈值并连续出现 " + config.consecutiveSamples + " 次");
                }
                if (active && !now.isBefore(notified.plusSeconds(config.cooldownSeconds))) {
                    notified = now; return event(now, "REMINDER", label + "仍超过阈值");
                }
            } else {
                consecutive = 0;
                if (active && next <= Math.max(0, threshold - 5)) {
                    active = false; since = null; return event(now, "RECOVERED", label + "已恢复，低于阈值至少 5 个百分点");
                }
            }
            return null;
        }
        Event event(Instant now, String type, String message) { return new Event(now.toString(), type, metric, value, threshold, message); }
        Map<String, Object> view() {
            var result = new LinkedHashMap<String, Object>();
            result.put("metric", metric); result.put("label", label); result.put("threshold", threshold);
            result.put("value", value); result.put("active", active); result.put("consecutive", consecutive);
            result.put("since", since == null ? null : since.toString()); result.put("available", value != null); return result;
        }
    }
    final Supplier<Reading> probe; final Clock clock;
    final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "resource-monitor"); thread.setDaemon(true); return thread;
    });
    final Instant started; final Journal metrics, alerts;
    final Deque<Sample> history = new ArrayDeque<>(); final Deque<Event> events = new ArrayDeque<>();
    Config config; List<Rule> rules; Sample latest; String recordingError, samplingError;
    ScheduledFuture<?> scheduled; Instant lastErrorOutput, lastCleanup; boolean closed;

    ResourceMonitor(Config config, Supplier<Reading> probe, Clock clock, boolean start) throws IOException {
        this.config = config; this.probe = probe; this.clock = clock; this.started = clock.instant();
        Files.createDirectories(config.directory);
        String instance = UUID.randomUUID().toString();
        metrics = new Journal(config.directory, "metrics", instance, config.maxLogBytes);
        alerts = new Journal(config.directory, "alerts", instance, config.maxLogBytes);
        resetRules(); cleanup(clock.instant());
        if (start) { tick(); schedule(); }
    }
    static ResourceMonitor start(Path directory, Supplier<int[]> workload) throws IOException {
        return new ResourceMonitor(Config.load(directory), () -> systemReading(workload.get()), Clock.systemUTC(), true);
    }
    void resetRules() {
        rules = List.of(new Rule("cpu", "系统/容器 CPU", config.cpuPercent),
            new Rule("memory", "系统/容器内存", config.memoryPercent), new Rule("heap", "Java 堆内存", config.heapPercent));
    }
    void schedule() { scheduled = scheduler.scheduleWithFixedDelay(this::tick, config.intervalSeconds, config.intervalSeconds, TimeUnit.SECONDS); }
    synchronized void tick() {
        if (closed) return;
        Instant now = clock.instant();
        try {
            Reading r = probe.get();
            latest = new Sample(now.toString(), Math.max(0, Duration.between(started, now).getSeconds()),
                percent(r.cpuPercent), percent(r.processCpuPercent), ratio(r.memoryUsedBytes, r.memoryTotalBytes),
                r.memoryUsedBytes, r.memoryTotalBytes, ratio(r.heapUsedBytes, r.heapMaxBytes), r.heapUsedBytes, r.heapMaxBytes,
                r.nonHeapUsedBytes, r.processors, r.memorySource, r.runningJobs, r.queuedJobs);
            samplingError = null; history.addLast(latest);
            while (history.size() > 3600 || (!history.isEmpty() && Instant.parse(history.peekFirst().at).isBefore(now.minusSeconds(3600)))) history.removeFirst();
            List<Event> newEvents = new ArrayList<>();
            Double[] values = { latest.cpuPercent, latest.memoryPercent, latest.heapPercent };
            for (int i = 0; i < rules.size(); i++) {
                Event event = rules.get(i).accept(values[i], now, config);
                if (event != null) { remember(event); newEvents.add(event); }
            }
            boolean failed = false;
            try { metrics.append(now, latest); } catch (IOException ex) { failed = true; }
            for (Event event : newEvents) {
                try { alerts.append(now, event); } catch (IOException ex) { failed = true; }
            }
            try { if (lastCleanup == null || !now.isBefore(lastCleanup.plusSeconds(3600))) cleanup(now); }
            catch (IOException ex) { failed = true; }
            recordingError = failed ? "监控日志写入或清理失败，请检查磁盘空间和目录权限" : null;
            if (failed) reportError(now, recordingError);
        } catch (RuntimeException ex) {
            samplingError = "资源采样失败，稍后自动重试";
            for (Rule rule : rules) rule.accept(null, now, config);
            reportError(now, samplingError);
        }
    }
    void remember(Event event) {
        events.addLast(event); while (events.size() > 100) events.removeFirst();
        System.err.println("[MONITOR " + event.type + "] " + event.at + " " + event.message
            + (event.value == null ? "" : String.format(Locale.ROOT, " (%.1f%% / %.1f%%)", event.value, event.threshold)));
    }
    void reportError(Instant now, String message) {
        if (lastErrorOutput == null || !now.isBefore(lastErrorOutput.plusSeconds(300))) {
            System.err.println("[MONITOR ERROR] " + now + " " + message); lastErrorOutput = now;
        }
    }
    synchronized Map<String, Object> view(int limit) {
        var view = new LinkedHashMap<String, Object>();
        view.put("latest", latest); view.put("settings", config.values()); view.put("rules", rules.stream().map(Rule::view).toList());
        view.put("samples", history.stream().skip(Math.max(0, history.size() - limit)).toList());
        view.put("events", new ArrayList<>(events)); view.put("logDirectory", config.directory.toString());
        view.put("recordingError", recordingError); view.put("samplingError", samplingError);
        view.put("startedAt", started.toString()); view.put("status", samplingError != null || recordingError != null ? "error"
            : rules.stream().anyMatch(rule -> rule.active) ? "alerting" : "normal"); return view;
    }
    synchronized void update(JsonObject body) throws IOException {
        Config next = new Config(config.directory,
            integer(body, "intervalSeconds"), required(body, "cpuPercent"), required(body, "memoryPercent"), required(body, "heapPercent"),
            integer(body, "consecutiveSamples"), config.cooldownSeconds, config.retentionDays, config.maxLogBytes);
        Path temp = Files.createTempFile(config.directory, "settings-", ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(next.values()), StandardCharsets.UTF_8);
            try { Files.move(temp, config.directory.resolve("settings.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, config.directory.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
        boolean running = scheduled != null;
        if (running) scheduled.cancel(false);
        config = next; resetRules(); if (running) schedule();
        Event event = new Event(clock.instant().toString(), "CONFIG_CHANGED", "configuration", null, null, "监控设置已更新，重新开始连续超限计数");
        remember(event);
        try { alerts.append(clock.instant(), event); } catch (IOException ex) { recordingError = "监控设置已保存，但告警日志写入失败"; reportError(clock.instant(), recordingError); }
    }
    static double required(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException("请填写有效的监控参数：" + key);
        return object.get(key).getAsDouble();
    }
    static int integer(JsonObject object, String key) {
        double value = required(object, key);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("监控间隔和连续次数必须为整数");
        return (int)value;
    }
    void cleanup(Instant now) throws IOException {
        // An idle alert journal may otherwise hold an expired file open indefinitely on Windows.
        metrics.closeIfOldDate(now); alerts.closeIfOldDate(now);
        Instant cutoff = now.minus(Duration.ofDays(config.retentionDays));
        try (var files = Files.list(config.directory)) {
            for (Path file : files.toList()) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    && file.getFileName().toString().matches("(?:metrics|alerts)-\\d{4}-\\d{2}-\\d{2}-[0-9a-f-]{36}-\\d+\\.jsonl")
                    && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)) Files.deleteIfExists(file);
            }
        }
        lastCleanup = now;
    }
    @Override public synchronized void close() {
        closed = true; scheduler.shutdownNow();
        for (Journal journal : List.of(metrics, alerts)) {
            try { journal.close(); } catch (IOException ex) { System.err.println("[MONITOR ERROR] Closing monitor logs failed"); }
        }
    }
    static Double percent(Double value) { return value == null || !Double.isFinite(value) || value < 0 || value > 100 ? null : Math.round(value * 10) / 10.0; }
    static Double ratio(Long used, Long total) { return used == null || total == null || used < 0 || total <= 0 ? null : Math.round(Math.min(100, used * 100.0 / total) * 10) / 10.0; }
    static Reading systemReading(int[] workload) {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        var os = bean instanceof OperatingSystemMXBean value ? value : null;
        Long total = os == null || os.getTotalMemorySize() <= 0 ? null : os.getTotalMemorySize();
        Long free = os == null || os.getFreeMemorySize() < 0 ? null : os.getFreeMemorySize();
        Long used = total == null || free == null ? null : Math.max(0, total - free);
        String source = "jdk-operating-environment";
        if (System.getProperty("os.name").startsWith("Linux")) {
            try {
                // cgroup v2 namespaced Docker root; includes this container's native HEIC child processes.
                Path max = Path.of("/sys/fs/cgroup/memory.max"), current = Path.of("/sys/fs/cgroup/memory.current");
                String limit = Files.isReadable(max) ? Files.readString(max).trim() : "max";
                if (!limit.equals("max") && Files.isReadable(current) && Long.parseLong(limit) > 0) {
                    total = Long.parseLong(limit); used = Long.parseLong(Files.readString(current).trim()); source = "cgroup-v2";
                } else {
                    Map<String, Long> memory = new HashMap<>();
                    for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2 && Set.of("MemTotal:", "MemAvailable:").contains(parts[0])) memory.put(parts[0], Long.parseLong(parts[1]) * 1024);
                    }
                    // Only substitute host memory if the JVM's memory total also describes the host.
                    Long host = memory.get("MemTotal:"), available = memory.get("MemAvailable:");
                    if (host != null && available != null && (total == null || Math.abs(total - host) < 1024 * 1024)) {
                        total = host; used = Math.max(0, host - available); source = "linux-memavailable";
                    }
                }
            } catch (IOException | NumberFormatException ignored) { /* Keep portable JVM measurements. */ }
        }
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long maximum = heap.getMax() > 0 ? heap.getMax() : Runtime.getRuntime().maxMemory();
        return new Reading(os == null ? null : os.getCpuLoad() * 100, os == null ? null : os.getProcessCpuLoad() * 100,
            used, total, heap.getUsed(), maximum, ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),
            bean.getAvailableProcessors(), source, workload[0], workload[1]);
    }
    static final class Journal implements AutoCloseable {
        final Path directory; final String kind, instance; final long maximum;
        String day; int part; long bytes; BufferedWriter writer;
        Journal(Path directory, String kind, String instance, long maximum) { this.directory = directory; this.kind = kind; this.instance = instance; this.maximum = maximum; }
        void append(Instant now, Object record) throws IOException {
            String line = JSON.toJson(record) + "\n", date = now.atZone(ZoneOffset.UTC).toLocalDate().toString();
            long size = line.getBytes(StandardCharsets.UTF_8).length;
            if (writer == null || !date.equals(day) || bytes + size > maximum) {
                if (writer != null) { writer.close(); writer = null; }
                part = date.equals(day) ? part + 1 : 0; day = date;
                Path file = directory.resolve(kind + "-" + day + "-" + instance + "-" + part + ".jsonl");
                writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW); bytes = 0;
            }
            try { writer.write(line); writer.flush(); bytes += size; }
            catch (IOException ex) { try { writer.close(); } catch (IOException ignored) {} writer = null; part++; throw ex; }
        }
        public void close() throws IOException { if (writer != null) { writer.close(); writer = null; } }
        void closeIfOldDate(Instant now) throws IOException {
            if (writer != null && !now.atZone(ZoneOffset.UTC).toLocalDate().toString().equals(day)) close();
        }
    }
}
