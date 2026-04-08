# Shared Resources API — TeamCity Plugin

A TeamCity server plugin that provides an atomic REST API for managing shared resource pools. Designed to solve data-loss and "failed to persist" errors caused by concurrent writes when multiple scripts call the standard REST API in rapid succession.

## Problem

When Versioned Settings are enabled, TeamCity periodically syncs project configuration from the VCS — applying changes committed to `project-config.xml` (or its Kotlin DSL equivalent) back to the server. If a REST API call writes to a project's shared resources at the same moment TeamCity is applying a Versioned Settings sync, both operations attempt to write the same `project-config.xml` simultaneously. The result is either a `TeamCity failed to persist settings on disk` error or silently lost changes: whichever write lands second overwrites the first without merging it.

This is most visible in pipelines that dynamically register or deregister resources (e.g. adding a freshly provisioned GPU node to a pool), where the REST call races against the background sync triggered by an unrelated commit to the settings repository.

## Solution

This plugin exposes two endpoints under `/app/sharedResourcesApi/`:

- **GET** lists all shared resources owned by a project.
- **PUT** applies one or more changes to one or more resources in a single, atomic disk write — one `persist()` call regardless of how many pools or values are modified.

A server-side `ReentrantLock` serialises concurrent PUT requests so they queue up rather than race.

## Installation

1. Build the plugin zip:
   ```
   mvn package
   ```
   Output: `server/target/shared-resources-api.zip`

2. Install via the TeamCity Administration UI:
   - Go to **Administration → Plugins**
   - Upload `shared-resources-api.zip`
   - Restart TeamCity when prompted

Requires TeamCity 2024.12 or later.

## Authentication

Use the same Bearer token you use for `/app/rest`:

```
Authorization: Bearer <token>
```

The token must belong to a user with **VIEW_PROJECT** permission for GET requests and **EDIT_PROJECT** permission for PUT requests on the target project.

## API Reference

### GET /app/sharedResourcesApi/resources

Returns all shared resources owned by the specified project.

**Query parameters**

| Parameter   | Required | Description                        |
|-------------|----------|------------------------------------|
| `projectId` | yes      | External project ID (e.g. `_Root`) |

**Example**

```
GET /app/sharedResourcesApi/resources?projectId=MyProject
```

```json
{
  "resources": [
    {
      "featureId": "PROJECT_EXT_1",
      "name": "GPU-Pool",
      "type": "custom",
      "values": ["gpu-01", "gpu-02", "gpu-03"]
    },
    {
      "featureId": "PROJECT_EXT_2",
      "name": "License-Pool",
      "type": "quoted",
      "quota": "5"
    }
  ]
}
```

**Status codes**

| Code | Meaning                               |
|------|---------------------------------------|
| 200  | Success                               |
| 400  | Missing `projectId` parameter         |
| 401  | No authenticated user                 |
| 403  | User lacks VIEW_PROJECT permission    |
| 404  | Project not found                     |

---

### PUT /app/sharedResourcesApi/resources

Applies one or more updates to one or more resources in a single write. All updates in the request are validated before any change is applied, and a single `persist()` call flushes the result to disk.

**Query parameters**

| Parameter   | Required | Description                        |
|-------------|----------|------------------------------------|
| `projectId` | yes      | External project ID (e.g. `_Root`) |

**Request body**

```json
{
  "resources": [
    {
      "name": "GPU-Pool",
      "addValues": ["gpu-04", "gpu-05"],
      "removeValues": ["gpu-01"]
    },
    {
      "name": "License-Pool",
      "quota": 10
    }
  ]
}
```

**Resource update fields**

| Field           | Applies to    | Description                                                                 |
|-----------------|---------------|-----------------------------------------------------------------------------|
| `name`          | all           | **Required.** Name of the resource to update (must already exist).          |
| `addValues`     | custom type   | Values to add. Duplicates are ignored; order is preserved.                  |
| `removeValues`  | custom type   | Values to remove. No-op if a value is already absent.                       |
| `setValues`     | custom type   | Replaces the entire value list. Cannot be combined with `addValues`/`removeValues`. |
| `quota`         | quoted type   | Sets the maximum concurrent uses.                                           |

**Success response**

```json
{
  "status": "ok",
  "updatedResources": ["GPU-Pool", "License-Pool"]
}
```

**Error responses**

All errors return JSON with an `error` field:

```json
{ "error": "Shared resource not found: 'NoSuchPool' in project 'MyProject'" }
```

**Status codes**

