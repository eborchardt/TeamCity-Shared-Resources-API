package com.example.teamcity.sharedresources;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Request body for PUT /app/sharedResourcesApi/resources
 *
 * Example — mixed update (add/remove values on one resource, set quota on another):
 * {
 *   "resources": [
 *     {
 *       "name": "GPU-Pool",
 *       "addValues": ["gpu-05", "gpu-06"],
 *       "removeValues": ["gpu-01"]
 *     },
 *     {
 *       "name": "Build-License-Pool",
 *       "setValues": ["lic-01", "lic-02", "lic-03", "lic-04"]
 *     },
 *     {
 *       "name": "Concurrent-Builds",
 *       "quota": 8
 *     }
 *   ]
 * }
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchUpdateRequest {

    private List<ResourceUpdate> resources;

    public List<ResourceUpdate> getResources() { return resources; }
    public void setResources(List<ResourceUpdate> resources) { this.resources = resources; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceUpdate {
        /** Name of the shared resource to update (must already exist). */
        private String name;

        // --- Custom/values-type resources ---

        /** Add these values to the pool (order-preserving, duplicates ignored). */
        private List<String> addValues;

        /** Remove these values from the pool (no-op if already absent). */
        private List<String> removeValues;

        /**
         * Replace the entire value list. Cannot be combined with addValues/removeValues.
         * Useful for a full sync from an external source of truth.
         */
        private List<String> setValues;

        // --- Quoted-type resources ---

        /** Set the maximum concurrent uses (quota). Ignored for custom-type resources. */
        private Integer quota;

        public String  getName()         { return name; }
        public void    setName(String v) { name = v; }

        public List<String> getAddValues()         { return addValues; }
        public void         setAddValues(List<String> v) { addValues = v; }

        public List<String> getRemoveValues()         { return removeValues; }
        public void         setRemoveValues(List<String> v) { removeValues = v; }

        public List<String> getSetValues()         { return setValues; }
        public void         setSetValues(List<String> v) { setValues = v; }

        public Integer getQuota()         { return quota; }
        public void    setQuota(Integer v) { quota = v; }
    }
}
