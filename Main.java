import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// ============================================================
//  AI-Driven Autonomous Task Automator Agent — AgentCore
//  Microsoft Agents League Hackathon
//  v2.0 — Full-Stack: Java backend + HTML dashboard connected
// ============================================================

// ─── Custom Exceptions ───────────────────────────────────────

class InvalidAgentCommandException extends Exception {
    public InvalidAgentCommandException(String command) {
        super("Unrecognized agent command: \"" + command + "\". Type 'help' for valid commands.");
    }
}

class TaskExecutionException extends Exception {
    private final String taskName;
    public TaskExecutionException(String taskName, String reason) {
        super("Task [" + taskName + "] failed — " + reason);
        this.taskName = taskName;
    }
    public String getTaskName() { return taskName; }
}

class AgentSystemException extends RuntimeException {
    public AgentSystemException(String message) {
        super("AGENT SYSTEM FAULT: " + message);
    }
}

// ─── Task Status Enum ─────────────────────────────────────────

enum TaskStatus {
    PENDING, QUEUED, RUNNING, COMPLETED, FAILED
}

// ─── Task Record ──────────────────────────────────────────────

class AgentTask {
    private final String id;
    private final String name;
    private final String type;
    private volatile TaskStatus status;
    private final long createdAt;
    private volatile long startedAt;
    private volatile long completedAt;
    private volatile String threadName;
    private volatile String resultMessage;

    public AgentTask(String id, String name, String type) {
        this.id        = id;
        this.name      = name;
        this.type      = type;
        this.status    = TaskStatus.PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    public String getId()                  { return id; }
    public String getName()                { return name; }
    public String getType()                { return type; }
    public TaskStatus getStatus()          { return status; }
    public void setStatus(TaskStatus s)    { this.status = s; }
    public long getCreatedAt()             { return createdAt; }
    public long getStartedAt()             { return startedAt; }
    public void setStartedAt(long t)       { this.startedAt = t; }
    public long getCompletedAt()           { return completedAt; }
    public void setCompletedAt(long t)     { this.completedAt = t; }
    public String getThreadName()          { return threadName; }
    public void setThreadName(String t)    { this.threadName = t; }
    public String getResultMessage()       { return resultMessage; }
    public void setResultMessage(String m) { this.resultMessage = m; }

    public long getDurationMs() {
        if (startedAt == 0 || completedAt == 0) return -1;
        return completedAt - startedAt;
    }

    /** Serialises this task to a JSON object string for the REST API. */
    public String toJson() {
        return String.format(
            "{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\",\"status\":\"%s\"," +
            "\"durationMs\":%d,\"thread\":\"%s\",\"result\":\"%s\"}",
            id, name, type, status, getDurationMs(),
            threadName    != null ? threadName              : "N/A",
            resultMessage != null ? resultMessage.replace("\"", "'") : ""
        );
    }
}

// ─── Failure Simulator ────────────────────────────────────────

class FailureSimulator {
    private static final Random RNG = new Random();

    /** Throws a TaskExecutionException ~25 % of the time. */
    public static void maybeThrow(String taskName) throws TaskExecutionException {
        int roll = RNG.nextInt(100);
        if (roll < 8)  throw new TaskExecutionException(taskName, "Network failure — host unreachable");
        if (roll < 16) throw new TaskExecutionException(taskName, "Server timeout — response exceeded 30 s");
        if (roll < 25) throw new TaskExecutionException(taskName, "Database unavailable — connection pool exhausted");
    }
}

// ─── Agent Logger ─────────────────────────────────────────────

class AgentLogger {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static String ts() { return LocalDateTime.now().format(FMT); }

    public static void agent(String msg)   { log("AGENT",   msg, "\u001B[36m"); }
    public static void info(String msg)    { log("INFO",    msg, "\u001B[34m"); }
    public static void task(String msg)    { log("TASK",    msg, "\u001B[35m"); }
    public static void thread(String msg)  { log("THREAD",  msg, "\u001B[33m"); }
    public static void success(String msg) { log("SUCCESS", msg, "\u001B[32m"); }
    public static void warning(String msg) { log("WARNING", msg, "\u001B[93m"); }
    public static void error(String msg)   { log("ERROR",   msg, "\u001B[31m"); }

