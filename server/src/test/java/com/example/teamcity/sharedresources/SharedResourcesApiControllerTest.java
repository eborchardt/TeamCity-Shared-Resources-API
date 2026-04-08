package com.example.teamcity.sharedresources;

import com.fasterxml.jackson.databind.ObjectMapper;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.SProjectFeatureDescriptor;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

import static com.example.teamcity.sharedresources.SharedResourcesApiController.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SharedResourcesApiControllerTest {

    @Mock private ProjectManager projectManager;
    @Mock private ServerPaths serverPaths;
    @Mock private SProject project;
    @Mock private SProjectFeatureDescriptor gpuFeature;
    @Mock private SProjectFeatureDescriptor licenseFeature;
    @Mock private User user;

    private TestableController controller;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(serverPaths.getPluginDataDirectory()).thenReturn(new java.io.File(System.getProperty("java.io.tmpdir"), "tc-test-plugin-data"));
        SharedResourcesSettings settings = new SharedResourcesSettings(serverPaths);
        controller = new TestableController(projectManager, user, settings);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();

        // Default project setup
        when(projectManager.findProjectByExternalId("_Root")).thenReturn(project);
        when(project.getProjectId()).thenReturn("_Root");
        when(project.getExternalId()).thenReturn("_Root");
        when(user.isPermissionGrantedForProject(any(), eq(Permission.VIEW_PROJECT))).thenReturn(true);
        when(user.isPermissionGrantedForProject(any(), eq(Permission.EDIT_PROJECT))).thenReturn(true);

        // GPU-Pool: custom type with 3 values
        when(gpuFeature.getId()).thenReturn("PROJECT_EXT_1");
        when(gpuFeature.getParameters()).thenReturn(new HashMap<>(Map.of(
                PARAM_NAME, "GPU-Pool",
                PARAM_TYPE, "custom",
                PARAM_VALUES, "gpu-01\ngpu-02\ngpu-03"
        )));

        // License-Pool: quoted type with quota 5
        when(licenseFeature.getId()).thenReturn("PROJECT_EXT_2");
        when(licenseFeature.getParameters()).thenReturn(new HashMap<>(Map.of(
                PARAM_NAME, "License-Pool",
                PARAM_TYPE, "quoted",
                PARAM_QUOTA, "5"
        )));

        when(project.getOwnFeaturesOfType(FEATURE_TYPE))
                .thenReturn(List.of(gpuFeature, licenseFeature));
    }

    // -------------------------------------------------------------------------
    // GET /resources
    // -------------------------------------------------------------------------

    @Test
    void get_returnsAllResources() throws Exception {
        request.setMethod("GET");
        request.addParameter("projectId", "_Root");

        controller.handle(request, response);

        assertEquals(200, response.getStatus());
        Map<?, ?> body = objectMapper.readValue(response.getContentAsString(), Map.class);
        List<?> resources = (List<?>) body.get("resources");
        assertEquals(2, resources.size());

        Map<?, ?> gpu = (Map<?, ?>) resources.get(0);
        assertEquals("GPU-Pool", gpu.get("name"));
        assertEquals("custom", gpu.get("type"));
        assertEquals(List.of("gpu-01", "gpu-02", "gpu-03"), gpu.get("values"));

        Map<?, ?> license = (Map<?, ?>) resources.get(1);
        assertEquals("License-Pool", license.get("name"));
        assertEquals("quoted", license.get("type"));
        assertEquals("5", license.get("quota"));
    }

    @Test
    void get_missingProjectId_returns400() throws Exception {
        request.setMethod("GET");

        controller.handle(request, response);

        assertEquals(400, response.getStatus());
        assertErrorContains("projectId");
    }

    @Test
    void get_unknownProject_returns404() throws Exception {
        request.setMethod("GET");
        request.addParameter("projectId", "NonExistent");

        controller.handle(request, response);

        assertEquals(404, response.getStatus());
    }

    @Test
    void get_noViewPermission_returns403() throws Exception {
        when(user.isPermissionGrantedForProject(any(), eq(Permission.VIEW_PROJECT))).thenReturn(false);
        request.setMethod("GET");
        request.addParameter("projectId", "_Root");

        controller.handle(request, response);

        assertEquals(403, response.getStatus());
    }

    @Test
    void get_unauthenticated_returns401() throws Exception {
        TestableController unauthed = new TestableController(projectManager, null,
                new SharedResourcesSettings(serverPaths));
        request.setMethod("GET");
        request.addParameter("projectId", "_Root");

        unauthed.handle(request, response);

        assertEquals(401, response.getStatus());
    }

    // -------------------------------------------------------------------------
    // PUT /resources — value manipulation
    // -------------------------------------------------------------------------

    @Test
    void put_addValues() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"addValues\":[\"gpu-04\",\"gpu-05\"]}]}");

        assertEquals(200, response.getStatus());

        ArgumentCaptor<Map<String, String>> params = captureUpdateParams("PROJECT_EXT_1");
        String values = params.getValue().get(PARAM_VALUES);
        assertTrue(values.contains("gpu-01"));
        assertTrue(values.contains("gpu-04"));
        assertTrue(values.contains("gpu-05"));
        verify(project).persist();
    }

    @Test
    void put_removeValues() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"removeValues\":[\"gpu-02\"]}]}");

        assertEquals(200, response.getStatus());

        ArgumentCaptor<Map<String, String>> params = captureUpdateParams("PROJECT_EXT_1");
        String values = params.getValue().get(PARAM_VALUES);
        assertFalse(values.contains("gpu-02"));
        assertTrue(values.contains("gpu-01"));
        assertTrue(values.contains("gpu-03"));
        verify(project).persist();
    }

    @Test
    void put_setValues_replacesEntireList() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"setValues\":[\"gpu-10\",\"gpu-11\"]}]}");

        assertEquals(200, response.getStatus());

        ArgumentCaptor<Map<String, String>> params = captureUpdateParams("PROJECT_EXT_1");
        assertEquals("gpu-10\ngpu-11", params.getValue().get(PARAM_VALUES));
        verify(project).persist();
    }

    @Test
    void put_addAndRemoveInSameCall() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"addValues\":[\"gpu-04\"],\"removeValues\":[\"gpu-01\"]}]}");

        assertEquals(200, response.getStatus());

        ArgumentCaptor<Map<String, String>> params = captureUpdateParams("PROJECT_EXT_1");
        String values = params.getValue().get(PARAM_VALUES);
        assertFalse(values.contains("gpu-01"));
        assertTrue(values.contains("gpu-02"));
        assertTrue(values.contains("gpu-04"));
    }

    @Test
    void put_updateQuota() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"License-Pool\",\"quota\":10}]}");

        assertEquals(200, response.getStatus());

        ArgumentCaptor<Map<String, String>> params = captureUpdateParams("PROJECT_EXT_2");
        assertEquals("10", params.getValue().get(PARAM_QUOTA));
        verify(project).persist();
    }

    @Test
    void put_batchUpdatesTwoResourcesInOnePersist() throws Exception {
        putRequest("{\"resources\":["
                + "{\"name\":\"GPU-Pool\",\"addValues\":[\"gpu-04\"]},"
                + "{\"name\":\"License-Pool\",\"quota\":8}"
                + "]}");

        assertEquals(200, response.getStatus());
        // Both features updated, but only ONE persist call
        verify(project).updateFeature(eq("PROJECT_EXT_1"), eq(FEATURE_TYPE), any());
        verify(project).updateFeature(eq("PROJECT_EXT_2"), eq(FEATURE_TYPE), any());
        verify(project, times(1)).persist();
    }

    @Test
    void put_addDuplicateValue_isIdempotent() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"addValues\":[\"gpu-01\"]}]}");

        assertEquals(200, response.getStatus());

        ArgumentCaptor<Map<String, String>> params = captureUpdateParams("PROJECT_EXT_1");
        // gpu-01 should appear exactly once
        long count = Arrays.stream(params.getValue().get(PARAM_VALUES).split("\n"))
                .filter("gpu-01"::equals).count();
        assertEquals(1, count);
    }

    @Test
    void put_removeAbsentValue_isNoOp() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"removeValues\":[\"gpu-99\"]}]}");

        assertEquals(200, response.getStatus());

        ArgumentCaptor<Map<String, String>> params = captureUpdateParams("PROJECT_EXT_1");
        assertTrue(params.getValue().get(PARAM_VALUES).contains("gpu-01"));
    }

    // -------------------------------------------------------------------------
    // PUT /resources — error cases
    // -------------------------------------------------------------------------

    @Test
    void put_unknownResource_returns404() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"NoSuchPool\",\"addValues\":[\"x\"]}]}");

        assertEquals(404, response.getStatus());
        assertErrorContains("NoSuchPool");
        verify(project, never()).persist();
    }

    @Test
    void put_setValuesAndAddValuesCombined_returns400() throws Exception {
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"setValues\":[\"a\"],\"addValues\":[\"b\"]}]}");

        assertEquals(400, response.getStatus());
        assertErrorContains("setValues");
        verify(project, never()).persist();
    }

    @Test
    void put_missingName_returns400() throws Exception {
        putRequest("{\"resources\":[{\"addValues\":[\"x\"]}]}");

        assertEquals(400, response.getStatus());
        verify(project, never()).persist();
    }

    @Test
    void put_emptyResourcesArray_returns400() throws Exception {
        putRequest("{\"resources\":[]}");

        assertEquals(400, response.getStatus());
        verify(project, never()).persist();
    }

    @Test
    void put_persistFails_returns500WithRetryAfter() throws Exception {
        doThrow(new jetbrains.buildServer.serverSide.PersistFailedException("disk full"))
                .when(project).persist();

        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"addValues\":[\"gpu-04\"]}]}");

        assertEquals(500, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
        assertErrorContains("persist");
    }

    @Test
    void put_noEditPermission_returns403() throws Exception {
        when(user.isPermissionGrantedForProject(any(), eq(Permission.EDIT_PROJECT))).thenReturn(false);
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"addValues\":[\"x\"]}]}");

        assertEquals(403, response.getStatus());
        verify(project, never()).persist();
    }

    @Test
    void put_lockContention_returns503WithRetryAfter() throws Exception {
        controller.simulateLockContention();
        putRequest("{\"resources\":[{\"name\":\"GPU-Pool\",\"addValues\":[\"gpu-04\"]}]}");

        assertEquals(503, response.getStatus());
        assertNotNull(response.getHeader("Retry-After"));
        verify(project, never()).persist();
    }

    @Test
    void unknownMethod_returns405() throws Exception {
        request.setMethod("DELETE");
        request.addParameter("projectId", "_Root");

        controller.handle(request, response);

        assertEquals(405, response.getStatus());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void putRequest(String body) throws Exception {
        request.setMethod("PUT");
        request.addParameter("projectId", "_Root");
        request.setContentType("application/json");
        request.setContent(body.getBytes());
        controller.handle(request, response);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, String>> captureUpdateParams(String featureId) {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(project).updateFeature(eq(featureId), eq(FEATURE_TYPE), captor.capture());
        return captor;
    }

    private void assertErrorContains(String substring) throws Exception {
        Map<?, ?> body = objectMapper.readValue(response.getContentAsString(), Map.class);
        String error = (String) body.get("error");
        assertNotNull(error, "Expected 'error' field in response body");
        assertTrue(error.contains(substring),
                "Expected error to contain '" + substring + "' but was: " + error);
    }

    // -------------------------------------------------------------------------
    // Test subclass — overrides getAuthenticatedUser to avoid static dependency
    // -------------------------------------------------------------------------

    static class TestableController extends SharedResourcesApiController {
        private final User mockUser;
        private boolean simulateLockContention = false;

        TestableController(ProjectManager projectManager, User mockUser, SharedResourcesSettings settings) {
            super(projectManager, settings);
            this.mockUser = mockUser;
        }

        void simulateLockContention() {
            this.simulateLockContention = true;
        }

        @Override
        protected boolean tryAcquireWriteLock() throws InterruptedException {
            if (simulateLockContention) return false;
            return super.tryAcquireWriteLock();
        }

        @Override
        protected User getAuthenticatedUser(HttpServletRequest req) {
            return mockUser;
        }

        ModelAndView handle(HttpServletRequest req, javax.servlet.http.HttpServletResponse resp)
                throws Exception {
            return doHandle(req, resp);
        }
    }
}
