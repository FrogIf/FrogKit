package sch.frog.kit.server.handle;

import sch.frog.kit.common.LogKit;
import sch.frog.kit.common.util.StringUtil;
import sch.frog.kit.server.RequestBody;
import sch.frog.kit.server.RequestJson;
import sch.frog.kit.server.ResponseJson;
import sch.frog.kit.server.exception.RequestParseException;
import sch.frog.kit.server.handle.annotation.RequestAction;
import sch.frog.kit.server.handle.annotation.RequestParam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebContainer {

    public static final String BODY_MARK = "..";

    private final HashMap<String, RequestActionBox> requestMap = new HashMap<>();

    private final ArrayList<RequestActionBox> requestActions = new ArrayList<>();

    private final HashMap<Class<?>, IRequestConverter> converterMap = new HashMap<>();

    public WebContainer(Map<Class<?>, IRequestConverter> converterMap, List<? extends IWebView> views) {
        this.basicConverterInit();
        this.init(converterMap, views);
    }

    private void basicConverterInit(){
        converterMap.put(Byte.class, Byte::parseByte);
        converterMap.put(byte.class, Byte::parseByte);
        converterMap.put(Short.class, Short::parseShort);
        converterMap.put(short.class, Short::parseShort);
        IRequestConverter charConverter = value -> {
            if(value.length() == 1){
                return value.charAt(0);
            }else{
                throw new RequestParseException("can't convert value to char");
            }
        };
        converterMap.put(Character.class, charConverter);
        converterMap.put(char.class, charConverter);
        converterMap.put(Integer.class, Integer::parseInt);
        converterMap.put(int.class, Integer::parseInt);
        converterMap.put(Long.class, Long::parseLong);
        converterMap.put(long.class, Long::parseLong);
        converterMap.put(Float.class, Float::parseFloat);
        converterMap.put(float.class, Float::parseFloat);
        converterMap.put(Double.class, Double::parseDouble);
        converterMap.put(double.class, Double::parseDouble);
        converterMap.put(Boolean.class, Boolean::parseBoolean);
        converterMap.put(boolean.class, Boolean::parseBoolean);
        converterMap.put(String.class, value -> value);
    }

    private void init(Map<Class<?>, IRequestConverter> converterMap, List<? extends IWebView> views){
        if(converterMap != null){
            this.converterMap.putAll(converterMap);
        }
        for (IWebView view : views) {
            this.readViewInfo(view);
        }
    }

    private String pathFix(String path){
        if(!path.startsWith("/")){
            path = "/" + path;
        }
        return path;
    }

    private void readViewInfo(IWebView view){
        Class<? extends IWebView> clazz = view.getClass();
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            RequestAction anno = method.getAnnotation(RequestAction.class);
            if(anno != null){
                RequestActionBox action = new RequestActionBox();
                action.setMethod(method);
                action.setInstanceObj(view);
                action.setDescription(anno.description());
                String path = anno.path();
                action.setPath(path);
                if(requestMap.get(path) != null){
                    throw new IllegalStateException("path duplicate definition for path : " + path);
                }
                requestMap.put(pathFix(path), action);
                requestActions.add(action);

                Parameter[] parameters = method.getParameters();
                RequestParamInfo[] params = new RequestParamInfo[parameters.length];
                action.setParams(params);
                int i = 0;
                for (Parameter p : parameters) {
                    RequestParam param = p.getAnnotation(RequestParam.class);
                    if(param == null){
                        throw new IllegalStateException("request param undefined, path : " + path + ", param : " + p.getName());
                    }else{
                        RequestParamInfo paramInfo = new RequestParamInfo();
                        String name = param.name();
                        if(StringUtil.isBlank(name)){
                            name = p.getName();
                        }
                        paramInfo.setName(name);
                        paramInfo.setDescription(param.description());
                        paramInfo.setRequired(param.required());
                        paramInfo.setType(p.getType());
                        params[i] = paramInfo;
                    }
                    i++;
                }
            }
        }
    }

    public ResponseJson handle(String path, RequestJson json){
        RequestActionBox requestActionBox = this.requestMap.get(path);
        if(requestActionBox == null){
            return ResponseJson.NOT_FOUND;
        }

        RequestParamInfo[] params = requestActionBox.getParams();
        Object[] paramValueArray = new Object[params.length];
        int i = 0;
        for (RequestParamInfo info : params) {
            String name = info.getName();
            Object val = null;
            if(BODY_MARK.equals(name)){
                val = convert(info.getType(), json.toString());
            }else{
                List<String> namePath = parseParamNamePath(name);
                RequestBody body = json;
                int m = namePath.size() - 1;
                for (String key : namePath) {
                    if(m == 0){ // 到达最末一级
                        String valStr = body.getStringValue(key);
                        Class<?> type = info.getType();
                        if(valStr == null){
                            if(info.isRequired()){
                                return new ResponseJson(ResponseJson.CODE_BAD_REQUEST, name + " is required");
                            }else{
                                if(type.isPrimitive()){
                                    val = getBasicTypeDefaultValue(type);
                                }
                            }
                        }else {
                            val = convert(type, valStr);
                        }
                    }else{
                        body = body.get(key);
                        if(body == null){
                            if(info.isRequired()){
                                return new ResponseJson(ResponseJson.CODE_BAD_REQUEST, name + " is required");
                            }else{
                                Class<?> type = info.getType();
                                if(type.isPrimitive()){
                                    val = getBasicTypeDefaultValue(type);
                                }
                                break;
                            }
                        }
                        m--;
                    }
                }
            }
            paramValueArray[i] = val;
            i++;
        }

        Object instanceObj = requestActionBox.getInstanceObj();
        Method method = requestActionBox.getMethod();
        Object result;
        try {
            result = method.invoke(instanceObj, paramValueArray);
        } catch (InvocationTargetException e){
            Throwable targetException = e.getTargetException();
            if(targetException != null){
                LogKit.error(targetException.getMessage());
            }else{
                LogKit.error("internal error");
            }
            return ResponseJson.ERROR;
        }catch (Exception e) {
            LogKit.error(e.getMessage());
            return ResponseJson.ERROR;
        }


        ResponseJson responseJson = new ResponseJson(ResponseJson.CODE_SUCCESS, "success");
        responseJson.setData(result);
        return responseJson;
    }

    private Object convert(Class<?> type, String valStr){
        try{
            IRequestConverter converter = converterMap.get(type);
            if(converter == null){
                LogKit.error("can't convert value to " + type.getName());
                return ResponseJson.ERROR;
            }
            return converter.parse(valStr);
        }catch (Exception e){
            LogKit.error(e.getMessage());
            return new ResponseJson(ResponseJson.CODE_BAD_REQUEST, e.getMessage());
        }
    }

    private List<String> parseParamNamePath(String name){
        StringBuilder sb = new StringBuilder();
        ArrayList<String> path = new ArrayList<>();
        for(int i = 0, len = name.length(); i < len; i++){
            char ch = name.charAt(i);
            if(ch == '.'){
                path.add(sb.toString());
                sb.delete(0, sb.length());
            }else{
                sb.append(ch);
            }
        }
        if(path.size() == 0){
            path.add(name);
        }
        return path;
    }

    private Object getBasicTypeDefaultValue(Class<?> type){
        if(type == boolean.class){
            return false;
        }else{
            return 0;
        }
    }

    public List<RequestActionBox> getActions(){
        return requestActions;
    }

    public void addAction(RequestActionBox actionBox){
        requestMap.put(pathFix(actionBox.getPath()), actionBox);
        this.requestActions.add(actionBox);
    }

    public interface IRequestConverter{

        Object parse(String request) throws Exception;

    }

}
