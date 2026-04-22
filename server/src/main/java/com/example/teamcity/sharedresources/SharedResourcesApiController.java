package com.example.teamcity.sharedresources;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SProjectFeatureDescriptor;
import jetbrains.buildServer.users.User;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/** REST endpoints for reading and updating shared resources. */
public class SharedResourcesApiController extends BaseController {

    static final String FEATURE_TYPE = "JetBrains.SharedResources";
    static final String PARAM_NAME   = "name";
    static final String PARAM_TYPE   = "type";
    static final String PARAM_QUOTA  = "quota";
    static final String PARAM_VALUES = "values";

    private final ProjectManager projectManager;
    private final SharedResourcesSettings settings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Serializes plugin-side PUT requests. */
    private final ReentrantLock writeLock = new ReentrantLock();

    public SharedResourcesApiController(@NotNull ProjectManager projectManager,
                                        @NotNull SharedResourcesSettings settings) {
        this.projectManager = projectManager;
        this.settings = settings;
        setSupportedMethods("GET", "PUT");
    }

    @Override
    protected void initApplicationContext() throws BeansException {
        super.initApplicationContext();
        ApplicationContext ctx = getApplicationContext();
        WebControllerManager webManager;
        try {
            webManager = ctx.getBean(WebControllerManager.class);
        } catch (Exception e) {
            // Separate classloader deployments may need the parent context.
            webManager = ctx.getParent().getBean(WebControllerManager.class);
        }
        webManager.registerController("/app/sharedResourcesApi/**", this);
    }

    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response) throws Exception {
        response.setContentType("application/json;charset=UTF-8");

        User currentUser = getAuthenticatedUser(request);
        if (currentUser == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            writeJson(response, error("Authentication required"));
            return null;
        }

        String method = request.getMethod();
        switch (method) {
            case "GET":  handleGet(request, response, currentUser); break;
            case "PUT":  handlePut(request, response, currentUser); break;
            default:
                response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                writeJson(response, error("Method not allowed: " + method));
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // GET /app/sharedResourcesApi/resources?projectId=<id>
    // -------------------------------------------------------------------------

    private void handleGet(HttpServletRequest request, HttpServletResponse response,
                           User user) throws Exception {
        SProject project = resolveProject(request, response);
        if (project == null) return;

        if (!canView(user, project)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            writeJson(response, error("No VIEW_PROJECT permission on: " + project.getExternalId()));
            return;
        }

        List<Map<String, Object>> resources = project
                .getOwnFeaturesOfType(FEATURE_TYPE)
                .stream()
                .map(this::featureToMap)
                .collect(Collectors.toList());

        writeJson(response, Map.of("resources", resources));
    }

    // -------------------------------------------------------------------------
    // PUT /app/sharedResourcesApi/resources?projectId=<id>
    // -------------------------------------------------------------------------

    private void handlePut(HttpServletRequest request, HttpServletResponse response,
                           User user) throws Exception {
        SProject project = resolveProject(request, response);
        if (project == null) return;

        if (!canEdit(user, project)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            writeJson(response, error("No EDIT_PROJECT permission on: " + project.getExternalId()));
            return;
        }

        BatchUpdateRequest body;
        try {
            body = objectMapper.readValue(request.getInputStream(), BatchUpdateRequest.class);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeJson(response, error("Invalid JSON body: " + e.getMessage()));
            return;
        }

        if (body.getResources() == null || body.getResources().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeJson(response, error("'resources' array is required and must not be empty"));
            return;
        }

        if (!tryAcquireWriteLock()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader("Retry-After", String.valueOf(settings.getRetryAfterLockSeconds()));
            writeJson(response, error("Server is busy processing another shared resource update. Retry in a few seconds."));
            return;
        }

        try {
            List<String> applied = applyUpdates(project, body.getResources());
            writeJson(response, Map.of(
                    "status", "ok",
                    "updatedResources", applied
            ));
        } catch (ResourceNotFoundException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            writeJson(response, error(e.getMessage()));
        } catch (BadRequestException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeJson(response, error(e.getMessage()));
        } catch (PersistException e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setHeader("Retry-After", String.valueOf(settings.getRetryAfterPersistSeconds()));
            writeJson(response, error("TeamCity could not persist the config change. " +
                    "This may be a transient conflict with an internal write (e.g. Kotlin DSL sync). " +
                    "Retry in a few seconds. Detail: " + e.getMessage()));
        } finally {
            writeLock.unlock();
        }
    }

    // -------------------------------------------------------------------------
    // Core update logic — runs inside the write lock
    // -------------------------------------------------------------------------

    private List<String> applyUpdates(SProject project,
                                      List<BatchUpdateRequest.ResourceUpdate> updates)
            throws ResourceNotFoundException, BadRequestException, PersistException {

        Map<String, SProjectFeatureDescriptor> byName = new LinkedHashMap<>();
        for (SProjectFeatureDescriptor f : project.getOwnFeaturesOfType(FEATURE_TYPE)) {
            byName.put(f.getParameters().get(PARAM_NAME), f);
        }
        Map<String, Map<String, String>> originalParamsByFeatureId = new LinkedHashMap<>();
        Set<String> seenResourceNames = new HashSet<>();

        for (BatchUpdateRequest.ResourceUpdate u : updates) {
            if (u.getName() == null || u.getName().isBlank()) {
                throw new BadRequestException("Each resource update must specify a 'name'");
            }
            if (!seenResourceNames.add(u.getName())) {
                throw new BadRequestException("Duplicate resource update in request: '" + u.getName() + "'");
            }
            if (!byName.containsKey(u.getName())) {
                throw new ResourceNotFoundException("Shared resource not found: '" + u.getName() +
                        "' in project '" + project.getExternalId() + "'");
            }
            if (u.getSetValues() != null && (u.getAddValues() != null || u.getRemoveValues() != null)) {
                throw new BadRequestException("Cannot combine 'setValues' with 'addValues'/'removeValues' for resource: " + u.getName());
            }

            SProjectFeatureDescriptor feature = byName.get(u.getName());
            String type = feature.getParameters().getOrDefault(PARAM_TYPE, "quoted");
            validateUpdateFieldsForType(u, type);
        }

        List<String> applied = new ArrayList<>();
        for (BatchUpdateRequest.ResourceUpdate u : updates) {
            SProjectFeatureDescriptor feature = byName.get(u.getName());
            Map<String, String> originalParams = new HashMap<>(feature.getParameters());
            originalParamsByFeatureId.put(feature.getId(), originalParams);
            Map<String, String> params = new HashMap<>(originalParams);
            String type = params.getOrDefault(PARAM_TYPE, "quoted");

            if ("custom".equals(type) || "customValues".equals(type)) {
                applyValueChanges(params, u);
            } else if ("quoted".equals(type) && u.getQuota() != null) {
                params.put(PARAM_QUOTA, String.valueOf(u.getQuota()));
            }
            project.updateFeature(feature.getId(), FEATURE_TYPE, params);
            applied.add(u.getName());
        }

        int attempts = settings.getPersistMaxAttempts();
        long delayMs  = settings.getPersistRetryDelayMs();
        Exception lastFailure = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                project.persist();
                return applied;
            } catch (Exception e) {
                lastFailure = e;
                if (i < attempts) {
                    try { Thread.sleep(delayMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        rollbackUpdates(project, originalParamsByFeatureId);
                        throw new PersistException("Persist retry interrupted: " + e.getMessage());
                    }
                }
            }
        }
        rollbackUpdates(project, originalParamsByFeatureId);
        throw new PersistException(lastFailure.getMessage());
    }

    private void applyValueChanges(Map<String, String> params,
                                   BatchUpdateRequest.ResourceUpdate update) {
        if (update.getSetValues() != null) {
            params.put(PARAM_VALUES, String.join("\n", update.getSetValues()));
            return;
        }

        String raw = params.getOrDefault(PARAM_VALUES, "");
        LinkedHashSet<String> values = new LinkedHashSet<>(
                Arrays.asList(raw.isEmpty() ? new String[0] : raw.split("\n")));

        if (update.getRemoveValues() != null) values.removeAll(update.getRemoveValues());
        if (update.getAddValues()    != null) values.addAll(update.getAddValues());

        params.put(PARAM_VALUES, String.join("\n", values));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SProject resolveProject(HttpServletRequest request,
                                    HttpServletResponse response) throws Exception {
        String projectId = request.getParameter("projectId");
        if (projectId == null || projectId.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            writeJson(response, error("'projectId' query parameter is required"));
            return null;
        }
        SProject project = projectManager.findProjectByExternalId(projectId);
        if (project == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            writeJson(response, error("Project not found: " + projectId));
            return null;
        }
        return project;
    }

    private Map<String, Object> featureToMap(SProjectFeatureDescriptor f) {
        Map<String, String> p = f.getParameters();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("featureId", f.getId());
        m.put("name",  p.get(PARAM_NAME));
        m.put("type",  p.getOrDefault(PARAM_TYPE, "quoted"));

        String type = p.getOrDefault(PARAM_TYPE, "quoted");
        if ("custom".equals(type) || "customValues".equals(type)) {
            String raw = p.getOrDefault(PARAM_VALUES, "");
            m.put("values", raw.isEmpty()
                    ? Collections.emptyList()
                    : Arrays.asList(raw.split("\n")));
        } else if ("quoted".equals(type)) {
            m.put("quota", p.get(PARAM_QUOTA));
        }
        return m;
    }

    private boolean canView(User user, SProject project) {
        return user.isPermissionGrantedForProject(project.getProjectId(),
                jetbrains.buildServer.serverSide.auth.Permission.VIEW_PROJECT);
    }

    private boolean canEdit(User user, SProject project) {
        return user.isPermissionGrantedForProject(project.getProjectId(),
                jetbrains.buildServer.serverSide.auth.Permission.EDIT_PROJECT);
    }

    private void writeJson(HttpServletResponse response, Object value) throws Exception {
        objectMapper.writeValue(response.getOutputStream(), value);
    }

    /** Overridable in tests. */
    protected boolean tryAcquireWriteLock() throws InterruptedException {
        return writeLock.tryLock(settings.getLockTimeoutSeconds(), TimeUnit.SECONDS);
    }

    /** Overridable in tests. */
    protected User getAuthenticatedUser(HttpServletRequest request) {
        return jetbrains.buildServer.web.util.SessionUser.getUser(request);
    }

    private void validateUpdateFieldsForType(BatchUpdateRequest.ResourceUpdate update, String type)
            throws BadRequestException {
        boolean hasValueChanges = update.getSetValues() != null
                || update.getAddValues() != null
                || update.getRemoveValues() != null;

        if (("custom".equals(type) || "customValues".equals(type)) && update.getQuota() != null) {
            throw new BadRequestException("Resource '" + update.getName() + "' is type '" + type +
                    "' and does not support 'quota'");
        }

        if ("quoted".equals(type) && hasValueChanges) {
            throw new BadRequestException("Resource '" + update.getName() + "' is type 'quoted' and does not support value list updates");
        }
    }

    private void rollbackUpdates(SProject project, Map<String, Map<String, String>> originalParamsByFeatureId)
            throws PersistException {
        try {
            for (Map.Entry<String, Map<String, String>> entry : originalParamsByFeatureId.entrySet()) {
                project.updateFeature(entry.getKey(), FEATURE_TYPE, new HashMap<>(entry.getValue()));
            }
        } catch (Exception rollbackFailure) {
            throw new PersistException("Persist failed and rollback did not complete cleanly: " + rollbackFailure.getMessage());
        }
    }

    private Map<String, String> error(String message) {
        return Map.of("error", message);
    }

    // -------------------------------------------------------------------------
    // Custom exceptions
    // -------------------------------------------------------------------------

    static class ResourceNotFoundException extends Exception {
        ResourceNotFoundException(String msg) { super(msg); }
    }

    static class BadRequestException extends Exception {
        BadRequestException(String msg) { super(msg); }
    }

    static class PersistException extends Exception {
        PersistException(String msg) { super(msg); }
    }
}