| Code | Meaning                                                             |
|------|---------------------------------------------------------------------|
| 200  | All updates applied and persisted                                   |
| 400  | Invalid request (missing name, empty resources array, conflicting fields) |
| 401  | No authenticated user                                               |
| 403  | User lacks EDIT_PROJECT permission                                  |
| 404  | Project or named resource not found                                 |
| 500  | TeamCity could not persist the change (see `Retry-After` header)   |
| 503  | Server busy — another update is in progress (see `Retry-After` header) |

On 500 and 503 responses, a `Retry-After` header indicates how many seconds to wait before retrying.

---

## Usage Examples

### Add an agent to a pool

```bash
curl -s -X PUT \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"resources":[{"name":"GPU-Pool","addValues":["gpu-07"]}]}' \
  "http://teamcity/app/sharedResourcesApi/resources?projectId=_Root"
```

### Remove an agent from a pool

```bash
curl -s -X PUT \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"resources":[{"name":"GPU-Pool","removeValues":["gpu-07"]}]}' \
  "http://teamcity/app/sharedResourcesApi/resources?projectId=_Root"
```

### Full sync — replace a pool's entire value list

```bash
curl -s -X PUT \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"resources":[{"name":"GPU-Pool","setValues":["gpu-01","gpu-02","gpu-03"]}]}' \
  "http://teamcity/app/sharedResourcesApi/resources?projectId=_Root"
```

### Batch update — two resources in one call

```bash
curl -s -X PUT \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "resources": [
      {"name": "GPU-Pool", "addValues": ["gpu-08"], "removeValues": ["gpu-02"]},
      {"name": "License-Pool", "quota": 8}
    ]
  }' \
  "http://teamcity/app/sharedResourcesApi/resources?projectId=_Root"
```

### Retry on 500/503

```bash
while true; do
  response=$(curl -s -w "\n%{http_code}" -X PUT \
    -H "Authorization: Bearer <token>" \
    -H "Content-Type: application/json" \
    -d '{"resources":[{"name":"GPU-Pool","addValues":["gpu-09"]}]}' \
    "http://teamcity/app/sharedResourcesApi/resources?projectId=_Root")
  
  code=$(echo "$response" | tail -1)
  if [ "$code" = "200" ]; then break; fi
  echo "Got $code, retrying in 5s..."
  sleep 5
done
```

---

## Admin Settings

After installation, a **Shared Resources API** tab appears under **Administration → Server Administration**. Three settings control retry behaviour:

| Setting                         | Default | Description                                                  |
|---------------------------------|---------|--------------------------------------------------------------|
| Lock acquisition timeout (s)    | 30      | How long a PUT waits for the write lock before returning 503 |
| Retry-After for lock contention | 5       | Value of the `Retry-After` header on a 503 response          |
| Retry-After for persist failure | 10      | Value of the `Retry-After` header on a 500 response          |

---

## Testing Error Handling

`lock-file.py` is included in this repository to simulate the Versioned Settings race condition locally. It holds an exclusive OS-level lock on a file until you press Enter, blocking any process — including TeamCity — from writing to it.

**Usage:**

```bash
python lock-file.py <path-to-file>
```

**To reproduce the 500 / persist-failure path:**

1. Find the project config file on the TeamCity server, e.g.:
   ```
   C:\ProgramData\JetBrains\TeamCity\config\projects\<ProjectId>\project-config.xml
   ```
2. Lock it in one terminal:
   ```bash
   python lock-file.py "C:\ProgramData\JetBrains\TeamCity\config\projects\SharedResources\project-config.xml"
   ```
3. In another terminal, send a PUT request. The plugin will attempt to call `persist()`, fail, and return a 500 with a `Retry-After` header.
4. Press Enter in the first terminal to release the lock. The next PUT will succeed.

**Expected behaviour while locked:**

- **GET** returns 200 — reads come from TeamCity's in-memory model, not the file.
- **PUT** returns 500 with `Retry-After: 10` (or whatever is configured in Admin Settings).

> **Note:** Locking the file externally leaves TeamCity's in-memory model and on-disk state temporarily out of sync — any `updateFeature()` calls already applied in memory will be lost on server restart if `persist()` never succeeds. This matches the real Versioned Settings failure mode. Always release the lock before restarting.

---

## Building from Source

**Prerequisites:** JDK 11+, Maven 3.6+

```bash
mvn package
# zip is at: server/target/shared-resources-api.zip
```

**Run tests only:**

```bash
mvn test
```

The tests use `MockHttpServletRequest`/`MockHttpServletResponse` and run without a TeamCity server.
