/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements.  See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership.  The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.
 */

package org.noerp.webapp.event;

import java.io.Writer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.noerp.base.lang.JSON;
import org.noerp.base.util.Base64;
import org.noerp.base.util.Debug;
import org.noerp.base.util.UtilGenerics;
import org.noerp.entity.Delegator;
import org.noerp.entity.DelegatorFactory;
import org.noerp.service.GenericServiceException;
import org.noerp.service.LocalDispatcher;
import org.noerp.service.ModelService;
import org.noerp.service.ServiceContainer;
import org.noerp.service.ServiceUtil;
import org.noerp.webapp.control.ConfigXMLReader;
import org.noerp.webapp.control.ConfigXMLReader.Event;
import org.noerp.webapp.control.ConfigXMLReader.RequestMap;

/**
 * JsonRpcEventHandler
 */
public class JsonRpcEventHandler implements EventHandler {

	public static final String module = JsonRpcEventHandler.class.getName();
	protected Delegator delegator;
	protected LocalDispatcher dispatcher;

	/**
	 * 初始化
	 */
	public void init(ServletContext context) throws EventHandlerException {
		String delegatorName = context.getInitParameter("entityDelegatorName");
		this.delegator = DelegatorFactory.getDelegator(delegatorName);
		this.dispatcher = ServiceContainer.getLocalDispatcher(delegator.getDelegatorName(), delegator);
	}

	/**
	 * @see org.noerp.webapp.event.EventHandler#invoke(ConfigXMLReader.Event,
	 *      ConfigXMLReader.RequestMap, javax.servlet.http.HttpServletRequest,
	 *      javax.servlet.http.HttpServletResponse)
	 */
	public String invoke(Event event, RequestMap requestMap, HttpServletRequest request, HttpServletResponse response)
			throws EventHandlerException {

		ServiceRpcHandler rpcHandler = new ServiceRpcHandler(request);
		Map<String, Object> result = rpcHandler.getResult();
		
		Map<String, Object> data;
		try {
			data = rpcHandler.execute();
			result.put("result", data);
		} catch (ServiceRpcError e) {
			result.put("error", e.getMap());
		}

		response.setContentType("application/json");
		
		try {
			Writer out = response.getWriter();
			out.write(JSON.from(result).toString());
			out.flush();
        } catch (Exception e) {
            throw new EventHandlerException(e.getMessage(), e);
        }
		
		return null;
	}

	/**
	 * 
	 * @author Kevin
	 *
	 */
	class ServiceRpcHandler {

		private String version = "2.0";
		private String method;
		private Object id;
		private Map<String, Object> params;
		
		private HttpServletRequest request;
		private Map<String, Object> result = new HashMap<String, Object>();

		/**
		 * 
		 * @param request
		 */
		ServiceRpcHandler(HttpServletRequest request) {
			init(request);
			
			if(version != null){
				result.put("jsonrpc", version);
			}
			
			if(id != null){
				result.put("id", id);
			}
		}

		/**
		 * 初始化
		 * 
		 * @param request
		 */
		public void init(HttpServletRequest request) {

			this.request = request;
			
			String jsonrpc = (String) request.getAttribute("jsonrpc");
			if (jsonrpc != null) {
				this.version = jsonrpc;
			}
						
			id = request.getAttribute("id");
			
			method = (String) request.getAttribute("method");
			params = UtilGenerics.<Map<String, Object>>cast(request.getAttribute("params"));
		}
		
		/**
		 * 获得结果
		 * 
		 * @return
		 */
		public Map<String, Object> getResult(){
			return result;
		}
		
		/**
		 * 执行具体的方法
		 * 
		 * @return
		 * @throws ServiceRpcError
		 */
		public Map<String, Object> execute() throws ServiceRpcError{
			
			if(method == null){
				throw new ServiceRpcError(-32601, "Procedure not found.");
			}
			
			ModelService model = null;
            try {
                model = dispatcher.getDispatchContext().getModelService(method);
            } catch (GenericServiceException e) {
                Debug.logWarning(e, module);
            }
            
            if (model == null || !model.export) {
            	throw new ServiceRpcError(-32601, "Procedure not found.");
            }
            
            Map<String, Object> context = getAuthorization();
            context.put("locale", this.request.getLocale());
            
            if(params != null){
            	context.putAll(params);
            }
            
            if (context.get("locale") == null) {
                context.put("locale", Locale.getDefault());
            }
            
            Map<String, Object> finalContext = model.makeValid(context, ModelService.IN_PARAM);
            
            Map<String, Object> resp;
            try {
                resp = dispatcher.runSync(method, finalContext);
            } catch (GenericServiceException e) {
                throw new ServiceRpcError(500, e.getMessage());
            }
            
            if (ServiceUtil.isError(resp)) {
                Debug.logError(ServiceUtil.getErrorMessage(resp), module);
                throw new ServiceRpcError(500, ServiceUtil.getErrorMessage(resp));
            }

            // return only definied parameters
            return model.makeValid(resp, ModelService.OUT_PARAM, false, null);
		}
		
		/**
		 * 获得Basic authorization
		 * 
		 * @return
		 */
		private Map<String, Object> getAuthorization() {
			
			Map<String, Object> userPass = new HashMap<String, Object>();
			String credentials = request.getHeader("Authorization");
			
            if (credentials != null && credentials.startsWith("Basic ")) {
            	
                credentials = Base64.base64Decode(credentials.replace("Basic ", ""));

                String[] parts = credentials.split(":");
                if (parts.length < 2) {
                    return null;
                }
                
                userPass.put("login.username", parts[0]);
                userPass.put("login.password", parts[1]);
            }
            
            return userPass;
        }
	}

	/**
	 * 
	 * @author Kevin
	 *
	 */
	class ServiceRpcError extends Exception {
		
		private static final long serialVersionUID = 8715480445655815910L;
		private int code;
		
		/**
		 * 
		 * @param code
		 * @param message
		 */
		ServiceRpcError(int code, String message){
			super(message);
			this.code = code;
		}
		
		/**
		 * 
		 * @return
		 */
		public Map<String, Object> getMap(){
			Map<String, Object> map = new HashMap<String, Object>();
			map.put("code", code);
			map.put("message", getMessage());
			
			return map;
		}
	}
}
