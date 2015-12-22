package org.nuxeo.java.client.api.objects;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Strings;
import org.nuxeo.java.client.api.NuxeoClient;
import org.nuxeo.java.client.internals.spi.NuxeoClientException;

import retrofit.Call;
import retrofit.Response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.squareup.okhttp.Request;

/**
 * @since 1.0
 */
public abstract class NuxeoObject {

    private static final Logger logger = LogManager.getLogger(NuxeoObject.class);

    public static final String CREATE_RAW_CALL = "createRawCall";

    public static final String ORIGINAL_REQUEST = "originalRequest";

    public static final String MD_5 = "MD5";

    @JsonProperty("entity-type")
    protected final String entityType;

    @JsonProperty("repository")
    protected String repositoryName;

    @JsonIgnore
    protected boolean refreshCache = false;

    @JsonIgnore
    protected NuxeoClient client;

    /**
     * For Serialization purpose.
     */
    public NuxeoObject(String entityType) {
        this.entityType = entityType;
    }

    /**
     * The constructor to use.
     */
    public NuxeoObject(String entityType, NuxeoClient client) {
        this.entityType = entityType;
        this.client = client;
    }

    public String getEntityType() {
        return entityType;
    }

    /**
     * Handle cache and invocation of API methods.
     *
     * @return the response as business objects.
     */
    protected Object getResponse(Object api, Object... parametersArray) {
        if (client == null) {
            throw new NuxeoClientException("You should pass to your Nuxeo object the client instance");
        }
        String method = getCurrentMethodName();
        Call<?> methodResult = getCall(api, method, parametersArray);
        String cacheKey = Strings.EMPTY;
        if (client.isCacheEnabled()) {
            if (refreshCache) {
                this.refreshCache = false;
                client.getNuxeoCache().invalidateAll();
            } else {
                cacheKey = computeCacheKey(methodResult);
                Object result = client.getNuxeoCache().getBody(cacheKey);
                if (result != null) {
                    return result;
                }
            }
        }
        try {
            //TODO JAVACLIENT-28
            client.getNuxeoQueue().add(methodResult);
            Response<?> response = methodResult.execute();
            if (!response.isSuccess()) {
                client.getNuxeoQueue().remove(methodResult);
                NuxeoClientException nuxeoClientException;
                String errorBody = response.errorBody().string();
                if (errorBody.equals(Strings.EMPTY)) {
                    nuxeoClientException = new NuxeoClientException(response.code(), response.message());
                } else {
                    nuxeoClientException = (NuxeoClientException) client.getConverterFactory().readJSON(errorBody,
                            NuxeoClientException.class);
                }
                throw nuxeoClientException;
            }
            if (client.isCacheEnabled()) {
                client.getNuxeoCache().put(cacheKey, response);
            }
            client.getNuxeoQueue().remove(methodResult);
            return response.body();
        } catch (IOException reason) {
            throw new NuxeoClientException(reason);
        }
    }

    /**
     * Compute the cache key with request
     */
    protected String computeCacheKey(Call<?> methodResult) {
        com.squareup.okhttp.Call rawCall;
        Request originalRequest;
        try {
            // TODO JAVACLIENT-26
            Method rawCallMethod = methodResult.getClass().getDeclaredMethod(CREATE_RAW_CALL);
            rawCallMethod.setAccessible(true);
            rawCall = (com.squareup.okhttp.Call) rawCallMethod.invoke(methodResult);
            Field originalRequestField = rawCall.getClass().getDeclaredField(ORIGINAL_REQUEST);
            originalRequestField.setAccessible(true);
            originalRequest = (Request) originalRequestField.get(rawCall);
            logger.debug("Request:" + originalRequest.toString());
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException reason) {
            throw new NuxeoClientException(reason);
        } catch (InvocationTargetException reason) {
            throw new NuxeoClientException(reason.getTargetException().getMessage(), reason);
        }
        StringBuffer sb = new StringBuffer();
        sb.append(originalRequest.toString());
        sb.append(originalRequest.headers().toString());

        MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance(MD_5);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        digest.update(sb.toString().getBytes());
        byte messageDigest[] = digest.digest();
        StringBuffer hexString = new StringBuffer();
        for (int i = 0; i < messageDigest.length; i++)
            hexString.append(Integer.toHexString(0xFF & messageDigest[i]));
        return hexString.toString();
    }

    /**
     * Invoking the method of each class "API"
     */
    protected Call<?> getCall(Object api, String methodName, Object... parametersArray) {
        try {
            Method[] methods = api.getClass().getInterfaces()[0].getMethods();
            List<Object> parameters = new ArrayList<>(Arrays.asList(parametersArray));
            if (repositoryName != null)
                parameters.add(repositoryName);
            parametersArray = parameters.toArray();
            Method method = null;
            for (Method currentMethod : methods) {
                if (currentMethod.getName().equals(methodName)) {
                    if (currentMethod.getParameterTypes().length == parametersArray.length) {
                        method = currentMethod;
                        break;
                    }
                }
            }
            return (Call<?>) method.invoke(api, parametersArray);
        } catch (IllegalArgumentException | IllegalAccessException | InvocationTargetException reason) {
            throw new NuxeoClientException(reason);
        }
    }

    protected String getCurrentMethodName() {
        StackTraceElement stackTraceElements[] = (new Throwable()).getStackTrace();
        return stackTraceElements[2].getMethodName();
    }

    public String getRepositoryName() {
        return repositoryName;
    }

}
