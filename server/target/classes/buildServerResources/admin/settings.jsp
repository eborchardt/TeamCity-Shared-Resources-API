<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<jsp:useBean id="settings" type="com.example.teamcity.sharedresources.SharedResourcesSettings" scope="request"/>

<div class="editNotificatorSettingsPage">
  <h2 class="noBorder">Shared Resources API</h2>
  <p>Controls how the plugin handles concurrent write requests to shared resource pools.</p>

  <form method="post" action="${pageContext.request.contextPath}/admin/sharedResourcesApi.html">
    <table class="runnerFormTable">
      <tr>
        <th><label for="lockTimeoutSeconds">Lock acquisition timeout</label></th>
        <td>
          <input type="number" id="lockTimeoutSeconds" name="lockTimeoutSeconds"
                 value="${settings.lockTimeoutSeconds}" min="1" max="300" style="width:70px"/>
          <span class="smallNote">seconds — how long a PUT waits for the write lock before returning 503. Default: 30.</span>
        </td>
      </tr>
      <tr>
        <th><label for="retryAfterLockSeconds">Retry-After (lock contention)</label></th>
        <td>
          <input type="number" id="retryAfterLockSeconds" name="retryAfterLockSeconds"
                 value="${settings.retryAfterLockSeconds}" min="1" max="60" style="width:70px"/>
          <span class="smallNote">seconds — sent in the <code>Retry-After</code> header with a 503. Default: 5.</span>
        </td>
      </tr>
      <tr>
        <th><label for="retryAfterPersistSeconds">Retry-After (persist failure)</label></th>
        <td>
          <input type="number" id="retryAfterPersistSeconds" name="retryAfterPersistSeconds"
                 value="${settings.retryAfterPersistSeconds}" min="1" max="60" style="width:70px"/>
          <span class="smallNote">seconds — sent in the <code>Retry-After</code> header with a 500. Default: 10.</span>
        </td>
      </tr>
    </table>

    <div class="saveButtonsBlock">
      <input class="submitButton" type="submit" value="Save"/>
    </div>
  </form>
</div>
