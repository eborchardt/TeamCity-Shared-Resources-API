package com.example.teamcity.sharedresources;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Request body for PUT /app/sharedResourcesApi/resources. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchUpdateRequest {

    private List<ResourceUpdate> resources;

    public List<ResourceUpdate> getResources() { return resources; }
    public void setResources(List<ResourceUpdate> resources) { this.resources = resources; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResourceUpdate {
        private String name;

        private List<String> addValues;
        private List<String> removeValues;
        private List<String> setValues;
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
