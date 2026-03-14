import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    static final RuntimeState STATE = new RuntimeState();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--self-test".equals(args[0])) {
            selfTest();
            System.out.println("self-test ok");
            return;
        }
        int port = 8081;
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/invoke", new InvokeHandler());
        server.createContext("/runtime/invoke", new InvokeHandler());
        server.createContext("/runtime/async-jobs", new AsyncJobsHandler());
        server.createContext("/ops/runtime", new RuntimeOverviewHandler());
        server.createContext("/ops/overview", new RuntimeOverviewHandler());
        server.createContext("/ops/jobs", new JobsHandler());
        server.createContext("/ops/routes", new RoutesHandler());
        server.createContext("/ops/circuits", new CircuitsHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        System.out.println("agent-model-runtime listening on " + port);
        server.start();
    }

    static void selfTest() {
        RuntimeState state = new RuntimeState();
        try {
            RuntimeRequest request = RuntimeRequest.fromBody("{\"request_id\":\"req-self\",\"task_type\":\"pricing_inference\",\"input\":{\"payload\":\"price analysis\"}}");
            RuntimeResponse response = state.invoke(request, request.rawBody.length());
            if (!"ok".equals(response.status)) {
                throw new IllegalStateException("invoke should succeed");
            }
            if (!"pricing_inference".equals(response.taskType)) {
                throw new IllegalStateException("task type should be preserved");
            }
            AsyncSubmission asyncSubmission = state.submitAsync(request, request.rawBody.length());
            if (asyncSubmission.jobId == null || asyncSubmission.jobId.isEmpty()) {
                throw new IllegalStateException("async job id should exist");
            }
            if (!state.snapshotOverview().contains("queue_depth")) {
                throw new IllegalStateException("overview should contain queue metrics");
            }
        } finally {
            state.shutdown();
        }
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, jsonObject(mapOf(
                    "status", "UP",
                    "service", "agent-model-runtime",
                    "runtime_status", "ready"
            )));
        }
    }

    static class InvokeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, jsonObject(mapOf("status", "error", "message", "method not allowed")));
                return;
            }
            String body = readBody(exchange.getRequestBody());
            RuntimeRequest request = RuntimeRequest.fromBody(body);
            int statusCode;
            String payload;
            try {
                RuntimeResponse response = STATE.invoke(request, body.length());
                statusCode = response.httpStatus;
                payload = response.toJson();
            } catch (RuntimeException exc) {
                statusCode = 500;
                payload = jsonObject(mapOf(
                        "status", "error",
                        "request_id", request.requestId,
                        "task_type", request.taskType,
                        "message", exc.getMessage(),
                        "error_code", "RUNTIME_INTERNAL_ERROR"
                ));
            }
            writeJson(exchange, statusCode, payload);
        }
    }

    static class AsyncJobsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/runtime/async-jobs".equals(path)) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, jsonObject(mapOf("status", "error", "message", "method not allowed")));
                    return;
                }
                String body = readBody(exchange.getRequestBody());
                RuntimeRequest request = RuntimeRequest.fromBody(body);
                AsyncSubmission submission = STATE.submitAsync(request, body.length());
                writeJson(exchange, 202, submission.toJson());
                return;
            }

            String prefix = "/runtime/async-jobs/";
            if (!path.startsWith(prefix)) {
                writeJson(exchange, 404, jsonObject(mapOf("status", "error", "message", "not found")));
                return;
            }
            String suffix = path.substring(prefix.length());
            if (suffix.isEmpty()) {
                writeJson(exchange, 404, jsonObject(mapOf("status", "error", "message", "job id required")));
                return;
            }
            if (suffix.endsWith("/cancel")) {
                String jobId = suffix.substring(0, suffix.length() - "/cancel".length());
                if (!"POST".equals(exchange.getRequestMethod())) {
                    writeJson(exchange, 405, jsonObject(mapOf("status", "error", "message", "method not allowed")));
                    return;
                }
                writeJson(exchange, 200, STATE.cancelAsyncJob(jobId));
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                writeJson(exchange, 405, jsonObject(mapOf("status", "error", "message", "method not allowed")));
                return;
            }
            writeJson(exchange, 200, STATE.getAsyncJob(suffix));
        }
    }

    static class RuntimeOverviewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, STATE.snapshotOverview());
        }
    }

    static class JobsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, STATE.snapshotJobs());
        }
    }

    static class RoutesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, STATE.snapshotRoutes());
        }
    }

    static class CircuitsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            writeJson(exchange, 200, STATE.snapshotCircuits());
        }
    }

    static class RuntimeState {
        final int maxConcurrency = 4;
        final int maxAttempts = 2;
        final Semaphore semaphore = new Semaphore(maxConcurrency, true);
        final ExecutorService workers = Executors.newFixedThreadPool(maxConcurrency);
        final ExecutorService asyncCoordinator = Executors.newCachedThreadPool();
        final AtomicInteger inflight = new AtomicInteger(0);
        final AtomicInteger queueDepth = new AtomicInteger(0);
        final AtomicInteger totalCalls = new AtomicInteger(0);
        final AtomicInteger successCalls = new AtomicInteger(0);
        final AtomicInteger errorCalls = new AtomicInteger(0);
        final AtomicInteger cacheHits = new AtomicInteger(0);
        final AtomicInteger asyncSubmitted = new AtomicInteger(0);
        final AtomicInteger asyncCancelled = new AtomicInteger(0);
        final AtomicInteger nextJobId = new AtomicInteger(1);
        final Map<String, RoutePolicy> routes = new LinkedHashMap<String, RoutePolicy>();
        final Map<String, CircuitState> circuits = new ConcurrentHashMap<String, CircuitState>();
        final Map<String, CachedResult> cache = new ConcurrentHashMap<String, CachedResult>();
        final Map<String, JobRecord> jobsById = new ConcurrentHashMap<String, JobRecord>();
        final Map<String, Future<?>> asyncFutures = new ConcurrentHashMap<String, Future<?>>();
        final Deque<JobRecord> recentJobs = new ArrayDeque<JobRecord>();
        final Object jobLock = new Object();

        RuntimeState() {
            registerRoute(new RoutePolicy("pricing_inference", "general-llm-v1", "general-llm-v2", 2000, 2, 1));
            registerRoute(new RoutePolicy("structured_extraction", "general-llm-v1", "document-llm-v1", 2500, 2, 2));
            registerRoute(new RoutePolicy("document_extraction", "document-llm-v1", "general-llm-v1", 3000, 2, 2));
            registerRoute(new RoutePolicy("intent_understanding", "general-llm-v1", "general-llm-v2", 1500, 1, 1));
            registerRoute(new RoutePolicy("default", "general-llm-v1", "general-llm-v2", 2000, 2, 1));
        }

        void registerRoute(RoutePolicy route) {
            routes.put(route.taskType, route);
            ensureCircuit(route.primaryModelRoute);
            ensureCircuit(route.fallbackModelRoute);
        }

        void ensureCircuit(String modelRoute) {
            if (modelRoute == null || modelRoute.isEmpty()) {
                return;
            }
            circuits.putIfAbsent(modelRoute, new CircuitState(modelRoute));
        }

        RouteDecision resolveRoute(RoutePolicy route) {
            RouteDecision decision = fetchRecommendedRoute(route.taskType);
            if (decision != null) {
                ensureCircuit(decision.preferredModelRoute);
                ensureCircuit(decision.fallbackModelRoute);
                return decision;
            }
            return new RouteDecision(route.taskType, route.primaryModelRoute, route.fallbackModelRoute, "local", "", "", "");
        }

        RouteDecision fetchRecommendedRoute(String taskType) {
            try {
                String payload = fetchText("http://127.0.0.1:8005/routes/recommendations/" + taskType);
                String preferred = extractJsonString(payload, "preferred_model");
                String fallback = extractJsonString(payload, "fallback_model");
                if (preferred.isEmpty()) {
                    return null;
                }
                String preferredEndpoint = extractJsonString(payload, "preferred_endpoint");
                String preferredAuthEnv = extractJsonString(payload, "preferred_auth_env");
                String preferredProvider = extractJsonString(payload, "preferred_provider");
                return new RouteDecision(taskType, preferred, fallback.isEmpty() ? preferred : fallback, "l6", preferredEndpoint, preferredAuthEnv, preferredProvider);
            } catch (Exception ignored) {
                return null;
            }
        }

        RuntimeResponse invoke(RuntimeRequest request, int payloadSize) {
            RoutePolicy route = routes.containsKey(request.taskType) ? routes.get(request.taskType) : routes.get("default");
            JobRecord job = JobRecord.queued(nextJobId.getAndIncrement(), request, route.queueName, payloadSize, false);
            jobsById.put(job.jobId, job);
            appendRecentJob(job);
            return executeJob(request, payloadSize, route, job, true);
        }

        AsyncSubmission submitAsync(RuntimeRequest request, int payloadSize) {
            asyncSubmitted.incrementAndGet();
            RoutePolicy route = routes.containsKey(request.taskType) ? routes.get(request.taskType) : routes.get("default");
            JobRecord job = JobRecord.queued(nextJobId.getAndIncrement(), request, route.queueName, payloadSize, true);
            jobsById.put(job.jobId, job);
            appendRecentJob(job);
            Future<?> future = asyncCoordinator.submit(new Runnable() {
                @Override
                public void run() {
                    if (job.isCancelled()) {
                        return;
                    }
                    job.markRunning();
                    replaceRecentJob(job);
                    try {
                        RuntimeResponse response = executeJob(request, payloadSize, route, job, true);
                        job.lastResponseJson = response.toJson();
                    } catch (RuntimeException exc) {
                        job.markFinished("error", route.primaryModelRoute, 0, 1, false, "RUNTIME_INTERNAL_ERROR", 0);
                        job.lastResponseJson = jsonObject(mapOf(
                                "status", "error",
                                "job_id", job.jobId,
                                "request_id", request.requestId,
                                "task_type", request.taskType,
                                "error_code", "RUNTIME_INTERNAL_ERROR",
                                "message", exc.getMessage()
                        ));
                        replaceRecentJob(job);
                    } finally {
                        asyncFutures.remove(job.jobId);
                    }
                }
            });
            asyncFutures.put(job.jobId, future);
            return new AsyncSubmission(job.jobId, request.requestId, "queued", route.queueName);
        }

        String getAsyncJob(String jobId) {
            JobRecord job = jobsById.get(jobId);
            if (job == null) {
                return jsonObject(mapOf("status", "error", "message", "job not found", "job_id", jobId));
            }
            return jsonObject(mapOf(
                    "status", "ok",
                    "service", "agent-model-runtime",
                    "item", rawJson(jsonObject(job.toMap())),
                    "result", job.lastResponseJson == null ? rawJson("null") : rawJson(job.lastResponseJson)
            ));
        }

        String cancelAsyncJob(String jobId) {
            JobRecord job = jobsById.get(jobId);
            if (job == null) {
                return jsonObject(mapOf("status", "error", "message", "job not found", "job_id", jobId));
            }
            Future<?> future = asyncFutures.get(jobId);
            boolean cancelled = false;
            if (future != null) {
                cancelled = future.cancel(true);
            }
            if (cancelled || "queued".equals(job.status) || "running".equals(job.status)) {
                asyncCancelled.incrementAndGet();
                job.markFinished("cancelled", job.modelRoute, job.attempts, Math.max(1, System.currentTimeMillis() - job.createdAtMs), false, "CANCELLED", job.queueWaitMs);
                job.lastResponseJson = jsonObject(mapOf(
                        "status", "cancelled",
                        "job_id", job.jobId,
                        "request_id", job.requestId,
                        "task_type", job.taskType,
                        "message", "async job cancelled"
                ));
                replaceRecentJob(job);
            }
            asyncFutures.remove(jobId);
            return jsonObject(mapOf(
                    "status", cancelled ? "ok" : "noop",
                    "job_id", jobId,
                    "job_status", job.status,
                    "message", cancelled ? "async job cancelled" : "job was not cancellable"
            ));
        }

        RuntimeResponse executeJob(RuntimeRequest request, int payloadSize, RoutePolicy route, JobRecord job, boolean countMetrics) {
            if (countMetrics) {
                totalCalls.incrementAndGet();
            }
            String cacheKey = request.taskType + "::" + normalizeCacheKey(request.rawBody);
            CachedResult cached = cache.get(cacheKey);
            if (cached != null) {
                if (countMetrics) {
                    cacheHits.incrementAndGet();
                    successCalls.incrementAndGet();
                }
                RuntimeResponse cachedResponse = cached.response.copy();
                cachedResponse.cached = true;
                cachedResponse.queueWaitMs = 0;
                cachedResponse.jobId = job.jobId;
                job.setResultMetadata(cachedResponse.result);
                job.markFinished("success", cachedResponse.modelRoute, cachedResponse.attempts, cachedResponse.durationMs, true, "cache_hit", 0);
                replaceRecentJob(job);
                return cachedResponse;
            }

            long queueStart = System.currentTimeMillis();
            queueDepth.incrementAndGet();
            try {
                semaphore.acquire();
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return failResponse(request, route, job, payloadSize, 503, "RUNTIME_INTERRUPTED", exc.getMessage(), 0, 0, countMetrics);
            } finally {
                queueDepth.decrementAndGet();
            }

            inflight.incrementAndGet();
            long queueWaitMs = System.currentTimeMillis() - queueStart;
            try {
                RouteDecision routeDecision = resolveRoute(route);
                CircuitState primaryCircuit = circuits.get(routeDecision.preferredModelRoute);
                String selectedModelRoute = chooseModelRoute(routeDecision, primaryCircuit);
                int attempts = 0;
                long startedAt = System.currentTimeMillis();
                String lastErrorCode = "";
                String lastErrorMessage = "";
                for (int attempt = 1; attempt <= route.maxAttempts; attempt++) {
                    attempts = attempt;
                    Future<ModelExecutionResult> future = workers.submit(new ModelCall(request, routeDecision, selectedModelRoute, attempt));
                    try {
                        long effectiveTimeoutMs = determineEffectiveTimeoutMs(request, route, routeDecision, selectedModelRoute);
                        ModelExecutionResult modelResult = future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
                        RuntimeResponse response = RuntimeResponse.success(request, job.jobId, route.queueName, selectedModelRoute, attempts,
                                System.currentTimeMillis() - startedAt, payloadSize, queueWaitMs, modelResult.result);
                        job.lastResponseJson = response.toJson();
                        job.setResultMetadata(response.result);
                        job.markFinished("success", selectedModelRoute, attempts, response.durationMs, false, "", queueWaitMs);
                        replaceRecentJob(job);
                        circuits.get(selectedModelRoute).recordSuccess();
                        if (countMetrics) {
                            successCalls.incrementAndGet();
                        }
                        cache.put(cacheKey, new CachedResult(response.copy()));
                        return response;
                    } catch (TimeoutException exc) {
                        future.cancel(true);
                        lastErrorCode = "RUNTIME_TIMEOUT";
                        lastErrorMessage = "model execution timed out";
                    } catch (Exception exc) {
                        future.cancel(true);
                        lastErrorCode = "MODEL_EXECUTION_FAILED";
                        lastErrorMessage = exc.getCause() != null ? exc.getCause().getMessage() : exc.getMessage();
                    }
                }
                circuits.get(selectedModelRoute).recordFailure();
                String fallbackRoute = routeDecision.fallbackModelRoute;
                if (!fallbackRoute.equals(selectedModelRoute) && circuits.containsKey(fallbackRoute) && circuits.get(fallbackRoute).isAvailable()) {
                    try {
                        Future<ModelExecutionResult> future = workers.submit(new ModelCall(request, routeDecision, fallbackRoute, 1));
                        long fallbackTimeoutMs = determineEffectiveTimeoutMs(request, route, routeDecision, fallbackRoute);
                        ModelExecutionResult modelResult = future.get(fallbackTimeoutMs, TimeUnit.MILLISECONDS);
                        RuntimeResponse response = RuntimeResponse.success(request, job.jobId, route.queueName, fallbackRoute, 1,
                                System.currentTimeMillis() - startedAt, payloadSize, queueWaitMs, modelResult.result);
                        job.lastResponseJson = response.toJson();
                        job.setResultMetadata(response.result);
                        job.markFinished("success", fallbackRoute, 1, response.durationMs, false, "fallback", queueWaitMs);
                        replaceRecentJob(job);
                        circuits.get(fallbackRoute).recordSuccess();
                        if (countMetrics) {
                            successCalls.incrementAndGet();
                        }
                        cache.put(cacheKey, new CachedResult(response.copy()));
                        return response;
                    } catch (Exception fallbackExc) {
                        lastErrorCode = "MODEL_FALLBACK_FAILED";
                        lastErrorMessage = fallbackExc.getCause() != null ? fallbackExc.getCause().getMessage() : fallbackExc.getMessage();
                        circuits.get(fallbackRoute).recordFailure();
                    }
                }
                return failResponse(request, route, job, payloadSize, 504, lastErrorCode, lastErrorMessage, attempts, queueWaitMs, countMetrics);
            } finally {
                inflight.decrementAndGet();
                semaphore.release();
            }
        }

        private String chooseModelRoute(RouteDecision route, CircuitState primaryCircuit) {
            if (primaryCircuit != null && primaryCircuit.isAvailable()) {
                return route.preferredModelRoute;
            }
            return route.fallbackModelRoute;
        }

        private long determineEffectiveTimeoutMs(RuntimeRequest request, RoutePolicy route, RouteDecision decision, String modelRoute) {
            long baseTimeoutMs = request.timeoutMs > 0 ? request.timeoutMs : route.timeoutMs;
            boolean isRemotePreferredRoute = decision != null
                    && "l6".equals(decision.source)
                    && modelRoute.equals(decision.preferredModelRoute)
                    && decision.preferredEndpoint != null
                    && !decision.preferredEndpoint.isEmpty();
            if (isRemotePreferredRoute) {
                return Math.max(baseTimeoutMs, 8000L);
            }
            return baseTimeoutMs;
        }

        private RuntimeResponse failResponse(RuntimeRequest request, RoutePolicy route, JobRecord job, int payloadSize, int httpStatus,
                                             String errorCode, String message, int attempts, long queueWaitMs, boolean countMetrics) {
            if (countMetrics) {
                errorCalls.incrementAndGet();
            }
            RouteDecision routeDecision = resolveRoute(route);
            String selectedRoute = chooseModelRoute(routeDecision, circuits.get(routeDecision.preferredModelRoute));
            long durationMs = Math.max(1, System.currentTimeMillis() - job.createdAtMs);
            job.markFinished("error", selectedRoute, attempts, durationMs, false, errorCode, queueWaitMs);
            job.lastResponseJson = RuntimeResponse.error(httpStatus, request, job.jobId, route.queueName, selectedRoute, attempts, durationMs,
                    payloadSize, queueWaitMs, errorCode.isEmpty() ? "RUNTIME_EXECUTION_FAILED" : errorCode,
                    message == null || message.isEmpty() ? "runtime execution failed" : message).toJson();
            replaceRecentJob(job);
            return RuntimeResponse.error(httpStatus, request, job.jobId, route.queueName, selectedRoute, attempts, durationMs,
                    payloadSize, queueWaitMs, errorCode.isEmpty() ? "RUNTIME_EXECUTION_FAILED" : errorCode,
                    message == null || message.isEmpty() ? "runtime execution failed" : message);
        }

        String snapshotOverview() {
            List<Map<String, Object>> routeItems = new ArrayList<Map<String, Object>>();
            for (RoutePolicy route : routes.values()) {
                if ("default".equals(route.taskType)) {
                    continue;
                }
                RouteDecision decision = resolveRoute(route);
                CircuitState circuit = circuits.get(decision.preferredModelRoute);
                routeItems.add(mapOf(
                        "task_type", route.taskType,
                        "queue_name", route.queueName,
                        "model_route", chooseModelRoute(decision, circuit),
                        "primary_model_route", decision.preferredModelRoute,
                        "fallback_model_route", decision.fallbackModelRoute,
                        "route_source", decision.source,
                        "timeout_ms", route.timeoutMs,
                        "max_attempts", route.maxAttempts,
                        "priority", route.priority
                ));
            }
            return jsonObject(mapOf(
                    "status", "UP",
                    "service", "agent-model-runtime",
                    "queue_depth", queueDepth.get(),
                    "inflight", inflight.get(),
                    "max_concurrency", maxConcurrency,
                    "total_calls", totalCalls.get(),
                    "success_calls", successCalls.get(),
                    "error_calls", errorCalls.get(),
                    "cache_hits", cacheHits.get(),
                    "async_submitted", asyncSubmitted.get(),
                    "async_cancelled", asyncCancelled.get(),
                    "retry_policy", rawJson(jsonObject(mapOf("max_attempts", maxAttempts, "timeout_ms", 2000))),
                    "circuit_breakers", rawJson(snapshotCircuitItems()),
                    "routes", rawJson(jsonArray(routeItems)),
                    "recent_jobs", rawJson(jsonArray(snapshotRecentJobMaps(10)))
            ));
        }

        String snapshotJobs() {
            return jsonObject(mapOf(
                    "status", "UP",
                    "service", "agent-model-runtime",
                    "items", rawJson(jsonArray(snapshotRecentJobMaps(50)))
            ));
        }

        String snapshotRoutes() {
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (RoutePolicy route : routes.values()) {
                RouteDecision decision = resolveRoute(route);
                items.add(mapOf(
                        "task_type", route.taskType,
                        "queue_name", route.queueName,
                        "priority", route.priority,
                        "timeout_ms", route.timeoutMs,
                        "max_attempts", route.maxAttempts,
                        "primary_model_route", decision.preferredModelRoute,
                        "fallback_model_route", decision.fallbackModelRoute,
                        "route_source", decision.source
                ));
            }
            return jsonObject(mapOf(
                    "status", "UP",
                    "service", "agent-model-runtime",
                    "items", rawJson(jsonArray(items))
            ));
        }

        String snapshotCircuits() {
            return jsonObject(mapOf(
                    "status", "UP",
                    "service", "agent-model-runtime",
                    "items", rawJson(snapshotCircuitItems())
            ));
        }

        private String snapshotCircuitItems() {
            List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
            for (CircuitState circuit : circuits.values()) {
                items.add(mapOf(
                        "model_route", circuit.modelRoute,
                        "state", circuit.state(),
                        "failure_count", circuit.failureCount.get(),
                        "open_until", circuit.openUntilMs > 0 ? Instant.ofEpochMilli(circuit.openUntilMs).toString() : ""
                ));
            }
            return jsonArray(items);
        }

        private List<Map<String, Object>> snapshotRecentJobMaps(int limit) {
            synchronized (jobLock) {
                List<Map<String, Object>> items = new ArrayList<Map<String, Object>>();
                int count = 0;
                for (JobRecord record : recentJobs) {
                    items.add(record.toMap());
                    count += 1;
                    if (count >= limit) {
                        break;
                    }
                }
                return items;
            }
        }

        private void appendRecentJob(JobRecord job) {
            synchronized (jobLock) {
                recentJobs.addFirst(job.copy());
                while (recentJobs.size() > 100) {
                    recentJobs.removeLast();
                }
            }
        }

        private void replaceRecentJob(JobRecord job) {
            synchronized (jobLock) {
                ArrayDeque<JobRecord> refreshed = new ArrayDeque<JobRecord>();
                boolean inserted = false;
                for (JobRecord existing : recentJobs) {
                    if (Objects.equals(existing.jobId, job.jobId) && !inserted) {
                        refreshed.addLast(job.copy());
                        inserted = true;
                    } else {
                        refreshed.addLast(existing);
                    }
                }
                if (!inserted) {
                    refreshed.addFirst(job.copy());
                }
                recentJobs.clear();
                recentJobs.addAll(refreshed);
                while (recentJobs.size() > 100) {
                    recentJobs.removeLast();
                }
            }
        }

        void shutdown() {
            asyncCoordinator.shutdownNow();
            workers.shutdownNow();
        }
    }

    static class RouteDecision {
        final String taskType;
        final String preferredModelRoute;
        final String fallbackModelRoute;
        final String source;
        final String preferredEndpoint;
        final String preferredAuthEnv;
        final String preferredProvider;

        RouteDecision(String taskType, String preferredModelRoute, String fallbackModelRoute, String source,
                      String preferredEndpoint, String preferredAuthEnv, String preferredProvider) {
            this.taskType = taskType;
            this.preferredModelRoute = preferredModelRoute;
            this.fallbackModelRoute = fallbackModelRoute;
            this.source = source;
            this.preferredEndpoint = preferredEndpoint;
            this.preferredAuthEnv = preferredAuthEnv;
            this.preferredProvider = preferredProvider;
        }
    }

    static class ModelCall implements Callable<ModelExecutionResult> {
        final RuntimeRequest request;
        final RouteDecision routeDecision;
        final String modelRoute;
        final int attempt;

        ModelCall(RuntimeRequest request, RouteDecision routeDecision, String modelRoute, int attempt) {
            this.request = request;
            this.routeDecision = routeDecision;
            this.modelRoute = modelRoute;
            this.attempt = attempt;
        }

        @Override
        public ModelExecutionResult call() throws Exception {
            String lowered = request.rawBody.toLowerCase(Locale.ROOT);
            if (lowered.contains("force_fail")) {
                throw new IOException("forced model failure");
            }
            if (routeDecision != null && "l6".equals(routeDecision.source)
                    && modelRoute.equals(routeDecision.preferredModelRoute)
                    && routeDecision.preferredEndpoint != null
                    && !routeDecision.preferredEndpoint.isEmpty()) {
                return new ModelExecutionResult(callRemoteModel(request, routeDecision, modelRoute));
            }
            if (lowered.contains("simulate_timeout") || lowered.contains("slow")) {
                Thread.sleep(Math.max(request.timeoutMs + 200L, 400L));
            } else {
                Thread.sleep(20L * attempt);
            }
            String summary = buildSummary(request.taskType, request.rawBody);
            Map<String, Object> result = mapOf(
                    "summary", summary,
                    "task_type", request.taskType,
                    "risk_level", inferRiskLevel(request.rawBody),
                    "attempt", attempt,
                    "model_route", modelRoute,
                    "execution_mode", "local-fallback",
                    "output_tokens_estimate", Math.max(24, request.rawBody.length() / 3)
            );
            return new ModelExecutionResult(result);
        }
    }

    static Map<String, Object> callRemoteModel(RuntimeRequest request, RouteDecision routeDecision, String modelRoute) throws Exception {
        String endpoint = routeDecision.preferredEndpoint;
        String token = routeDecision.preferredAuthEnv == null || routeDecision.preferredAuthEnv.isEmpty()
                ? ""
                : System.getenv(routeDecision.preferredAuthEnv);
        String modelName = modelRoute.contains(":") ? modelRoute.substring(modelRoute.indexOf(':') + 1) : modelRoute;
        String prompt = extractJsonString(request.rawBody, "prompt");
        if (prompt.isEmpty()) {
            prompt = "Task type: " + request.taskType + "\nPayload: " + request.rawBody + "\nRespond with a short execution summary.";
        }
        String body = jsonObject(mapOf(
                "model", modelName,
                "messages", rawJson(jsonArray(Arrays.asList(
                        mapOf("role", "system", "content", "You are a precise runtime model responder."),
                        mapOf("role", "user", "content", prompt)
                ))),
                "temperature", 0,
                "max_tokens", 128
        ));
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint + "/chat/completions").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(Math.max(request.timeoutMs, 8000));
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (token != null && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        InputStream input = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = readBody(input);
        String content = extractJsonString(response, "content");
        String reasoning = extractJsonString(response, "reasoning");
        String text = extractJsonString(response, "text");
        String summary = !content.isEmpty() ? content : !reasoning.isEmpty() ? reasoning : !text.isEmpty() ? text : "remote model call completed";
        summary = summary.replace("\n", " ").trim();
        return mapOf(
                "summary", summary,
                "task_type", request.taskType,
                "risk_level", inferRiskLevel(request.rawBody),
                "attempt", 1,
                "model_route", modelRoute,
                "execution_mode", "remote-l6",
                "provider", routeDecision.preferredProvider,
                "endpoint", endpoint,
                "request_prompt_preview", prompt.length() > 240 ? prompt.substring(0, 240) + "..." : prompt,
                "request_prompt_full", prompt,
                "output_tokens_estimate", Math.max(24, summary.length() / 2)
        );
    }

    static String buildSummary(String taskType, String rawBody) {
        if (rawBody.contains("price") || rawBody.contains("quote")) {
            return "Pricing inference summary generated with risk and comparison highlights.";
        }
        if (rawBody.contains("authorization") || rawBody.contains("deviation")) {
            return "Compliance extraction summary generated with authorization and deviation risks.";
        }
        if ("intent_understanding".equals(taskType)) {
            return "Intent understanding completed with routing guidance.";
        }
        return "Model execution completed with structured reasoning output.";
    }

    static String inferRiskLevel(String rawBody) {
        if (rawBody.contains("incomplete") || rawBody.contains("deviation") || rawBody.contains("breach")) {
            return "high";
        }
        if (rawBody.contains("payment") || rawBody.contains("qualification")) {
            return "medium";
        }
        return "low";
    }

    static class RuntimeRequest {
        final String requestId;
        final String taskType;
        final String capabilityCode;
        final String priority;
        final int timeoutMs;
        final String rawBody;

        RuntimeRequest(String requestId, String taskType, String capabilityCode, String priority, int timeoutMs, String rawBody) {
            this.requestId = requestId;
            this.taskType = taskType;
            this.capabilityCode = capabilityCode;
            this.priority = priority;
            this.timeoutMs = timeoutMs;
            this.rawBody = rawBody == null ? "" : rawBody;
        }

        static RuntimeRequest fromBody(String body) {
            String requestId = extractJsonString(body, "request_id");
            if (requestId.isEmpty()) {
                requestId = "req-runtime-" + System.currentTimeMillis();
            }
            String taskType = extractJsonString(body, "task_type");
            if (taskType.isEmpty()) {
                taskType = "pricing_inference";
            }
            String capabilityCode = extractJsonString(body, "capability_code");
            String priority = extractJsonString(body, "priority");
            if (priority.isEmpty()) {
                priority = "normal";
            }
            int timeoutMs = extractJsonInt(body, "timeout_ms", 0);
            return new RuntimeRequest(requestId, taskType, capabilityCode, priority, timeoutMs, body);
        }
    }

    static class RuntimeResponse {
        int httpStatus;
        String status;
        String requestId;
        String jobId;
        String taskType;
        String runtime;
        String modelRoute;
        int attempts;
        boolean cached;
        long durationMs;
        int payloadSize;
        long queueWaitMs;
        String queueName;
        String errorCode;
        String message;
        Map<String, Object> result;

        static RuntimeResponse success(RuntimeRequest request, String jobId, String queueName, String modelRoute, int attempts,
                                       long durationMs, int payloadSize, long queueWaitMs, Map<String, Object> result) {
            RuntimeResponse response = new RuntimeResponse();
            response.httpStatus = 200;
            response.status = "ok";
            response.requestId = request.requestId;
            response.jobId = jobId;
            response.taskType = request.taskType;
            response.runtime = "priority-scheduler";
            response.modelRoute = modelRoute;
            response.attempts = attempts;
            response.cached = false;
            response.durationMs = Math.max(1, durationMs);
            response.payloadSize = payloadSize;
            response.queueWaitMs = queueWaitMs;
            response.queueName = queueName;
            response.result = result;
            return response;
        }

        static RuntimeResponse error(int httpStatus, RuntimeRequest request, String jobId, String queueName, String modelRoute,
                                     int attempts, long durationMs, int payloadSize, long queueWaitMs, String errorCode, String message) {
            RuntimeResponse response = new RuntimeResponse();
            response.httpStatus = httpStatus;
            response.status = "error";
            response.requestId = request.requestId;
            response.jobId = jobId;
            response.taskType = request.taskType;
            response.runtime = "priority-scheduler";
            response.modelRoute = modelRoute;
            response.attempts = attempts;
            response.cached = false;
            response.durationMs = Math.max(1, durationMs);
            response.payloadSize = payloadSize;
            response.queueWaitMs = queueWaitMs;
            response.queueName = queueName;
            response.errorCode = errorCode;
            response.message = message;
            response.result = Collections.<String, Object>emptyMap();
            return response;
        }

        RuntimeResponse copy() {
            RuntimeResponse response = new RuntimeResponse();
            response.httpStatus = httpStatus;
            response.status = status;
            response.requestId = requestId;
            response.jobId = jobId;
            response.taskType = taskType;
            response.runtime = runtime;
            response.modelRoute = modelRoute;
            response.attempts = attempts;
            response.cached = cached;
            response.durationMs = durationMs;
            response.payloadSize = payloadSize;
            response.queueWaitMs = queueWaitMs;
            response.queueName = queueName;
            response.errorCode = errorCode;
            response.message = message;
            response.result = result == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(result);
            return response;
        }

        String toJson() {
            if ("error".equals(status)) {
                return jsonObject(mapOf(
                        "status", status,
                        "service", serviceNameFor(taskType),
                        "provider", "agent-model-runtime",
                        "request_id", requestId,
                        "job_id", jobId,
                        "task_type", taskType,
                        "runtime", runtime,
                        "queue_name", queueName,
                        "model_route", modelRoute,
                        "attempts", attempts,
                        "cached", cached,
                        "duration_ms", durationMs,
                        "payload_size", payloadSize,
                        "queue_wait_ms", queueWaitMs,
                        "error_code", errorCode,
                        "message", message,
                        "errors", rawJson(jsonArray(Arrays.asList(mapOf("code", errorCode, "message", message))))
                ));
            }
            return jsonObject(mapOf(
                    "status", status,
                    "service", serviceNameFor(taskType),
                    "provider", "agent-model-runtime",
                    "request_id", requestId,
                    "job_id", jobId,
                    "task_type", taskType,
                    "runtime", runtime,
                    "queue_name", queueName,
                    "model_route", modelRoute,
                    "attempts", attempts,
                    "cached", cached,
                    "duration_ms", durationMs,
                    "payload_size", payloadSize,
                    "queue_wait_ms", queueWaitMs,
                    "result", rawJson(jsonObject(result)),
                    "errors", rawJson("[]")
            ));
        }
    }

    static class AsyncSubmission {
        final String jobId;
        final String requestId;
        final String status;
        final String queueName;

        AsyncSubmission(String jobId, String requestId, String status, String queueName) {
            this.jobId = jobId;
            this.requestId = requestId;
            this.status = status;
            this.queueName = queueName;
        }

        String toJson() {
            return jsonObject(mapOf(
                    "status", "accepted",
                    "service", "agent-model-runtime",
                    "job_id", jobId,
                    "request_id", requestId,
                    "job_status", status,
                    "queue_name", queueName
            ));
        }
    }

    static class ModelExecutionResult {
        final Map<String, Object> result;

        ModelExecutionResult(Map<String, Object> result) {
            this.result = result;
        }
    }

    static class CachedResult {
        final RuntimeResponse response;

        CachedResult(RuntimeResponse response) {
            this.response = response;
        }
    }

    static class RoutePolicy {
        final String taskType;
        final String primaryModelRoute;
        final String fallbackModelRoute;
        final int timeoutMs;
        final int maxAttempts;
        final int priority;
        final String queueName;

        RoutePolicy(String taskType, String primaryModelRoute, String fallbackModelRoute, int timeoutMs, int maxAttempts, int priority) {
            this.taskType = taskType;
            this.primaryModelRoute = primaryModelRoute;
            this.fallbackModelRoute = fallbackModelRoute;
            this.timeoutMs = timeoutMs;
            this.maxAttempts = maxAttempts;
            this.priority = priority;
            this.queueName = taskType + "-queue";
        }
    }

    static class CircuitState {
        final String modelRoute;
        final AtomicInteger failureCount = new AtomicInteger(0);
        volatile long openUntilMs = 0;

        CircuitState(String modelRoute) {
            this.modelRoute = modelRoute;
        }

        boolean isAvailable() {
            return System.currentTimeMillis() >= openUntilMs;
        }

        void recordSuccess() {
            failureCount.set(0);
            openUntilMs = 0;
        }

        void recordFailure() {
            int failures = failureCount.incrementAndGet();
            if (failures >= 2) {
                openUntilMs = System.currentTimeMillis() + 30_000L;
            }
        }

        String state() {
            return isAvailable() ? "CLOSED" : "OPEN";
        }
    }

    static class JobRecord {
        final String jobId;
        final String requestId;
        final String taskType;
        final String queueName;
        final int payloadSize;
        final long createdAtMs;
        final boolean async;
        String status;
        String modelRoute;
        int attempts;
        long durationMs;
        boolean cached;
        String errorCode;
        long queueWaitMs;
        long finishedAtMs;
        String lastResponseJson;
        String resultSummary = "";
        String executionMode = "";
        String provider = "";
        String endpoint = "";
        String requestPromptPreview = "";
        String requestPromptFull = "";

        JobRecord(String jobId, String requestId, String taskType, String queueName, int payloadSize, long createdAtMs, boolean async) {
            this.jobId = jobId;
            this.requestId = requestId;
            this.taskType = taskType;
            this.queueName = queueName;
            this.payloadSize = payloadSize;
            this.createdAtMs = createdAtMs;
            this.async = async;
            this.status = "queued";
            this.modelRoute = "";
        }

        static JobRecord queued(int numericId, RuntimeRequest request, String queueName, int payloadSize, boolean async) {
            return new JobRecord("job-" + numericId, request.requestId, request.taskType, queueName, payloadSize, System.currentTimeMillis(), async);
        }

        void markRunning() {
            this.status = "running";
        }

        void markFinished(String status, String modelRoute, int attempts, long durationMs, boolean cached, String errorCode, long queueWaitMs) {
            this.status = status;
            this.modelRoute = modelRoute == null ? "" : modelRoute;
            this.attempts = attempts;
            this.durationMs = durationMs;
            this.cached = cached;
            this.errorCode = errorCode;
            this.queueWaitMs = queueWaitMs;
            this.finishedAtMs = System.currentTimeMillis();
        }

        boolean isCancelled() {
            return "cancelled".equals(status);
        }

        JobRecord copy() {
            JobRecord copy = new JobRecord(jobId, requestId, taskType, queueName, payloadSize, createdAtMs, async);
            copy.status = status;
            copy.modelRoute = modelRoute;
            copy.attempts = attempts;
            copy.durationMs = durationMs;
            copy.cached = cached;
            copy.errorCode = errorCode;
            copy.queueWaitMs = queueWaitMs;
            copy.finishedAtMs = finishedAtMs;
            copy.lastResponseJson = lastResponseJson;
            copy.resultSummary = resultSummary;
            copy.executionMode = executionMode;
            copy.provider = provider;
            copy.endpoint = endpoint;
            copy.requestPromptPreview = requestPromptPreview;
            copy.requestPromptFull = requestPromptFull;
            return copy;
        }

        Map<String, Object> toMap() {
            return mapOf(
                    "job_id", jobId,
                    "request_id", requestId,
                    "task_type", taskType,
                    "queue_name", queueName,
                    "status", status,
                    "mode", async ? "async" : "sync",
                    "model_route", modelRoute,
                    "attempts", attempts,
                    "duration_ms", durationMs,
                    "cached", cached,
                    "error_code", errorCode == null ? "" : errorCode,
                    "result_summary", resultSummary,
                    "execution_mode", executionMode,
                    "provider", provider,
                    "endpoint", endpoint,
                    "request_prompt_preview", requestPromptPreview,
                    "request_prompt_full", requestPromptFull,
                    "queue_wait_ms", queueWaitMs,
                    "created_at", Instant.ofEpochMilli(createdAtMs).toString(),
                    "finished_at", finishedAtMs > 0 ? Instant.ofEpochMilli(finishedAtMs).toString() : ""
            );
        }

        void setResultMetadata(Map<String, Object> result) {
            if (result == null) {
                return;
            }
            resultSummary = stringValue(result.get("summary"));
            executionMode = stringValue(result.get("execution_mode"));
            provider = stringValue(result.get("provider"));
            endpoint = stringValue(result.get("endpoint"));
            requestPromptPreview = stringValue(result.get("request_prompt_preview"));
            requestPromptFull = stringValue(result.get("request_prompt_full"));
        }
    }

    static String normalizeCacheKey(String rawBody) {
        if (rawBody == null) {
            return "";
        }
        String requestId = extractJsonString(rawBody, "request_id");
        if (requestId.isEmpty()) {
            return rawBody;
        }
        return rawBody.replace("\"request_id\":\"" + requestId + "\"", "\"request_id\":\"<normalized>\"");
    }

    static String serviceNameFor(String taskType) {
        if ("pricing_inference".equals(taskType)) {
            return "pricing";
        }
        return "model-runtime";
    }

    static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static void writeJson(HttpExchange exchange, int statusCode, String payload) throws IOException {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String fetchText(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        try (InputStream input = connection.getInputStream()) {
            return readBody(input);
        }
    }

    static String readBody(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    static String extractJsonString(String body, String key) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String extractRawJson(String body, String key) {
        if (body == null || body.trim().isEmpty()) {
            return "{}";
        }
        String anchor = "\"" + key + "\":";
        int start = body.indexOf(anchor);
        if (start < 0) {
            return "{}";
        }
        int valueStart = start + anchor.length();
        while (valueStart < body.length() && Character.isWhitespace(body.charAt(valueStart))) {
            valueStart += 1;
        }
        if (valueStart >= body.length()) {
            return "{}";
        }
        char first = body.charAt(valueStart);
        if (first == '"') {
            int end = body.indexOf("\"", valueStart + 1);
            while (end > 0 && body.charAt(end - 1) == '\\') {
                end = body.indexOf("\"", end + 1);
            }
            return end < 0 ? "{}" : body.substring(valueStart, end + 1);
        }
        if (first != '{' && first != '[') {
            int end = body.indexOf(",", valueStart);
            if (end < 0) {
                end = body.indexOf("}", valueStart);
            }
            return end < 0 ? "{}" : body.substring(valueStart, end).trim();
        }
        int depth = 0;
        for (int i = valueStart; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{' || c == '[') {
                depth += 1;
            } else if (c == '}' || c == ']') {
                depth -= 1;
                if (depth == 0) {
                    return body.substring(valueStart, i + 1);
                }
            }
        }
        return "{}";
    }

    static int extractJsonInt(String body, String key, int defaultValue) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(body == null ? "" : body);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : defaultValue;
    }

    static Map<String, Object> mapOf(Object... entries) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

    static RawJson rawJson(Object text) {
        return new RawJson(String.valueOf(text));
    }

    static String jsonArray(List<?> items) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (Object item : items) {
            if (!first) {
                builder.append(',');
            }
            builder.append(toJsonValue(item));
            first = false;
        }
        builder.append(']');
        return builder.toString();
    }

    static String jsonObject(Map<String, ?> map) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(entry.getKey())).append('"').append(':');
            builder.append(toJsonValue(entry.getValue()));
            first = false;
        }
        builder.append('}');
        return builder.toString();
    }

    @SuppressWarnings("unchecked")
    static String toJsonValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof RawJson) {
            return ((RawJson) value).value;
        }
        if (value instanceof String) {
            return '"' + escapeJson((String) value) + '"';
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            return jsonObject((Map<String, ?>) value);
        }
        if (value instanceof List) {
            return jsonArray((List<?>) value);
        }
        return '"' + escapeJson(String.valueOf(value)) + '"';
    }

    static String escapeJson(String value) {
        return (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    static class RawJson {
        final String value;

        RawJson(String value) {
            this.value = value;
        }
    }
}
