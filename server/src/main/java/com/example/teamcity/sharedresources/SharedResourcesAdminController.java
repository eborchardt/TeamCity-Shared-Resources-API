package com.example.teamcity.sharedresources;

import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.controllers.admin.AdminPage;
import jetbrains.buildServer.web.openapi.Groupable;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

/**
 * Handles form POSTs from the admin settings page and registers the admin tab.
 *
 * The AdminPage tab is created inside initApplicationContext() so that PagePlaces
 * and PluginDescriptor can be looked up by type — avoiding any dependency on their
 * bean names (which differ across TC versions).
 */
public class SharedResourcesAdminController extends BaseController {

    private final SharedResourcesSettings settings;

    public SharedResourcesAdminController(@NotNull SharedResourcesSettings settings) {
        this.settings = settings;
        setSupportedMethods("GET", "POST");
    }

    @Override
    protected void initApplicationContext() throws BeansException {
        super.initApplicationContext();
        ApplicationContext ctx = getApplicationContext();

        // Register this controller for form POST
        WebControllerManager wcm;
        try {
            wcm = ctx.getBean(WebControllerManager.class);
        } catch (Exception e) {
            wcm = ctx.getParent().getBean(WebControllerManager.class);
        }
        wcm.registerController("/admin/sharedResourcesApi.html", this);

        // Look up what AdminPage needs by type rather than by bean name
        PagePlaces pagePlaces;
        try {
            pagePlaces = ctx.getBean(PagePlaces.class);
        } catch (Exception e) {
            pagePlaces = ctx.getParent().getBean(PagePlaces.class);
        }

        PluginDescriptor descriptor;
        try {
            descriptor = ctx.getBean(PluginDescriptor.class);
        } catch (Exception e) {
            descriptor = ctx.getParent().getBean(PluginDescriptor.class);
        }

        new SettingsTab(pagePlaces, descriptor);
    }

    @Override
    protected ModelAndView doHandle(@NotNull HttpServletRequest request,
                                    @NotNull HttpServletResponse response) throws Exception {
        if ("POST".equals(request.getMethod())) {
            int lockTimeout      = parsePositive(request.getParameter("lockTimeoutSeconds"),      SharedResourcesSettings.DEFAULT_LOCK_TIMEOUT);
            int retryLock        = parsePositive(request.getParameter("retryAfterLockSeconds"),   SharedResourcesSettings.DEFAULT_RETRY_LOCK);
            int retryPersist     = parsePositive(request.getParameter("retryAfterPersistSeconds"),SharedResourcesSettings.DEFAULT_RETRY_PERSIST);
            int persistAttempts  = parsePositive(request.getParameter("persistMaxAttempts"),      SharedResourcesSettings.DEFAULT_PERSIST_ATTEMPTS);
            int persistDelayMs   = parsePositive(request.getParameter("persistRetryDelayMs"),     SharedResourcesSettings.DEFAULT_PERSIST_DELAY_MS);
            settings.update(lockTimeout, retryLock, retryPersist, persistAttempts, persistDelayMs);
        }
        response.sendRedirect(request.getContextPath() + "/admin/admin.html?item=sharedResourcesApi");
        return null;
    }

    private static int parsePositive(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            int v = Integer.parseInt(value.trim());
            return v > 0 ? v : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // -------------------------------------------------------------------------
    // Admin tab — created as a non-Spring object so we can supply PagePlaces
    // and PluginDescriptor by type rather than by bean name.
    // -------------------------------------------------------------------------

    private class SettingsTab extends AdminPage {

        SettingsTab(@NotNull PagePlaces pagePlaces, @NotNull PluginDescriptor descriptor) {
            super(pagePlaces);
            setPluginName("sharedResourcesApi");
            setIncludeUrl(descriptor.getPluginResourcesPath("admin/settings.jsp"));
            setTabTitle("Shared Resources API");
            register();
        }

        @Override
        public String getGroup() {
            return Groupable.SERVER_RELATED_GROUP;
        }

        @Override
        public void fillModel(@NotNull Map<String, Object> model, @NotNull HttpServletRequest request) {
            model.put("settings", settings);
        }
    }
}