    private static void log(String level, String msg, String color) {
        System.out.printf("%s[%s] %s[%s]\u001B[0m %s%n", "\u001B[90m", ts(), color, level, msg);
    }
}

// ─── Agent Memory ─────────────────────────────────────────────

class AgentMemory {
    private final ConcurrentLinkedDeque<AgentTask> history = new ConcurrentLinkedDeque<>();
    private final AtomicInteger successCount               = new AtomicInteger(0);
    private final AtomicInteger failureCount               = new AtomicInteger(0);
    private final AtomicLong    totalTasksQueued           = new AtomicLong(0);

    public void record(AgentTask task) {
        history.addFirst(task);
        if      (task.getStatus() == TaskStatus.COMPLETED) successCount.incrementAndGet();
        else if (task.getStatus() == TaskStatus.FAILED)    failureCount.incrementAndGet();
        totalTasksQueued.incrementAndGet();
    }

    /** Serialises the full history to a JSON array for the REST API. */
    public String toJsonArray() {
        StringBuilder sb    = new StringBuilder("[");
        boolean       first = true;
        for (AgentTask t : history) {
            if (!first) sb.append(",");
            sb.append(t.toJson());
            first = false;
        }
        return sb.append("]").toString();
    }

    public void printHistory() {
        if (history.isEmpty()) { AgentLogger.info("No task history found."); return; }
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║          AGENT TASK HISTORY (latest first)       ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        int i = 1;
        for (AgentTask t : history) {
            String dur = t.getDurationMs() >= 0 ? t.getDurationMs() + " ms" : "N/A";
            System.out.printf("║ %2d. %-20s %-10s %8s ║%n", i++, t.getName(), t.getStatus(), dur);
        }
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.printf("║  Total: %-4d  Success: %-4d  Failed: %-4d       ║%n",
            totalTasksQueued.get(), successCount.get(), failureCount.get());
        System.out.println("╚══════════════════════════════════════════════════╝\n");
    }

    public int  getSuccessCount() { return successCount.get(); }
    public int  getFailureCount() { return failureCount.get(); }
    public long getTotalQueued()  { return totalTasksQueued.get(); }
}

// ─── Task Worker ──────────────────────────────────────────────

class TaskWorker implements Callable<AgentTask> {
    private final AgentTask task;
    private final ConcurrentHashMap<String, AgentTask> liveRegistry;
    private final AgentMemory memory;

    public TaskWorker(AgentTask task,
                      ConcurrentHashMap<String, AgentTask> liveRegistry,
                      AgentMemory memory) {
        this.task         = task;
        this.liveRegistry = liveRegistry;
        this.memory       = memory;
    }

    @Override
    public AgentTask call() {
        String tName = Thread.currentThread().getName();
        task.setThreadName(tName);
        task.setStatus(TaskStatus.RUNNING);
        task.setStartedAt(System.currentTimeMillis());
        liveRegistry.put(task.getId(), task);

        AgentLogger.thread("Task [" + task.getName() + "] assigned to " + tName);

        try {
            execute(false);
            task.setStatus(TaskStatus.COMPLETED);
            task.setResultMessage("Completed successfully on first attempt.");
            AgentLogger.success(task.getName() + " → COMPLETED in " + task.getDurationMs() + " ms");
        } catch (TaskExecutionException e) {
            AgentLogger.error(e.getMessage());
            AgentLogger.warning("Auto-retry initiated for [" + task.getName() + "] …");
            try {
                Thread.sleep(500);
                execute(true);
                task.setStatus(TaskStatus.COMPLETED);
                task.setResultMessage("Completed on retry after transient failure.");
                AgentLogger.success(task.getName() + " → COMPLETED (retry)");
            } catch (TaskExecutionException | InterruptedException ex) {
                task.setStatus(TaskStatus.FAILED);
                task.setResultMessage(ex.getMessage());
                AgentLogger.error(task.getName() + " → FAILED after retry — " + ex.getMessage());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            task.setStatus(TaskStatus.FAILED);
            task.setResultMessage("Task interrupted.");
        }

        task.setCompletedAt(System.currentTimeMillis());
        memory.record(task);
        liveRegistry.remove(task.getId());
        return task;
    }

    private void execute(boolean retry) throws TaskExecutionException, InterruptedException {
        int sleepMs = switch (task.getType()) {
            case "BACKUP"   -> retry ? 900  : 1200;
            case "SYNC"     -> retry ? 700  : 1000;
            case "REPORT"   -> retry ? 800  : 1400;
            case "HEALTH"   -> retry ? 400  : 600;
            case "SECURITY" -> retry ? 1000 : 1600;
            default         -> 800;
        };
        Thread.sleep(sleepMs);
        if (!retry) FailureSimulator.maybeThrow(task.getName());
    }
}

// ─── Agent NLP Parser ─────────────────────────────────────────

class AgentNLP {
    public static String parse(String input) throws InvalidAgentCommandException {
        String cmd = input.trim().toLowerCase();
        if (cmd.contains("backup")   || cmd.contains("back up"))   return "BACKUP";
        if (cmd.contains("sync")     || cmd.contains("database"))  return "SYNC";
        if (cmd.contains("report")   || cmd.contains("analytics")) return "REPORT";
        if (cmd.contains("health")   || cmd.contains("server"))    return "HEALTH";
        if (cmd.contains("security") || cmd.contains("scan"))      return "SECURITY";
        if (cmd.contains("analyze")  || cmd.contains("system"))    return "REPORT";
        if (cmd.contains("history")  || cmd.contains("log"))       return "HISTORY";
        if (cmd.contains("status")   || cmd.contains("active"))    return "STATUS";
        if (cmd.contains("help"))                                   return "HELP";
        if (cmd.equals("exit")       || cmd.equals("quit"))        return "EXIT";
        throw new InvalidAgentCommandException(input);
    }
}

// ═════════════════════════════════════════════════════════════
//  HTTP API SERVER
//  Uses com.sun.net.httpserver.HttpServer — zero external deps.
//  Exposes four endpoints the dashboard's fetch() calls hit.
// ═════════════════════════════════════════════════════════════

class AgentHttpServer {

