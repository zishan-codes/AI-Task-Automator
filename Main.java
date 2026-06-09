import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// ============================================================
//  AI-Driven Autonomous Task Automator Agent — AgentCore
//  Microsoft Agents League Hackathon
// ============================================================

// ─── Custom Exceptions ──────────────────────────────────────

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

// ─── Task Status Enum ────────────────────────────────────────

enum TaskStatus {
    PENDING, QUEUED, RUNNING, COMPLETED, FAILED
}

// ─── Task Record ─────────────────────────────────────────────

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

    // Getters & setters
    public String getId()            { return id; }
    public String getName()          { return name; }
    public String getType()          { return type; }
    public TaskStatus getStatus()    { return status; }
    public void setStatus(TaskStatus s) { this.status = s; }
    public long getCreatedAt()       { return createdAt; }
    public long getStartedAt()       { return startedAt; }
    public void setStartedAt(long t) { this.startedAt = t; }
    public long getCompletedAt()     { return completedAt; }
    public void setCompletedAt(long t) { this.completedAt = t; }
    public String getThreadName()    { return threadName; }
    public void setThreadName(String t) { this.threadName = t; }
    public String getResultMessage() { return resultMessage; }
    public void setResultMessage(String m) { this.resultMessage = m; }

    /** Execution duration in ms, or -1 if not yet complete. */
    public long getDurationMs() {
        if (startedAt == 0 || completedAt == 0) return -1;
        return completedAt - startedAt;
    }

    /** JSON-style snapshot for future REST API integration. */
    public String toJson() {
        return String.format(
            "{\"id\":\"%s\",\"name\":\"%s\",\"type\":\"%s\",\"status\":\"%s\"," +
            "\"durationMs\":%d,\"thread\":\"%s\",\"result\":\"%s\"}",
            id, name, type, status, getDurationMs(),
            threadName != null ? threadName : "N/A",
            resultMessage != null ? resultMessage.replace("\"","'") : ""
        );
    }
}

// ─── Failure Simulator ───────────────────────────────────────

class FailureSimulator {
    private static final Random RNG = new Random();

    /** Throws a TaskExecutionException ~25 % of the time. */
    public static void maybeThrow(String taskName) throws TaskExecutionException {
        int roll = RNG.nextInt(100);
        if (roll < 8) throw new TaskExecutionException(taskName, "Network failure — host unreachable");
        if (roll < 16) throw new TaskExecutionException(taskName, "Server timeout — response exceeded 30 s");
        if (roll < 25) throw new TaskExecutionException(taskName, "Database unavailable — connection pool exhausted");
    }
}

// ─── Agent Logger ────────────────────────────────────────────

class AgentLogger {
    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static String ts() {
        return LocalDateTime.now().format(FMT);
    }

    public static void agent(String msg)   { log("AGENT",   msg, "\u001B[36m"); }
    public static void info(String msg)    { log("INFO",    msg, "\u001B[34m"); }
    public static void task(String msg)    { log("TASK",    msg, "\u001B[35m"); }
    public static void thread(String msg)  { log("THREAD",  msg, "\u001B[33m"); }
    public static void success(String msg) { log("SUCCESS", msg, "\u001B[32m"); }
    public static void warning(String msg) { log("WARNING", msg, "\u001B[93m"); }
    public static void error(String msg)   { log("ERROR",   msg, "\u001B[31m"); }

    private static void log(String level, String msg, String color) {
        System.out.printf("%s[%s] %s[%s]\u001B[0m %s%n",
            "\u001B[90m", ts(), color, level, msg);
    }
}

// ─── Agent Memory ────────────────────────────────────────────

class AgentMemory {
    private final ConcurrentLinkedDeque<AgentTask> history = new ConcurrentLinkedDeque<>();
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong    totalTasksQueued = new AtomicLong(0);

    public void record(AgentTask task) {
        history.addFirst(task);
        if (task.getStatus() == TaskStatus.COMPLETED) successCount.incrementAndGet();
        else if (task.getStatus() == TaskStatus.FAILED) failureCount.incrementAndGet();
        totalTasksQueued.incrementAndGet();
    }

