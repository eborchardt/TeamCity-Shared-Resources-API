package com.example.teamcity.sharedresources;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SharedResourcesAdminControllerTest {

    @Test
    void post_saveSuccess_redirectsToAdminPage() throws Exception {
        SharedResourcesSettings settings = mock(SharedResourcesSettings.class);
        TestableAdminController controller = new TestableAdminController(settings);
        MockHttpServletRequest request = buildPostRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handle(request, response);

        assertEquals("/tc/admin/admin.html?item=sharedResourcesApi", response.getRedirectedUrl());
        verify(settings).update(45, 7, 12, 4, 1500);
    }

    @Test
    void post_saveFailure_redirectsWithErrorMessage() throws Exception {
        SharedResourcesSettings settings = mock(SharedResourcesSettings.class);
        doThrow(new SharedResourcesSettings.SettingsPersistenceException("Could not save shared-resources-api settings",
                new java.io.IOException("disk full")))
                .when(settings).update(45, 7, 12, 4, 1500);

        TestableAdminController controller = new TestableAdminController(settings);
        MockHttpServletRequest request = buildPostRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.handle(request, response);

        assertTrue(response.getRedirectedUrl().startsWith("/tc/admin/admin.html?item=sharedResourcesApi&saveError="));
        assertTrue(response.getRedirectedUrl().contains("Could+not+save+shared-resources-api+settings"));
    }

    private MockHttpServletRequest buildPostRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setContextPath("/tc");
        request.addParameter("lockTimeoutSeconds", "45");
        request.addParameter("retryAfterLockSeconds", "7");
        request.addParameter("retryAfterPersistSeconds", "12");
        request.addParameter("persistMaxAttempts", "4");
        request.addParameter("persistRetryDelayMs", "1500");
        return request;
    }

    static class TestableAdminController extends SharedResourcesAdminController {
        TestableAdminController(SharedResourcesSettings settings) {
            super(settings);
        }

        ModelAndView handle(HttpServletRequest req, javax.servlet.http.HttpServletResponse resp)
                throws Exception {
            return doHandle(req, resp);
        }
    }
}