    static final int PORT = 8080;

    private final Main      agentCore;
    private       HttpServer server;

    AgentHttpServer(Main agentCore) { this.agentCore = agentCore; }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // ── POST /api/command ─────────────────────────────────
        // Dashboard sends: {"command": "schedule backup"}
        // Java parses it, runs the task, returns JSON result.
        server.createContext("/api/command", ex -> {
            addCors(ex);
            if (preflight(ex)) return;
            if (!"POST".equals(ex.getRequestMethod())) { send(ex, 405, "{\"error\":\"POST only\"}"); return; }
            try {
    String body = new String(ex.getRequestBody().readAllBytes());
    String command = extractField(body, "command");

    if (command == null || command.isBlank()) {
        send(ex, 400, "{\"error\":\"field 'command' required\"}");
        return;
    }

    send(ex, 200, agentCore.handleApiCommand(command));

} catch (IOException e) {
    send(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
}
        });

        // ── GET /api/status ───────────────────────────────────
        // Dashboard polls this every 2 s to refresh the four stat cards.
        server.createContext("/api/status", ex -> {
            addCors(ex);
            if (preflight(ex)) return;
            send(ex, 200, agentCore.getStatusJson());
        });

        // ── GET /api/tasks ────────────────────────────────────
        // Returns completed task history as a JSON array.
        server.createContext("/api/tasks", ex -> {
            addCors(ex);
            if (preflight(ex)) return;
            send(ex, 200, agentCore.getMemory().toJsonArray());
        });

        // ── GET /api/live ─────────────────────────────────────
        // Returns only tasks currently in RUNNING state.
        server.createContext("/api/live", ex -> {
            addCors(ex);
            if (preflight(ex)) return;
            send(ex, 200, agentCore.getLiveJson());
        });

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        AgentLogger.success("HTTP API server started → http://localhost:" + PORT);
        AgentLogger.info("Endpoints ready:");
        AgentLogger.info("  POST http://localhost:" + PORT + "/api/command");
        AgentLogger.info("  GET  http://localhost:" + PORT + "/api/status");
        AgentLogger.info("  GET  http://localhost:" + PORT + "/api/tasks");
        AgentLogger.info("  GET  http://localhost:" + PORT + "/api/live");
    }

    void stop() { if (server != null) server.stop(0); }

    // ── Utility methods ───────────────────────────────────────

    private static void addCors(HttpExchange ex) {
        // CORS headers allow the browser (on port 5500) to fetch from Java (port 8080)
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Content-Type",                 "application/json");
    }

