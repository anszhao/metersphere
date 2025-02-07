package io.metersphere.api.parser.api;


import io.metersphere.api.dto.converter.ApiDefinitionDetail;
import io.metersphere.api.dto.converter.ApiImportDataAnalysisResult;
import io.metersphere.api.dto.converter.ApiImportFileParseResult;
import io.metersphere.api.dto.definition.ApiDefinitionMockDTO;
import io.metersphere.api.dto.definition.ApiTestCaseDTO;
import io.metersphere.api.dto.request.ImportRequest;
import io.metersphere.api.dto.request.MsCommonElement;
import io.metersphere.api.dto.request.http.MsHTTPConfig;
import io.metersphere.api.dto.request.http.MsHTTPElement;
import io.metersphere.api.dto.request.http.body.*;
import io.metersphere.api.parser.ApiDefinitionImportParser;
import io.metersphere.plugin.api.spi.AbstractMsTestElement;
import io.metersphere.project.dto.environment.auth.NoAuth;
import io.metersphere.sdk.exception.MSException;
import io.metersphere.sdk.util.LogUtils;
import io.metersphere.system.uid.IDGenerator;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class HttpApiDefinitionImportAbstractParser<T> implements ApiDefinitionImportParser<T> {

    @Override
    public ApiImportDataAnalysisResult generateInsertAndUpdateData(ApiImportFileParseResult importParser, List<ApiDefinitionDetail> existenceApiDefinitionList) {
        //        API类型，通过 Method & Path 组合判断，接口是否存在
        Map<String, ApiDefinitionDetail> savedApiDefinitionMap = existenceApiDefinitionList.stream().collect(Collectors.toMap(t -> t.getMethod() + t.getPath(), t -> t, (oldValue, newValue) -> newValue));
        Map<String, ApiDefinitionDetail> importDataMap = importParser.getData().stream().collect(Collectors.toMap(t -> t.getMethod() + t.getPath(), t -> t, (oldValue, newValue) -> newValue));

        ApiImportDataAnalysisResult insertAndUpdateData = new ApiImportDataAnalysisResult();

        importDataMap.forEach((key, api) -> {
            if (savedApiDefinitionMap.containsKey(key)) {
                insertAndUpdateData.addExistenceApi(api, new ArrayList<>() {{
                    this.add(savedApiDefinitionMap.get(key));
                }});
            } else {
                insertAndUpdateData.getInsertApiList().add(api);
            }
            List<ApiTestCaseDTO> caseList = importParser.getCaseMap().get(api.getId());
            if (CollectionUtils.isNotEmpty(caseList)) {
                insertAndUpdateData.getApiIdAndTestCaseMap().put(api.getId(), caseList);
            }
            List<ApiDefinitionMockDTO> mockDTOList = importParser.getMockMap().get(api.getId());
            if (CollectionUtils.isNotEmpty(mockDTOList)) {
                insertAndUpdateData.getApiIdAndMockMap().put(api.getId(), mockDTOList);
            }
        });

        return insertAndUpdateData;
    }

    protected String getApiTestStr(InputStream source) {
        StringBuilder testStr = null;
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            testStr = new StringBuilder();
            String inputStr;
            while ((inputStr = bufferedReader.readLine()) != null) {
                testStr.append(inputStr);
            }
            source.close();
        } catch (Exception e) {
            LogUtils.error(e.getMessage(), e);
            throw new MSException(e.getMessage());
        }
        return StringUtils.isNotBlank(testStr) ? testStr.toString() : StringUtils.EMPTY;
    }


    protected ApiDefinitionDetail buildApiDefinition(String name, String path, String method, String modulePath, ImportRequest importRequest) {
        ApiDefinitionDetail apiDefinition = new ApiDefinitionDetail();
        apiDefinition.setId(IDGenerator.nextStr());
        if (name != null) {
            apiDefinition.setName(StringUtils.trim(name));
            if (apiDefinition.getName().length() > 255) {
                apiDefinition.setName(apiDefinition.getName().substring(0, 250) + "...");
            }
        }
        apiDefinition.setPath(formatPath(StringUtils.trim(path)));
        apiDefinition.setProtocol(StringUtils.trim(importRequest.getProtocol()));
        apiDefinition.setMethod(StringUtils.trim(method));
        apiDefinition.setProjectId(StringUtils.trim(importRequest.getProjectId()));
        apiDefinition.setModulePath(StringUtils.trim(modulePath));
        apiDefinition.setResponse(new ArrayList<>());
        return apiDefinition;
    }

    protected MsHTTPElement buildHttpRequest(String name, String path, String method) {
        MsHTTPElement request = new MsHTTPElement();
        request.setName(name);
        // 路径去掉域名/IP 地址，保留方法名称及参数
        request.setPath(formatPath(path));
        request.setMethod(method);
        request.setHeaders(new ArrayList<>());
        request.setQuery(new ArrayList<>());
        request.setRest(new ArrayList<>());
        request.setBody(new Body());
        MsHTTPConfig httpConfig = new MsHTTPConfig();
        httpConfig.setConnectTimeout(60000L);
        httpConfig.setResponseTimeout(60000L);
        request.setOtherConfig(httpConfig);
        request.setAuthConfig(new NoAuth());
        //        assertionConfig
        Body body = new Body();
        body.setBinaryBody(new BinaryBody());
        body.setFormDataBody(new FormDataBody());
        body.setXmlBody(new XmlBody());
        body.setRawBody(new RawBody());
        body.setNoneBody(new NoneBody());
        body.setJsonBody(new JsonBody());
        body.setWwwFormBody(new WWWFormBody());
        body.setNoneBody(new NoneBody());
        body.setBodyType(Body.BodyType.NONE.name());
        request.setBody(body);

        MsCommonElement commonElement = new MsCommonElement();
        LinkedList<AbstractMsTestElement> children = new LinkedList<>();
        children.add(commonElement);
        request.setChildren(children);

        return request;
    }

    protected String formatPath(String url) {
        try {
            URI urlObject = new URI(url);
            return StringUtils.isBlank(urlObject.getPath()) ? url : urlObject.getPath();
        } catch (Exception ex) {
            //只需要返回？前的路径
            return url.split("\\?")[0];
        }
    }
}
