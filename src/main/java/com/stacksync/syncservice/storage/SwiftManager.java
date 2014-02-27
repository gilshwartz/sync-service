package com.stacksync.syncservice.storage;

import java.io.IOException;
import java.util.UUID;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import com.google.gson.Gson;
import com.stacksync.commons.models.User;
import com.stacksync.commons.models.Workspace;
import com.stacksync.syncservice.exceptions.storage.EndpointNotFoundException;
import com.stacksync.syncservice.exceptions.storage.UnauthorizedException;
import com.stacksync.syncservice.exceptions.storage.UnexpectedStatusCodeException;
import com.stacksync.syncservice.storage.swift.LoginResponseObject;
import com.stacksync.syncservice.storage.swift.ServiceObject;
import com.stacksync.syncservice.util.Config;

public class SwiftManager extends StorageManager {
	
	private static StorageManager instance = null;
	
	private String authUrl;
	private String user;
	private String tenant;
	private String password;
	private String storageUrl;
	private String authToken;

	private SwiftManager() {

		this.authUrl = Config.getSwiftAuthUrl();
		this.user = Config.getSwiftUser();
		this.tenant = Config.getSwiftTenant();
		this.password = Config.getSwiftPassword();
	}
	
	public static synchronized StorageManager getInstance(){
		if (instance == null){
			instance = new SwiftManager();
		}
		
		return instance;
	}

	public void login() throws EndpointNotFoundException, UnauthorizedException, UnexpectedStatusCodeException, IOException {

		HttpClient httpClient = new DefaultHttpClient();

		try {
			HttpPost request = new HttpPost(authUrl);

			String body = String
					.format("{\"auth\": {\"passwordCredentials\": {\"username\": \"%s\", \"password\": \"%s\"}, \"tenantName\":\"%s\"}}",
							user, password, tenant);
			StringEntity entity = new StringEntity(body);
			entity.setContentType("application/json");
			request.setEntity(entity);
			HttpResponse response = httpClient.execute(request);
			
			SwiftResponse swiftResponse = new SwiftResponse(response);
			
			if (swiftResponse.getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
				throw new UnauthorizedException("404 User unauthorized");
			}
			
			if (swiftResponse.getStatusCode() < 200 || swiftResponse.getStatusCode() >= 300) {
				throw new UnexpectedStatusCodeException("Unexpected status code: " + swiftResponse.getStatusCode());
			}
			
			String responseBody = swiftResponse.getResponseBodyAsString();
			
			Gson gson = new Gson();
			LoginResponseObject loginResponse = gson.fromJson(responseBody, LoginResponseObject.class);
			
			this.authToken = loginResponse.getAccess().getToken().getId();
			
			Boolean endpointFound = false;
			
			for (ServiceObject service : loginResponse.getAccess().getServiceCatalog()){
				
				if (service.getType().equals("object-store")){
					this.storageUrl = service.getEndpoints().get(0).getPublicURL();
					endpointFound = true;
					break;
				}
			}
			
			if (!endpointFound){
				throw new EndpointNotFoundException();
			}

		} finally {
			httpClient.getConnectionManager().shutdown();
		}
	}

	@Override
	public void createNewWorkspace(User user, Workspace workspace) throws Exception {

		HttpClient httpClient = new DefaultHttpClient();

		String url = workspace.getSwiftUrl() + "/" + workspace.getSwiftContainer();
		
		try {

			HttpPut request = new HttpPut(url);
			request.setHeader(SwiftResponse.X_AUTH_TOKEN, authToken);

			HttpResponse response = httpClient.execute(request);
			
			SwiftResponse swiftResponse = new SwiftResponse(response);
			
			if (swiftResponse.getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
				throw new UnauthorizedException("404 User unauthorized");
			}
			
			if (swiftResponse.getStatusCode() < 200 || swiftResponse.getStatusCode() >= 300) {
				throw new UnexpectedStatusCodeException("Unexpected status code: " + swiftResponse.getStatusCode());
			}

		} finally {
			httpClient.getConnectionManager().shutdown();
		}
	}
	
	@Override
	public void grantUserToWorkspace(User owner, User user, Workspace workspace) throws Exception {

		
		String permissions = getWorkspacePermissions(owner, workspace);
		
		String tenantUser = Config.getSwiftTenant() + ":" + user.getSwiftUser();
		
		if (permissions.contains(tenantUser)){
			return;
		}
		
		permissions += "," + tenantUser;
		
		HttpClient httpClient = new DefaultHttpClient();
		String url = workspace.getSwiftUrl() + "/" + workspace.getSwiftContainer();

		try {

			HttpPut request = new HttpPut(url);
			request.setHeader(SwiftResponse.X_AUTH_TOKEN, authToken);
			request.setHeader(SwiftResponse.X_CONTAINER_READ, permissions);
			request.setHeader(SwiftResponse.X_CONTAINER_WRITE, permissions);

			HttpResponse response = httpClient.execute(request);
			
			SwiftResponse swiftResponse = new SwiftResponse(response);
			
			if (swiftResponse.getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
				throw new UnauthorizedException("404 User unauthorized");
			}
			
			if (swiftResponse.getStatusCode() < 200 || swiftResponse.getStatusCode() >= 300) {
				throw new UnexpectedStatusCodeException("Unexpected status code: " + swiftResponse.getStatusCode());
			}

		} finally {
			httpClient.getConnectionManager().shutdown();
		}
	}
	
	private String getWorkspacePermissions(User user, Workspace workspace) throws Exception {

		HttpClient httpClient = new DefaultHttpClient();

		String url = workspace.getSwiftUrl() + "/" + workspace.getSwiftContainer();
		
		try {

			HttpHead  request = new HttpHead(url);
			request.setHeader(SwiftResponse.X_AUTH_TOKEN, authToken);

			HttpResponse response = httpClient.execute(request);
			
			SwiftResponse swiftResponse = new SwiftResponse(response);
			
			if (swiftResponse.getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
				throw new UnauthorizedException("404 User unauthorized");
			}
			
			if (swiftResponse.getStatusCode() < 200 || swiftResponse.getStatusCode() >= 300) {
				throw new UnexpectedStatusCodeException("Unexpected status code: " + swiftResponse.getStatusCode());
			}
			
			//We suppose there are the same permissions for read and write
			Header containerWriteHeader = swiftResponse.getResponseHeader(SwiftResponse.X_CONTAINER_WRITE);
			
			if (containerWriteHeader == null){
				return "";
			}
			
			return containerWriteHeader.getValue();
			
		} finally {
			httpClient.getConnectionManager().shutdown();
		}
	}
	
	
	
	
}