    private static boolean preflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static void send(HttpExchange ex, int code, String json) throws IOException {
        byte[] b = json.getBytes("UTF-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /**
     * Minimal JSON string-field extractor.
     * Handles {"command":"value"} without needing a JSON library.
     */
    private static String extractField(String json, String field) {
        String key   = "\"" + field + "\"";
        int    start = json.indexOf(key);
        if (start < 0) return null;
        start = json.indexOf('"', start + key.length() + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }
}

// ─── AgentCore (Main class) ───────────────────────────────────

public class Main {

    static final int THREAD_POOL_SIZE              = 4;
    static final AtomicInteger TASK_COUNTER        = new AtomicInteger(1);

    private final ExecutorService                      workerPool;
    private final ScheduledExecutorService             scheduler;
    private final ConcurrentHashMap<String, AgentTask> liveRegistry;
    private final AgentMemory                          memory;
    private final long                                 startTime;
    private       AgentHttpServer                      httpServer;

    public Main() {
        this.workerPool   = Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
            Thread t = new Thread(r);
            t.setName("AgentWorker-" + TASK_COUNTER.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        this.scheduler    = Executors.newScheduledThreadPool(2);
        this.liveRegistry = new ConcurrentHashMap<>();
        this.memory       = new AgentMemory();
        this.startTime    = System.currentTimeMillis();
    }

    // ── API helpers (called by AgentHttpServer) ───────────────

    AgentMemory getMemory() { return memory; }

    String getStatusJson() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        return String.format(
            "{\"uptime\":%d,\"total\":%d,\"success\":%d,\"failed\":%d,\"active\":%d}",
            uptime, memory.getTotalQueued(),
            memory.getSuccessCount(), memory.getFailureCount(), liveRegistry.size()
        );
    }

    String getLiveJson() {
        StringBuilder sb    = new StringBuilder("[");
        boolean       first = true;
        for (AgentTask t : liveRegistry.values()) {
            if (!first) sb.append(",");
            sb.append(t.toJson());
            first = false;
        }
        return sb.append("]").toString();
    }

    /**
     * Entry point for HTTP POST /api/command.
     * Parses the command, dispatches or handles it, and returns a
     * JSON string the dashboard can act on directly.
     */
    String handleApiCommand(String command) {
        AgentLogger.info("[API] Command received: \"" + command + "\"");
        try {
            String type = AgentNLP.parse(command);
            return switch (type) {
                case "HISTORY" -> "{\"type\":\"HISTORY\",\"tasks\":" + memory.toJsonArray() + "}";
                case "STATUS"  -> "{\"type\":\"STATUS\"," + getStatusJson().substring(1);  // reuse, trim leading {
                case "HELP"    -> "{\"type\":\"HELP\"}";
                case "EXIT"    -> "{\"type\":\"EXIT\"}";
                default -> {
                    AgentTask task = dispatchTask(command, type);
                    yield "{\"type\":\"TASK_QUEUED\",\"task\":" + task.toJson() + "}";
                }
            };
        } catch (InvalidAgentCommandException e) {
            return "{\"type\":\"ERROR\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    // ── Task Dispatch ─────────────────────────────────────────

    private AgentTask dispatchTask(String command, String taskType) {
        String taskId   = "TASK-" + String.format("%04d", TASK_COUNTER.getAndIncrement());
        String taskName = switch (taskType) {
            case "BACKUP"   -> "System Backup";
            case "SYNC"     -> "Database Sync";
            case "REPORT"   -> "Analytics Report";
            case "HEALTH"   -> "Server Health Monitor";
            case "SECURITY" -> "Security Scan";
            default         -> "Generic Task";
        };

        AgentTask task = new AgentTask(taskId, taskName, taskType);
        task.setStatus(TaskStatus.QUEUED);
        printReasoning(command, taskType, taskId);
        AgentLogger.agent("Queuing [" + taskName + "] (" + taskId + ")");

        liveRegistry.put(taskId, task);
        workerPool.submit(new TaskWorker(task, liveRegistry, memory));
        return task;
    }

    // ── Console Methods ───────────────────────────────────────

    private static void printBanner() {
        System.out.println("\u001B[36m");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║   AI-DRIVEN AUTONOMOUS TASK AUTOMATOR AGENT  v2.0            ║");
        System.out.println("║   AgentCore — Microsoft Agents League Hackathon              ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║   HTTP API  →  http://localhost:8080/api/                    ║");
        System.out.println("║   Dashboard →  open index.html in browser via Live Server    ║");
        System.out.println("║   Console   →  type commands below OR use the dashboard      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("\u001B[0m");
    }

    private void printReasoning(String command, String taskType, String taskId) {
        System.out.println("\u001B[90m┌─ Agent Reasoning ─────────────────────────────────┐\u001B[0m");
        System.out.printf("\u001B[90m│\u001B[0m  Intent detected   : \u001B[33m%s\u001B[0m%n", command);
        System.out.printf("\u001B[90m│\u001B[0m  Task selected     : \u001B[35m%s\u001B[0m%n", taskType);
        System.out.printf("\u001B[90m│\u001B[0m  Task ID assigned  : \u001B[36m%s\u001B[0m%n", taskId);
        System.out.printf("\u001B[90m│\u001B[0m  Priority          : \u001B[32mNORMAL\u001B[0m%n");
        System.out.printf("\u001B[90m│\u001B[0m  Worker threads    : \u001B[34m%d available\u001B[0m%n", THREAD_POOL_SIZE);
        System.out.println("\u001B[90m└───────────────────────────────────────────────────┘\u001B[0m");
    }

    private void printStatus() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n\u001B[34m╔═══ Agent Status ════════════════════════════════╗\u001B[0m");
        System.out.printf("\u001B[34m║\u001B[0m  Uptime          : %d s%n", uptime);
        System.out.printf("\u001B[34m║\u001B[0m  Total queued    : %d%n",   memory.getTotalQueued());
        System.out.printf("\u001B[34m║\u001B[0m  Successful      : %d%n",   memory.getSuccessCount());
        System.out.printf("\u001B[34m║\u001B[0m  Failed          : %d%n",   memory.getFailureCount());
        System.out.printf("\u001B[34m║\u001B[0m  Live tasks      : %d%n",   liveRegistry.size());
        System.out.printf("\u001B[34m║\u001B[0m  API server      : http://localhost:%d%n", AgentHttpServer.PORT);
        System.out.println("\u001B[34m╚════════════════════════════════════════════════╝\u001B[0m\n");
    }

    private static void printHelp() {
        System.out.println("\n\u001B[33m  AVAILABLE COMMANDS\u001B[0m");
        System.out.println("  schedule backup     │ generate report    │ sync database");
        System.out.println("  analyze system      │ check server health │ run security scan");
        System.out.println("  show task history   │ status │ help │ exit\n");
    }

    private void runStartupDiagnostics() {
        AgentLogger.agent("AgentCore v2.0 initialising …");
        AgentLogger.info("Thread pool created — capacity: " + THREAD_POOL_SIZE);
        AgentLogger.info("Concurrent task registry mounted");
        AgentLogger.info("Agent memory module online");
        AgentLogger.info("Scheduled executor ready");
        AgentLogger.success("AgentCore ONLINE — all systems nominal");
        System.out.println();
    }

    // ── Main REPL ─────────────────────────────────────────────

    public void run() {
        printBanner();
        runStartupDiagnostics();

        // Start the HTTP API server — this is what connects the dashboard
        httpServer = new AgentHttpServer(this);
        try {
            httpServer.start();
        } catch (IOException e) {
            AgentLogger.error("HTTP server failed to start: " + e.getMessage());
            AgentLogger.warning("Dashboard will not be connected. Console mode only.");
        }

        System.out.println();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\u001B[36m[AGENT] › \u001B[0m");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            AgentLogger.info("Console command: \"" + input + "\"");
            try {
                String taskType = AgentNLP.parse(input);
                switch (taskType) {
                    case "EXIT" -> {
                        AgentLogger.agent("Shutting down AgentCore …");
                        httpServer.stop();
                        workerPool.shutdown();
                        scheduler.shutdown();
                        try { workerPool.awaitTermination(5, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) {}
                        AgentLogger.success("AgentCore shutdown complete. Goodbye.");
                        return;
                    }
                    case "HISTORY" -> memory.printHistory();
                    case "STATUS"  -> printStatus();
                    case "HELP"    -> printHelp();
                    default        -> dispatchTask(input, taskType);
                }
            } catch (InvalidAgentCommandException e) {
                AgentLogger.error(e.getMessage());
                AgentLogger.info("Type 'help' to see available commands.");
            } catch (Exception e) {
                AgentLogger.error("Unexpected agent fault: " + e.getMessage());
            }
        }
    }

    // ── Entry Point ───────────────────────────────────────────

    public static void main(String[] args) {
        try {
            new Main().run();
        } catch (AgentSystemException e) {
            AgentLogger.error(e.getMessage());
            System.exit(1);
        }
    }
}
