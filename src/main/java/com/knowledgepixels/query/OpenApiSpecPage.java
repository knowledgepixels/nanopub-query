package com.knowledgepixels.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.knowledgepixels.query.GrlcSpec.InvalidGrlcSpecException;

import io.vertx.core.MultiMap;

/**
 * Page for Open API compliant specification.
 */
public class OpenApiSpecPage {

    private Map<String, Object> dataMap = new LinkedHashMap<>();

    /**
     * Creates a new page instance.
     *
     * @param requestUrl The request URL
     * @param parameters The URL request parameters
     * @throws InvalidGrlcSpecException 
     */
    public OpenApiSpecPage(String requestUrl, MultiMap parameters) throws InvalidGrlcSpecException {
        GrlcSpec grlcSpec = new GrlcSpec(requestUrl, parameters);
        

        dataMap.put("openapi", "3.0.4");

        Map<String, Object> infoMap = new LinkedHashMap<>();
        infoMap.put("title", grlcSpec.getLabel());
        infoMap.put("description", "API definition source: <a target=\"_blank\" href=\"" + grlcSpec.getNanopub().getUri() +
                "\"><svg height=\"0.8em\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 8 8\"><path d=\"M5,8H8L3,0H0M8,4.8V0H5M0,3.2V8H3\"/></svg> " +
                grlcSpec.getArtifactCode().substring(0, 10) + "</a>");
        infoMap.put("version", grlcSpec.getArtifactCode().substring(0, 10));
        dataMap.put("info", infoMap);

        List<Object> serversList = new ArrayList<>();
        Map<String, Object> serverMap = new LinkedHashMap<>();
        serverMap.put("url", Utils.getEnvString("NANOPUB_QUERY_URL", "http://localhost:9393/") + "api/" + grlcSpec.getArtifactCode());
        serverMap.put("description", "This Nanopub Query instance");
        serversList.add(serverMap);
        dataMap.put("servers", serversList);

        Map<String, Object> pathsMap = new LinkedHashMap<>();
        Map<String, Object> rootPathMap = new LinkedHashMap<>();
        Map<String, Object> getOpMap = new LinkedHashMap<>();
        //getOpMap.put("summary", label);
        getOpMap.put("description", grlcSpec.getDescription());
        Map<String, Object> responsesMap = new LinkedHashMap<>();
        Map<String, Object> successrespMap = new LinkedHashMap<>();
        Map<String, Object> contentMap = new LinkedHashMap<>();
        contentMap.put("text/csv", new HashMap<>());
        contentMap.put("application/json", new HashMap<>());
        successrespMap.put("content", contentMap);
        successrespMap.put("description", "result table");
        responsesMap.put("200", successrespMap);
        List<Object> parametersList = new ArrayList<>();

        final Map<String, Object> stringType = new LinkedHashMap<>();
        stringType.put("type", "string");
        final Map<String, Object> stringListType = new LinkedHashMap<>();
        stringListType.put("type", "array");
        stringListType.put("items", stringType);

        for (String p : grlcSpec.getPlaceholdersList()) {
            Map<String, Object> paramMap = new LinkedHashMap<>();
            paramMap.put("in", "query");
            String name = GrlcSpec.getParamName(p);
            paramMap.put("name", name);
            paramMap.put("required", !GrlcSpec.isOptionalPlaceholder(p));
            if (GrlcSpec.isMultiPlaceholder(p)) {
                paramMap.put("style", "form");
                paramMap.put("explde", "true");
                paramMap.put("schema", stringListType);
            } else {
                paramMap.put("schema", stringType);
            }
            parametersList.add(paramMap);
        }
        getOpMap.put("parameters", parametersList);
        getOpMap.put("responses", responsesMap);
        rootPathMap.put("get", getOpMap);
        pathsMap.put("/" + grlcSpec.getQueryName(), rootPathMap);
        dataMap.put("paths", pathsMap);
    }

    /**
     * Returns the Open API spec as a string.
     *
     * @return Open API specification string
     */
    public String getSpec() {
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        return new Yaml(options).dump(dataMap);
    }

}
