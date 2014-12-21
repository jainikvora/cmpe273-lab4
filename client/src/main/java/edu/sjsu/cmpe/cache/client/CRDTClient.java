package edu.sjsu.cmpe.cache.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;


import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.JsonNode;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.async.Callback;
import com.mashape.unirest.http.exceptions.UnirestException;

/**
 * Created by Jainik on 12/20/2014.
 */
public class CRDTClient {
    private List<DistributedCacheService> serviceList;

    public CRDTClient() {
        serviceList = new ArrayList<DistributedCacheService>();
    }

    public void addCacheServer(String serverAddress) {
        serviceList.add(new DistributedCacheService(serverAddress));
    }
    
    /********  Asynchronously put values in the server   *********/

    public boolean put(long key, String value) {
        final AtomicInteger successPutCount = new AtomicInteger(0);
        final List<DistributedCacheService> successServerList = new ArrayList<DistributedCacheService>();
        final AtomicInteger callbacks = new AtomicInteger(serviceList.size());

        for(final DistributedCacheService server : serviceList) {
            Future<HttpResponse<JsonNode>> future = Unirest.put(server.getCacheServerUrl()+ "/cache/{key}/{value}")
                    .header("accept", "application/json")
                    .routeParam("key", Long.toString(key))
                    .routeParam("value", value)
                    .asJsonAsync(new Callback<JsonNode>() {

                        public void failed(UnirestException e) {
                            System.out.println("The write to "+ server.getCacheServerUrl() + " has failed");
                            callbacks.decrementAndGet();
                        }

                        public void completed(HttpResponse<JsonNode> response) {
                            successServerList.add(server);
                            System.out.println("The write to "+ server.getCacheServerUrl() + " has successful");
                            successPutCount.incrementAndGet();
                            callbacks.decrementAndGet();
                        }

                        public void cancelled() {
                            System.out.println("The write to "+ server.getCacheServerUrl() + "  has been cancelled");
                            callbacks.decrementAndGet();
                        }

                    });
        }
        
        while(true == true) {
            //System.out.println("Waiting for ");
            if(callbacks.get() == 0)
                break;
        }
        
        System.out.println("Success Put Count:" + successPutCount.get());

        if(successPutCount.get() > (serviceList.size()/2)) {
        	System.out.println("Put successeded for majority nodes");
            return true;
        } else {
        	System.out.println("Put failed for majority nodes");
            for (DistributedCacheService server : successServerList) {
            	server.delete(key);
            	System.out.println("Deleted value from node: "+ server.getCacheServerUrl());
            }
            return false;
        }
    }
    
    /********  Read and repair values in the server   *********/
    
    public String get(long key) {
        final HashMap<DistributedCacheService, String> readResults = new HashMap<DistributedCacheService, String>();
        final AtomicInteger callbacks = new AtomicInteger(serviceList.size());

        for(final DistributedCacheService server : serviceList) {
        	System.out.println("Reading from:" + server.getCacheServerUrl());
            Future<HttpResponse<JsonNode>> future = Unirest.get(server.getCacheServerUrl()+ "/cache/{key}")
                    .header("accept", "application/json")
                    .routeParam("key", Long.toString(key))
                    .asJsonAsync(new Callback<JsonNode>() {

                        public void failed(UnirestException e) {
                            System.out.println("The read from "+ server.getCacheServerUrl() + " has failed");
                            callbacks.decrementAndGet();
                        }

                        public void completed(HttpResponse<JsonNode> response) {
                        	if(response.getCode() == 200)
                        	readResults.put(server, response.getBody().getObject().getString("value"));
                        	else
                        	readResults.put(server, "");
                            System.out.println("The read from "+ server.getCacheServerUrl() + " has successful");
                            callbacks.decrementAndGet();
                        }

                        public void cancelled() {
                            System.out.println("The read from "+ server.getCacheServerUrl() + "  has been cancelled");
                            callbacks.decrementAndGet();
                        }

                    });
        }
        
        while(true == true) {
            //System.out.println("Waiting for ");
            if(callbacks.get() == 0)
                break;
        }
        
        HashMap<DistributedCacheService, String> repairMap = this.buildRepairMap(readResults);
        
        if(repairMap.size() == 0){
        	System.out.println("Repair was not Required");
        	List<String> readValues = new ArrayList<String>(readResults.values());
        	return readValues.get(0);
        } else {
        	System.out.println("Repair was Required");
        	List<String> readValues = new ArrayList<String>(repairMap.values());
        	
        	for(Entry<DistributedCacheService, String> entry : repairMap.entrySet()) {
        		
        		final DistributedCacheService server = entry.getKey();
        		String value = entry.getValue();
        		
        		System.out.println("Repairing: " + server.getCacheServerUrl());
        		
        		Future<HttpResponse<JsonNode>> future = Unirest.put(server.getCacheServerUrl()+ "/cache/{key}/{value}")
                        .header("accept", "application/json")
                        .routeParam("key", Long.toString(key))
                        .routeParam("value", value)
                        .asJsonAsync(new Callback<JsonNode>() {

                            public void failed(UnirestException e) {
                                System.out.println("The repair to "+ server.getCacheServerUrl() + " has failed");
                            }

                            public void completed(HttpResponse<JsonNode> response) {
                                System.out.println("The repair to "+ server.getCacheServerUrl() + " has successful");
                            }

                            public void cancelled() {
                                System.out.println("The repair to "+ server.getCacheServerUrl() + "  has been cancelled");
                            }

                        });
            }
        	return readValues.get(0);
        }
    }
    
    public HashMap<DistributedCacheService, String> buildRepairMap(HashMap<DistributedCacheService, String> readResults) {
    	HashMap<DistributedCacheService, String> repairMap = new HashMap<DistributedCacheService, String>();
    	
    	HashMap<String, Integer> valueCounts = new HashMap<String, Integer>();
    	int maxCount = 0;
    	
    	for(Entry<DistributedCacheService, String> entry : readResults.entrySet()) {
    		String value = entry.getValue();
    		if(valueCounts.containsKey(value)) {
    			valueCounts.put(value, valueCounts.get(value) + 1);
    			if(maxCount < valueCounts.get(value)) {
    				maxCount++;
    			}
    		} else {
    			valueCounts.put(value, 1);
    			maxCount++;
    		}
    	}
    	
    	String valueToUpdate = "";
    	
    	for(Entry<String, Integer> entry : valueCounts.entrySet()) {
    		if(entry.getValue() == maxCount) {
    			valueToUpdate = entry.getKey();
    			break;
    		}
    	}
    	
    	if(maxCount != readResults.size()) {
    		for(Entry<DistributedCacheService, String> entry : readResults.entrySet()) {
        		if(!entry.getValue().equals(valueToUpdate)) {
        			repairMap.put(entry.getKey(), valueToUpdate);
        		}	
        	}
    	}
    	
    	return repairMap;
    }
}