    public void printHistory() {
        if (history.isEmpty()) {
            AgentLogger.info("No task history found.");
            return;
        }
        System.out.println("\n╔══════════════════════════════════════════════════╗");
        System.out.println("║          AGENT TASK HISTORY (latest first)       ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        int i = 1;
        for (AgentTask t : history) {
            String dur = t.getDurationMs() >= 0 ? t.getDurationMs() + " ms" : "N/A";
            System.out.printf("║ %2d. %-20s %-10s %8s ║%n",
                i++, t.getName(), t.getStatus(), dur);
        }
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.printf("║  Total: %-4d  Success: %-4d  Failed: %-4d       ║%n",
            totalTasksQueued.get(), successCount.get(), failureCount.get());
        System.out.println("╚══════════════════════════════════════════════════╝\n");
    }

    public int getSuccessCount() { return successCount.get(); }
    public int getFailureCount() { return failureCount.get(); }
    public long getTotalQueued() { return totalTasksQueued.get(); }
}

// ─── Task Worker ─────────────────────────────────────────────

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
            execute(false);                      // first attempt
            task.setStatus(TaskStatus.COMPLETED);
            task.setResultMessage("Completed successfully on first attempt.");
            AgentLogger.success(task.getName() + " → COMPLETED in " + task.getDurationMs() + " ms");
        } catch (TaskExecutionException e) {
            AgentLogger.error(e.getMessage());
            AgentLogger.warning("Auto-retry initiated for [" + task.getName() + "] …");
            try {
                Thread.sleep(500);               // brief wait before retry
                execute(true);                   // second attempt
                task.setStatus(TaskStatus.COMPLETED);
                task.setResultMessage("Completed on retry after transient failure.");
                AgentLogger.success(task.getName() + " → COMPLETED (retry) in " +
                    (System.currentTimeMillis() - task.getStartedAt()) + " ms");
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

    /** Simulates the actual task work. retry=true means second attempt. */
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

// ─── Agent NLP Parser ────────────────────────────────────────

class AgentNLP {
    /** Maps a natural language command → task type, or throws if unknown. */
    public static String parse(String input) throws InvalidAgentCommandException {
        String cmd = input.trim().toLowerCase();

        if (cmd.contains("backup") || cmd.contains("back up"))   return "BACKUP";
        if (cmd.contains("sync") || cmd.contains("database"))    return "SYNC";
        if (cmd.contains("report") || cmd.contains("analytics")) return "REPORT";
        if (cmd.contains("health") || cmd.contains("server"))    return "HEALTH";
        if (cmd.contains("security") || cmd.contains("scan"))    return "SECURITY";
        if (cmd.contains("analyze") || cmd.contains("system"))   return "REPORT";
        if (cmd.contains("history") || cmd.contains("log"))      return "HISTORY";
        if (cmd.contains("status") || cmd.contains("active"))    return "STATUS";
        if (cmd.contains("help"))                                 return "HELP";
        if (cmd.equals("exit") || cmd.equals("quit"))            return "EXIT";

        throw new InvalidAgentCommandException(input);
    }
}

// ─── AgentCore ───────────────────────────────────────────────

public class Main {

    private static final int THREAD_POOL_SIZE = 4;
    private static final AtomicInteger TASK_COUNTER = new AtomicInteger(1);

    private final ExecutorService          workerPool;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, AgentTask> liveRegistry;
    private final AgentMemory              memory;
    private final long                     startTime;

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

    // ── Banner ───────────────────────────────────────────────

    private static void printBanner() {
        System.out.println("\u001B[36m");
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       AI-DRIVEN AUTONOMOUS TASK AUTOMATOR AGENT              ║");
        System.out.println("║       AgentCore v1.0 — Microsoft Agents League Hackathon     ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.println("║  Commands:  schedule backup  │ generate report               ║");
        System.out.println("║             sync database    │ analyze system                ║");
        System.out.println("║             check server health │ run security scan          ║");
        System.out.println("║             show task history │ status │ help │ exit         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println("\u001B[0m");
    }

    // ── Agent Reasoning Log ──────────────────────────────────

    private void printReasoning(String command, String taskType, String taskId) {
        System.out.println("\u001B[90m┌─ Agent Reasoning ─────────────────────────────────┐\u001B[0m");
        System.out.printf("\u001B[90m│\u001B[0m  Intent detected   : \u001B[33m%s\u001B[0m%n", command);
        System.out.printf("\u001B[90m│\u001B[0m  Task selected     : \u001B[35m%s\u001B[0m%n", taskType);
        System.out.printf("\u001B[90m│\u001B[0m  Task ID assigned  : \u001B[36m%s\u001B[0m%n", taskId);
        System.out.printf("\u001B[90m│\u001B[0m  Priority          : \u001B[32mNORMAL\u001B[0m%n");
        System.out.printf("\u001B[90m│\u001B[0m  Worker threads    : \u001B[34m%d available\u001B[0m%n", THREAD_POOL_SIZE);
        System.out.println("\u001B[90m└───────────────────────────────────────────────────┘\u001B[0m");
    }

    // ── Dispatch Task ────────────────────────────────────────

    private void dispatchTask(String command, String taskType) {
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

        AgentLogger.agent("Command accepted — queuing [" + taskName + "] (" + taskId + ")");
        AgentLogger.task("Status → QUEUED");

        liveRegistry.put(taskId, task);

        TaskWorker worker = new TaskWorker(task, liveRegistry, memory);
        workerPool.submit(worker);
    }

    // ── Status Display ───────────────────────────────────────

    private void printStatus() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n\u001B[34m╔═══ Agent Status ════════════════════════════════╗\u001B[0m");
        System.out.printf("\u001B[34m║\u001B[0m  Uptime          : %d s%n", uptime);
        System.out.printf("\u001B[34m║\u001B[0m  Total queued    : %d%n", memory.getTotalQueued());
        System.out.printf("\u001B[34m║\u001B[0m  Successful      : %d%n", memory.getSuccessCount());
        System.out.printf("\u001B[34m║\u001B[0m  Failed          : %d%n", memory.getFailureCount());
        System.out.printf("\u001B[34m║\u001B[0m  Live tasks      : %d%n", liveRegistry.size());

        if (!liveRegistry.isEmpty()) {
            System.out.println("\u001B[34m║\u001B[0m  Active:");
            liveRegistry.values().forEach(t ->
                System.out.printf("\u001B[34m║\u001B[0m    · %-20s [%s] on %s%n",
                    t.getName(), t.getStatus(), t.getThreadName() != null ? t.getThreadName() : "pending"));
        }
        System.out.println("\u001B[34m╚════════════════════════════════════════════════╝\u001B[0m\n");
    }

    // ── Help ─────────────────────────────────────────────────

    private static void printHelp() {
        System.out.println("\n\u001B[33m  AVAILABLE COMMANDS\u001B[0m");
        System.out.println("  ─────────────────────────────────────────────");
        System.out.println("  schedule backup          → Run system backup");
        System.out.println("  sync database            → Sync database");
        System.out.println("  generate report          → Generate analytics");
        System.out.println("  analyze system           → Analyse and report");
        System.out.println("  check server health      → Health monitor");
        System.out.println("  run security scan        → Security audit");
        System.out.println("  show task history        → Execution history");
        System.out.println("  status                   → Live agent status");
        System.out.println("  help                     → This menu");
        System.out.println("  exit                     → Shutdown agent\n");
    }

    // ── Startup Self-Test ────────────────────────────────────

    private void runStartupDiagnostics() {
        AgentLogger.agent("AgentCore initialising …");
        AgentLogger.info("Thread pool created — capacity: " + THREAD_POOL_SIZE);
        AgentLogger.info("Concurrent task registry mounted");
        AgentLogger.info("Agent memory module online");
        AgentLogger.info("Scheduled executor ready");
        AgentLogger.success("AgentCore ONLINE — all systems nominal");
        System.out.println();
    }

    // ── Main REPL ────────────────────────────────────────────

    public void run() {
        printBanner();
        runStartupDiagnostics();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\u001B[36m[AGENT] › \u001B[0m");
            if (!scanner.hasNextLine()) break;

            String input = scanner.nextLine().trim();
            if (input.isEmpty()) continue;

            AgentLogger.info("Command received: \"" + input + "\"");

            try {
                String taskType = AgentNLP.parse(input);

                switch (taskType) {
                    case "EXIT"    -> {
                        AgentLogger.agent("Shutting down AgentCore …");
                        workerPool.shutdown();
                        scheduler.shutdown();
                        try {
                            workerPool.awaitTermination(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {}
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

    // ── Entry Point ──────────────────────────────────────────

    public static void main(String[] args) {
        try {
            new Main().run();
        } catch (AgentSystemException e) {
            AgentLogger.error(e.getMessage());
            System.exit(1);
        }
    }
}