package com.stacksync.syncservice.test.xmlrpc;

import java.net.URL;

import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcClientConfigImpl;

public class ApiGetMetadata {
	
	public static void main(String[] args) throws Exception {

		XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl();
		config.setEnabledForExtensions(true);
		config.setServerURL(new URL("http://127.0.0.1:" + Constants.XMLRPC_PORT));
		XmlRpcClient client = new XmlRpcClient();
		client.setConfig(config);
		
		String strUserId = "159a1286-33df-4453-bf80-cff4af0d97b0";
		String strItemId = "100";
		String strIncludeList = "true";
		String strIncludeDeleted = "true";
		String strIncludeChunks = "true";
		String strVersion = "null";
		
		Object[] params = new Object[] { strUserId, strItemId, strIncludeList, strIncludeDeleted, strIncludeChunks, strVersion};

		long startTotal = System.currentTimeMillis();
		String strResponse = (String) client.execute("XmlRpcSyncHandler.getMetadata", params);

		System.out.println("Response --> " + Constants.PrettyPrintJson(strResponse));

		long totalTime = System.currentTimeMillis() - startTotal;
		System.out.println("Total level time --> " + totalTime + " ms");
	}
}
